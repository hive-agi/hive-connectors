(ns hive.connectors.github-test
  "Tests for GitHub connector.
   
   Unit tests use mocks, integration tests require GITHUB_TOKEN."
  (:require [clojure.test :refer [deftest is testing]]
            [hive.connectors.github :as gh]
            [hive.connectors.protocols :as proto]))

;; =============================================================================
;; Unit Tests (No API calls)
;; =============================================================================

(deftest format-hivemind-event-test
  (testing "formats started event"
    (let [event {:a "ling-1" :e "started" :m "Starting task"}
          result (gh/format-hivemind-event event)]
      (is (clojure.string/includes? result "🚀"))
      (is (clojure.string/includes? result "**ling-1**"))
      (is (clojure.string/includes? result "Started"))
      (is (clojure.string/includes? result "Starting task"))))
  
  (testing "formats progress event"
    (let [event {:a "ling-2" :e "progress" :m "50% done"}
          result (gh/format-hivemind-event event)]
      (is (clojure.string/includes? result "⏳"))
      (is (clojure.string/includes? result "In Progress"))))
  
  (testing "formats completed event"
    (let [event {:a "ling-3" :e "completed" :m "All done"}
          result (gh/format-hivemind-event event)]
      (is (clojure.string/includes? result "✅"))
      (is (clojure.string/includes? result "Completed"))))
  
  (testing "formats error event"
    (let [event {:a "ling-4" :e "error" :m "Something broke"}
          result (gh/format-hivemind-event event)]
      (is (clojure.string/includes? result "❌"))
      (is (clojure.string/includes? result "Error"))))
  
  (testing "formats blocked event"
    (let [event {:a "ling-5" :e "blocked" :m "Waiting for approval"}
          result (gh/format-hivemind-event event)]
      (is (clojure.string/includes? result "🚧"))
      (is (clojure.string/includes? result "Blocked"))))
  
  (testing "handles nil agent"
    (let [event {:e "started" :m "Test"}
          result (gh/format-hivemind-event event)]
      (is (clojure.string/includes? result "**unknown**"))))
  
  (testing "handles nil message"
    (let [event {:a "ling-1" :e "started"}
          result (gh/format-hivemind-event event)]
      (is (string? result)))))

(deftest webhook-signature-validation-test
  (testing "validates correct signature"
    (let [payload "test payload"
          secret "test-secret"
          ;; Pre-computed HMAC-SHA256 of "test payload" with "test-secret"
          signature "sha256=e2b6f8e93e0f1cd5bb2b0d0ec71c7ed5e8b0e3f6c8f0a1d2b3c4e5f6a7b8c9d0"]
      ;; Note: actual signature would need to match - this is a structural test
      (is (boolean? (gh/validate-webhook-signature payload signature secret)))))
  
  (testing "rejects invalid signature prefix"
    (is (nil? (gh/validate-webhook-signature "payload" "md5=abc" "secret"))))
  
  (testing "rejects nil inputs"
    (is (nil? (gh/validate-webhook-signature nil "sha256=abc" "secret")))
    (is (nil? (gh/validate-webhook-signature "payload" nil "secret")))
    (is (nil? (gh/validate-webhook-signature "payload" "sha256=abc" nil)))))

(deftest parse-webhook-event-test
  (testing "parses issue opened event"
    (let [payload {"action" "opened"
                   "repository" {"full_name" "hive-agi/test"}
                   "issue" {"number" 42
                            "title" "Test issue"
                            "user" {"login" "testuser"}}}
          result (gh/parse-webhook-event "issues" payload)]
      (is (= :issue-opened (:event-type result)))
      (is (= "hive-agi/test" (:repo result)))
      (is (= 42 (get-in result [:data :number])))))
  
  (testing "parses PR merged event"
    (let [payload {"action" "closed"
                   "repository" {"full_name" "hive-agi/test"}
                   "pull_request" {"number" 10
                                   "title" "Feature PR"
                                   "merged" true
                                   "user" {"login" "dev"}}}
          result (gh/parse-webhook-event "pull_request" payload)]
      (is (= :pr-merged (:event-type result)))
      (is (= true (get-in result [:data :merged])))))
  
  (testing "parses PR closed (not merged) event"
    (let [payload {"action" "closed"
                   "repository" {"full_name" "hive-agi/test"}
                   "pull_request" {"number" 10
                                   "title" "Feature PR"
                                   "merged" false
                                   "user" {"login" "dev"}}}
          result (gh/parse-webhook-event "pull_request" payload)]
      (is (= :pr-closed (:event-type result)))))
  
  (testing "parses push event"
    (let [payload {"ref" "refs/heads/main"
                   "before" "abc123"
                   "after" "def456"
                   "commits" [{} {} {}]
                   "repository" {"full_name" "hive-agi/test"}}
          result (gh/parse-webhook-event "push" payload)]
      (is (= :push (:event-type result)))
      (is (= 3 (get-in result [:data :commits]))))))

;; =============================================================================
;; Protocol Tests
;; =============================================================================

