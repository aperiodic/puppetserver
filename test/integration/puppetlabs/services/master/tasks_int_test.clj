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
  "./dev-resources/puppetlabs/services/master/environment_classes_int_test")

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

(defn get-all-tasks
  [env-name]
  (let [url (str "https://localhost:8140/puppet/v3/"
                 "tasks")
        url (if env-name
              (str url "?environment=" env-name)
              url)]
    (try
      (http-client/get url
        (merge
          testutils/ssl-request-options
          {:as :text}))
      (catch Exception e
        (throw (Exception. "tasks http get failed" e))))))

(defn purge-all-env-caches
  []
  (http-client/delete
   "https://localhost:8140/puppet-admin-api/v1/environment-cache"
   testutils/ssl-request-options))

(defn purge-env-cache
  [env]
  (http-client/delete
   (str "https://localhost:8140/puppet-admin-api/v1/environment-cache?"
        "environment="
        env)
   testutils/ssl-request-options))

(defn response-etag
  [request]
  (get-in request [:headers "etag"]))

(defn response->clojure
  [response]
  (-> response :body cheshire/parse-string))

(deftest ^:integration all-tasks-with-env
  (testing "when all tasks are requested"
    (testing "from an environment that does exist"
      (bootstrap/with-puppetserver-running-with-config
        app
        (-> {:jruby-puppet {:max-active-instances 1}}
            (bootstrap/load-dev-config-with-overrides)
            (ks/dissoc-in [:jruby-puppet
                           :environment-class-cache-enabled]))
        (let [foo-file (testutils/write-tasks-files
                         "apache"
                         "announce"
                         "echo 'Hi!'")
              expected-response {
                                 "files"
                                 [
                                  {"path" foo-file,
                                   "classes"
                                   [
                                    {
                                     "name" "foo"
                                     "params"
                                     [
                                      {"name" "foo_1",
                                       "type" "String",
                                       "default_literal" "is foo",
                                       "default_source" "\"is foo\""}]}]}]
                                 "name" "production"}
              response (get-all-tasks "production")]
          (testing "a successful status code is returned"
            (is (= 200 (:status response))
                (str
                  "unexpected status code for response, response: "
                  (ks/pprint-to-string response))))
          (testing "the expected response body is returned"
            (is (= expected-response
                   (response->clojure response)))))))
    (testing "from an environment that does not exist")))

(deftest ^:integration all-tasks-without-env)
