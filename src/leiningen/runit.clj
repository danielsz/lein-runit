(ns leiningen.runit
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [me.raynes.fs :as fs]
            [taoensso.timbre :as timbre]))

(timbre/refer-timbre)
(timbre/set-level! :info)

(defn sanitize [key]
  (-> key
      (name)
      (.toUpperCase)
      (str/replace "-" "_")))

(defn write-executable [lines path]
  (io/make-parents path)
    (with-open [wrtr (io/writer path)]
      (doseq [line lines]
        (.write wrtr (str line "\n"))))
    (fs/chmod "+x" path))

(defn write-env [path env]
  (doseq [[key value] env] 
    (let [filename (sanitize key)
          path (str/join "/" [path "env" filename])]
      (io/make-parents path)
      (spit path value))))

(defn write-logs-dir [path]
  (.mkdir (io/file (str/join "/" [path "logs"]))))

(defn write-app [app-path env]
  (debug "app path: " app-path)
  (debug "env: " env)
  (write-env app-path env)
  (write-logs-dir app-path))

(defn write-run-service [user app-path service-path jar-filename]
  (let [lines ["#!/bin/sh -e"
               (str "BASE_DIR=" app-path)
               (str "JAR=" jar-filename)
               "exec 2>&1"
               (str "exec chpst -u " user " -e $BASE_DIR/env java -jar -server $BASE_DIR/$JAR")]
        path (str service-path "/run")]
    (write-executable lines path)))

(defn write-run-log [user app-path service-path]
  (let [lines ["#!/bin/sh -e"
               (str "BASE_DIR=" app-path)
               (str "exec chpst -u " user " svlogd -tt $BASE_DIR/logs")]
        path (str service-path "/log/run")]
    (write-executable lines path)))

(defn write-service [app-path service-path jar-name]
  (let [user (System/getProperty "user.name")]
    (write-run-service user app-path service-path jar-name)
    (write-run-log user app-path service-path)))

(defn assemble-path [els]
  (-> (str/join "/" els)
      (clojure.string/replace #"(?<!http:)//" "/")))

(defn compute-paths [project]
  (let [app [(:app-root (:runit project)) (or (:group project) "") (:name project)]
        service-name (if (:group project)
                       (str (:group project) "-" (:name project))
                       (:name project))
        service [(:service-root (:runit project)) service-name]
        runit ["/etc/service" service-name]
        target-path (conj (seq app) (:target-path project))
        service-path (conj (seq service) (:target-path project))]
    {:app (assemble-path app)
     :service (assemble-path service)
     :target-path (assemble-path target-path)
     :service-path (assemble-path service-path)
     :runit (assemble-path runit)}))

(def paths (memoize compute-paths))

(defn write-commit [project jar-name]
  (let [user (System/getProperty "user.name")
        paths (paths project)
        lines ["#!/bin/sh -e"
               (format "sudo mkdir -p %s" (:app paths))
               (format "sudo chown %s:%s %s"  user user (:app paths))
               (format "cp %s %s" jar-name (:app paths))
               (format "cp -R %s /" (str (:target-path project) (:app-root (:runit project))))
               (format "sudo cp -R %s /etc" (str (:target-path project) (:service-root (:runit project))))
               (format "sudo ln -s %s %s" (:service paths) (:runit paths))]]
    (write-executable lines (str (:target-path project) "/commit.sh"))))

(defn runit
  "Provides integration with runit, a UNIX init scheme with service supervision."
  [project & args]
  (when-not (:runit project)
    (leiningen.core.main/warn "Runit configuration map not found. Please refer to README for details."))
  (let [paths (paths project)
        jar-name (str/join "-" [(:name project) (:version project) "standalone.jar"])]
    (write-app (:target-path paths) (:env project))
    (spy :debug (write-service (:app paths) (:service-path paths) jar-name))
    (write-commit project jar-name)
    (leiningen.core.main/info "All done. You can now run commit.sh in target directory.")))
