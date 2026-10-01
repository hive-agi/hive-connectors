(ns hive.connectors.addon
  "IAddon `hive.connectors`. Construction is pure. `initialize!` validates the
   config and starts the digest scheduler; `shutdown!` stops it.

   The scheduler is one daemon thread that wakes every :digest/tick-seconds,
   reads the persisted state, and posts the daily digest when it is due (the
   local hour reached, today not posted). Due-ness comes from the state file,
   so a restart never posts twice and a host that was down at the hour posts
   when it returns.

   Config: manifest `:addon/config`, then config.edn
   `:addons {\"hive.connectors\" {:digest/channel \"#general\" ..}}`. See
   hive.connectors.digest.config for every key."
  (:require [clojure.data.json :as json]
            [hive-addon.protocol :as addon]
            [hive.connectors.digest :as digest]
            [hive.connectors.digest.boundary :as boundary]
            [hive.connectors.digest.config :as config]
            [hive.connectors.digest.schedule :as schedule])
  (:import (java.time Instant)
           (java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit)
           (java.util.concurrent.atomic AtomicBoolean)))

;; SPDX-License-Identifier: MIT

(def addon-id-value "hive.connectors")

(defn default-deps
  "Production effects for `settings`. Secrets resolve per call, so a token
   rotated in pass or the environment is picked up without a restart."
  [settings]
  (let [state-file (:digest/state-file settings)]
    {:merged-prs! (fn [window]
                    (boundary/merged-prs! {:org (:digest/org settings)
                                           :token (boundary/secret (:digest/github-token settings))}
                                          window))
     :list-artifacts! boundary/clojars-artifacts!
     :fetch-text! boundary/fetch-text!
     :post! (fn [text opts]
              (if-let [token (boundary/secret (:digest/slack-token settings))]
                (boundary/post! token (:digest/channel settings) text opts)
                {:ok false :error "no Slack token: set SLACK_BOT_TOKEN or :digest/slack-token {:command [..]}"}))
     :read-state #(boundary/read-state state-file)
     :write-state! #(boundary/write-state! state-file %)
     :now #(Instant/now)}))

(defn- daemon-scheduler ^ScheduledExecutorService []
  (Executors/newSingleThreadScheduledExecutor
   (reify ThreadFactory
     (newThread [_ runnable]
       (doto (Thread. ^Runnable runnable "hive-connectors-digest")
         (.setDaemon true))))))

(defn- exclusive
  "Run `f` unless a digest run is in flight."
  [{:keys [state]} f]
  (let [^AtomicBoolean busy (:busy @state)]
    (if (.compareAndSet busy false true)
      (try (f) (finally (.set busy false)))
      {:skipped :busy})))

(defn- record! [{:keys [state]} result now]
  (swap! state assoc :last-run {:at (str now) :result (dissoc result :slack :markdown)})
  result)

(defn post!
  "Post now. `force?` posts even when today already has a post."
  ([a] (post! a {}))
  ([{:keys [state] :as a} opts]
   (let [{:keys [settings deps]} @state]
     (if-not settings
       {:skipped :not-initialized}
       (let [now ((:now deps))]
         (record! a (exclusive a #(digest/post! deps settings now opts)) now))))))

(defn preview!
  [{:keys [state]}]
  (let [{:keys [settings deps]} @state]
    (if-not settings
      {:skipped :not-initialized}
      (digest/preview! deps settings ((:now deps))))))

(defn tick!
  "One scheduler wake-up: post when due."
  [{:keys [state] :as a}]
  (let [{:keys [settings deps]} @state]
    (when (and settings (:digest/enabled? settings))
      (let [now ((:now deps))]
        (when (schedule/due? now {:hour (:digest/hour settings) :zone (:digest/zone settings)}
                             (:last-date ((:read-state deps))))
          (post! a {}))))))

(defn status
  [{:keys [state]}]
  (let [{:keys [settings deps lifecycle errors last-run]} @state]
    (if-not settings
      {:lifecycle (or lifecycle :new) :errors errors}
      (let [persisted (try ((:read-state deps))
                           (catch Exception e {:unreadable (ex-message e)}))]
        {:lifecycle lifecycle
         :enabled? (:digest/enabled? settings)
         :channel (:digest/channel settings)
         :hour (:digest/hour settings)
         :zone (:digest/zone settings)
         :org (:digest/org settings)
         :feeds (mapv #(select-keys % [:feed/id :feed/url :feed/role]) (:digest/feeds settings))
         :state-file (:digest/state-file settings)
         :state persisted
         :due-now? (schedule/due? ((:now deps)) {:hour (:digest/hour settings) :zone (:digest/zone settings)}
                                  (:last-date persisted))
         :last-run last-run}))))

(defn- text-result [x]
  {:content [{:type "text" :text (json/write-str x :escape-slash false)}]})

(defn tool [a]
  {:name "connectors"
   :description (str "hive-connectors daily digest of hive-agi activity (merged PRs, hive-store releases, RSS) "
                     "posted to Slack. status: schedule, channel, last post. preview: today's Slack text and "
                     "the Markdown copy, nothing posted. post: post now (once a day; force=true posts again).")
   :inputSchema {:type "object"
                 :properties {"command" {:type "string" :enum ["status" "preview" "post"]}
                              "force" {:type "boolean" :description "[post] post even if today already has a post"}}
                 :required ["command"]}
   :handler (fn [params]
              (let [command (or (get params "command") (:command params))
                    force? (true? (or (get params "force") (:force params)))]
                (case command
                  "status" (text-result (status a))
                  "preview" (text-result (preview! a))
                  "post" (text-result (post! a {:force? force?}))
                  {:content [{:type "text" :text (str "unknown command: " command)}] :isError true})))})

(defn- start! [{:keys [state] :as a} settings deps]
  (let [sched (daemon-scheduler)]
    (swap! state assoc :lifecycle :active :settings settings :deps deps :scheduler sched)
    (.scheduleWithFixedDelay sched
                             ^Runnable (fn []
                                         (try (tick! a)
                                              (catch Throwable t
                                                ;; A failed tick must not kill the schedule; it is kept for status.
                                                (swap! state assoc :last-run {:error (str t)}))))
                             (long (:digest/initial-delay-ms settings))
                             (long (* 1000 (:digest/tick-seconds settings)))
                             TimeUnit/MILLISECONDS)
    {:success? true :errors []
     :metadata {:channel (:digest/channel settings)
                :hour (:digest/hour settings)
                :zone (:digest/zone settings)}}))

(defn- initialize-addon! [{:keys [state seed] :as a} runtime-config]
  (locking state
    (if (= :active (:lifecycle @state))
      {:success? true :already-initialized? true}
      (let [cfg (merge (:addon/config seed) seed (:addon/config runtime-config) runtime-config)
            parsed (config/settings cfg (System/getProperty "user.home"))]
        (if-let [problems (:error parsed)]
          (do (swap! state assoc :lifecycle :error :errors problems)
              {:success? false :errors [(str "invalid hive.connectors config: " (pr-str problems))]})
          (let [settings (:ok parsed)]
            (start! a settings (merge (default-deps settings) (:digest/deps cfg)))))))))

(defn- shutdown-addon! [{:keys [state]}]
  (locking state
    (when-let [^ScheduledExecutorService sched (:scheduler @state)]
      (.shutdownNow sched))
    (swap! state #(-> % (dissoc :scheduler :settings :deps) (assoc :lifecycle :stopped))))
  nil)

(defrecord HiveConnectorsAddon [state seed]
  addon/IAddon
  (addon-id [_] addon-id-value)
  (addon-type [_] :native)
  (capabilities [_] #{:tools :health-reporting})
  (initialize! [this runtime-config] (initialize-addon! this runtime-config))
  (shutdown! [this] (shutdown-addon! this))
  (tools [this] [(tool this)])
  (schema-extensions [_] {})
  (health [this]
    (let [{:keys [lifecycle last-run] :as s} (status this)]
      (if (= :active lifecycle)
        {:status (if (or (:error last-run) (false? (get-in last-run [:result :posted]))) :degraded :ok)
         :details (select-keys s [:channel :hour :zone :last-run])}
        {:status :down :details (select-keys s [:lifecycle :errors])})))
  (excluded-tools [_] #{})
  (hooks [this]
    {:connectors/digest-post! (fn ([] (post! this {})) ([opts] (post! this opts)))
     :connectors/digest-preview (fn [] (preview! this))
     :connectors/status (fn [] (status this))}))

(defn make-addon
  "Uninitialized IAddon. No thread or file is touched."
  ([] (make-addon {}))
  ([seed] (->HiveConnectorsAddon (atom {:lifecycle :new :busy (AtomicBoolean. false)}) (or seed {}))))

(defn addon-ctor
  "hive-addon.mount constructor: config -> uninitialized IAddon."
  [config]
  (make-addon config))
