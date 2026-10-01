(ns hive.connectors.digest.catalog
  "Pure. The public libraries of a Maven group, sorted for an announcement:
   tiers by maturity, the releases since the last roundup, and how the core
   libraries depend on one another.

   A library record: {:name :version :published Instant :downloads long
                      :deps #{sibling names} :description}"
  (:require [clojure.string :as str])
  (:import (java.time Instant)))

;; SPDX-License-Identifier: MIT

(defn major
  "Major version number, or nil when the version does not start with one."
  [version]
  (let [v (str version)
        dot (str/index-of v ".")]
    (parse-long (subs v 0 (or dot (count v))))))

(defn tier
  "`:stable` at 1.0 or later. `:mature` for a 0.x library the ecosystem leans
   on, measured as at least `mature-downloads` downloads. `:young` otherwise."
  [{:keys [version downloads]} mature-downloads]
  (let [m (major version)]
    (cond
      (and m (>= m 1)) :stable
      (>= (or downloads 0) mature-downloads) :mature
      :else :young)))

(defn- by-downloads [libs]
  (vec (sort-by (juxt #(- (or (:downloads %) 0)) :name) libs)))

(defn- newest-first [libs]
  (vec (sort-by #(str (:published %)) #(compare %2 %1) libs)))

(defn- used-by
  "Names in `pool` whose deps include `target`, sorted."
  [target pool]
  (vec (sort (keep #(when (contains? (:deps %) target) (:name %)) pool))))

(defn group-young
  "Young libraries into the configured named groups, in order. `groups` is
   [[label #{names}] ..]; whatever no group claims goes under `rest-label`."
  [young groups rest-label]
  (let [claimed (into #{} (mapcat second) groups)
        named (keep (fn [[label names]]
                      (when-let [xs (seq (filter #(contains? names (:name %)) young))]
                        {:label label :libs (vec xs)}))
                    groups)
        rest-libs (remove #(contains? claimed (:name %)) young)]
    (cond-> (vec named)
      (seq rest-libs) (conj {:label rest-label :libs (vec rest-libs)}))))

(defn catalog
  "Library records -> the roundup's data.

     :stable :mature   core libraries, most downloaded first
     :young            the rest, newest release first, grouped
     :fresh            released after `since` (all, when `since` is nil), newest first
     :relations        per core library, the core libraries built on it
     :leans            the core libraries the most young libraries depend on"
  [libs {:keys [since mature-downloads groups rest-label]}]
  (let [tiered (map #(assoc % :tier (tier % mature-downloads)) libs)
        {:keys [stable mature young]} (group-by :tier tiered)
        core (concat stable mature)
        core-names (into #{} (map :name) core)
        relations (->> core-names
                       (map (fn [n] {:lib n :used-by (used-by n core)}))
                       (filter (comp seq :used-by))
                       (sort-by (juxt #(- (count (:used-by %))) :lib))
                       vec)
        leans (->> core-names
                   (map (fn [n] {:lib n :count (count (used-by n young))}))
                   (filter (comp pos? :count))
                   (sort-by (juxt (comp - :count) :lib))
                   vec)]
    {:total (count tiered)
     :stable (by-downloads stable)
     :mature (by-downloads mature)
     :young (group-young (newest-first young) groups rest-label)
     :fresh (newest-first (filter #(or (nil? since)
                                       (and (:published %) (.isAfter ^Instant (:published %) since)))
                                  tiered))
     :relations relations
     :leans leans}))
