(ns hive.connectors.digest.text
  "Pure text helpers for the digest, over hive-system.pattern. Every pattern
   is named here once; call sites name what they look for."
  (:require [clojure.string :as str]
            [hive-system.pattern.core :as pattern]))

;; SPDX-License-Identifier: MIT

(def patterns
  "The digest's named patterns."
  [#:pattern{:id :digest/conventional-title
             :expr "^([A-Za-z][\\w-]*)(?:\\(([^)]*)\\))?(!)?:\\s*(.+)$"}
   #:pattern{:id :digest/promotion-title
             :expr "^(?:staging\\s*(?:→|->)\\s*main|staging promote|promote staging|promote:|release:|merge (?:branch|pull))"
             :flags #{:case-insensitive}}
   #:pattern{:id :digest/html-list-item
             :expr "<li>(.*?)</li>"
             :flags #{:case-insensitive :dotall}}
   #:pattern{:id :digest/html-tag
             :expr "<[^>]+>"}
   #:pattern{:id :digest/whitespace-run
             :expr "\\s+"}
   #:pattern{:id :digest/sentence-end
             :expr "[.!?](?=\\s)"}])

(defn register!
  "Register the digest's patterns. Idempotent (keyed by id); runs on load."
  []
  (run! pattern/register-pattern! patterns))

(register!)

(defn matches
  "Every match of pattern `id` in `s`, as hive-system Match maps."
  [id s]
  (or (:ok (pattern/scan id (str s))) []))

(defn groups
  "Capture groups of the first match of `id` in `s`, or nil."
  [id s]
  (some-> (:ok (pattern/find-first id (str s))) :match/groups))

(defn found?
  [id s]
  (true? (:ok (pattern/match? id (str s)))))

(defn excise
  "`s` with every match of `id` replaced by `with`."
  [id s with]
  (let [s (str s)]
    (loop [[m & more] (matches id s) at 0 out (StringBuilder.)]
      (if m
        (recur more (:match/end m)
               (doto out (.append (subs s at (:match/start m))) (.append ^String with)))
        (str (doto out (.append (subs s at))))))))

(def ^:private entities
  [["&lt;" "<"] ["&gt;" ">"] ["&quot;" "\""] ["&#39;" "'"] ["&#x27;" "'"] ["&nbsp;" " "] ["&amp;" "&"]])

(defn unescape-entities
  [s]
  (reduce (fn [acc [from to]] (str/replace acc from to)) (str s) entities))

(defn squash
  "Collapse whitespace runs to one space and trim. Blank -> nil."
  [s]
  (when s
    (not-empty (str/trim (excise :digest/whitespace-run s " ")))))

(defn strip-html
  "HTML fragment -> plain text on one line, or nil."
  [s]
  (when s
    (squash (unescape-entities (excise :digest/html-tag s " ")))))

(defn first-sentence
  [s]
  (when-let [s (squash s)]
    (if-let [m (first (matches :digest/sentence-end s))]
      (subs s 0 (:match/end m))
      s)))

(defn truncate
  "At most `n` characters, cut at a word boundary, with an ellipsis."
  [s n]
  (let [s (str s)]
    (if (<= (count s) n)
      s
      (let [cut (subs s 0 n)
            space (str/last-index-of cut " ")]
        (str (str/trimr (if (and space (> space (quot n 2))) (subs cut 0 space) cut)) "…")))))
