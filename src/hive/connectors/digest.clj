(ns hive.connectors.digest
  "The daily digest, end to end. Collect a window of hive-agi activity
   (merged pull requests from GitHub; releases and news from RSS or Atom
   feeds, the hive-store release feed by default), compose it, render it,
   and post it to Slack once a day: the digest as the message, and a
   Markdown copy in its thread for pasting into ClojureVerse, Reddit or
   Discord.

   Every effect arrives in `deps`, so the pipeline runs against stubs:
     :merged-prs!  (fn [window]) -> GitHub search hits
     :fetch-text!  (fn [url]) -> feed XML
     :post!        (fn [text opts]) -> {:ok :ts :channel} | {:ok false :error}
     :read-state   (fn []) -> state map
     :write-state! (fn [state])"
  (:require [hive.connectors.digest.compose :as compose]
            [hive.connectors.digest.config :as config]
            [hive.connectors.digest.feed :as feed]
            [hive.connectors.digest.render :as render]
            [hive.connectors.digest.schedule :as schedule]
            [hive.connectors.digest.maven :as maven]))

;; SPDX-License-Identifier: MIT

(defn- attempt
  "Run `f`; a failure becomes {:error \"label: message\"} so one dead source
   does not sink the others."
  [label f]
  (try
    {:ok (f)}
    (catch Exception e
      {:error (str label ": " (ex-message e))})))

(defn shipped!
  "Maven releases of `group` published inside the window. Each artifact's
   metadata is read on its own, so one unreadable artifact is an error line,
   not a lost section."
  [{:keys [list-artifacts! fetch-text!]} {:digest/keys [maven-group maven-repo]} window]
  (if-not maven-group
    {:ok []}
    (let [listed (attempt "clojars" #(list-artifacts! maven-group))]
      (if-let [e (:error listed)]
        {:ok [] :errors [e]}
        (let [results (pmap (fn [artifact]
                              (attempt (str "maven " artifact)
                                       #(maven/release (maven/parse-metadata
                                                        (fetch-text! (maven/metadata-url maven-repo maven-group artifact))))))
                            (:ok listed))]
          {:ok (into [] (comp (keep :ok) (filter #(compose/in-window? window (:published %)))) results)
           :errors (vec (keep :error results))})))))

(defn collect!
  [{:keys [merged-prs! fetch-text!] :as deps} settings window]
  (let [prs (attempt "github" #(merged-prs! window))
        shipped (shipped! deps settings window)
        feeds (mapv (fn [f]
                      (assoc (attempt (str "feed " (:feed/id f)) #(feed/parse (fetch-text! (:feed/url f))))
                             :feed f))
                    (:digest/feeds settings))
        role-items (fn [role]
                     (into []
                           (comp (filter #(= role (get-in % [:feed :feed/role])))
                                 (mapcat #(get-in % [:ok :items]))
                                 (filter #(compose/in-window? window (:published %))))
                           feeds))]
    {:prs (or (:ok prs) [])
     :github-ok? (contains? prs :ok)
     :shipped (:ok shipped)
     :releases (role-items :release)
     :news (role-items :news)
     :errors (-> (vec (keep :error (cons prs feeds))) (into (:errors shipped)))}))

(defn build!
  "Collect and render the digest for the window ending at `now`, without
   posting. `last-at` is the previous post's window end, or nil."
  [deps settings now last-at]
  (let [window (schedule/window now last-at {:default-hours (:digest/default-hours settings)
                                             :max-hours (:digest/max-hours settings)})
        raw (collect! deps settings window)
        digest (compose/digest (assoc raw :window window :org (:digest/org settings)))
        opts (config/render-opts settings)]
    {:window window
     :digest digest
     :github-ok? (:github-ok? raw)
     :errors (:errors raw)
     :slack (render/render render/slack digest opts)
     :markdown (render/render render/markdown digest opts)}))

(defn- summary [{:keys [window digest errors]}]
  {:from (str (:from window))
   :to (str (:to window))
   :merged (:merged digest)
   :listed (:listed digest)
   :shipped (mapv #(str (:artifact %) " " (:version %)) (:shipped digest))
   :releases (count (:releases digest))
   :news (count (:news digest))
   :errors errors})

(defn post!
  "Post today's digest unless today already has one. `force?` posts anyway.
   GitHub being unreachable posts nothing and records nothing, so the next
   tick retries. A day with nothing to say records the day without posting."
  [{:keys [post! read-state write-state!] :as deps} settings now {:keys [force?]}]
  (let [state (read-state)
        today (schedule/local-date now (:digest/zone settings))]
    (if (and (not force?) (= today (:last-date state)))
      {:skipped :already-posted :date today}
      (let [{:keys [digest slack markdown window github-ok?] :as built}
            (build! deps settings now (:last-at state))
            mark! (fn [extra]
                    (write-state! (merge state {:last-date today :last-at (str (:to window))} extra)))]
        (cond
          (not github-ok?)
          (assoc (summary built) :skipped :github-unavailable)

          (compose/empty-digest? digest)
          (do (mark! {:last-result :empty})
              (assoc (summary built) :skipped :empty :date today))

          :else
          (let [main (post! slack {})]
            (if-not (:ok main)
              (assoc (summary built) :posted false :error (:error main))
              (let [thread (when (:digest/copy-paste-thread? settings)
                             (post! (render/copy-paste-reply markdown) {:thread-ts (:ts main)}))]
                (mark! {:last-result :posted :last-ts (:ts main) :last-channel (:channel main)})
                (cond-> (assoc (summary built)
                               :posted true :date today :ts (:ts main) :channel (:channel main))
                  thread (assoc :thread (select-keys thread [:ok :ts :error])))))))))))

(defn preview!
  "What today's post would say, from the last post's window end to now."
  [{:keys [read-state] :as deps} settings now]
  (let [built (build! deps settings now (:last-at (read-state)))]
    (assoc (summary built)
           :slack (:slack built)
           :markdown (:markdown built)
           :github-ok? (:github-ok? built))))
