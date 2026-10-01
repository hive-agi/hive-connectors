(ns hive.connectors.digest.schedule
  "Pure. When today's digest is due, and which window of time it covers."
  (:import (java.time Duration Instant ZoneId)))

;; SPDX-License-Identifier: MIT

(defn local-date
  "ISO local date of `now` in `zone`, e.g. \"2026-10-01\"."
  [^Instant now zone]
  (str (.toLocalDate (.atZone now (ZoneId/of zone)))))

(defn due?
  "Due once the local hour reaches `hour` on a day that has no post yet. A
   host that was down at that hour posts when it comes back, the same day."
  [^Instant now {:keys [hour zone]} last-date]
  (let [z (.atZone now (ZoneId/of zone))]
    (and (>= (.getHour z) hour)
         (not= (str (.toLocalDate z)) last-date))))

(defn window
  "[from, to=now). `from` is the previous post, no further back than
   `max-hours`; with no previous post it is `default-hours` back."
  [^Instant now last-at {:keys [default-hours max-hours]}]
  (let [floor (.minus now (Duration/ofHours max-hours))
        last-at (some-> last-at str Instant/parse)
        from (cond
               (nil? last-at) (.minus now (Duration/ofHours default-hours))
               (.isBefore last-at floor) floor
               :else last-at)]
    {:from from :to now}))
