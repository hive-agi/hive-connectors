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
  "`prs` are GitHub search hits for the window; `releases` and `news` are
   feed items already inside it. Promotion PRs (staging -> main) repeat work
   listed elsewhere, so they are neither listed nor counted."
  [{:keys [window org prs releases news]}]
  (let [work (remove #(= :promotion (:kind %)) (map item/pr->item prs))
        sections (->> (group-by :repo work)
                      (map section)
                      (filter (comp seq :items))
                      (sort-by (juxt #(- (count (:items %))) :repo))
                      vec)]
    {:window window
     :org org
     :merged (count work)
     :repos (count (into #{} (map :repo) work))
     :listed (reduce + 0 (map (comp count :items) sections))
     :sections sections
     :releases (vec (sort-by :title releases))
     :news (vec news)}))

(defn empty-digest?
  "Nothing worth posting."
  [{:keys [listed releases news]}]
  (and (zero? listed) (empty? releases) (empty? news)))
