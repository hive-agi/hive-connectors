(ns hive.connectors.digest-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-addon.protocol :as proto]
            [hive.connectors.addon :as addon]
            [hive.connectors.digest :as digest]
            [hive.connectors.digest.compose :as compose]
            [hive.connectors.digest.config :as config]
            [hive.connectors.digest.feed :as feed]
            [hive.connectors.digest.item :as item]
            [hive.connectors.digest.render :as render]
            [hive.connectors.digest.schedule :as schedule]
            [hive.connectors.digest.text :as text])
  (:import (java.time Instant)))

;; SPDX-License-Identifier: MIT

(defn- pr-hit [repo n title merged-at]
  {:title title
   :number n
   :html_url (str "https://github.com/hive-agi/" repo "/pull/" n)
   :repository_url (str "https://api.github.com/repos/hive-agi/" repo)
   :pull_request {:merged_at merged-at}})

(def prs
  [(pr-hit "hive-mcp" 225 "feat(hot): reloadable core, seams S1-S13 closed" "2026-10-01T14:16:57Z")
   (pr-hit "hive-mcp" 226 "fix(config): set-config-value! read-modify-writes the file" "2026-10-01T14:32:56Z")
   (pr-hit "hive-mcp" 227 "staging → main: bounded host JVM launcher" "2026-10-01T01:59:58Z")
   (pr-hit "hive-mcp" 228 "ci: staging gate" "2026-10-01T02:00:00Z")
   (pr-hit "bb-mcp" 40 "feat(compact)!: compact tool schemas by default" "2026-10-01T15:20:47Z")
   (pr-hit "hive-carto-flow-vim" 9 "Hot-reload the Vim plugin into a running Vim on connect" "2026-10-01T15:05:10Z")
   (pr-hit "hive-spi" 3 "build: ship sources beside the AOT classes" "2026-10-01T02:27:45Z")])

