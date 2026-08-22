# hive-connectors

<!-- hive-badges -->

[![Clojars Project](https://img.shields.io/clojars/v/io.github.hive-agi/hive-connectors.svg)](https://clojars.org/io.github.hive-agi/hive-connectors)
[![cljdoc](https://cljdoc.org/badge/io.github.hive-agi/hive-connectors)](https://cljdoc.org/d/io.github.hive-agi/hive-connectors/CURRENT)
[![release](https://github.com/hive-agi/hive-connectors/actions/workflows/release.yml/badge.svg)](https://github.com/hive-agi/hive-connectors/actions/workflows/release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

<!-- /hive-badges -->

Protocol-based connectors for [hive-mcp](https://github.com/hive-agi/hive-mcp). Enables hivemind agents to communicate through external channels.

## Available Connectors

| Connector | Status | Description |
|-----------|--------|-------------|
| **GitHub** | ✅ Full | Issues, PRs, comments, webhooks via IConnector protocol |
| **Slack** | ✅ MVP | Send hivemind events to Slack channels via official Java SDK |
| Linear | Planned | Task sync and notifications |
| Notion | Planned | Documentation sync |

## Installation

Add to your `deps.edn`:

```clojure
{:deps {io.github.hive-agi/hive-connectors {:git/tag "v0.2.0" :git/sha "..."}}}
```

## Core Protocols

### IConnector

The main protocol for external service integrations:

```clojure
(defprotocol IConnector
  (connector-id [this])          ; :github, :slack, :linear
  (authenticate [this creds])    ; Returns {:ok true :client ...}
  (capabilities [this])          ; #{:read :write :subscribe :webhook}
  (schema [this])                ; Data schema for validation
  (query [this client params])   ; Query resources
  (mutate [this client op data]) ; Create/update/delete
  (subscribe [this client event-type callback])) ; Real-time events
```

### IDataMapper

Bidirectional mapping between external data and hive memory format:

```clojure
(defprotocol IDataMapper
  (to-memory [this data])    ; External → memory entry
  (to-task [this data])      ; External → kanban task
  (from-memory [this entry]) ; Memory → external
  (from-task [this task]))   ; Kanban → external
```

### IWebhookHandler

Handle incoming webhooks from external services:

```clojure
(defprotocol IWebhookHandler
  (validate-signature [this request secret])
  (parse-event [this request])
  (route-event [this event handlers]))
```

## Usage

### GitHub Connector (Full Implementation)

```clojure
(require '[hive.connectors.github :as github]
         '[hive.connectors.protocols :as proto])

;; === Using IConnector Protocol ===

(def connector (github/make-connector))

;; Authenticate
(def auth-result (proto/authenticate connector {:token "ghp_..."}))
(def client (:client auth-result))

;; Query issues
(proto/query connector client
             {:resource :issues
              :repo "hive-agi/hive-connectors"
              :state :open
              :limit 50})

;; Query pull requests
(proto/query connector client
             {:resource :pull-requests
              :repo "hive-agi/hive-connectors"})

;; Get single issue/PR
(proto/query connector client
             {:resource :issue
              :repo "hive-agi/hive-connectors"
              :number 42})

(proto/query connector client
             {:resource :pull-request
              :repo "hive-agi/hive-connectors"
              :number 10})

;; Get PR files and reviews
(proto/query connector client
             {:resource :pr-files
              :repo "hive-agi/hive-connectors"
              :number 10})

(proto/query connector client
             {:resource :pr-reviews
              :repo "hive-agi/hive-connectors"
              :number 10})

;; Create issue
(proto/mutate connector client :create-issue
              {:repo "hive-agi/hive-connectors"
               :title "Bug: something broken"
               :body "Description..."
               :labels ["bug" "hivemind"]})

;; Comment on issue/PR
(proto/mutate connector client :comment
              {:repo "hive-agi/hive-connectors"
               :number 42
               :body "Automated update from hivemind"})

;; Post hivemind event notification
(proto/mutate connector client :notify
              {:repo "hive-agi/hive-connectors"
               :number 42
               :event {:a "ling-1" :e "completed" :m "Task finished"}})

;; Close/reopen issues
(proto/mutate connector client :close-issue
              {:repo "hive-agi/hive-connectors" :number 42})

(proto/mutate connector client :reopen-issue
              {:repo "hive-agi/hive-connectors" :number 42})
```

### GitHub Data Mapping

```clojure
(def mapper (github/make-data-mapper))

;; Convert GitHub issue to hive memory entry
(proto/to-memory mapper
                 {:number 42
                  :title "Bug: auth broken"
                  :body "Details..."
                  :url "https://github.com/..."
                  :labels ["bug" "priority:high"]})
;; => {:type :note
;;     :content "# Bug: auth broken\n\nDetails..."
;;     :tags ["github" "issue" "bug" "priority:high"]
;;     :metadata {:external-ref {:system :github :type :issue :id "42"}
;;                :external-url "https://github.com/..."}}

;; Convert to kanban task
(proto/to-task mapper issue)
;; => {:title "Bug: auth broken"
;;     :status :todo
;;     :priority :high
;;     ...}

;; Convert memory back to GitHub format
(proto/from-memory mapper memory-entry)
;; => {:title "..." :body "..." :labels [...]}
```

### GitHub Webhooks

```clojure
(def handler (github/make-webhook-handler))

;; In your webhook endpoint:
(defn handle-webhook [request]
  ;; Validate signature
  (when (proto/validate-signature handler request webhook-secret)
    ;; Parse event
    (let [event (proto/parse-event handler request)]
      ;; Route to handlers
      (proto/route-event handler event
        {:issue-opened (fn [e] (notify-hivemind! e))
         :pr-merged    (fn [e] (trigger-deploy! e))
         :pr-review-submitted (fn [e] (update-status! e))}))))

;; Supported event types:
;; :issue-opened, :issue-closed, :issue-reopened
;; :issue-comment-created
;; :pr-opened, :pr-closed, :pr-merged, :pr-updated
;; :pr-review-submitted
;; :push
```

### Slack Connector

```clojure
(require '[hive.connectors.slack :as slack])

;; Send a simple message
(slack/send-message! "xoxb-your-token" "#hivemind" "Hello from the hive!")

;; Format and send a hivemind event
(def event {:a "ling-1" :e "completed" :m "Finished refactoring auth module"})
(slack/notify-channel! "xoxb-your-token" "#hivemind" event)
;; => Posts: "🎉 *ling-1* completed: Finished refactoring auth module"
```

## Environment Variables

```bash
export GITHUB_TOKEN="ghp_your-token"
export SLACK_BOT_TOKEN="xoxb-your-bot-token"
export SLACK_CHANNEL="#hivemind"
```

Copy `.env.example` to `.env` and fill in your tokens.

## Required Permissions

### GitHub PAT Scopes

- `repo` - Full control of private repositories (or `public_repo` for public only)
- `write:discussion` - Optional, for discussion comments

### Slack OAuth Scopes

- `chat:write` - Post messages to channels
- `chat:write.public` - Post to channels the bot isn't a member of

## Testing

```bash
# Run all tests
clj -M:test

# Run with coverage
clj -M:test:coverage
```

Tests include:
- Unit tests for formatting, parsing, protocol compliance
- Integration tests (require `GITHUB_TOKEN`)

## Architecture

```
hive-connectors/
├── src/hive/connectors/
│   ├── protocols.clj    # IConnector, IDataMapper, IWebhookHandler, etc.
│   ├── github.clj       # Full GitHub implementation
│   └── slack.clj        # Slack messaging
└── test/hive/connectors/
    └── github_test.clj  # Comprehensive test suite
```

Each connector implements:
1. **IConnector** - Main CRUD operations
2. **IDataMapper** - Bidirectional data transformation
3. **IWebhookHandler** - Real-time event handling (where applicable)

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feat/linear-connector`)
3. Implement the protocols in `src/hive/connectors/`
4. Add tests in `test/hive/connectors/`
5. Run tests: `clj -M:test`
6. Submit a PR

## License

MIT License - see [LICENSE](LICENSE)
