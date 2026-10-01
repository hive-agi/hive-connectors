(ns hive.connectors.slack
  "Slack connector using official Java SDK.
   Sends hivemind events to Slack channels."
  (:import [com.slack.api Slack]
           [com.slack.api.methods.request.chat ChatPostMessageRequest]))

(defn create-client
  "Create a reusable Slack client instance.
   The client is thread-safe and should be reused across calls."
  []
  (Slack/getInstance))

(defn send-message!
  "Send a message to a Slack channel.

   Args:
     token   - Slack bot token (xoxb-...)
     channel - Channel name (#general) or ID (C01234...)
     text    - Message text (supports Slack mrkdwn formatting)

   Returns:
     {:ok true} on success
     {:ok false :error \"error_code\"} on failure"
  [token channel text]
  (let [client (create-client)
        methods (.methods client token)
        request (-> (ChatPostMessageRequest/builder)
                    (.channel channel)
                    (.text text)
                    (.build))
        response (.chatPostMessage methods request)]
    (if (.isOk response)
      {:ok true
       :ts (.getTs response)
       :channel (.getChannel response)}
      {:ok false
       :error (.getError response)})))

(defn post-message!
  "Send `text` to `channel` with options:
     :thread-ts  reply inside the thread of that message
     :unfurl?    link and media previews (default false)
   Same result shape as send-message!."
  [token channel text {:keys [thread-ts unfurl?]}]
  (let [builder (cond-> (-> (ChatPostMessageRequest/builder)
                            (.channel channel)
                            (.text text)
                            (.unfurlLinks (boolean unfurl?))
                            (.unfurlMedia (boolean unfurl?)))
                  thread-ts (.threadTs thread-ts))
        response (.chatPostMessage (.methods (create-client) token) (.build builder))]
    (if (.isOk response)
      {:ok true
       :ts (.getTs response)
       :channel (.getChannel response)}
      {:ok false
       :error (.getError response)})))

(defn format-hivemind-event
  "Format a hivemind event for Slack display.

   Event map keys:
     :a - agent ID (e.g., \"ling-1\")
     :e - event type (started, progress, completed, error, blocked)
     :m - message content

   Returns formatted mrkdwn string."
  [{:keys [a e m]}]
  (let [emoji (case e
                "started"   "🚀"
                "progress"  "⏳"
                "completed" "🎉"
                "error"     "❌"
                "blocked"   "🚧"
                "📢")
        event-display (case e
                        "started"   "started"
                        "progress"  "progress"
                        "completed" "completed"
                        "error"     "error"
                        "blocked"   "blocked"
                        e)]
    (format "%s *%s* %s: %s" emoji (or a "unknown") event-display (or m ""))))

(defn notify-channel!
  "Send a formatted hivemind event to a Slack channel.

   Args:
     token   - Slack bot token
     channel - Target channel
     event   - Hivemind event map {:a agent-id :e event-type :m message}

   Returns:
     Result map from send-message!"
  [token channel event]
  (send-message! token channel (format-hivemind-event event)))

(comment
  ;; REPL examples

  ;; Send a simple message
  (send-message! (System/getenv "SLACK_BOT_TOKEN")
                 "#hivemind"
                 "Hello from hive-connectors!")

  ;; Send a hivemind event
  (notify-channel! (System/getenv "SLACK_BOT_TOKEN")
                   "#hivemind"
                   {:a "ling-1"
                    :e "completed"
                    :m "Finished bootstrapping hive-connectors repo"})

  ;; Format without sending (for testing)
  (format-hivemind-event {:a "ling-2" :e "started" :m "Working on Slack connector"})
  ;; => "🚀 *ling-2* started: Working on Slack connector"
  )
