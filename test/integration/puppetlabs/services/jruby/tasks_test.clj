(ns puppetlabs.services.jruby.tasks-test
  (:require [clojure.test :refer :all]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [puppetlabs.services.jruby.puppet-environments :as puppet-env]
            [me.raynes.fs :as fs]
            [cheshire.core :as cheshire]
            [puppetlabs.puppetserver.testutils :as testutils]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-bootstrap]))

(defn gen-classes
  [[mod-dir manifests]]
  (let [manifest-dir (fs/file mod-dir "manifests")]
    (ks/mkdirs! manifest-dir)
    (doseq [manifest manifests]
      (spit (fs/file manifest-dir (str manifest ".pp"))
            (str
              "class " manifest "($" manifest "_a, Integer $"
              manifest "_b, String $" manifest
              "_c = 'c default value') { }\n"
              "class " manifest "2($" manifest "2_a, Integer $"
              manifest "2_b, String $" manifest
              "2_c = 'c default value') { }\n")))))

(defn create-env
  [[env-dir manifests]]
  (testutils/create-env-conf env-dir "")
  (gen-classes [env-dir manifests]))

(defn roundtrip-via-json
  [obj]
  (-> obj
      (cheshire/generate-string)
      (cheshire/parse-string)))

(defn expected-class-info
  [class]
    {"name" class
     "params" [{"name" (str class "_a")}
               {"name" (str class "_b"),
                "type" "Integer"}
               {"name" (str class "_c"),
                "type" "String",
                "default_literal" "c default value"
                "default_source" "'c default value'"}]})

(defn expected-manifests-info
  [manifests]
  (into {}
        (apply concat
               (for [[dir names] manifests]
                 (do
                   (for [name names]
                     [(.getAbsolutePath
                        (fs/file dir
                                 "manifests"
                                 (str name ".pp")))
                      {"classes"
                       [(expected-class-info name)
                        (expected-class-info
                         (str name "2"))]}]))))))

(deftest ^:integration all-tasks-test
  (testing "all-tasks-listed"
    (let [code-dir (ks/temp-dir)
          conf-dir (ks/temp-dir)
          config (jruby-testutils/jruby-puppet-tk-config
                  (jruby-testutils/jruby-puppet-config
                   {:master-code-dir (.getAbsolutePath code-dir)
                    :master-conf-dir (.getAbsolutePath conf-dir)}))]

      (tk-bootstrap/with-app-with-config
       app
       jruby-testutils/jruby-service-and-dependencies
       config
       (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
             instance (jruby-testutils/borrow-instance jruby-service :test)
             jruby-puppet (:jruby-puppet instance)
             env-registry (:environment-registry instance)

             _ (testutils/create-file (fs/file conf-dir "puppet.conf")
                                      "[main]\nenvironment_timeout=unlimited\nbasemodulepath=$codedir/modules\n")

             env-dir (fn [env-name]
                       (fs/file code-dir "environments" env-name))
             env-1-dir (env-dir "env1")
             env-1-dir-and-manifests [env-1-dir ["foo" "bar"]]
             _ (create-env env-1-dir-and-manifests)

             env-2-dir (env-dir "env2")
             env-2-dir-and-manifests [env-2-dir ["baz" "bim" "boom"]]
             _ (create-env env-2-dir-and-manifests)

             env-1-mod-dir (fs/file env-1-dir "modules")
             env-1-mod-1-dir-and-manifests [(fs/file env-1-mod-dir
                                                     "envmod1")
                                            ["envmod1baz" "envmod1bim"]]
             _ (gen-classes env-1-mod-1-dir-and-manifests)
             env-1-mod-2-dir (fs/file env-1-mod-dir "envmod2")
             env-1-mod-2-dir-and-manifests [env-1-mod-2-dir
                                            ["envmod2baz" "envmod2bim"]]
             _ (gen-classes env-1-mod-2-dir-and-manifests)

             env-3-dir-and-manifests [(env-dir "env3") ["dip" "dap" "dup"]]

             base-mod-dir (fs/file code-dir "modules")
             base-mod-1-and-manifests [(fs/file base-mod-dir "basemod1")
                                       ["basemod1bap"]]
             _ (gen-classes base-mod-1-and-manifests)

             bogus-env-dir (env-dir "bogus-env")
             _ (create-env [bogus-env-dir []])
             _ (gen-classes [bogus-env-dir ["envbogus"]])
             _ (gen-classes [(fs/file base-mod-dir "base-bogus") ["base-bogus1"]])

             get-tasks (fn [env]
                         (.getTasks jruby-puppet env))]
         (try
           (testing "initial parse"
             (let [expected-envs-info {"env1" (expected-manifests-info
                                               [env-1-dir-and-manifests
                                                env-1-mod-1-dir-and-manifests
                                                env-1-mod-2-dir-and-manifests
                                                base-mod-1-and-manifests])
                                       "env2" (expected-manifests-info
                                               [env-2-dir-and-manifests
                                                base-mod-1-and-manifests])}]
               (is (= (expected-envs-info "env1")
                      (get-tasks "env1"))
                   "Unexpected info retrieved for 'env1'")))
           (finally
             (jruby-testutils/return-instance jruby-service instance :test))))))))
