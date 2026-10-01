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
| **Daily digest** | ✅ IAddon | hive-agi activity, posted to Slack once a day with a Markdown copy |
| Linear | Planned | Task sync and notifications |
| Notion | Planned | Documentation sync |

## Daily digest (IAddon `hive.connectors`)

The jar carries `META-INF/hive-addons/hive-connectors.edn`, so a hive that has
hive-connectors on its classpath mounts the `hive.connectors` addon. Once a day
it posts a digest to Slack:

- **Released on Clojars**: every artifact of the Maven group
  (`io.github.hive-agi`) whose new version was deployed inside the window,
  as `hive-addon 1.1.0 (was 1.0.16)`. Underneath each one is its changelog,
  made of the features and fixes merged in its repo. The release date comes
  from the `lastUpdated` stamp in the artifact's `maven-metadata.xml`.
- **Also merged**: repos that merged work without a release, grouped by repo.
  Features, perf work and fixes are listed. CI, chores, tests, docs and
  refactors are counted, not listed. Staging promotions repeat work already
  listed, so they are left out.
- **From the hive store**: the hive-store release feed (`https://store.hive-mcp.com/api/feed`),
  minus anything already listed as a Clojars release.
- **Elsewhere**: items from any other RSS or Atom feed you add.

The post is Slack mrkdwn, which copies cleanly into another Slack workspace
(Clojurians, for example). Its thread carries the same digest as Markdown in
a code block, for ClojureVerse, Reddit or Discord.

```text
Clojars metadata (releases) ─┐
GitHub search (merged PRs) ──┤
hive-store feed (releases) ──┼─▶ compose ─▶ render ─▶ Slack post + Markdown thread reply
other RSS / Atom (news) ─────┘                          (once a day, state file dedupes)
```

Configure it in `~/.config/hive-mcp/config.edn`:

```clojure
{:addons
 {"hive.connectors"
  {:digest/channel      "#updates"                     ; channel name or id (the default)
   :digest/maven-group  "io.github.hive-agi"           ; nil turns the Clojars section off
   :digest/hour         9                              ; local hour the post is due
   :digest/zone         "America/Bahia"
   :digest/org          "hive-agi"
   :digest/slack-token  {:env "SLACK_BOT_TOKEN"
                         :command ["pass" "show" "slack/api/xoxb-..."]}
   :digest/github-token {:env "GITHUB_TOKEN" :command ["gh" "auth" "token"]}
   :digest/feeds        [{:feed/id "hive-store"
                          :feed/url "https://store.hive-mcp.com/api/feed"
                          :feed/role :release}
                         "https://planet.clojure.in/atom.xml"]}}}   ; a bare URL is :news
```

Each secret resolves per call: the env var first, then the first line the
command prints. Rotating a token needs no restart. The bot needs the
`chat:write` scope and must be a member of the channel.

Use the `connectors` tool (`status`, `preview`, `post`, and `force=true` to
post again the same day), or the shell:

```bash
clojure -M:digest preview          # print the Slack text and the Markdown copy
clojure -M:digest post             # post now (once a day)
clojure -M:digest post --force     # post even if today already has a post
```

The addon and the CLI share the state file
(`~/.local/state/hive-connectors/digest.edn`), so they never post the same day
twice. If GitHub is unreachable, nothing is posted and nothing is recorded,
and the next tick (every 10 minutes) retries. Each window starts where the
previous post ended, so a day the host was down is not lost (the window is
capped at 72 hours).

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
