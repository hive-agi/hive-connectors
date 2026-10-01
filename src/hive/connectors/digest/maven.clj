(ns hive.connectors.digest.maven
  "Pure. Maven repository metadata -> the release it records. Clojars stamps
   `lastUpdated` when a version is deployed, so it dates the newest release."
  (:require [clojure.string :as str]
            [hive.connectors.digest.xml :as xml])
  (:import (java.time LocalDateTime ZoneOffset)
           (java.time.format DateTimeFormatter)))

;; SPDX-License-Identifier: MIT

(def ^:private stamp-format (DateTimeFormatter/ofPattern "yyyyMMddHHmmss"))

(defn parse-stamp
  "Maven `lastUpdated` (yyyyMMddHHmmss, UTC) -> Instant, or nil."
  [s]
  (when-let [s (some-> s str/trim not-empty)]
    (.toInstant (LocalDateTime/parse s stamp-format) ZoneOffset/UTC)))

(defn metadata-url
  "URL of an artifact's maven-metadata.xml in repository `repo`."
  [repo group artifact]
  (str repo "/" (str/replace group "." "/") "/" artifact "/maven-metadata.xml"))

(defn parse-metadata
  "maven-metadata.xml -> {:group :artifact :release :latest :versions :updated}."
  [s]
  (let [root (xml/root s)
        v (xml/child root "versioning")]
    {:group (xml/content (xml/child root "groupId"))
     :artifact (xml/content (xml/child root "artifactId"))
     :release (some-> v (xml/child "release") xml/content)
     :latest (some-> v (xml/child "latest") xml/content)
     :versions (mapv xml/content (some-> v (xml/child "versions") (xml/kids "version")))
     :updated (some-> v (xml/child "lastUpdated") xml/content parse-stamp)}))

(defn snapshot? [version]
  (str/ends-with? (str version) "-SNAPSHOT"))

(defn release
  "Parsed metadata -> {:artifact :group :version :previous :published :url},
   or nil when there is no stable version."
  [{:keys [group artifact release versions updated]}]
  (let [stable (vec (remove snapshot? versions))
        version (if (and release (not (snapshot? release))) release (peek stable))]
    (when version
      {:artifact artifact
       :group group
       :version version
       :previous (last (take-while #(not= % version) stable))
       :published updated
       :url (str "https://clojars.org/" group "/" artifact "/versions/" version)})))
