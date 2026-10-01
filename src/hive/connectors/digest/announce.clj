(ns hive.connectors.digest.announce
  "The release roundup, end to end: read every public library of the Maven
   group (Clojars artifact API for downloads and sibling dependencies, Maven
   metadata for the release date, GitHub for which repositories are public),
   sort it into tiers, render it, and post it to the announcement channel at
   most once per ISO week, with the raw text in a thread for Clojurians.

   Effects arrive in `deps`, on top of the digest's:
     :list-artifacts!  (fn [group]) -> artifact names
     :artifact-info!   (fn [group artifact]) -> Clojars artifact JSON
     :public-repos!    (fn [org]) -> {repo-name description}
     :fetch-text!      (fn [url]) -> Maven metadata XML
     :post! :read-state :write-state!"
  (:require [hive.connectors.digest.catalog :as catalog]
            [hive.connectors.digest.maven :as maven]
            [hive.connectors.digest.render :as render]
            [hive.connectors.digest.roundup :as roundup])
  (:import (java.time Instant ZoneId)
           (java.time.temporal IsoFields)
[java.time Duration]))

;; SPDX-License-Identifier: MIT

(defn- attempt [label f]
  (try
    {:ok (f)}
    (catch Exception e
      {:error (str label ": " (ex-message e))})))

(defn iso-week
  "\"2026-W40\" for `now` in `zone`."
  [^Instant now zone]
  (let [d (.toLocalDate (.atZone now (ZoneId/of zone)))]
    (format "%d-W%02d" (.get d IsoFields/WEEK_BASED_YEAR) (.get d IsoFields/WEEK_OF_WEEK_BASED_YEAR))))

(defn- library
  "One artifact -> a library record, or an error."
  [{:keys [artifact-info! fetch-text!]} group repo artifact description]
  (attempt (str "library " artifact)
           (fn []
             (let [info (artifact-info! group artifact)
                   meta (maven/parse-metadata (fetch-text! (maven/metadata-url repo group artifact)))
                   release (maven/release meta)
                   sibling? (fn [d] (and (= group (:group_name d)) (not= "test" (:scope d))))]
               (when release
                 {:name artifact
                  :version (:version release)
                  :published (:published release)
                  :downloads (:downloads info)
                  :deps (into #{} (comp (filter sibling?) (map :jar_name)) (:dependencies info))
                  :description description})))))

(defn libraries!
  "Every library of the group whose repository is public. A library that
   cannot be read is an error line, not a missing tier."
  [{:keys [list-artifacts! public-repos!] :as deps} {:digest/keys [maven-group maven-repo org]}]
  (let [public (attempt "github repos" #(public-repos! org))
        listed (attempt "clojars" #(list-artifacts! maven-group))]
    (if-let [e (or (:error public) (:error listed))]
      {:ok [] :errors [e]}
      (let [repos (:ok public)
            results (pmap #(library deps maven-group maven-repo % (get repos %))
                          (filter #(contains? repos %) (:ok listed)))]
        {:ok (into [] (keep :ok) results)
         :errors (vec (keep :error results))}))))

(defn- roundup-opts [settings now]
  {:title (:announce/title settings)
   :date-label (render/day-label now (:digest/zone settings))
   :group (:digest/maven-group settings)
   :blurbs (:announce/blurbs settings)
   :links (:announce/links settings)
   :fresh-cap 8
   :young-cap 8
   :relations-cap 3})

(defn build!
  "Read the libraries and render the roundup for releases after `since`,
   a [instant label] pair from `since`."
  [deps settings now [since-at since-label]]
  (let [{libs :ok errors :errors} (libraries! deps settings)
        cat (catalog/catalog libs {:since since-at
                                   :mature-downloads (:announce/mature-downloads settings)
                                   :groups (:announce/groups settings)
                                   :rest-label (:announce/rest-label settings)})]
    {:catalog cat
     :errors errors
     :text (roundup/render cat (assoc (roundup-opts settings now) :since-label since-label))}))

(defn- summary [{:keys [catalog errors]}]
  {:libraries (:total catalog)
   :stable (mapv :name (:stable catalog))
   :mature (mapv :name (:mature catalog))
   :fresh (mapv #(str (:name %) " " (:version %)) (:fresh catalog))
   :errors errors})

(defn- default-days [settings]
  (or (:announce/default-days settings) 7))

(defn since
  "[instant label]: releases after the instant are fresh. The last roundup,
   or :announce/default-days (7 unless set) back when there was none, and
   the words the header uses for it."
  [state settings ^Instant now]
  (if-let [last-at (some-> (:announce/last-at state) Instant/parse)]
    [last-at "since the last roundup"]
    [(.minus now (Duration/ofDays (default-days settings)))
     (str "in the last " (default-days settings) " days")]))

(defn preview!
  [{:keys [read-state] :as deps} settings now]
  (let [built (build! deps settings now (since (read-state) settings now))]
    (assoc (summary built) :text (:text built))))

(defn announce!
  "Post this week's roundup unless the week already has one, or nothing was
   released since the last one. `force?` posts regardless. No libraries read
   means nothing is posted and nothing recorded."
  [{:keys [post! read-state write-state!] :as deps} settings now {:keys [force?]}]
  (let [state (read-state)
        week (iso-week now (:digest/zone settings))]
    (if (and (not force?) (= week (:announce/last-week state)))
      {:skipped :already-announced :week week}
      (let [{:keys [catalog text] :as built} (build! deps settings now (since state settings now))]
        (cond
          (zero? (:total catalog))
          (assoc (summary built) :skipped :no-libraries)

          (and (not force?) (empty? (:fresh catalog)))
          (assoc (summary built) :skipped :nothing-released :week week)

          :else
          (let [main (post! text {})]
            (if-not (:ok main)
              (assoc (summary built) :posted false :error (:error main))
              (let [thread (post! (roundup/paste-reply text) {:thread-ts (:ts main)})]
                (write-state! (merge state {:announce/last-week week
                                            :announce/last-at (str now)
                                            :announce/last-ts (:ts main)}))
                (assoc (summary built)
                       :posted true :week week :ts (:ts main) :channel (:channel main)
                       :thread (select-keys thread [:ok :ts :error]))))))))))
