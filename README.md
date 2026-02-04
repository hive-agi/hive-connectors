# hive-connectors

Protocol-based connectors for [hive-mcp](https://github.com/hive-agi/hive-mcp). Enables hivemind agents to communicate through external channels.

## Available Connectors

| Connector | Status | Description |
|-----------|--------|-------------|
| **Slack** | ✅ MVP | Send hivemind events to Slack channels via official Java SDK |
| **GitHub** | ✅ MVP | Issue/PR notifications, comments, and management via kohsuke/github-api |
| Linear | Planned | Task sync and notifications |
| Notion | Planned | Documentation sync |

## Installation

Add to your `deps.edn`:

```clojure
{:deps {io.github.hive-agi/hive-connectors {:git/tag "v0.1.0" :git/sha "..."}}}
```

## Usage

### Slack Connector

```clojure
(require '[hive.connectors.slack :as slack])

;; Create a client (singleton, reusable)
(def client (slack/create-client))

;; Send a simple message
(slack/send-message! "xoxb-your-token" "#hivemind" "Hello from the hive!")

;; Format and send a hivemind event
(def event {:a "ling-1" :e "completed" :m "Finished refactoring auth module"})
(slack/notify-channel! "xoxb-your-token" "#hivemind" event)
;; => Posts formatted message: "🎉 *ling-1* completed: Finished refactoring auth module"
```

### GitHub Connector

```clojure
(require '[hive.connectors.github :as github])

;; Create a client with token
(def client (github/create-client "ghp_your-token"))
;; Or from GITHUB_TOKEN env var
(def client (github/create-client-from-env))

;; Create an issue
(github/create-issue! client "hive-agi/hive-connectors"
                      "Bug: something is broken"
                      "Description of the issue..."
                      {:labels ["bug" "hivemind"]})
;; => {:ok true :number 42 :url "https://github.com/..."}

;; Comment on an issue/PR
(github/comment-on-issue! client "hive-agi/hive-connectors" 42
                          "Automated update from hivemind")

;; Post a hivemind event to an issue
(github/notify-issue! client "hive-agi/hive-connectors" 42
                      {:a "ling-1" :e "completed" :m "Fixed the bug"})
;; => Posts: "✅ **ling-1** | Completed\n\nFixed the bug"

;; List open issues
(github/list-issues client "hive-agi/hive-connectors")
```

### Environment Variables

```bash
export SLACK_BOT_TOKEN="xoxb-your-bot-token"
export SLACK_CHANNEL="#hivemind"
export GITHUB_TOKEN="ghp_your-token"
```

Copy `.env.example` to `.env` and fill in your tokens.

### Required Slack Permissions

Your Slack app needs these OAuth scopes:
- `chat:write` - Post messages to channels
- `chat:write.public` - Post to channels the bot isn't a member of

### Required GitHub Permissions

Your GitHub PAT needs these scopes:
- `repo` - Full control of private repositories (or `public_repo` for public only)
- `write:discussion` - Optional, for discussion comments

## Architecture

Each connector implements a common pattern:
1. **create-client** - Initialize the external service client
2. **send-message!** - Low-level message sending
3. **format-hivemind-event** - Convert hivemind event to channel-appropriate format
4. **notify-channel!** - High-level: format + send

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feat/discord-connector`)
3. Follow the existing connector pattern in `src/hive/connectors/`
4. Add tests in `test/hive/connectors/`
5. Submit a PR

### Adding a New Connector

```clojure
(ns hive.connectors.your-service
  "Your service connector for hive-mcp.")

(defn create-client []
  ;; Initialize service client
  )

(defn send-message! [client channel text]
  ;; Send raw message
  )

(defn format-hivemind-event [{:keys [a e m]}]
  ;; Format event for this channel
  )

(defn notify-channel! [client channel event]
  ;; Format + send
  )
```

## License

MIT License - see [LICENSE](LICENSE)
