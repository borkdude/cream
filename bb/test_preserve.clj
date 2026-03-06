#!/usr/bin/env bb

;; Tests which -H:Preserve packages are actually needed by trying to remove each one.
;; Writes results to /tmp/preserve-results.txt
;;
;; Strategy:
;; 1. First try an aggressive "minimal" build with only clojure.lang, java.lang, java.io
;; 2. Then try removing each remaining package individually from the current full set
;;
;; Usage: GRAALVM_HOME=/path/to/graalvm bb bb/test_preserve.clj

(ns test-preserve
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def graalvm-home (or (System/getenv "GRAALVM_HOME")
                      (do (println "ERROR: GRAALVM_HOME not set")
                          (System/exit 1))))

(def results-file "/tmp/preserve-results.txt")
(def project-dir (str (fs/absolutize ".")))
(def build-native-clj (str (fs/file project-dir "bb" "build_native.clj")))
(def build-native-backup (str build-native-clj ".bak"))

;; All packages currently in build_native.clj
(def current-packages
  ["clojure.lang"
   "java.io"
   "java.lang"
   "java.lang.invoke"
   "java.lang.ref"
   "java.lang.reflect"
   "java.net"
   "java.nio"
   "java.nio.channels"
   "java.nio.charset"
   "java.nio.file"
   "java.nio.file.attribute"
   "java.security"
   "java.security.cert"
   "java.time"
   "java.time.format"
   "java.time.temporal"
   "java.util"
   "java.util.concurrent"
   "java.util.concurrent.atomic"
   "java.util.concurrent.locks"
   "java.util.function"
   "java.util.jar"
   "java.util.regex"
   "java.util.stream"
   "java.util.zip"
   "javax.xml.parsers"])

(defn generate-build-script [packages]
  (let [preserve-lines (str/join "\n"
                          (map #(format "         \"-H:Preserve=package=%s\"" %) packages))]
    (format "(ns build-native
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [babashka.tasks :as tasks]))

(tasks/clojure \"-T:build\" \"uber\")
(println \"done with uberjar\")

(p/shell (str (fs/file (System/getenv \"GRAALVM_HOME\") \"bin\"
                       (if (fs/windows?) \"native-image.cmd\" \"native-image\")))
         \"-jar\" \"target/cream-1.0.0-standalone.jar\"
         \"-O1\"
         \"--initialize-at-run-time=com.sun.tools.javac.file.Locations,jdk.internal.jrtfs.SystemImage\"
         \"--initialize-at-build-time=clojure,cream,org.xml.sax,com.sun.tools.doclint,com.sun.tools.javac.parser.Tokens$TokenKind,com.sun.tools.javac.parser.Tokens$Token$Tag\"
         \"--features=ClojureFeature,clj_easy.graal_build_time.InitClojureClasses\"
         \"--add-modules=java.xml\"
         \"-H:+UnlockExperimentalVMOptions\"
         \"-H:Name=cream\"
         \"-H:+RuntimeClassLoading\"
         \"-H:ConfigurationFileDirectories=.\"
         \"-H:IncludeResources=clojure/.*\"
%s
         (str \"-Djava.home=\" (System/getenv \"GRAALVM_HOME\"))
         \"-J-Djava.file.encoding=UTF-8\"
         \"-Djava.file.encoding=UTF-8\"
         \"--enable-url-protocols=http,https,jar,unix\"
         \"-H:+AllowJRTFileSystem\")
" preserve-lines)))

(defn log [& args]
  (let [msg (str/join " " args)]
    (println msg)
    (spit results-file (str msg "\n") :append true)))

(defn try-build-and-test [label packages]
  (log (format "\n=== %s ===" label))
  (log (format "Packages (%d): %s" (count packages) (str/join ", " packages)))
  (spit build-native-clj (generate-build-script packages))
  (let [build-result (p/process {:dir project-dir :out :string :err :string
                                  :env (assoc (into {} (System/getenv)) "GRAALVM_HOME" graalvm-home)}
                       "bb" "build-native")
        {:keys [exit out err]} @build-result]
    (if (not (zero? exit))
      (do (log (format "BUILD FAILED"))
          (log (format "  stderr (last 5 lines): %s"
                 (str/join "\n    " (take-last 5 (str/split-lines err)))))
          {:result :build-fail})
      (let [size-mb (format "%.1f" (/ (.length (fs/file project-dir "cream")) 1048576.0))
            _ (log (format "Build OK — %sMB" size-mb))
            test-result (p/process {:dir project-dir :out :string :err :string
                                     :env (assoc (into {} (System/getenv)) "GRAALVM_HOME" graalvm-home)}
                          "bb" "run-lib-tests")
            {:keys [exit out err]} @test-result
            output (str out err)]
        (if (zero? exit)
          (do (log (format "TESTS PASSED — %sMB" size-mb))
              {:result :pass :size size-mb})
          (do (log "TESTS FAILED")
              (log (format "  last 10 lines: %s"
                     (str/join "\n    " (take-last 10 (str/split-lines output)))))
              {:result :test-fail}))))))

;; Backup original
(fs/copy build-native-clj build-native-backup {:replace-existing true})
(spit results-file (format "Preserve package test — %s\n" (java.time.LocalDateTime/now)))

;; Phase 1: Try aggressive minimal set
(log "\n########## PHASE 1: Minimal set ##########")
(def minimal-packages ["clojure.lang" "java.lang" "java.io"])
(def phase1 (try-build-and-test "MINIMAL (clojure.lang + java.lang + java.io)" minimal-packages))

;; Phase 2: Try removing each package individually
(log "\n########## PHASE 2: Remove each package individually ##########")
(def phase2-results
  (into {}
    (for [pkg current-packages]
      (let [remaining (vec (remove #{pkg} current-packages))
            result (try-build-and-test (format "WITHOUT %s" pkg) remaining)]
        [pkg result]))))

;; Restore original
(fs/copy build-native-backup build-native-clj {:replace-existing true})
(fs/delete build-native-backup)

;; Summary
(log "\n########## SUMMARY ##########")
(log (format "Phase 1 minimal (%d packages): %s" (count minimal-packages) (:result phase1)))
(log "\nPhase 2 — packages that can be REMOVED (tests still pass):")
(doseq [[pkg {:keys [result size]}] (sort-by key phase2-results)
        :when (= result :pass)]
  (log (format "  %s (binary: %sMB)" pkg size)))
(log "\nPhase 2 — packages that are REQUIRED:")
(doseq [[pkg {:keys [result]}] (sort-by key phase2-results)
        :when (not= result :pass)]
  (log (format "  %s (%s)" pkg (name result))))

(log (format "\nDone. Results in %s" results-file))
