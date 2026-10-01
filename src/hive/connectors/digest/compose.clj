(ns hive.connectors.digest.compose
  "Pure. One window of raw hive activity -> one digest value. Rendering lives
   in hive.connectors.digest.render."
  (:require [hive.connectors.digest.item :as item])
  (:import (java.time Instant)))

;; SPDX-License-Identifier: MIT

(defn in-window?
  "Is instant `t` inside [from, to)?"
  [{:keys [^Instant from ^Instant to]} ^Instant t]
  (boolean (and t (not (.isBefore t from)) (.isBefore t to))))

(defn- section [[repo items]]
  {:repo repo
   :items (item/sort-items (filter item/public? items))})

(defn digest
  "`prs` are GitHub search hits for the window, `shipped` the Maven releases
   published inside it, `releases` and `news` feed items inside it.

   A repo that shipped a release carries its pull requests as that release's
   changelog, so they are listed once, under the release. Promotion PRs
   (staging -> main) repeat work listed elsewhere, so they are neither listed
   nor counted, except as the changelog of a release whose repo merged nothing
   else in the window. A feed item naming a shipped release
   (\"hive-addon 1.1.0\") is a duplicate and is dropped."
  [{:keys [window org prs shipped releases news]}]
  (let [items (map item/pr->item prs)
        promotion? #(= :promotion (:kind %))
        work (remove promotion? items)
        by-repo (group-by :repo work)
        promotions (group-by :repo (filter promotion? items))
        changelog (fn [repo]
                    (let [public (item/sort-items (filter item/public? (get by-repo repo)))]
                      (if (seq public) public (vec (get promotions repo)))))
        shipped (->> shipped
                     (sort-by :artifact)
                     (mapv #(assoc % :items (changelog (:artifact %)))))
        shipped-repos (into #{} (map :artifact) shipped)
        shipped-titles (into #{} (map #(str (:artifact %) " " (:version %))) shipped)
        sections (->> by-repo
                      (remove (comp shipped-repos key))
                      (map section)
                      (filter (comp seq :items))
                      (sort-by (juxt #(- (count (:items %))) :repo))
                      vec)
        listed (fn [xs] (reduce + 0 (map (comp count (partial remove promotion?) :items) xs)))]
    {:window window
     :org org
     :merged (count work)
     :repos (count by-repo)
     :listed (+ (listed sections) (listed shipped))
     :shipped shipped
     :sections sections
     :releases (vec (sort-by :title (remove #(contains? shipped-titles (:title %)) releases)))
     :news (vec news)}))

(defn empty-digest?
  "Nothing worth posting."
  [{:keys [listed shipped releases news]}]
  (and (zero? listed) (empty? shipped) (empty? releases) (empty? news)))
