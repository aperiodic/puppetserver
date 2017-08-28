(ns puppetlabs.services.master.tasks-int-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.puppetserver.testutils :as testutils]
            [cheshire.core :as cheshire]
            [me.raynes.fs :as fs]))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/master/tasks_int_test")

(defn purge-env-dir
  []
  (-> testutils/conf-dir
      (fs/file "environments")
      fs/delete-dir))

(use-fixtures :once
              (testutils/with-puppet-conf
               (fs/file test-resources-dir "puppet.conf")))

(use-fixtures :each
              (fn [f]
                (purge-env-dir)
                (try
                  (f)
                  (finally
                    (purge-env-dir)))))

(def request-as-text-with-ssl (assoc testutils/ssl-request-options :as :text))

(defn get-all-tasks
  [env-name]
  (let [url (str "https://localhost:8140/puppet/v3/tasks"
                 (if env-name (str "?environment=" env-name)))]
    (try
      (http-client/get url request-as-text-with-ssl)
      (catch Exception e
        (throw (Exception. "tasks http get failed" e))))))

(defn get-task-info
  "task-name is the task's full name, e.g. 'apache::reboot'."
  [env-name full-task-name]
  (let [[module-name task-name]  (str/split full-task-name #"::")
        url (str "https://localhost:8140/puppet/v3/tasks/" module-name "/" task-name
                 (if env-name (str "?environment=" env-name)))]
    (try
      (http-client/get url request-as-text-with-ssl)
      (catch Exception e
        (throw (Exception. "task info http get failed" e))))))

(defn parse-response
  [response]
  (-> response :body cheshire/parse-string))

(defn sort-tasks
  [tasks]
  (sort-by #(get % "name") tasks))

(def puppet-config
  (-> (bootstrap/load-dev-config-with-overrides {:jruby-puppet {:max-active-instances 1}})
      (ks/dissoc-in [:jruby-puppet
                     :environment-class-cache-enabled])))

(deftest ^:integration all-tasks-with-env
  (testing "full stack tasks listing smoke test"
    (bootstrap/with-puppetserver-running-with-config
      app
      puppet-config
      (do
        (testutils/write-tasks-files "apache" "announce" "echo 'Hi!'")
        (testutils/write-tasks-files "graphite" "install" "wheeee")
        (let [expected-response '({"name" "apache::announce"
                                   "environment" [{"name" "production"
                                                   "code_id" nil}]}
                                  {"name" "graphite::install"
                                   "environment" [{"name" "production"
                                                   "code_id" nil}]})
              response (get-all-tasks "production")]
          (testing "a successful status code is returned"
            (is (= 200 (:status response))
                (str
                  "unexpected status code for response, response: "
                  (ks/pprint-to-string response))))
          (testing "the expected response body is returned"
            (is (= expected-response
                   (sort-tasks (parse-response response))))))))))

(deftest ^:integration task-info
  (testing "full stack task metadata smoke test"
    (bootstrap/with-puppetserver-running-with-config
      app
      puppet-config
      (let [metadata {"description" "This is a test task"
                      "output" "'Hello, world!'"}
            _ (testutils/write-tasks-files "shell" "poc" "echo 'Hello, world!'" metadata)
            response (get-task-info "production" "shell::poc")
            code (:status response)]
        (testing "a successful status code is returned"
          (is (= 200 code)
              (str "unexpected status code " code " for response: "
                   (ks/pprint-to-string response))))

        (testing "the expected response body is returned"
          (let [expected-response {"metadata" metadata
                                   "code_id" nil
                                   "files" [{"filename" "poc.sh"
                                             "sha256" "0xdecadecafaded"
                                             "size_bytes" 21
                                             "uri" {"path" "/puppet/v3/file_contents/tasks/shell/poc.sh"
                                                    "params" {"environment" "production"
                                                              "code_id" nil}}}]}]
            (is (= expected-response (parse-response response)))))))))
