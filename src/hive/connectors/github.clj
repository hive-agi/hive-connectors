(ns hive.connectors.github
  "GitHub connector implementing IConnector protocol.
   
   Uses the official Java SDK (kohsuke/github-api) for:
   - Issue management (CRUD, comments, labels)
   - Pull request operations (list, comment, review status)
   - Webhook handling for real-time events
   - OAuth/PAT authentication"
  (:require [hive.connectors.protocols :as proto])
  (:import [org.kohsuke.github GitHub GitHubBuilder GHIssueState GHPullRequestQueryBuilder$Sort]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.util HexFormat]))

;; =============================================================================
;; Client Creation
;; =============================================================================

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

;; =============================================================================
;; Repository Operations
;; =============================================================================

(defn get-repo
  "Get a repository by owner/name.
   
   Args:
     client - GitHub client
     repo   - Repository in \"owner/repo\" format
   
   Returns:
     GHRepository instance"
  [client repo]
  (.getRepository client repo))

;; =============================================================================
;; Issue Operations
;; =============================================================================

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

(defn get-issue
  "Get a single issue by number.
   
   Args:
     client - GitHub client
     repo   - Repository in \"owner/repo\" format
     number - Issue number
   
   Returns:
     Issue map or nil if not found"
  [client repo number]
  (try
    (let [repository (get-repo client repo)
          issue (.getIssue repository number)]
      {:number (.getNumber issue)
       :title (.getTitle issue)
       :body (.getBody issue)
       :state (str (.getState issue))
       :url (str (.getHtmlUrl issue))
       :labels (mapv #(.getName %) (.getLabels issue))
       :assignees (mapv #(.getLogin %) (.getAssignees issue))
       :created-at (some-> (.getCreatedAt issue) .toInstant str)
       :updated-at (some-> (.getUpdatedAt issue) .toInstant str)
       :author (some-> (.getUser issue) .getLogin)})
    (catch Exception _e
      nil)))

(defn update-issue!
  "Update an existing issue.
   
   Args:
     client - GitHub client
     repo   - Repository in \"owner/repo\" format
     number - Issue number
     data   - Map with optional :title, :body, :labels, :assignees
   
   Returns:
     {:ok true} on success
     {:ok false :error \"...\"} on failure"
  [client repo number {:keys [title body labels assignees]}]
  (try
    (let [repository (get-repo client repo)
          issue (.getIssue repository number)]
      (when title (.setTitle issue title))
      (when body (.setBody issue body))
      (when labels (.setLabels issue (into-array String labels)))
      (when assignees
        (doseq [a (.getAssignees issue)] (.remove issue a))
        (doseq [a assignees] (.add issue (.getUser client a))))
      {:ok true})
    (catch Exception e
      {:ok false
       :error (.getMessage e)})))

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

(defn list-issues
  "List issues in a repository.
   
   Args:
     client - GitHub client
     repo   - Repository in \"owner/repo\" format
     opts   - Optional map with :state (:open, :closed, :all), :labels, :limit
   
   Returns:
     Sequence of issue maps"
  ([client repo]
   (list-issues client repo {}))
  ([client repo {:keys [state labels limit] :or {state :open limit 100}}]
   (let [repository (get-repo client repo)
         gh-state (case state
                    :open GHIssueState/OPEN
                    :closed GHIssueState/CLOSED
                    :all GHIssueState/ALL
                    GHIssueState/OPEN)
         issues (-> repository
                    (.getIssues gh-state))]
     (->> issues
          (take limit)
          (map (fn [issue]
                 {:number (.getNumber issue)
                  :title (.getTitle issue)
                  :state (str (.getState issue))
                  :url (str (.getHtmlUrl issue))
                  :labels (mapv #(.getName %) (.getLabels issue))
                  :is-pr (.isPullRequest issue)}))))))

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

(defn reopen-issue!
  "Reopen a closed issue.
   
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
      (.reopen issue)
      {:ok true})
    (catch Exception e
      {:ok false
       :error (.getMessage e)})))

;; =============================================================================
;; Pull Request Operations
;; =============================================================================

(defn list-pull-requests
  "List pull requests in a repository.
   
   Args:
     client - GitHub client
     repo   - Repository in \"owner/repo\" format
     opts   - Optional map with :state (:open, :closed, :all), :sort, :limit
   
   Returns:
     Sequence of PR maps"
  ([client repo]
   (list-pull-requests client repo {}))
  ([client repo {:keys [state sort limit] :or {state :open limit 100}}]
   (try
     (let [repository (get-repo client repo)
           gh-state (case state
                      :open GHIssueState/OPEN
                      :closed GHIssueState/CLOSED
                      :all GHIssueState/ALL
                      GHIssueState/OPEN)
           prs (.getPullRequests repository gh-state)]
       (->> prs
            (take limit)
            (map (fn [pr]
                   {:number (.getNumber pr)
                    :title (.getTitle pr)
                    :state (str (.getState pr))
                    :url (str (.getHtmlUrl pr))
                    :head-ref (some-> (.getHead pr) .getRef)
                    :base-ref (some-> (.getBase pr) .getRef)
                    :author (some-> (.getUser pr) .getLogin)
                    :mergeable (.getMergeable pr)
                    :draft (.isDraft pr)
                    :labels (mapv #(.getName %) (.getLabels pr))}))))
     (catch Exception _e
       []))))

(defn get-pull-request
  "Get a single pull request by number.
   
   Args:
     client - GitHub client
     repo   - Repository in \"owner/repo\" format
     number - PR number
   
   Returns:
     PR map or nil if not found"
  [client repo number]
  (try
    (let [repository (get-repo client repo)
          pr (.getPullRequest repository number)]
      {:number (.getNumber pr)
       :title (.getTitle pr)
       :body (.getBody pr)
       :state (str (.getState pr))
       :url (str (.getHtmlUrl pr))
       :head-ref (some-> (.getHead pr) .getRef)
       :base-ref (some-> (.getBase pr) .getRef)
       :head-sha (some-> (.getHead pr) .getSha)
       :author (some-> (.getUser pr) .getLogin)
       :mergeable (.getMergeable pr)
       :merged (.isMerged pr)
       :draft (.isDraft pr)
       :labels (mapv #(.getName %) (.getLabels pr))
       :additions (.getAdditions pr)
       :deletions (.getDeletions pr)
       :changed-files (.getChangedFiles pr)
       :created-at (some-> (.getCreatedAt pr) .toInstant str)
       :updated-at (some-> (.getUpdatedAt pr) .toInstant str)})
    (catch Exception _e
      nil)))

(defn comment-on-pr!
  "Add a comment to a pull request (alias for comment-on-issue!)."
  [client repo number body]
  (comment-on-issue! client repo number body))

(defn list-pr-files
  "List files changed in a pull request.
   
   Args:
     client - GitHub client
     repo   - Repository in \"owner/repo\" format
     number - PR number
   
   Returns:
     Sequence of file change maps"
  [client repo number]
  (try
    (let [repository (get-repo client repo)
          pr (.getPullRequest repository number)
          files (.listFiles pr)]
      (map (fn [f]
             {:filename (.getFilename f)
              :status (.getStatus f)
              :additions (.getAdditions f)
              :deletions (.getDeletions f)
              :changes (.getChanges f)
              :patch (.getPatch f)})
           files))
    (catch Exception _e
      [])))

(defn list-pr-reviews
  "List reviews on a pull request.
   
   Args:
     client - GitHub client
     repo   - Repository in \"owner/repo\" format
     number - PR number
   
   Returns:
     Sequence of review maps"
  [client repo number]
  (try
    (let [repository (get-repo client repo)
          pr (.getPullRequest repository number)
          reviews (.listReviews pr)]
      (map (fn [r]
             {:id (.getId r)
              :user (some-> (.getUser r) .getLogin)
              :state (str (.getState r))
              :body (.getBody r)
              :submitted-at (some-> (.getSubmittedAt r) .toInstant str)})
           reviews))
    (catch Exception _e
      [])))

;; =============================================================================
;; Hivemind Event Formatting
;; =============================================================================

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

;; =============================================================================
;; Webhook Signature Validation
;; =============================================================================

(defn- hmac-sha256
  "Compute HMAC-SHA256 of message with secret."
  [secret message]
  (let [mac (Mac/getInstance "HmacSHA256")
        key (SecretKeySpec. (.getBytes secret "UTF-8") "HmacSHA256")]
    (.init mac key)
    (.doFinal mac (.getBytes message "UTF-8"))))

(defn- bytes->hex
  "Convert byte array to hex string."
  [bytes]
  (.formatHex (HexFormat/of) bytes))

(defn validate-webhook-signature
  "Validate GitHub webhook signature.
   
   Args:
     payload   - Raw request body string
     signature - X-Hub-Signature-256 header value (sha256=...)
     secret    - Webhook secret configured in GitHub
   
   Returns:
     true if signature is valid, false otherwise"
  [payload signature secret]
  (when (and payload signature secret
             (.startsWith signature "sha256="))
    (let [expected-sig (subs signature 7)
          actual-sig (bytes->hex (hmac-sha256 secret payload))]
      (= expected-sig actual-sig))))

(defn parse-webhook-event
  "Parse a GitHub webhook payload into a normalized event.
   
   Args:
     event-type - X-GitHub-Event header value
     payload    - Parsed JSON payload (map)
   
   Returns:
     {:event-type :issue-opened/:pr-merged/etc.
      :repo \"owner/repo\"
      :data {...normalized-data...}
      :raw payload}"
  [event-type payload]
  (let [action (get payload "action")
        repo (get-in payload ["repository" "full_name"])
        normalized-type (case [event-type action]
                          ["issues" "opened"] :issue-opened
                          ["issues" "closed"] :issue-closed
                          ["issues" "reopened"] :issue-reopened
                          ["issue_comment" "created"] :issue-comment-created
                          ["pull_request" "opened"] :pr-opened
                          ["pull_request" "closed"] (if (get-in payload ["pull_request" "merged"])
                                                      :pr-merged
                                                      :pr-closed)
                          ["pull_request" "synchronize"] :pr-updated
                          ["pull_request_review" "submitted"] :pr-review-submitted
                          ["push" nil] :push
                          :unknown)]
    {:event-type normalized-type
     :repo repo
     :action action
     :data (case event-type
             "issues" {:number (get-in payload ["issue" "number"])
                       :title (get-in payload ["issue" "title"])
                       :author (get-in payload ["issue" "user" "login"])}
             "pull_request" {:number (get-in payload ["pull_request" "number"])
                             :title (get-in payload ["pull_request" "title"])
                             :author (get-in payload ["pull_request" "user" "login"])
                             :merged (get-in payload ["pull_request" "merged"])}
             "issue_comment" {:issue-number (get-in payload ["issue" "number"])
                              :comment-id (get-in payload ["comment" "id"])
                              :author (get-in payload ["comment" "user" "login"])
                              :body (get-in payload ["comment" "body"])}
             "push" {:ref (get payload "ref")
                     :before (get payload "before")
                     :after (get payload "after")
                     :commits (count (get payload "commits" []))}
             payload)
     :raw payload}))

;; =============================================================================
;; IConnector Protocol Implementation
;; =============================================================================

(defrecord GitHubConnector [token]
  proto/IConnector
  
  (connector-id [_this]
    :github)
  
  (authenticate [_this credentials]
    (try
      (let [token (or (:token credentials)
                      (System/getenv "GITHUB_TOKEN"))]
        (if token
          {:ok true
           :client (create-client token)}
          {:ok false
           :error "No token provided. Set :token in credentials or GITHUB_TOKEN env var."}))
      (catch Exception e
        {:ok false
         :error (.getMessage e)})))
  
  (capabilities [_this]
    #{:read :write :search :webhook})
  
  (schema [_this]
    {:issue {:number :int
             :title :string
             :body :string
             :state #{:open :closed}
             :labels [:string]
             :assignees [:string]}
     :pull-request {:number :int
                    :title :string
                    :body :string
                    :state #{:open :closed :merged}
                    :head-ref :string
                    :base-ref :string
                    :draft :boolean}})
  
  (query [_this client params]
    (try
      (let [{:keys [resource repo state limit]
             :or {state :open limit 100}} params]
        (case resource
          :issues {:ok true
                   :data (list-issues client repo {:state state :limit limit})}
          :pull-requests {:ok true
                          :data (list-pull-requests client repo {:state state :limit limit})}
          :issue (if-let [issue (get-issue client repo (:number params))]
                   {:ok true :data issue}
                   {:ok false :error "Issue not found"})
          :pull-request (if-let [pr (get-pull-request client repo (:number params))]
                          {:ok true :data pr}
                          {:ok false :error "Pull request not found"})
          :pr-files {:ok true
                     :data (list-pr-files client repo (:number params))}
          :pr-reviews {:ok true
                       :data (list-pr-reviews client repo (:number params))}
          {:ok false :error (str "Unknown resource: " resource)}))
      (catch Exception e
        {:ok false
         :error (.getMessage e)})))
  
  (mutate [_this client op data]
    (let [{:keys [repo number title body labels assignees]} data]
      (case op
        :create-issue (create-issue! client repo title body
                                     {:labels labels :assignees assignees})
        :update-issue (update-issue! client repo number data)
        :close-issue (close-issue! client repo number)
        :reopen-issue (reopen-issue! client repo number)
        :comment (comment-on-issue! client repo number body)
        :notify (notify-issue! client repo number (:event data))
        {:ok false :error (str "Unknown operation: " op)})))
  
  (subscribe [_this _client _event-type _callback]
    ;; GitHub uses webhooks, not subscriptions
    {:ok false
     :error "GitHub uses webhooks. Configure webhook URL in repository settings."}))

;; =============================================================================
;; IDataMapper Protocol Implementation
;; =============================================================================

(defrecord GitHubDataMapper []
  proto/IDataMapper
  
  (to-memory [_this external-data]
    (let [{:keys [number title body url labels]} external-data]
      {:type :note
       :content (format "# %s\n\n%s" title (or body ""))
       :tags (into ["github" "issue"] labels)
       :metadata {:external-ref {:system :github
                                 :type :issue
                                 :id (str number)}
                  :external-url url}}))
  
  (to-task [_this external-data]
    (let [{:keys [number title body url state labels]} external-data
          priority (cond
                     (some #{"priority:high" "urgent" "P0"} labels) :high
                     (some #{"priority:low" "P2" "backlog"} labels) :low
                     :else :medium)
          status (case state
                   "OPEN" :todo
                   "CLOSED" :done
                   :todo)]
      {:title title
       :description body
       :status status
       :priority priority
       :tags (into ["github"] labels)
       :metadata {:external-ref {:system :github
                                 :type :issue
                                 :id (str number)}
                  :external-url url}}))
  
  (from-memory [_this memory-entry]
    (let [{:keys [content tags metadata]} memory-entry
          lines (clojure.string/split-lines content)
          title (clojure.string/replace (first lines) #"^#\s*" "")
          body (clojure.string/join "\n" (rest lines))]
      {:title title
       :body body
       :labels (filterv #(not= % "github") tags)}))
  
  (from-task [_this kanban-task]
    (let [{:keys [title description tags priority]} kanban-task
          priority-label (case priority
                           :high "priority:high"
                           :low "priority:low"
                           nil)]
      {:title title
       :body description
       :labels (cond-> (filterv #(not= % "github") tags)
                 priority-label (conj priority-label))})))

;; =============================================================================
;; IWebhookHandler Protocol Implementation
;; =============================================================================

(defrecord GitHubWebhookHandler []
  proto/IWebhookHandler
  
  (validate-signature [_this request secret]
    (let [payload (:body request)
          signature (get-in request [:headers "x-hub-signature-256"])]
      (validate-webhook-signature payload signature secret)))
  
  (parse-event [_this request]
    (let [event-type (get-in request [:headers "x-github-event"])
          payload (:body request)]
      (parse-webhook-event event-type payload)))
  
  (route-event [_this event handlers]
    (let [handler (get handlers (:event-type event))]
      (if handler
        (handler event)
        {:ok false :error (str "No handler for event: " (:event-type event))}))))

;; =============================================================================
;; Constructor Functions
;; =============================================================================

(defn make-connector
  "Create a new GitHubConnector instance.
   
   Args:
     opts - Optional map with :token
   
   Returns:
     GitHubConnector record"
  ([]
   (make-connector {}))
  ([{:keys [token]}]
   (->GitHubConnector (or token (System/getenv "GITHUB_TOKEN")))))

(defn make-data-mapper
  "Create a new GitHubDataMapper instance."
  []
  (->GitHubDataMapper))

(defn make-webhook-handler
  "Create a new GitHubWebhookHandler instance."
  []
  (->GitHubWebhookHandler))

;; =============================================================================
;; REPL Examples
;; =============================================================================

(comment
  ;; Create client
  (def client (create-client (System/getenv "GITHUB_TOKEN")))

  ;; Or from env directly
  (def client (create-client-from-env))

  ;; === Using functions directly ===
  
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
  (list-issues client "hive-agi/hive-connectors" {:state :closed})

  ;; List pull requests
  (list-pull-requests client "hive-agi/hive-connectors")

  ;; Get PR details
  (get-pull-request client "hive-agi/hive-connectors" 1)

  ;; List files changed in PR
  (list-pr-files client "hive-agi/hive-connectors" 1)

  ;; === Using IConnector protocol ===
  
  (def connector (make-connector))
  
  ;; Authenticate
  (def auth-result (proto/authenticate connector {:token (System/getenv "GITHUB_TOKEN")}))
  (def gh-client (:client auth-result))
  
  ;; Query issues
  (proto/query connector gh-client {:resource :issues
                                    :repo "hive-agi/hive-connectors"
                                    :state :open})
  
  ;; Query PRs
  (proto/query connector gh-client {:resource :pull-requests
                                    :repo "hive-agi/hive-connectors"})
  
  ;; Create issue via mutate
  (proto/mutate connector gh-client :create-issue
                {:repo "hive-agi/hive-connectors"
                 :title "Test via IConnector"
                 :body "Testing the protocol"})
  
  ;; Comment via mutate
  (proto/mutate connector gh-client :comment
                {:repo "hive-agi/hive-connectors"
                 :number 1
                 :body "Comment via IConnector protocol"})
  
  ;; === Using IDataMapper ===
  
  (def mapper (make-data-mapper))
  
  ;; Convert issue to memory entry
  (proto/to-memory mapper {:number 42
                           :title "Bug: something broken"
                           :body "Details here..."
                           :url "https://github.com/..."
                           :labels ["bug" "urgent"]})
  
  ;; Convert issue to kanban task
  (proto/to-task mapper {:number 42
                         :title "Bug: something broken"
                         :body "Details here..."
                         :url "https://github.com/..."
                         :state "OPEN"
                         :labels ["bug" "priority:high"]})
  )
