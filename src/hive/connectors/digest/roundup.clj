(ns hive.connectors.digest.roundup
  "Pure. A catalog -> the release roundup, in Slack mrkdwn, kept to about
   twenty lines: the post for #announcements, and the raw text that pastes
   into Clojurians with \"Format messages with markup\" on."
  (:require [clojure.string :as str]
            [hive.connectors.digest.render :as render]
            [hive.connectors.digest.text :as text]))

;; SPDX-License-Identifier: MIT

(def ^:private esc render/slack-escape)

(defn- code [s] (str "`" (esc s) "`"))

(defn blurb
  "The short description of `lib`: the configured one, else its repository
   description cut to `n` characters."
  [{:keys [name description]} blurbs n]
  (or (get blurbs name)
      (some-> description text/squash (text/truncate n))))

(defn- lib-entry [lib blurbs]
  (str (code (:name lib)) " " (esc (:version lib))
       (when-let [b (blurb lib blurbs 48)] (str " (" (esc b) ")"))))

(defn- bullets
  "Entries joined `per-line` to a bullet line."
  [entries per-line]
  (map #(str "• " (str/join " · " %)) (partition-all per-line entries)))

(defn- names-line [label libs cap]
  (let [shown (take cap libs)
        more (- (count libs) (count shown))]
    (str "• " (esc label) ": " (str/join " " (map (comp code :name) shown))
         (when (pos? more) (str " +" more)))))

(defn render
  "Catalog -> mrkdwn, about twenty lines: no blank lines, the bold section
   headers carry the structure. opts: :title :date-label :group :blurbs
   :links :fresh-cap :young-cap :relations-cap"
  [{:keys [total stable mature young fresh relations leans]}
   {:keys [title date-label since-label group blurbs links fresh-cap young-cap relations-cap]}]
  (let [fresh-shown (take fresh-cap fresh)
        fresh-more (- (count fresh) (count fresh-shown))]
    (->> (concat
          [(str "*" (esc (str title " (" date-label ")")) "* :rocket:")
           (str total " public libraries on Clojars under " (code group) "; " (count fresh) " released " (or since-label "since the last roundup") ".")]
          (when (seq fresh)
            [(str "Fresh: " (str/join " · " (map #(str (code (:name %)) " " (esc (:version %))) fresh-shown))
                  (when (pos? fresh-more) (str " +" fresh-more)))])
          (when (seq stable)
            (cons "*Stable (≥ 1.0)*" (bullets (map #(lib-entry % blurbs) stable) 2)))
          (when (seq mature)
            (cons "*Mature, still 0.x (the foundation, most downloaded)*"
                  (bullets (map #(lib-entry % blurbs) mature) 3)))
          (when (seq young)
            (cons "*Younger / experimental (APIs may still move)*"
                  (map #(names-line (:label %) (:libs %) young-cap) young)))
          (when (or (seq relations) (seq leans))
            (concat ["*How they fit*"]
                    (map (fn [{:keys [lib used-by]}]
                           (str "• " (code lib) " → " (str/join " · " (map code used-by))))
                         (take relations-cap relations))
                    (when (seq leans)
                      [(str "• Of the younger libraries, "
                            (str/join " and " (map (fn [{:keys [lib count]}]
                                                     (str count (if (= 1 count) " builds on " " build on ") (code lib)))
                                                   (take 2 leans)))
                            ".")])))
          [(str "Use: " (code (str group "/<name> {:mvn/version \"…\"}"))
                (str/join "" (map #(str " · " %) links)))])
         (str/join "\n"))))

(defn paste-reply
  "The thread reply carrying the raw text, for pasting into another Slack
   workspace with \"Format messages with markup\" turned on. `mrkdwn` is the
   post as sent, already escaped for the API; it is unescaped first so the
   fenced copy shows `&` and `<name>`, not their entities."
  [mrkdwn]
  (str "Raw text for Clojurians (Preferences → Advanced → \"Format messages with markup\", then paste):\n```\n"
       (esc (text/unescape-entities mrkdwn))
       "\n```"))
