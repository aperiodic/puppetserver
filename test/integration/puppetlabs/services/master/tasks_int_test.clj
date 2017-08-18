(ns puppetlabs.services.master.tasks-int-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.puppetserver.testutils :as testutils]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-bootstrap-testutils]
            [puppetlabs.trapperkeeper.testutils.webserver :as jetty9]
            [puppetlabs.services.master.master-core :as master-core]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]
            [cheshire.core :as cheshire]
            [me.raynes.fs :as fs]
            [clojure.tools.logging :as log]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-puppet-testutils])
  (:import (com.puppetlabs.puppetserver JRubyPuppetResponse JRubyPuppet)
           (java.util HashMap)))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/master/tasks_int_test")

(defn purge-env-dir
  []
  (-> testutils/conf-dir
      (fs/file "environments")
      fs/delete-dir))

(def test-conf
  (-> (bootstrap/load-dev-config-with-overrides {:jruby-puppet {:master-code-dir testutils/conf-dir
                                                                :certname "localhost"
                                                                :max-active-instances 1}})
    (ks/dissoc-in [:jruby-puppet :environment-class-cache-enabled])))

(use-fixtures :once
              (testutils/with-puppet-conf
                (fs/file test-resources-dir "puppet.conf"))
              (fn [f]
                (bootstrap/with-puppetserver-running-with-config
                  _, test-conf
                  (f))))

#_(use-fixtures :each
              (fn [f]
                (purge-env-dir)
                  (f)
                ))

(defn get-all-tasks
  ([] (get-all-tasks nil))
  ([env-name]
   (let [url (str "https://localhost:8140/puppet/v3/tasks"
                  (if env-name (str "?environment=" env-name)))]
     (try
       (http-client/get url
         (merge
           testutils/ssl-request-options
           {:as :text}))
       (catch Exception e
         (throw (Exception. "tasks http get failed" e)))))))

(defn response->clojure
  [response]
  (-> response :body (cheshire/parse-string)))

(deftest ^:integration all-tasks-with-env
  (testing "when all tasks are requested"
    (testing "from an environment that does exist"
      (let [ann-task (testutils/write-task-files "apache" "announce" "echo 'Hi!'")
            expected-response [{"name" "apache::announce"
                                "environments" [{"name" "production"
                                                 "code_id" "null"}]}]
            response (get-all-tasks "production")]
        (testing "a successful status code is returned"
          (is (= 200 (:status response))
              (str "unexpected status code for response, response: "
                   (ks/pprint-to-string response))))
        (testing "the expected response body is returned"
          (is (= expected-response
                 (response->clojure response))))))

    (testing "from an environment that does not exist"
      (let [response (get-all-tasks)]
        (testing "a not found response is returned"
          (let [resp-code (:status response)]
            (is (= 404 resp-code)
                (str "unexpected status code " resp-code " in response: "
                     (ks/pprint-to-string response)))))))))

(deftest ^:integration all-tasks-without-env)