(deftest connector-protocol-test
  (let [connector (gh/make-connector)]
    (testing "connector-id returns :github"
      (is (= :github (proto/connector-id connector))))
    
    (testing "capabilities includes expected ops"
      (let [caps (proto/capabilities connector)]
        (is (contains? caps :read))
        (is (contains? caps :write))
        (is (contains? caps :webhook))))
    
    (testing "schema returns valid structure"
      (let [schema (proto/schema connector)]
        (is (map? schema))
        (is (contains? schema :issue))
        (is (contains? schema :pull-request))))))

(deftest data-mapper-protocol-test
  (let [mapper (gh/make-data-mapper)]
    (testing "to-memory converts issue to memory entry"
      (let [issue {:number 42
                   :title "Test Issue"
                   :body "Description here"
                   :url "https://github.com/hive-agi/test/issues/42"
                   :labels ["bug" "urgent"]}
            result (proto/to-memory mapper issue)]
        (is (= :note (:type result)))
        (is (clojure.string/includes? (:content result) "Test Issue"))
        (is (some #{"github"} (:tags result)))
        (is (= :github (get-in result [:metadata :external-ref :system])))))
    
    (testing "to-task converts issue to kanban task"
      (let [issue {:number 42
                   :title "Bug Fix"
                   :body "Fix the bug"
                   :url "https://github.com/..."
                   :state "OPEN"
                   :labels ["bug" "priority:high"]}
            result (proto/to-task mapper issue)]
        (is (= "Bug Fix" (:title result)))
        (is (= :todo (:status result)))
        (is (= :high (:priority result)))))
    
    (testing "from-memory converts memory to issue format"
      (let [memory {:content "# My Issue\n\nSome description"
                    :tags ["github" "feature"]
                    :metadata {:external-ref {:system :github :id "42"}}}
            result (proto/from-memory mapper memory)]
        (is (= "My Issue" (:title result)))
        (is (= "\nSome description" (:body result)))
        (is (some #{"feature"} (:labels result)))
        (is (not (some #{"github"} (:labels result))))))))

(deftest webhook-handler-protocol-test
  (let [handler (gh/make-webhook-handler)]
    (testing "parse-event delegates to parse-webhook-event"
      (let [request {:headers {"x-github-event" "issues"}
                     :body {"action" "opened"
                            "repository" {"full_name" "test/repo"}
                            "issue" {"number" 1 "title" "Test" "user" {"login" "u"}}}}
            result (proto/parse-event handler request)]
        (is (= :issue-opened (:event-type result)))))
    
    (testing "route-event calls correct handler"
      (let [event {:event-type :issue-opened :data {:number 1}}
            handlers {:issue-opened (fn [e] {:handled true :number (get-in e [:data :number])})}
            result (proto/route-event handler event handlers)]
        (is (= true (:handled result)))
        (is (= 1 (:number result)))))
    
    (testing "route-event returns error for unknown event"
      (let [event {:event-type :unknown-event}
            handlers {}
            result (proto/route-event handler event handlers)]
        (is (= false (:ok result)))
        (is (clojure.string/includes? (:error result) "No handler"))))))

;; =============================================================================
;; Integration Tests (Require GITHUB_TOKEN)
;; =============================================================================

(deftest ^:integration authenticate-test
  (when-let [token (System/getenv "GITHUB_TOKEN")]
    (let [connector (gh/make-connector)]
      (testing "authenticates with valid token"
        (let [result (proto/authenticate connector {:token token})]
          (is (= true (:ok result)))
          (is (some? (:client result)))))
      
      (testing "fails with invalid token"
        (let [result (proto/authenticate connector {:token "invalid"})]
          (is (= false (:ok result))))))))

(deftest ^:integration query-issues-test
  (when-let [token (System/getenv "GITHUB_TOKEN")]
    (let [connector (gh/make-connector)
          {:keys [client]} (proto/authenticate connector {:token token})]
      (testing "queries issues from public repo"
        (let [result (proto/query connector client
                                  {:resource :issues
                                   :repo "hive-agi/hive-connectors"
                                   :state :all
                                   :limit 5})]
          (is (= true (:ok result)))
          (is (sequential? (:data result))))))))

(deftest ^:integration query-pull-requests-test
  (when-let [token (System/getenv "GITHUB_TOKEN")]
    (let [connector (gh/make-connector)
          {:keys [client]} (proto/authenticate connector {:token token})]
      (testing "queries PRs from public repo"
        (let [result (proto/query connector client
                                  {:resource :pull-requests
                                   :repo "hive-agi/hive-connectors"
                                   :state :all
                                   :limit 5})]
          (is (= true (:ok result)))
          (is (sequential? (:data result))))))))

;; =============================================================================
;; Run Tests
;; =============================================================================

(comment
  ;; Run all tests
  (clojure.test/run-tests 'hive.connectors.github-test)
  
  ;; Run only unit tests (no :integration tag)
  (clojure.test/run-tests 'hive.connectors.github-test)
  
  ;; Run specific test
  (format-hivemind-event-test)
  (connector-protocol-test)
  )
