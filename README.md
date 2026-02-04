# hive-connectors

Protocol-based connectors for [hive-mcp](https://github.com/hive-agi/hive-mcp). Enables hivemind agents to communicate through external channels.

## Available Connectors

| Connector | Status | Description |
|-----------|--------|-------------|
| **Slack** | MVP | Send hivemind events to Slack channels via official Java SDK |
| GitHub | Planned | Issue/PR notifications and comments |
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

### Environment Variables

```bash
export SLACK_BOT_TOKEN="xoxb-your-bot-token"
export SLACK_CHANNEL="#hivemind"
```

### Required Slack Permissions

Your Slack app needs these OAuth scopes:
- `chat:write` - Post messages to channels
- `chat:write.public` - Post to channels the bot isn't a member of

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
