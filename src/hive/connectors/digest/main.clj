(ns hive.connectors.digest.main
  "The digest from a shell, without a running hive:

     clojure -M:digest preview          print today's Slack text and Markdown copy
     clojure -M:digest post             post now, once a day
     clojure -M:digest post --force     post even if today already has a post
     clojure -M:digest status           settings and persisted state

   Settings are the same as the addon's: config.edn (HIVE_MCP_CONFIG, else
   ~/.config/hive-mcp/config.edn) under :addons \"hive.connectors\". The state
   file is shared with the addon, so the two never post the same day twice."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [hive.connectors.addon :as addon]
            [hive.connectors.digest :as digest]
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

(defn run
  "Run `command`; returns [exit-code value]."
  [command flags]
  (let [parsed (config/settings (addon-config (config-file)) (System/getProperty "user.home"))]
    (if-let [problems (:error parsed)]
      [2 {:error "invalid hive.connectors config" :problems problems}]
      (let [settings (:ok parsed)
            deps (addon/default-deps settings)
            now ((:now deps))]
        (case command
          "preview" (let [r (digest/preview! deps settings now)] [(if (:github-ok? r) 0 1) r])
          "post" (let [r (digest/post! deps settings now {:force? (contains? flags "--force")})]
                   [(if (or (:posted r) (#{:already-posted :empty} (:skipped r))) 0 1) r])
          "status" [0 {:settings (dissoc settings :digest/slack-token :digest/github-token)
                       :state ((:read-state deps))}]
          [64 {:error (str "usage: preview | post [--force] | status; got " (pr-str command))}])))))

(defn -main [& args]
  (let [[code value] (run (first args) (set (rest args)))]
    (if (and (= "preview" (first args)) (:slack value))
      (do (println (:slack value))
          (println "\n----- Markdown copy -----\n")
          (println (:markdown value))
          (println "\n-----")
          (pp/pprint (dissoc value :slack :markdown)))
      (pp/pprint value))
    (shutdown-agents)
    (System/exit code)))
