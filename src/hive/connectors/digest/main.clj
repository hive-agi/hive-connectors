(ns hive.connectors.digest.main
  "The digest and the release roundup from a shell, without a running hive:

     clojure -M:digest preview                print today's Slack text and Markdown copy
     clojure -M:digest post [--force]         post the digest, once a day
     clojure -M:digest announce-preview       print the release roundup
     clojure -M:digest announce [--force]     post the roundup, once per ISO week,
                                              only when something was released
     clojure -M:digest status                 settings and persisted state

   `--channel <name|id>` overrides the channel of a post or announce.

   Settings are the same as the addon's: config.edn (HIVE_MCP_CONFIG, else
   ~/.config/hive-mcp/config.edn) under :addons \"hive.connectors\". Secrets
   come from SLACK_BOT_TOKEN and GITHUB_TOKEN when set, which is how a
   Kubernetes CronJob runs it. The state file is shared with the addon, so
   nothing is posted twice for the same day or week."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [hive.connectors.addon :as addon]
            [hive.connectors.digest :as digest]
            [hive.connectors.digest.announce :as announce]
            [hive.connectors.digest.config :as config])
  (:gen-class))

;; SPDX-License-Identifier: MIT

(defn config-file []
  (io/file (or (System/getenv "HIVE_MCP_CONFIG")
               (str (System/getProperty "user.home") "/.config/hive-mcp/config.edn"))))

(defn addon-config
  "The hive.connectors map in config.edn, {} when absent. Tagged literals in
   the rest of the file are read as their bare values."
  [f]
  (if (.exists (io/file f))
    (get-in (edn/read-string {:default (fn [_ v] v)} (slurp f)) [:addons "hive.connectors"] {})
    {}))

(defn option
  "The value after `flag` in `args`, or nil."
  [args flag]
  (second (drop-while #(not= flag %) args)))

(defn run
  "Run `command` with `args`; returns [exit-code value]."
  [command args]
  (let [parsed (config/settings (addon-config (config-file)) (System/getProperty "user.home"))
        force? (boolean (some #{"--force"} args))
        channel (option args "--channel")]
    (if-let [problems (:error parsed)]
      [2 {:error "invalid hive.connectors config" :problems problems}]
      (let [settings (cond-> (:ok parsed)
                       channel (assoc :digest/channel channel :announce/channel channel))
            deps (addon/default-deps settings)
            now ((:now deps))]
        (case command
          "preview" (let [r (digest/preview! deps settings now)] [(if (:github-ok? r) 0 1) r])
          "post" (let [r (digest/post! deps settings now {:force? force?})]
                   [(if (or (:posted r) (#{:already-posted :empty} (:skipped r))) 0 1) r])
          "announce-preview" (let [r (announce/preview! deps settings now)]
                               [(if (seq (:errors r)) 1 0) r])
          "announce" (let [r (announce/announce! (addon/announce-deps settings) settings now {:force? force?})]
                       [(if (or (:posted r) (#{:already-announced :nothing-released} (:skipped r))) 0 1) r])
          "status" [0 {:settings (dissoc settings :digest/slack-token :digest/github-token)
                       :state ((:read-state deps))}]
          [64 {:error (str "usage: preview | post | announce-preview | announce [--force] [--channel c] | status; got "
                           (pr-str command))}])))))

(defn -main [& args]
  (let [[code value] (run (first args) (vec (rest args)))]
    (cond
      (and (= "preview" (first args)) (:slack value))
      (do (println (:slack value))
          (println "\n----- Markdown copy -----\n")
          (println (:markdown value))
          (println "\n-----")
          (pp/pprint (dissoc value :slack :markdown)))

      (and (= "announce-preview" (first args)) (:text value))
      (do (println (:text value))
          (println "\n-----")
          (pp/pprint (dissoc value :text)))

      :else (pp/pprint value))
    (shutdown-agents)
    (System/exit code)))
