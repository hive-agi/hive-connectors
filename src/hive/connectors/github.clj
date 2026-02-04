(ns hive.connectors.github
  "GitHub connector using official Java SDK (kohsuke/github-api).
   Enables hivemind agents to create issues, comment on PRs, and post notifications."
  (:import [org.kohsuke.github GitHub GitHubBuilder]
           [org.kohsuke.github GHIssueState]))

(defn create-client
  "Create a GitHub client authenticated with a personal access token.

   Args:
     token - GitHub PAT (ghp_...) with appropriate scopes

   Returns:
     GitHub client instance"
  [token]
  (-> (GitHubBuilder.)
      (.withOAuthToken token)
      (.build)))

(defn create-client-from-env
  "Create a GitHub client using GITHUB_TOKEN environment variable."
  []
  (create-client (System/getenv "GITHUB_TOKEN")))

(defn get-repo
  "Get a repository by owner/name.

   Args:
     client - GitHub client
     repo   - Repository in \"owner/repo\" format

   Returns:
     GHRepository instance"
  [client repo]
  (.getRepository client repo))

(defn create-issue!
  "Create a new issue in a repository.

   Args:
     client - GitHub client
     repo   - Repository in \"owner/repo\" format
     title  - Issue title
     body   - Issue body (markdown)
     opts   - Optional map with :labels and :assignees

   Returns:
     {:ok true :number N :url \"...\"} on success
     {:ok false :error \"...\"} on failure"
  ([client repo title body]
   (create-issue! client repo title body {}))
  ([client repo title body {:keys [labels assignees]}]
   (try
     (let [repository (get-repo client repo)
           builder (-> (.createIssue repository title)
                       (.body body))]
       (when (seq labels)
         (.labels builder (into-array String labels)))
       (when (seq assignees)
         (.assignees builder (into-array String assignees)))
       (let [issue (.create builder)]
         {:ok true
          :number (.getNumber issue)
          :url (str (.getHtmlUrl issue))}))
     (catch Exception e
       {:ok false
        :error (.getMessage e)}))))

(defn comment-on-issue!
  "Add a comment to an issue or pull request.

   Args:
     client - GitHub client
     repo   - Repository in \"owner/repo\" format
     number - Issue/PR number
     body   - Comment body (markdown)

   Returns:
     {:ok true :id N} on success
     {:ok false :error \"...\"} on failure"
  [client repo number body]
  (try
    (let [repository (get-repo client repo)
          issue (.getIssue repository number)
          comment (.comment issue body)]
      {:ok true
       :id (.getId comment)})
    (catch Exception e
      {:ok false
       :error (.getMessage e)})))

(defn format-hivemind-event
  "Format a hivemind event for GitHub markdown.

   Event map keys:
     :a - agent ID
     :e - event type (started, progress, completed, error, blocked)
     :m - message content

   Returns formatted markdown string."
  [{:keys [a e m]}]
  (let [emoji (case e
                "started"   "🚀"
                "progress"  "⏳"
                "completed" "✅"
                "error"     "❌"
                "blocked"   "🚧"
                "📢")
        status (case e
                 "started"   "Started"
                 "progress"  "In Progress"
                 "completed" "Completed"
                 "error"     "Error"
                 "blocked"   "Blocked"
                 e)]
    (format "%s **%s** | %s\n\n%s" emoji (or a "unknown") status (or m ""))))

(defn notify-issue!
  "Post a formatted hivemind event as a comment on an issue/PR.

   Args:
     client - GitHub client
     repo   - Repository in \"owner/repo\" format
     number - Issue/PR number
     event  - Hivemind event map {:a agent-id :e event-type :m message}

   Returns:
     Result map from comment-on-issue!"
  [client repo number event]
  (comment-on-issue! client repo number (format-hivemind-event event)))

(defn list-issues
  "List issues in a repository.

   Args:
     client - GitHub client
     repo   - Repository in \"owner/repo\" format
     opts   - Optional map with :state (:open, :closed, :all), :labels

   Returns:
     Sequence of issue maps"
  ([client repo]
   (list-issues client repo {}))
  ([client repo {:keys [state labels] :or {state :open}}]
   (let [repository (get-repo client repo)
         gh-state (case state
                    :open GHIssueState/OPEN
                    :closed GHIssueState/CLOSED
                    :all GHIssueState/ALL
                    GHIssueState/OPEN)
         issues (-> repository
                    (.getIssues gh-state))]
     (map (fn [issue]
            {:number (.getNumber issue)
             :title (.getTitle issue)
             :state (str (.getState issue))
             :url (str (.getHtmlUrl issue))
             :labels (mapv #(.getName %) (.getLabels issue))})
          issues))))

(defn close-issue!
  "Close an issue.

   Args:
     client - GitHub client
     repo   - Repository in \"owner/repo\" format
     number - Issue number

   Returns:
     {:ok true} on success
     {:ok false :error \"...\"} on failure"
  [client repo number]
  (try
    (let [repository (get-repo client repo)
          issue (.getIssue repository number)]
      (.close issue)
      {:ok true})
    (catch Exception e
      {:ok false
       :error (.getMessage e)})))

(comment
  ;; REPL examples

  ;; Create client
  (def client (create-client (System/getenv "GITHUB_TOKEN")))

  ;; Or from env directly
  (def client (create-client-from-env))

  ;; Create an issue
  (create-issue! client "hive-agi/hive-connectors"
                 "Test issue from connector"
                 "This is a test issue created via hive-connectors GitHub connector."
                 {:labels ["test" "automation"]})

  ;; Comment on an issue/PR
  (comment-on-issue! client "hive-agi/hive-connectors" 1
                     "Automated comment from hivemind agent")

  ;; Post a hivemind event to an issue
  (notify-issue! client "hive-agi/hive-connectors" 1
                 {:a "ling-1"
                  :e "completed"
                  :m "Finished implementing GitHub connector"})

  ;; List open issues
  (list-issues client "hive-agi/hive-connectors")

  ;; List closed issues
  (list-issues client "hive-agi/hive-connectors" {:state :closed}))
