(ns hive.connectors.announce-test
  "The release roundup: tiers, relations, the line budget, and posting at
   most once per week."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive.connectors.digest.announce :as announce]
            [hive.connectors.digest.catalog :as catalog]
            [hive.connectors.digest.config :as config]
            [hive.connectors.digest.roundup :as roundup])
  (:import (java.time Instant)))

;; SPDX-License-Identifier: MIT

(defn- lib [n v downloads deps published]
  {:name n :version v :downloads downloads :deps (set deps)
   :published (Instant/parse published) :description (str n " does things, at length and with detail")})

(def libs
  [(lib "hive-dsl" "0.5.34" 10310 [] "2026-09-26T04:20:01Z")
   (lib "hive-spi" "1.4.2" 9213 ["hive-dsl"] "2026-10-01T02:28:46Z")
   (lib "hive-weave" "0.4.1" 8090 ["hive-dsl"] "2026-09-26T04:25:28Z")
   (lib "hive-addon" "1.1.0" 1841 ["hive-dsl"] "2026-10-01T02:46:24Z")
   (lib "hive-mcp" "1.7.5" 367 ["hive-dsl" "hive-spi" "hive-weave" "hive-addon"] "2026-09-30T19:43:26Z")
   (lib "hive-emacs" "0.5.6" 335 ["hive-spi" "hive-addon"] "2026-09-14T02:41:00Z")
   (lib "hive-milvus" "0.4.23" 121 ["hive-spi"] "2026-09-28T14:12:42Z")
   (lib "hive-hot" "0.1.22" 314 [] "2026-10-01T16:14:22Z")])

