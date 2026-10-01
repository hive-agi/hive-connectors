(ns hive.connectors.digest-release-test
  "New Maven versions lead the digest, each with its repo's merged work as
   the changelog."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive.connectors.digest :as digest]
            [hive.connectors.digest.compose :as compose]
            [hive.connectors.digest.config :as config]
            [hive.connectors.digest.maven :as maven]
            [hive.connectors.digest.render :as render])
  (:import (java.time Instant)))

;; SPDX-License-Identifier: MIT

(defn- metadata [artifact versions stamp]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?><metadata><groupId>io.github.hive-agi</groupId>"
       "<artifactId>" artifact "</artifactId><versioning><release>" (last versions) "</release><versions>"
       (apply str (map #(str "<version>" % "</version>") versions))
       "</versions><lastUpdated>" stamp "</lastUpdated></versioning></metadata>"))

(def window {:from (Instant/parse "2026-09-30T12:00:00Z") :to (Instant/parse "2026-10-01T16:00:00Z")})

(defn- pr-hit [repo n title]
  {:title title :number n
   :html_url (str "https://github.com/hive-agi/" repo "/pull/" n)
   :repository_url (str "https://api.github.com/repos/hive-agi/" repo)
   :pull_request {:merged_at "2026-10-01T02:00:00Z"}})

(def prs
  [(pr-hit "hive-addon" 6 "feat(tool-contract): enforce non-empty input schemas for MCP root tools")
   (pr-hit "hive-addon" 7 "chore(release): 1.1.0 (minor)")
   (pr-hit "hive-mcp" 225 "fix(agent): report orphaned lings")])

(deftest metadata-parses-into-a-release
  (let [r (maven/release (maven/parse-metadata (metadata "hive-addon" ["1.0.15" "1.0.16" "1.1.0"] "20261001024624")))]
    (is (= {:artifact "hive-addon" :group "io.github.hive-agi" :version "1.1.0" :previous "1.0.16"
            :published (Instant/parse "2026-10-01T02:46:24Z")
            :url "https://clojars.org/io.github.hive-agi/hive-addon/versions/1.1.0"}
           r)))
  (testing "a SNAPSHOT never counts as the release"
    (is (= "0.2.0" (:version (maven/release {:artifact "a" :group "g" :versions ["0.1.0" "0.2.0" "0.3.0-SNAPSHOT"]
                                             :release "0.3.0-SNAPSHOT"})))))
  (is (= "https://repo.clojars.org/io/github/hive-agi/hive-addon/maven-metadata.xml"
         (maven/metadata-url "https://repo.clojars.org" "io.github.hive-agi" "hive-addon"))))

(def shipped
  [{:artifact "hive-addon" :group "io.github.hive-agi" :version "1.1.0" :previous "1.0.16"
    :published (Instant/parse "2026-10-01T02:46:24Z")
    :url "https://clojars.org/io.github.hive-agi/hive-addon/versions/1.1.0"}
   {:artifact "hive-spi" :group "io.github.hive-agi" :version "1.4.2" :previous "1.4.1"
    :published (Instant/parse "2026-10-01T02:28:46Z")
    :url "https://clojars.org/io.github.hive-agi/hive-spi/versions/1.4.2"}])

(def opts {:title "hive-mcp daily" :zone "America/Bahia" :per-repo 4 :total 28 :tagline nil :links []})

(deftest a-release-carries-its-repo-changelog
  (let [d (compose/digest {:window window :org "hive-agi" :prs prs :shipped shipped :news []
                           :releases [{:title "hive-addon 1.1.0" :url "x"} {:title "hive-emacs 0.5.6" :url "y"}]})]
    (is (= [[6] []] (mapv #(mapv :number (:items %)) (:shipped d))) "hive-addon's feature; hive-spi has none")
    (is (= ["hive-mcp"] (mapv :repo (:sections d))) "a released repo is not listed twice")
    (is (= ["hive-emacs 0.5.6"] (mapv :title (:releases d))) "the store item naming a shipped release is dropped")
    (is (= 2 (:listed d)))
    (let [slack (render/render render/slack d opts)
          md (render/render render/markdown d opts)]
      (is (str/includes? slack "2 new releases on Clojars. 3 pull requests merged across 2 hive-agi repos"))
      (is (str/includes? slack (str "*Released on Clojars*\n"
                                    "*<https://clojars.org/io.github.hive-agi/hive-addon/versions/1.1.0|hive-addon 1.1.0>* (was 1.0.16)\n"
                                    "• feat(tool-contract): enforce non-empty input schemas for MCP root tools")))
      (is (str/includes? slack "hive-spi 1.4.2>* (was 1.4.1)\n• maintenance release"))
      (is (str/includes? slack "*Also merged*\n*<https://github.com/hive-agi/hive-mcp|hive-mcp>*"))
      (is (str/includes? md "**[hive-addon 1.1.0](https://clojars.org/io.github.hive-agi/hive-addon/versions/1.1.0)** (was 1.0.16)")))))

(deftest a-promotion-only-release-uses-the-promotion-as-its-changelog
  (let [promo (pr-hit "hive-hot" 3 "Promote staging: remove-dirs!, owner claims, scoped reload fix")
        d (compose/digest {:window window :org "hive-agi" :news [] :releases []
                           :prs [promo
                                 (pr-hit "hive-vim" 12 "Promote staging: pack refresh")
                                 (pr-hit "hive-vim" 11 "Keep a reconnected Vim from a second replay")]
                           :shipped [{:artifact "hive-hot" :version "0.1.22" :previous "0.1.21" :url "u"}]})]
    (is (= [3] (mapv :number (:items (first (:shipped d))))))
    (is (= [[11]] (mapv #(mapv :number (:items %)) (:sections d))) "the unreleased promotion is still dropped")
    (is (= 1 (:merged d)) "promotions are not counted")
    (is (= 1 (:listed d)))
    (is (str/includes? (render/render render/slack d opts) "in the last 28 hours."))))

(deftest a-short-window-reads-in-minutes
  (is (= "40 minutes" (render/window-span {:from (Instant/parse "2026-10-01T16:00:00Z") :to (Instant/parse "2026-10-01T16:40:00Z")})))
  (is (= "1 hour" (render/window-span {:from (Instant/parse "2026-10-01T15:10:00Z") :to (Instant/parse "2026-10-01T16:20:00Z")}))))

(deftest the-pipeline-reads-clojars-and-keeps-releases-inside-the-window
  (let [settings (:ok (config/settings {:digest/feeds []} "HOME"))
        fetched (atom [])
        deps {:merged-prs! (fn [_] prs)
              :list-artifacts! (fn [group] (is (= "io.github.hive-agi" group)) ["hive-addon" "hive-old" "hive-broken"])
              :fetch-text! (fn [url]
                             (swap! fetched conj url)
                             (cond
                               (str/includes? url "/hive-addon/") (metadata "hive-addon" ["1.0.16" "1.1.0"] "20261001024624")
                               (str/includes? url "/hive-old/") (metadata "hive-old" ["0.1.0"] "20250101000000")
                               :else "not xml"))
              :read-state (fn [] {})}
        r (digest/preview! deps settings (:to window))]
    (is (= ["hive-addon 1.1.0"] (:shipped r)))
    (is (= 1 (count (:errors r))) "the unreadable artifact is one error line")
    (is (str/starts-with? (first (:errors r)) "maven hive-broken"))
    (is (str/includes? (:slack r) "hive-addon 1.1.0>* (was 1.0.16)"))
    (is (= 3 (count @fetched)))))

(deftest no-group-means-no-clojars-calls
  (let [settings (:ok (config/settings {:digest/feeds [] :digest/maven-group nil} "HOME"))]
    (is (= {:ok []} (digest/shipped! {:list-artifacts! (fn [_] (throw (ex-info "called" {})))} settings window)))))

(deftest updates-is-the-default-channel
  (is (= "#updates" (:digest/channel (:ok (config/settings {} "HOME"))))))
