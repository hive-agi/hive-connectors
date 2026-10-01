(ns hive.connectors.digest.config
  "Pure. Raw addon config -> digest settings with defaults, or the list of
   problems. Only :digest/* keys are read.

     :digest/enabled?        post on schedule (default true)
     :digest/channel         Slack channel name or id (default #updates)
     :digest/hour            local hour the post is due (0-23, default 9)
     :digest/zone            time zone id (default America/Bahia)
     :digest/org             GitHub organization (default hive-agi)
     :digest/maven-group     Maven group whose new releases lead the post, each
                             with its changelog (default io.github.hive-agi;
                             nil turns the section off)
     :digest/maven-repo      repository holding its metadata (default Clojars)
     :digest/feeds           [\"https://..\" {:feed/url .. :feed/id .. :feed/role :release|:news}]
                             default: the hive-store release feed, as :release
     :digest/slack-token     {:env \"SLACK_BOT_TOKEN\" :command [\"pass\" \"show\" \"..\"]}
     :digest/github-token    {:env \"GITHUB_TOKEN\" :command [\"gh\" \"auth\" \"token\"]}
     :digest/title :digest/tagline :digest/links [[label url] ..]
     :digest/per-repo :digest/total          item caps for the post
     :digest/default-hours :digest/max-hours window bounds
     :digest/copy-paste-thread?              reply with the Markdown copy
     :digest/state-file :digest/tick-seconds :digest/initial-delay-ms"
  (:require [clojure.string :as str])
  (:import (java.time ZoneId)))

;; SPDX-License-Identifier: MIT

(def store-feed
  {:feed/id "hive-store" :feed/url "https://store.hive-mcp.com/api/feed" :feed/role :release})

(defn defaults
  [home]
  {:digest/enabled? true
   :digest/channel "#updates"
   :digest/hour 9
   :digest/zone "America/Bahia"
   :digest/org "hive-agi"
   :digest/maven-group "io.github.hive-agi"
   :digest/maven-repo "https://repo.clojars.org"
   :digest/feeds [store-feed]
   :digest/slack-token {:env "SLACK_BOT_TOKEN"}
   :digest/github-token {:env "GITHUB_TOKEN" :command ["gh" "auth" "token"]}
   :digest/title "hive-mcp daily"
   :digest/tagline "hive-mcp: memory and agent coordination for Clojure, over MCP"
   :digest/links [["github.com/hive-agi/hive-mcp" "https://github.com/hive-agi/hive-mcp"]
                  ["store.hive-mcp.com" "https://store.hive-mcp.com"]]
   :digest/per-repo 4
   :digest/total 28
   :digest/default-hours 24
   :digest/max-hours 72
   :digest/copy-paste-thread? true
   :digest/state-file (str home "/.local/state/hive-connectors/digest.edn")
   :digest/tick-seconds 600
   :digest/initial-delay-ms 60000})

(defn normalize-feed
  "A feed URL string or map -> {:feed/id :feed/url :feed/role}. A bare URL
   is :news; the default store feed is :release."
  [f]
  (let [f (if (string? f) {:feed/url f} f)]
    (-> f
        (update :feed/id #(or % (:feed/url f)))
        (update :feed/role #(keyword (name (or % :news)))))))

(defn- valid-zone? [z]
  ;; ZoneId/of throws for an unknown id; that is the answer "invalid", reported by `problems`.
  (try (some? (ZoneId/of (str z))) (catch Exception _ false)))

(defn- problems [{:digest/keys [hour zone channel org feeds per-repo total]}]
  (cond-> []
    (not (and (int? hour) (<= 0 hour 23))) (conj {:key :digest/hour :problem "an integer 0-23"})
    (not (valid-zone? zone)) (conj {:key :digest/zone :problem "a java.time zone id"})
    (str/blank? (str channel)) (conj {:key :digest/channel :problem "a Slack channel"})
    (str/blank? (str org)) (conj {:key :digest/org :problem "a GitHub organization"})
    (not-every? (comp seq :feed/url) feeds) (conj {:key :digest/feeds :problem "every feed has a URL"})
    (not (pos-int? per-repo)) (conj {:key :digest/per-repo :problem "a positive integer"})
    (not (pos-int? total)) (conj {:key :digest/total :problem "a positive integer"})))

(defn settings
  "Raw config map -> {:ok settings} or {:error [problem ..]}."
  [raw home]
  (let [own (into {} (filter (fn [[k _]] (and (keyword? k) (= "digest" (namespace k))))) raw)
        s (update (merge (defaults home) own) :digest/feeds #(mapv normalize-feed %))
        ps (problems s)]
    (if (seq ps) {:error ps} {:ok s})))

(defn render-opts
  [s]
  {:title (:digest/title s)
   :zone (:digest/zone s)
   :per-repo (:digest/per-repo s)
   :total (:digest/total s)
   :tagline (:digest/tagline s)
   :links (:digest/links s)})
