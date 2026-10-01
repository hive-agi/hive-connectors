(ns hive.connectors.digest.feed
  "Pure. Parse an RSS 2.0, RSS 1.0 or Atom document into items, through the
   hardened parser in hive.connectors.digest.xml."
  (:require [clojure.string :as str]
            [hive.connectors.digest.text :as text]
            [hive.connectors.digest.xml :refer [child content kids local-name] :as xml])
  (:import (java.time Instant OffsetDateTime)
           (java.time.format DateTimeFormatter)
           (org.w3c.dom Element)))

;; SPDX-License-Identifier: MIT

(defn parse-instant
  "RFC 1123 (RSS) or ISO-8601 (Atom) date string -> Instant, or nil. The RSS
   day-of-week is dropped before parsing: feeds get it wrong often enough, and
   the date alone is unambiguous."
  [s]
  (when-let [s (some-> s str/trim not-empty)]
    (let [comma (str/index-of s ", ")
          no-weekday (if (and comma (<= comma 9)) (subs s (+ comma 2)) s)]
      ;; Each format is tried in turn; a parse failure means "not this format".
      ;; A date no format accepts is nil, and the item then falls outside every window.
      (or (try (Instant/from (.parse DateTimeFormatter/RFC_1123_DATE_TIME no-weekday)) (catch Exception _ nil))
          (try (.toInstant (OffsetDateTime/parse s)) (catch Exception _ nil))
          (try (Instant/parse s) (catch Exception _ nil))))))

(defn highlights
  "The first `n` list items of an HTML description (a changelog), as plain
   text. A description without a list gives its first sentence."
  ([html] (highlights html 3))
  ([html n]
   (when html
     (let [lis (keep #(text/strip-html (first (:match/groups %)))
                     (text/matches :digest/html-list-item html))]
       (if (seq lis)
         (vec (take n lis))
         (some-> (text/strip-html html) text/first-sentence vector))))))

(defn- rss-item [n]
  {:title (content (child n "title"))
   :url (content (child n "link"))
   :id (or (content (child n "guid")) (content (child n "link")))
   :published (parse-instant (or (content (child n "pubDate")) (content (child n "date"))))
   :categories (vec (keep content (kids n "category")))
   :highlights (highlights (content (child n "description")))})

(defn- atom-link [n]
  (let [links (kids n "link")
        alt (or (first (filter #(contains? #{"" "alternate"} (.getAttribute ^Element % "rel")) links))
                (first links))]
    (some-> ^Element alt (.getAttribute "href") not-empty)))

(defn- atom-item [n]
  {:title (content (child n "title"))
   :url (atom-link n)
   :id (or (content (child n "id")) (atom-link n))
   :published (parse-instant (or (content (child n "published")) (content (child n "updated"))))
   :categories (vec (keep #(not-empty (.getAttribute ^Element % "term")) (kids n "category")))
   :highlights (highlights (or (content (child n "summary")) (content (child n "content"))))})

(defn parse
  "XML string -> {:title .. :items [{:title :url :id :published :categories
   :highlights}]}. Throws ex-info when the document is neither RSS nor Atom."
  [^String s]
  (let [root (xml/root s)]
    (case (local-name root)
      "rss" (let [ch (child root "channel")]
              {:title (content (child ch "title")) :items (mapv rss-item (kids ch "item"))})
      "RDF" {:title (some-> (child root "channel") (child "title") content)
             :items (mapv rss-item (kids root "item"))}
      "feed" {:title (content (child root "title")) :items (mapv atom-item (kids root "entry"))}
      (throw (ex-info "Not an RSS or Atom document." {:root (local-name root)})))))
