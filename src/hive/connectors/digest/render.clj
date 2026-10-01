(ns hive.connectors.digest.render
  "Pure. A digest value -> text in one chat dialect. Two dialects ship:
   Slack mrkdwn (the post itself, which copies cleanly into another Slack
   workspace such as Clojurians) and Markdown (ClojureVerse, Reddit, Discord,
   GitHub discussions)."
  (:require [clojure.string :as str]
            [hive.connectors.digest.text :as text])
  (:import (java.time Instant ZoneId)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

;; SPDX-License-Identifier: MIT

(defn slack-escape
  "Slack mrkdwn treats &, < and > as control characters."
  [s]
  (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(def slack
  {:escape slack-escape
   :bold (fn [s] (str "*" s "*"))
   :link (fn [url label] (if url (str "<" url "|" (slack-escape label) ">") (slack-escape label)))
   :bullet "•"})

(def markdown
  {:escape identity
   :bold (fn [s] (str "**" s "**"))
   :link (fn [url label] (if url (str "[" label "](" url ")") label))
   :bullet "-"})

(def ^:private day-format
  (DateTimeFormatter/ofPattern "EEE d MMM yyyy" Locale/ENGLISH))

(defn day-label
  [^Instant t zone]
  (.format day-format (.atZone t (ZoneId/of zone))))

(defn window-span
  "The window's length in words: \"24 hours\", or \"40 minutes\" under an hour."
  [{:keys [^Instant from ^Instant to]}]
  (let [minutes (quot (- (.toEpochMilli to) (.toEpochMilli from)) 60000)]
    (if (< minutes 60)
      (str (max 1 minutes) " minute" (when (not= 1 (max 1 minutes)) "s"))
      (let [hours (Math/round (/ minutes 60.0))]
        (str hours " hour" (when (not= 1 hours) "s"))))))

(defn- item-line [{:keys [escape link bullet]} {:keys [title url number]}]
  (str bullet " " (escape (text/truncate title 140))
       (when number (str " (" (link url (str "#" number)) ")"))))

(defn- feed-line [{:keys [escape link bullet]} {:keys [title url highlights]}]
  (str bullet " " (link url (text/truncate (or title url) 100))
       (when-let [h (first highlights)]
         (str ": " (escape (text/truncate h 120))))))

(defn budget-sections
  "Cap each repo at `per-repo` items and the whole post at `total`. A repo
   that loses items keeps a count of what it dropped."
  [sections per-repo total]
  (loop [[s & more] sections left total out []]
    (if (or (nil? s) (<= left 0))
      (into out (map #(assoc % :shown [] :hidden (count (:items %)))) (when s (cons s more)))
      (let [shown (vec (take (min per-repo left) (:items s)))]
        (recur more (- left (count shown))
               (conj out (assoc s :shown shown :hidden (- (count (:items s)) (count shown)))))))))

(defn- section-lines [{:keys [bold link escape bullet] :as d} org {:keys [repo shown hidden]}]
  (concat [(bold (link (str "https://github.com/" org "/" repo) repo))]
          (map #(item-line d %) shown)
          (when (pos? hidden)
            [(str bullet " " (escape (str "…and " hidden " more")))])))

(defn- plural [n word]
  (str n " " word (when (not= 1 n) "s")))

(defn- summary-line [{:keys [merged repos listed window org shipped]}]
  (let [left-out (- merged listed)]
    (not-empty
     (str/join " "
               (cond-> []
                 (seq shipped)
                 (conj (str (plural (count shipped) "new release") " on Clojars."))
                 (pos? merged)
                 (conj (str (plural merged "pull request") " merged across " (plural repos (str org " repo"))
                            " in the last " (window-span window) "."))
                 (pos? left-out)
                 (conj (str (plural left-out "internal change") " (CI, chores, tests, docs, refactors) left out.")))))))

(defn- shipped-lines
  "One release as a changelog: coordinate and version, then the features and
   fixes merged in its repo."
  [{:keys [bold link escape bullet] :as d} per-repo {:keys [artifact version previous url items]}]
  (let [shown (take per-repo items)
        hidden (- (count items) (count shown))]
    (concat [(str (bold (link url (str artifact " " version)))
                  (when previous (escape (str " (was " previous ")"))))]
            (if (seq shown)
              (map #(item-line d %) shown)
              [(str bullet " " (escape "maintenance release"))])
            (when (pos? hidden)
              [(str bullet " " (escape (str "…and " hidden " more")))]))))

(defn render
  "Digest -> text in dialect `d`. opts: :title :zone :per-repo :total
   :tagline :links [[label url] ..]. New Clojars releases come first, each
   with its changelog; repos that merged work without a release follow."
  [{:keys [escape bold link] :as d} {:keys [window org shipped sections releases news] :as digest}
   {:keys [title zone per-repo total tagline links]}]
  (let [visible (filter (comp seq :shown) (budget-sections sections per-repo total))
        hidden-repos (- (count sections) (count visible))]
    (->> (concat
          [(bold (escape (str title " · " (day-label (:to window) zone))))]
          (some-> (summary-line digest) escape vector)
          (when (seq shipped)
            (concat ["" (bold "Released on Clojars")]
                    (mapcat #(concat (shipped-lines d per-repo %) [""]) (butlast shipped))
                    (shipped-lines d per-repo (last shipped))))
          (when (seq visible)
            (concat ["" (bold (if (seq shipped) "Also merged" "Merged"))]
                    (rest (mapcat #(cons "" (section-lines d org %)) visible))))
          (when (pos? hidden-repos)
            ["" (escape (str "Plus changes in " (plural hidden-repos "more repo") "."))])
          (when (seq releases)
            (concat ["" (bold "From the hive store")] (map #(feed-line d %) releases)))
          (when (seq news)
            (concat ["" (bold "Elsewhere")] (map #(feed-line d %) news)))
          (when (or tagline (seq links))
            ["" (str/join " · " (concat (when tagline [(escape tagline)])
                                        (map (fn [[label url]] (link url label)) links)))]))
         (str/join "\n"))))

(defn copy-paste-reply
  "The thread reply that carries the Markdown version, fenced so Slack shows
   it verbatim."
  [markdown-text]
  (str "Copy-paste version (Markdown, for ClojureVerse, Reddit, Discord):\n```\n"
       (slack-escape markdown-text)
       "\n```"))
