(ns hive.connectors.digest.item
  "Pure. Classify a merged pull request by its title, the way the hive repos
   write titles: Conventional Commits, plain sentences, and staging
   promotions. Decide which items a public daily post lists."
  (:require [clojure.string :as str]
            [hive.connectors.digest.text :as text]))

;; SPDX-License-Identifier: MIT

(def known-kinds
  "Conventional Commit types. A prefix outside this set (\"H2:\",
   \"transcript:\") belongs to a plain title, it is not a type."
  #{:feat :fix :perf :refactor :docs :chore :ci :test :build :style :revert :release})

(def public-kinds
  "Kinds a public post lists one by one. The other kinds are only counted."
  #{:feat :perf :change :fix})

(def ^:private kind-rank {:feat 0 :perf 1 :change 2 :fix 3})

(defn parse-title
  "Title string -> {:kind :scope :breaking? :summary}. A promotion PR
   (staging -> main, release:) is :promotion: it repeats work already listed."
  [title]
  (let [title (str/trim (str title))
        [kind scope bang summary] (text/groups :digest/conventional-title title)
        kind (some-> kind str/lower-case keyword)]
    (cond
      (text/found? :digest/promotion-title title) {:kind :promotion :summary title}
      (contains? known-kinds kind) {:kind kind
                                    :scope (some-> scope str/trim not-empty)
                                    :breaking? (some? bang)
                                    :summary (str/trim summary)}
      :else {:kind :change :summary title})))

(defn repo-name
  "Last path segment of a GitHub repository API URL."
  [repository-url]
  (last (str/split (str repository-url) #"/")))

(defn pr->item
  "One GitHub search hit (the issue shape) -> a digest item."
  [{:keys [title html_url number repository_url pull_request]}]
  (merge (parse-title title)
         {:repo (repo-name repository_url)
          :number number
          :url html_url
          :title (str/trim (str title))
          :merged-at (:merged_at pull_request)}))

(defn public?
  [item]
  (contains? public-kinds (:kind item)))

(defn sort-items
  "Breaking changes first, then features, perf, other changes, fixes. The
   sort is stable, so the input order holds inside a kind."
  [items]
  (vec (sort-by (juxt #(if (:breaking? %) 0 1) #(kind-rank (:kind %) 9)) items)))
