(ns leiningen.dynamodb-local
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [environ.core :refer [env]]
            [leiningen.core.main :as main])
  (:import [java.io File]
           [java.nio.file Files Paths LinkOption Path]
           [java.nio.file.attribute FileAttribute]
           [net.lingala.zip4j.core ZipFile]))

(def download-url "http://dynamodb-local.s3-website-us-west-2.amazonaws.com/dynamodb_local_latest.zip")

(def dynamo-directory (str (System/getProperty "user.home") File/separator ".lein-dynamodb-local"))

(defn ->path [str & strs]
  {:pre [(string? str) (every? string? strs)]}
  (Paths/get str (into-array String strs)))

(defn path? [x]
  (instance? Path x))

(defn exists? [path]
  {:pre [(path? path)]}
  (Files/exists path (into-array LinkOption [])))

(defn ensure-dynamo-directory []
  (let [path (->path dynamo-directory)]
    (when-not (exists? path)
      (-> (Files/createDirectory path (make-array FileAttribute 0))
          (.toString)))))

(defn- start-dynamo
  "Start DynamoDB Local with the given options"
  [port in-memory? shared-db? db-path jvm-opts]
  (let [lib-path (str (io/file dynamo-directory "DynamoDBLocal_lib"))
        jar-path (str (io/file dynamo-directory "DynamoDBLocal.jar"))
        command (cond-> (format "java %s -Djava.library.path=%s -jar %s -port %s" (str/join " " jvm-opts) lib-path jar-path port)
                  in-memory? (str " -inMemory")
                  shared-db? (str " -sharedDb")
                  (and (seq db-path) (not in-memory?)) (str " -dbPath " db-path))]
    (.exec (Runtime/getRuntime) command)))

(defn dynamo-options
  "Use DynamoDB Local options provided or default values"
  [project]
  (merge {:port (Integer/valueOf (env :dynamodb-port "8000"))
          :in-memory? false
          :db-path dynamo-directory}
         (:dynamodb-local project)))

(defn download-dynamo [url]
  (main/info "dynamodb-local: Downloading DynamoDB Local to" dynamo-directory)
  (ensure-dynamo-directory)
  (io/copy (io/input-stream (io/as-url url)) (io/as-file (str dynamo-directory "/" "dynamo.zip"))))

(defn unpack-dynamo []
  (main/info "dynamodb-local: Unpacking DynamoDB Local")
  (let [zip-file (->path dynamo-directory "dynamo.zip")]
    (.extractAll (ZipFile. (str zip-file)) dynamo-directory)))

(defn ensure-installed []
  (when-not (exists? (->path dynamo-directory "dynamo.zip"))
    (download-dynamo download-url)
    (unpack-dynamo)))

(defn clean-up-on-shutdown
  "kill the DynamoDB Local process on JVM shutdown"
  [dynamo-process]
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. (fn [] (doto dynamo-process (.destroy) (.waitFor))))))

(defn dynamodb-local
  "Run DynamoDB Local for the lifetime of the given task"
  [project & args]
  (ensure-installed)
  (let [{:keys [port in-memory? shared-db? db-path jvm-opts]} (dynamo-options project)
        dynamo-process (start-dynamo port in-memory? shared-db? db-path jvm-opts)]
    (main/info "dynamodb-local: Started DynamoDB Local")
    (clean-up-on-shutdown dynamo-process)
    (if (seq args)
      (main/apply-task (first args) project (rest args))
      (while true (Thread/sleep 5000)))))
