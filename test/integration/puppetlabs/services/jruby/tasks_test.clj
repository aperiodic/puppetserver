(ns puppetlabs.services.jruby.tasks-test
  (:require [clojure.test :refer :all]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [puppetlabs.services.jruby.puppet-environments :as puppet-env]
            [puppetlabs.services.master.master-core :as mc]
            [me.raynes.fs :as fs]
            [cheshire.core :as cheshire]
            [schema.core :as schema]
            [schema.test :as schema-test]
            [puppetlabs.puppetserver.testutils :as testutils]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-bootstrap]))

(use-fixtures :once schema-test/validate-schemas)

(def TaskOptions
  {:name schema/Str
   :module-name schema/Str
   :metadata? schema/Bool
   :number-of-files schema/Num})

(defn make-task-dir
  [env-dir mod-name]
  (fs/file env-dir "modules" mod-name "tasks"))

(defn gen-task
  "Assumes tasks dir already exists -- generates a set of task files for a
  single task."
  [env-dir task]
  (let [task-name (:name task)
        extensions '(".rb" "" ".sh" ".exe" ".py")
        task-dir (make-task-dir env-dir (:module-name task))]
    (fs/mkdirs task-dir)
    (when (:metadata? task)
      (fs/create (fs/file task-dir (str task-name ".json"))))
    (dotimes [n (:number-of-files task)]
      (fs/create (fs/file task-dir (str task-name (nth extensions n ".rb")))))))

(defn gen-tasks
  "Tasks is a list of task maps, with keys:
  :name String, file name of task
  :module-name String, name of module task is in
  :metadata? Boolean, whether to generate a metadata file
  :number-of-files Number, how many executable files to generate for the task (0 or more)"
  [env-dir tasks]
  (dorun (map (partial gen-task env-dir) tasks)))

(defn create-env
  [env-dir tasks]
  (testutils/create-env-conf env-dir "")
  (gen-tasks env-dir tasks))

(defn expected-tasks-info
  [tasks]
  (->> tasks
       (map (fn [{:keys [name module-name]}]
              {:module {:name module-name}
               :name (if (= "init" name)
                       module-name
                       (str module-name "::" name))}))))

(deftest ^:integration all-tasks-test
  (testing "all tasks listed"
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
              env-1-tasks [{:name "install"
                            :module-name "apache"
                            :metadata? true
                            :number-of-files 2}
                           {:name "init"
                            :module-name "apache"
                            :metadata? false
                            :number-of-files 1}
                           {:name "configure"
                            :module-name "django"
                            :metadata? true
                            :number-of-files 0}]

              get-tasks (fn [env]
                          (.getTasks jruby-puppet env))]

          (try (create-env env-1-dir env-1-tasks)
               (testing "task retrieval"
                 (is (= (-> env-1-tasks
                            expected-tasks-info
                            set)
                        (-> (get-tasks "env1")
                            mc/sort-nested-info-maps
                            set))
                     "Unexpected info retrieved for 'env1'"))
               (finally
                 (jruby-testutils/return-instance jruby-service instance :test))))))))