(def rss-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<rss version=\"2.0\"><channel><title>hive-store releases</title>
<item><title>hive-emacs 0.5.6</title><link>https://github.com/hive-agi/hive-emacs</link>
<guid isPermaLink=\"false\">io.github.hive-agi/hive-emacs@0.5.6</guid>
<pubDate>Wed, 1 Oct 2026 02:37:48 GMT</pubDate><category>public</category>
<description>&lt;h3&gt;Added&lt;/h3&gt;&lt;ul&gt;&lt;li&gt;&lt;strong&gt;attention:&lt;/strong&gt; surface Emacs prompts &amp;amp; more&lt;/li&gt;&lt;li&gt;two&lt;/li&gt;&lt;/ul&gt;</description></item>
<item><title>hive-emacs 0.5.5</title><link>https://github.com/hive-agi/hive-emacs</link>
<pubDate>Sat, 5 Sep 2026 01:36:14 GMT</pubDate><description>Old.</description></item>
</channel></rss>")

(def atom-xml
  "<?xml version=\"1.0\" encoding=\"utf-8\"?>
<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>Planet</title>
<entry><title>Clojure 1.13 alpha</title><link rel=\"alternate\" href=\"https://clojure.org/news\"/>
<id>urn:x</id><updated>2026-10-01T10:00:00Z</updated><summary>A new alpha is out. More text.</summary></entry>
</feed>")

(def window {:from (Instant/parse "2026-09-30T12:00:00Z") :to (Instant/parse "2026-10-01T16:00:00Z")})

(deftest titles-classify-the-way-hive-repos-write-them
  (is (= {:kind :feat :scope "hot" :breaking? true :summary "reloadable core"}
         (item/parse-title "feat(hot)!: reloadable core")))
  (is (= :promotion (:kind (item/parse-title "staging → main: x"))))
  (is (= :promotion (:kind (item/parse-title "staging -> main: carto-flow panel layouts"))))
  (is (= :promotion (:kind (item/parse-title "release: bb-mcp setup"))))
  (testing "an unknown prefix is part of a plain title"
    (is (= {:kind :change :summary "H2: ACP client"} (item/parse-title "H2: ACP client"))))
  (is (= :chore (:kind (item/parse-title "chore(release): 1.1.0 (minor)"))))
  (is (item/public? (item/parse-title "Dock the panel by layout")))
  (is (not (item/public? (item/parse-title "ci: run tests")))))

(deftest text-helpers
  (is (= "a & b" (text/strip-html "<p>a &amp; <b>b</b></p>")))
  (is (= "one two" (text/squash "  one \n\t two ")))
  (is (= "A new alpha is out." (text/first-sentence "A new alpha is out. More text.")))
  (is (= "abc" (text/truncate "abc" 10)))
  (is (str/ends-with? (text/truncate "the quick brown fox jumps" 12) "…"))
  (is (= "x-y-z" (text/excise :digest/whitespace-run "x y   z" "-"))))

(deftest feeds-parse
  (let [{:keys [title items]} (feed/parse rss-xml)
        [newest older] items]
    (is (= "hive-store releases" title))
    (is (= "hive-emacs 0.5.6" (:title newest)))
    (is (= (Instant/parse "2026-10-01T02:37:48Z") (:published newest)))
    (is (= ["attention: surface Emacs prompts & more" "two"] (:highlights newest)))
    (is (= ["Old."] (:highlights older))))
  (let [{:keys [items]} (feed/parse atom-xml)]
    (is (= "https://clojure.org/news" (:url (first items))))
    (is (= ["A new alpha is out."] (:highlights (first items)))))
  (testing "a DOCTYPE is refused, so entities cannot reach local files"
    (is (thrown? Exception (feed/parse "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/hostname\">]><rss><channel><item><title>&x;</title></item></channel></rss>")))))

(defn- sample-digest []
  (let [releases (filter #(compose/in-window? window (:published %)) (:items (feed/parse rss-xml)))]
    (compose/digest {:window window :org "hive-agi" :prs prs :releases releases :news []})))

(deftest compose-drops-promotions-and-counts-internal-work
  (let [d (sample-digest)]
    (is (= 6 (:merged d)) "the promotion is neither listed nor counted")
    (is (= 4 (:listed d)))
    (is (= ["hive-mcp" "bb-mcp" "hive-carto-flow-vim"] (mapv :repo (:sections d))))
    (is (= [225 226] (mapv :number (:items (first (:sections d))))) "feature before fix")
    (is (= ["hive-emacs 0.5.6"] (mapv :title (:releases d))) "the old release is outside the window")
    (is (not (compose/empty-digest? d)))))

(def opts {:title "hive-mcp daily" :zone "America/Bahia" :per-repo 4 :total 28
           :tagline "hive-mcp: memory & agents" :links [["github" "https://github.com/hive-agi/hive-mcp"]]})

(deftest renders-both-dialects
  (let [d (sample-digest)
        slack (render/render render/slack d opts)
        md (render/render render/markdown d opts)]
    (testing "the fixture's pubDate says Wed; 1 Oct 2026 is a Thursday, and the date still parses"
      (is (str/starts-with? slack "*hive-mcp daily · Thu 1 Oct 2026*")))
    (is (str/includes? slack "6 pull requests merged across 4 hive-agi repos in the last 28 hours. 2 internal changes"))
    (is (str/includes? slack "*<https://github.com/hive-agi/hive-mcp|hive-mcp>*"))
    (is (str/includes? slack "(<https://github.com/hive-agi/hive-mcp/pull/225|#225>)"))
    (is (str/includes? slack "memory &amp; agents") "slack control characters are escaped")
    (is (str/includes? md "**[hive-mcp](https://github.com/hive-agi/hive-mcp)**"))
    (is (str/includes? md "- feat(hot): reloadable core, seams S1-S13 closed ([#225](https://github.com/hive-agi/hive-mcp/pull/225))"))
    (is (str/includes? md "**Releases**\n- [hive-emacs 0.5.6](https://github.com/hive-agi/hive-emacs): attention: surface"))
    (is (not (str/includes? md "staging")))))

(deftest budget-caps-items-and-reports-the-rest
  (let [items (vec (repeat 5 {:title "x"}))
        out (render/budget-sections [{:repo "a" :items items} {:repo "b" :items items} {:repo "c" :items items}] 4 6)]
    (is (= [4 2 0] (mapv (comp count :shown) out)))
    (is (= [1 3 5] (mapv :hidden out)))))

(deftest schedule
  (let [zone "America/Bahia"]
    (is (not (schedule/due? (Instant/parse "2026-10-01T11:59:00Z") {:hour 9 :zone zone} nil)) "08:59 local")
    (is (schedule/due? (Instant/parse "2026-10-01T12:00:00Z") {:hour 9 :zone zone} "2026-09-30"))
    (is (not (schedule/due? (Instant/parse "2026-10-01T20:00:00Z") {:hour 9 :zone zone} "2026-10-01")))
    (is (= "2026-09-30" (schedule/local-date (Instant/parse "2026-10-01T02:00:00Z") zone)) "local day, not UTC")
    (let [now (Instant/parse "2026-10-05T12:00:00Z")
          bounds {:default-hours 24 :max-hours 72}]
      (is (= (Instant/parse "2026-10-04T12:00:00Z") (:from (schedule/window now nil bounds))))
      (is (= (Instant/parse "2026-10-04T20:00:00Z") (:from (schedule/window now "2026-10-04T20:00:00Z" bounds))))
      (is (= (Instant/parse "2026-10-02T12:00:00Z") (:from (schedule/window now "2026-09-20T00:00:00Z" bounds)))
          "a long outage is capped"))))

(deftest config-settings
  (let [{:keys [ok]} (config/settings {:digest/channel "#hive"
                                       :digest/feeds ["https://planet.clojure.in/atom.xml" config/store-feed]
                                       :other 1}
                                      "HOME")]
    (is (= "#hive" (:digest/channel ok)))
    (is (= [:news :release] (mapv :feed/role (:digest/feeds ok))))
    (is (= "HOME/.local/state/hive-connectors/digest.edn" (:digest/state-file ok)))
    (is (not (contains? ok :other))))
  (is (= #{:digest/hour :digest/zone}
         (set (map :key (:error (config/settings {:digest/hour 25 :digest/zone "Mars/Olympus"} "HOME")))))))

;; ---------------------------------------------------------------------------
;; the pipeline against stubs

(defn- stub-deps [{:keys [state posts github]}]
  {:merged-prs! (fn [_] (if (= :down github) (throw (ex-info "503" {})) prs))
   :fetch-text! (fn [url] (if (str/includes? url "store") rss-xml atom-xml))
   :post! (fn [text o] (let [ts (str "1700000000." (count @posts))]
                         (swap! posts conj {:text text :opts o})
                         {:ok true :ts ts :channel "C1"}))
   :read-state (fn [] @state)
   :write-state! (fn [s] (reset! state s))
   :now (fn [] (:to window))})

(def settings
  (:ok (config/settings {:digest/channel "#hive"
                         :digest/feeds [config/store-feed {:feed/url "https://planet/atom.xml" :feed/id "planet"}]}
                        "HOME")))

(deftest post-once-a-day-with-a-markdown-thread
  (let [state (atom {:last-at (str (:from window))})
        posts (atom [])
        deps (stub-deps {:state state :posts posts})
        now (:to window)
        r (digest/post! deps settings now {})]
    (is (:posted r))
    (is (= 2 (count @posts)))
    (is (str/starts-with? (:text (first @posts)) "*hive-mcp daily"))
    (is (str/includes? (:text (first @posts)) "*Elsewhere*"))
    (is (= {:thread-ts "1700000000.0"} (:opts (second @posts))))
    (is (str/includes? (:text (second @posts)) "```\n**hive-mcp daily"))
    (is (= "2026-10-01" (:last-date @state)))
    (is (= (str now) (:last-at @state)))
    (testing "the same day does not post again"
      (is (= :already-posted (:skipped (digest/post! deps settings now {}))))
      (is (= 2 (count @posts))))
    (testing "force posts again"
      (is (:posted (digest/post! deps settings now {:force? true})))
      (is (= 4 (count @posts))))))

(deftest github-down-posts-nothing-and-retries
  (let [state (atom {})
        posts (atom [])
        r (digest/post! (stub-deps {:state state :posts posts :github :down}) settings (:to window) {})]
    (is (= :github-unavailable (:skipped r)))
    (is (some #(str/starts-with? % "github:") (:errors r)))
    (is (empty? @posts))
    (is (= {} @state) "nothing recorded, so the next tick retries")))

(deftest the-addon-posts-only-when-due
  (let [state (atom {:last-date "2026-09-30" :last-at (str (:from window))})
        posts (atom [])
        a (addon/make-addon {:digest/channel "#hive"
                             :digest/feeds [config/store-feed]
                             :digest/initial-delay-ms 3600000
                             :digest/deps (stub-deps {:state state :posts posts})})]
    (try
      (is (:success? (proto/initialize! a {})))
      (is (= "hive.connectors" (proto/addon-id a)))
      (is (= ["connectors"] (mapv :name (proto/tools a))))
      (is (:posted (addon/tick! a)) "13:00 local, nothing posted today")
      (is (nil? (addon/tick! a)) "already posted today")
      (is (= 2 (count @posts)))
      (is (= :ok (:status (proto/health a))))
      (let [handler (:handler (first (proto/tools a)))
            preview (handler {"command" "preview"})]
        (is (str/includes? (get-in preview [:content 0 :text]) "hive-mcp daily")))
      (finally (proto/shutdown! a)))))
