(ns leiningen.runit
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [me.raynes.fs :as fs]))

(defn sanitize [key]
  (-> key
      (name)
      (.toUpperCase)
      (str/replace "-" "_")))

(defn write-env [env path]
  (doseq [[key value] env] 
    (let [filename (sanitize key)
          path (str/join "/" [path "env" filename])]
      (io/make-parents path)
      (spit path value))))

(defn write-logs-dir [path]
  (.mkdir (io/file (str/join "/" [path "logs"]))))

(defn write-app [app-path env]
  (write-env env app-path)
  (write-logs-dir app-path))

(defn write-run-service [user app-path service-path jar-filename]
  (let [lines ["#!/bin/sh -e"
               (str "BASE_DIR=" app-path)
               (str "JAR=" jar-filename)
               "exec 2>&1"
               (str "exec chpst -u " user " -e $BASE_DIR/env java -jar -server $BASE_DIR/$JAR")]
        path (str service-path "/run")]
    (io/make-parents path)
    (with-open [wrtr (io/writer path)]
      (doseq [line lines]
        (.write wrtr (str line "\n"))))
    (fs/chmod "+x" path)))

(defn write-run-log [user app-path service-path]
  (let [lines ["#!/bin/sh -e"
               (str "BASE_DIR=" app-path)
               (str "exec chpst -u " user " svlogd -tt $BASE_DIR/logs")]
        path (str service-path "/log/run")]
    (io/make-parents path)
    (with-open [wrtr (io/writer path)]
      (doseq [line lines]
        (.write wrtr (str line "\n"))))
    (fs/chmod "+x" path)))

(defn write-service [app-path service-path jar-filename]
  (let [user (System/getProperty "user.name")]
    (write-run-service user app-path service-path jar-filename)
    (write-run-log user app-path service-path)))

(defn runit
  "Provides integration with runit, a UNIX init scheme with service supervision."
  [project & args]
  (clojure.pprint/pprint project)
  (let [app-path (str/join "/" [(:app-root (:runit project)) (:group project) (:name project)])
        service-path (str/join "/" [(:service-root (:runit project)) (:group project) (:name project)])
        jar-filename (str/join "-" [(:name project) (:version project) "standalone.jar"])]
    (write-app app-path (:env project))
    (write-service app-path service-path jar-filename)))