(def opts {:since (Instant/parse "2026-09-30T00:00:00Z")
           :mature-downloads 3000
           :groups [["Editors" #{"hive-emacs"}] ["Stores" #{"hive-milvus"}]]
           :rest-label "Tooling"})

(deftest tiers-follow-version-and-downloads
  (is (= 1 (catalog/major "1.4.2")))
  (is (= :stable (catalog/tier {:version "1.1.0" :downloads 1} 3000)))
  (is (= :mature (catalog/tier {:version "0.5.34" :downloads 10310} 3000)))
  (is (= :young (catalog/tier {:version "0.1.22" :downloads 314} 3000)))
  (let [c (catalog/catalog libs opts)]
    (is (= ["hive-spi" "hive-addon" "hive-mcp"] (mapv :name (:stable c))) "most downloaded first")
    (is (= ["hive-dsl" "hive-weave"] (mapv :name (:mature c))))
    (is (= [["Editors" ["hive-emacs"]] ["Stores" ["hive-milvus"]] ["Tooling" ["hive-hot"]]]
           (mapv (juxt :label #(mapv :name (:libs %))) (:young c))))
    (is (= ["hive-hot" "hive-addon" "hive-spi" "hive-mcp"] (mapv :name (:fresh c))) "released after :since, newest first")
    (testing "relations stay inside the core libraries"
      (is (= {:lib "hive-dsl" :used-by ["hive-addon" "hive-mcp" "hive-spi" "hive-weave"]} (first (:relations c)))))
    (is (= [{:lib "hive-spi" :count 2} {:lib "hive-addon" :count 1}] (:leans c)))))

(deftest the-roundup-fits-the-budget
  (let [c (catalog/catalog libs opts)
        text (roundup/render c {:title "hive-agi libraries: release roundup" :date-label "Thu 1 Oct 2026"
                                :group "io.github.hive-agi" :blurbs {"hive-mcp" "MCP server"}
                                :links ["https://github.com/hive-agi"] :fresh-cap 8 :young-cap 8 :relations-cap 3})
        lines (str/split-lines text)]
    (is (<= (count lines) 20) text)
    (is (not-any? str/blank? lines) "bold headers carry the structure")
    (is (str/starts-with? text "*hive-agi libraries: release roundup (Thu 1 Oct 2026)* :rocket:"))
    (is (str/includes? text "8 public libraries on Clojars under `io.github.hive-agi`; 4 released since the last roundup."))
    (is (str/includes? text "Fresh: `hive-hot` 0.1.22 · `hive-addon` 1.1.0"))
    (is (str/includes? text "`hive-mcp` 1.7.5 (MCP server)"))
    (is (str/includes? text "`hive-weave` 0.4.1 (hive-weave does things, at length and with…)") "repo description, cut")
    (is (str/includes? text "• Editors: `hive-emacs`"))
    (is (str/includes? text "• `hive-dsl` → `hive-addon` · `hive-mcp` · `hive-spi` · `hive-weave`"))
    (is (str/includes? text "• Of the younger libraries, 2 build on `hive-spi` and 1 builds on `hive-addon`."))
    (is (str/ends-with? text "· https://github.com/hive-agi"))
    (testing "the paste reply fences the raw text"
      (is (str/includes? (roundup/paste-reply text) "```\n*hive-agi libraries")))))

(def metadata-xml
  (fn [artifact version stamp]
    (str "<metadata><groupId>io.github.hive-agi</groupId><artifactId>" artifact "</artifactId>"
         "<versioning><release>" version "</release><versions><version>" version "</version></versions>"
         "<lastUpdated>" stamp "</lastUpdated></versioning></metadata>")))

(defn- stub-deps [state posts]
  {:public-repos! (fn [_] {"hive-dsl" "Result DSL" "hive-addon" "IAddon"})
   :list-artifacts! (fn [_] ["hive-dsl" "hive-addon" "hive-private"])
   :artifact-info! (fn [_ a] {:downloads ({"hive-dsl" 10000 "hive-addon" 1800} a)
                              :dependencies (if (= a "hive-addon")
                                              [{:group_name "io.github.hive-agi" :jar_name "hive-dsl" :scope "compile"}
                                               {:group_name "io.github.hive-agi" :jar_name "hive-test" :scope "test"}]
                                              [])})
   :fetch-text! (fn [url] (cond (str/includes? url "/hive-addon/") (metadata-xml "hive-addon" "1.1.0" "20261001024624")
                                (str/includes? url "/hive-dsl/") (metadata-xml "hive-dsl" "0.5.34" "20260926042001")
                                :else (throw (ex-info "private" {}))))
   :post! (fn [text o] (swap! posts conj {:text text :opts o}) {:ok true :ts (str "1." (count @posts)) :channel "CANN"})
   :read-state (fn [] @state)
   :write-state! (fn [s] (reset! state s))})

(def settings (:ok (config/settings {} "HOME")))

(deftest only-public-libraries-are-read
  (let [{libs :ok errors :errors} (announce/libraries! (stub-deps (atom {}) (atom [])) settings)]
    (is (= #{"hive-dsl" "hive-addon"} (set (map :name libs))) "hive-private has no public repo")
    (is (empty? errors))
    (is (= #{"hive-dsl"} (:deps (first (filter #(= "hive-addon" (:name %)) libs)))) "test-scope deps are not relations")))

(deftest announce-once-a-week-and-only-with-news
  (let [state (atom {})
        posts (atom [])
        deps (stub-deps state posts)
        now (Instant/parse "2026-10-01T15:00:00Z")
        r (announce/announce! deps settings now {})]
    (is (:posted r))
    (is (= "2026-W40" (:week r)))
    (is (= 2 (count @posts)))
    (is (= {:thread-ts "1.1"} (:opts (second @posts))))
    (is (= "2026-W40" (:announce/last-week @state)))
    (is (= :already-announced (:skipped (announce/announce! deps settings (Instant/parse "2026-10-03T15:00:00Z") {}))))
    (testing "next week with no new release posts nothing"
      (is (= :nothing-released (:skipped (announce/announce! deps settings (Instant/parse "2026-10-08T15:00:00Z") {}))))
      (is (= 2 (count @posts))))
    (testing "force posts regardless"
      (is (:posted (announce/announce! deps settings (Instant/parse "2026-10-08T15:00:00Z") {:force? true}))))))

(deftest defaults
  (is (= "#announcements" (:announce/channel settings)))
  (is (= "2026-W01" (announce/iso-week (Instant/parse "2026-01-01T12:00:00Z") "America/Bahia"))))
