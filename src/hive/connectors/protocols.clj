(ns hive.connectors.protocols
  "Core protocols for hive-connectors.
   
   These protocols define the contract for external service integrations,
   following the IKGStore pattern from hive-mcp.")

;; =============================================================================
;; IConnector - Main connector protocol
;; =============================================================================

(defprotocol IConnector
  "Protocol for external service connectors.
   
   Implementations provide CRUD operations on external resources
   (issues, messages, tasks) with automatic mapping to hive memory format."
  
  (connector-id [this]
    "Return the connector identifier keyword.
     Examples: :github, :slack, :linear, :notion")
  
  (authenticate [this credentials]
    "Authenticate with the external service.
     
     Args:
       credentials - Map with auth data (varies by connector)
                    {:type :pat :token \"ghp_...\"} for GitHub PAT
                    {:type :oauth2 :access-token \"...\" :refresh-token \"...\"}
     
     Returns:
       {:ok true :client <client-instance>} on success
       {:ok false :error \"message\"} on failure")
  
  (capabilities [this]
    "Return set of supported operations.
     Possible values: #{:read :write :subscribe :search :webhook}")
  
  (schema [this]
    "Return the data schema for this connector's resources.
     Used for validation and mapping.")
  
  (query [this client params]
    "Query resources from the external service.
     
     Args:
       client - Authenticated client from `authenticate`
       params - Query parameters map
               {:resource :issues
                :repo \"owner/repo\"
                :state :open
                :limit 100}
     
     Returns:
       {:ok true :data [...]} on success
       {:ok false :error \"message\"} on failure")
  
  (mutate [this client op data]
    "Perform a mutation on the external service.
     
     Args:
       client - Authenticated client
       op     - Operation keyword (:create, :update, :delete, :close, :reopen)
       data   - Operation data map
     
     Returns:
       {:ok true :result {...}} on success
       {:ok false :error \"message\"} on failure")
  
  (subscribe [this client event-type callback]
    "Subscribe to events from the external service.
     
     Args:
       client     - Authenticated client
       event-type - Event to subscribe to (e.g., :issue-created, :pr-merged)
       callback   - Function to call with event data (fn [event] ...)
     
     Returns:
       {:ok true :subscription-id \"...\"} on success
       {:ok false :error \"message\"} on failure
     
     Note: Not all connectors support subscriptions."))

;; =============================================================================
;; IAuthProvider - Authentication provider protocol
;; =============================================================================

(defprotocol IAuthProvider
  "Protocol for authentication providers.
   
   Handles different auth mechanisms: PAT, OAuth2, API keys, etc."
  
  (auth-type [this]
    "Return the authentication type.
     Examples: :pat, :oauth2, :api-key, :bearer, :bot-token")
  
  (valid? [this credentials]
    "Check if credentials are still valid.
     
     Returns:
       true if credentials are valid
       false if expired or invalid")
  
  (refresh-token [this credentials]
    "Refresh expired credentials (for OAuth2 flows).
     
     Args:
       credentials - Current credentials with refresh token
     
     Returns:
       {:ok true :credentials {...new-creds...}} on success
       {:ok false :error \"message\"} on failure"))

;; =============================================================================
;; IDataMapper - Data mapping protocol
;; =============================================================================

(defprotocol IDataMapper
  "Protocol for mapping between external data and hive memory format.
   
   Provides bidirectional transformation between external service
   data formats and hive memory entries/kanban tasks."
  
  (to-memory [this external-data]
    "Convert external data to a hive memory entry.
     
     Args:
       external-data - Data from external service (issue, message, etc.)
     
     Returns:
       Memory entry map:
       {:type :note/:snippet/:decision
        :content \"...\"
        :tags [...]
        :metadata {:external-ref {:system :github :id \"...\"}
                   :external-url \"...\"}}")
  
  (to-task [this external-data]
    "Convert external data to a kanban task.
     
     Args:
       external-data - Data from external service (issue, task, etc.)
     
     Returns:
       Kanban task map:
       {:title \"...\"
        :description \"...\"
        :status :todo/:doing/:done
        :priority :low/:medium/:high
        :metadata {:external-ref {:system :github :id \"...\"}
                   :external-url \"...\"}}")
  
  (from-memory [this memory-entry]
    "Convert a hive memory entry to external service format.
     
     Args:
       memory-entry - Hive memory entry map
     
     Returns:
       External data format for the connector")
  
  (from-task [this kanban-task]
    "Convert a kanban task to external service format.
     
     Args:
       kanban-task - Hive kanban task map
     
     Returns:
       External data format for the connector"))

;; =============================================================================
;; IRateLimiter - Rate limiting protocol
;; =============================================================================

(defprotocol IRateLimiter
  "Protocol for rate limiting external API calls.
   
   Implementations handle connector-specific rate limits."
  
  (acquire! [this]
    "Acquire a rate limit token. Blocks if necessary.
     
     Returns:
       true when token acquired
       May throw if timeout exceeded")
  
  (remaining [this]
    "Return remaining requests in current window.
     
     Returns:
       {:remaining N :reset-at <instant>}")
  
  (backoff! [this retry-after]
    "Enter backoff state after rate limit hit.
     
     Args:
       retry-after - Seconds to wait (from Retry-After header)"))

;; =============================================================================
;; IWebhookHandler - Webhook handling protocol
;; =============================================================================

(defprotocol IWebhookHandler
  "Protocol for handling incoming webhooks from external services."
  
  (validate-signature [this request secret]
    "Validate webhook signature.
     
     Args:
       request - HTTP request map
       secret  - Webhook secret for HMAC verification
     
     Returns:
       true if signature valid, false otherwise")
  
  (parse-event [this request]
    "Parse webhook payload into normalized event.
     
     Args:
       request - HTTP request map with :body and :headers
     
     Returns:
       {:event-type :issue-created/:pr-merged/etc.
        :data {...normalized-event-data...}
        :raw {...original-payload...}}")
  
  (route-event [this event handlers]
    "Route parsed event to appropriate handler.
     
     Args:
       event    - Parsed event from parse-event
       handlers - Map of event-type -> handler-fn
     
     Returns:
       Result of handler execution"))

;; =============================================================================
;; Utility functions
;; =============================================================================

(defn connector?
  "Check if x implements IConnector."
  [x]
  (satisfies? IConnector x))

(defn auth-provider?
  "Check if x implements IAuthProvider."
  [x]
  (satisfies? IAuthProvider x))

(defn data-mapper?
  "Check if x implements IDataMapper."
  [x]
  (satisfies? IDataMapper x))
