(ns hive.connectors.digest.boundary
  "Effects for the digest: GitHub search, feed fetches, Slack, the state
   file, and secrets. Each fn does one effect and returns plain data."
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [hive.connectors.slack :as slack])
  (:import (java.io File)
           (java.nio.file AtomicMoveNotSupportedException CopyOption Files StandardCopyOption)
           (java.time Instant)
           (java.time.temporal ChronoUnit)))

;; SPDX-License-Identifier: MIT

(def ^:private user-agent "hive-connectors-digest")

(defn secret
  "Resolve a secret spec {:env NAME :command [argv]}: the env var when it is
   set, else the first line the command prints. The value is never logged.
   A command that fails, or is not installed (a container without `gh`),
   yields nil and the caller reports the secret missing or goes without it."
  [{:keys [env command]}]
  (or (some-> env System/getenv str/trim not-empty)
      (when (seq command)
        (let [{:keys [exit out]} (try (apply sh/sh command)
                                      (catch java.io.IOException _
                                        ;; Binary absent: the same answer as a failing command.
                                        {:exit 127}))]
          (when (zero? exit)
            (some-> out str/split-lines first str/trim not-empty))))))

(defn- iso-seconds [^Instant t]
  (str (.truncatedTo t ChronoUnit/SECONDS)))

(defn merged-prs!
  "Every pull request merged in `org` inside the window, as GitHub search
   hits. Throws on an HTTP failure, so a dead GitHub never reads as a quiet
   day."
  [{:keys [org token]} {:keys [from to]}]
  (let [q (format "org:%s is:pr is:merged merged:%s..%s" org (iso-seconds from) (iso-seconds to))
        headers (cond-> {"Accept" "application/vnd.github+json" "User-Agent" user-agent}
                  token (assoc "Authorization" (str "Bearer " token)))]
    (loop [page 1 acc []]
      (let [{:keys [body]} (http/get "https://api.github.com/search/issues"
                                     {:query-params {"q" q "per_page" "100" "page" (str page)}
                                      :headers headers
                                      :as :string
                                      :socket-timeout 20000
                                      :connection-timeout 10000})
            {:keys [items total_count]} (json/read-str body :key-fn keyword)
            acc (into acc items)]
        (if (and (seq items) (< (count acc) total_count) (< page 10))
          (recur (inc page) acc)
          acc)))))

(defn fetch-text!
  "GET `url` as a string. Throws on an HTTP failure."
  [url]
  (:body (http/get url {:as :string
                        :headers {"User-Agent" user-agent}
                        :socket-timeout 20000
                        :connection-timeout 10000})))

(defn clojars-artifacts!
  "Artifact names Clojars lists for `group`. Throws on an HTTP failure."
  [group]
  (let [{:keys [body]} (http/get (str "https://clojars.org/api/groups/" group)
                                 {:as :string
                                  :headers {"Accept" "application/json" "User-Agent" user-agent}
                                  :socket-timeout 20000
                                  :connection-timeout 10000})]
    (into [] (keep :jar_name) (json/read-str body :key-fn keyword))))

(defn clojars-artifact!
  "Clojars' JSON for one artifact: latest version, downloads, dependencies."
  [group artifact]
  (let [{:keys [body]} (http/get (str "https://clojars.org/api/artifacts/" group "/" artifact)
                                 {:as :string
                                  :headers {"Accept" "application/json" "User-Agent" user-agent}
                                  :socket-timeout 20000
                                  :connection-timeout 10000})]
    (json/read-str body :key-fn keyword)))

(defn public-repos!
  "{repo-name description} for every public repository of the GitHub `org`.
   `token` is optional; without one the unauthenticated rate limit applies."
  ([org] (public-repos! org nil))
  ([org token]
   (let [headers (cond-> {"Accept" "application/vnd.github+json" "User-Agent" user-agent}
                   token (assoc "Authorization" (str "Bearer " token)))]
     (loop [page 1 acc {}]
       (let [{:keys [body]} (http/get (str "https://api.github.com/orgs/" org "/repos")
                                      {:query-params {"type" "public" "per_page" "100" "page" (str page)}
                                       :headers headers
                                       :as :string
                                       :socket-timeout 20000
                                       :connection-timeout 10000})
             repos (json/read-str body :key-fn keyword)
             acc (into acc (map (juxt :name #(or (:description %) ""))) repos)]
         (if (and (= 100 (count repos)) (< page 10))
           (recur (inc page) acc)
           acc))))))

(defn post!
  "Post `text` to a Slack channel. Returns {:ok true :ts :channel} or
   {:ok false :error}; an exception becomes an :error value."
  [token channel text opts]
  (try
    (slack/post-message! token channel text opts)
    (catch Exception e
      {:ok false :error (str "slack: " (ex-message e))})))

(defn read-state
  "The digest state map, {} when there is no file yet. A file that does not
   read as EDN throws: guessing {} would post the day twice."
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      (edn/read-string (slurp f))
      {})))

(defn write-state!
  "Write the state map atomically (temp file, then rename). Returns it."
  [path state]
  (let [^File f (io/file path)
        _ (io/make-parents f)
        tmp (File/createTempFile "digest" ".edn" (.getParentFile f))]
    (spit tmp (pr-str state))
    (try
      (Files/move (.toPath tmp) (.toPath f)
                  (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
      (catch AtomicMoveNotSupportedException _
        ;; A filesystem without atomic rename still gets the write, non-atomically.
        (Files/move (.toPath tmp) (.toPath f)
                    (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
    state))
