#!/usr/bin/env bb

;; Runs library tests against the cream binary using cognitect test-runner.
;; Usage:
;;   bb bb/run_lib_tests.clj                     # run all
;;   bb bb/run_lib_tests.clj medley/medley       # run one library

(ns run-lib-tests
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def deps-edn (edn/read-string (slurp "deps.edn")))
(def lib-tests (get-in deps-edn [:aliases :lib-tests :extra-deps]))

(def filter-lib (first *command-line-args*))

;; Resolve library classpath (triggers git clones too)
(println "Resolving library classpath...")
(def lib-cp
  (-> (p/shell {:out :string} "clojure" "-Spath" "-A:lib-tests")
      :out
      str/trim))

(defn gitlibs-dir [lib-name git-sha]
  (fs/file (fs/home) ".gitlibs" "libs" (str lib-name) git-sha))

(defn find-test-dirs
  "Find test source directories in a gitlibs checkout."
  [git-dir]
  (let [;; Multi-dir layouts (e.g. test/clj + test/cljc)
        multi-candidates [["test/clj" "test/cljc"]]
        ;; Single-dir layouts
        single-candidates ["test"
                           "src/test/clojure"
                           "src/test"]]
    (or (first (filter (fn [dirs] (every? #(fs/exists? (fs/file git-dir %)) dirs))
                       multi-candidates))
        (when-let [d (first (filter #(fs/exists? (fs/file git-dir %)) single-candidates))]
          [d]))))

(def results (atom []))

;; Libraries that are deps but not test targets
(def skip-libs #{'io.github.cognitect-labs/test-runner
                 'org.clojure/test.check})

;; Maven-only libs that need a separate git clone for tests.
;; These use project.clj (no deps.edn) so can't be git deps.
(def clone-libs
  {'instaparse/instaparse
   {:git-url "https://github.com/Engelberg/instaparse"
    :test-dirs ["test"]
    ;; defparser macro reads grammar files via relative paths
    :run-from-clone true}
   'cheshire/cheshire
   {:git-url "https://github.com/dakrone/cheshire"
    :test-dirs ["test"]
    :ns-regex "cheshire.test.*"}})

;; Specific test vars to skip per library.
;; Each entry is a fully qualified var that gets :skip-cream metadata.
(def skip-tests
  (merge-with into
    {;; Shift_JIS/EUC-JP charsets not available in native image
     'hiccup/hiccup
     ["hiccup.util_test/test-url-encode"]
     ;; stest/check generators StackOverflow in Crema
     'org.clojure/data.json
     ["clojure.data.json-gen-test/roundtrip"]}
    (when (fs/windows?)
      {;; \r\n line ending mismatches on Windows
       'org.clojure/data.json
       ["clojure.data.json-test/pretty-print-nonescaped-unicode"]
       'lambdaisland/deep-diff2
       ["lambdaisland.deep-diff2-test/pretty-print-test"
        "lambdaisland.deep-diff2.printer-test/print-doc-test"]
       ;; defrecord schema metadata not working in Crema on Windows
       'prismatic/schema
       ["schema.core-test/defrecord-schema-test"
        "schema.core-test/defrecord-new-style-schema-test"
        "schema.core-test/fancier-defrecord-schema-test"
        "schema.core-test/defrecord-extra-validation-test"
        "schema.core-test/fancy-explain-test"
        "schema.core-test/simple-validated-defn-test"
        "schema.core-test/simple-primitive-validated-defn-test"
        "schema.core-test/sdefprotocol-test"]})))

;; Test namespaces to exclude per library (segfault in Crema).
;; These are excluded from the test-runner's namespace regex.
(def skip-namespaces
  {;; ForkJoinPool segfault in Crema under heavy concurrent dispatch
   'org.clojure/core.async
   ["clojure.core.async-test"
    "clojure.core.async.ioc-macros-test"
    "clojure.core.pipeline-test"]
   ;; Excluded by cheshire's own test-selectors; defspec macro
   ;; implicitly evals clojure.data.generators symbols without requiring it
   'cheshire/cheshire
   ["cheshire.test.generative"]})

(defn- run-lib-test [{:keys [lib-name lib-str test-paths work-dir ns-regex-override]}]
  (let [full-cp (str lib-cp fs/path-separator (str/join fs/path-separator test-paths))
        skips (get skip-tests lib-name)
        skip-nses (get skip-namespaces lib-name)
        skip-expr (when (seq skips)
                    (str/join " "
                      (for [v skips]
                        (let [[ns-str] (str/split v #"/")]
                          (format "(do (require '%s) (alter-meta! #'%s assoc :skip-cream true) nil)"
                                  ns-str v)))))
        default-regex ".*-test$|.*test-.*|.*test$"
        base-regex (or ns-regex-override default-regex)
        ;; Build ns regex that excludes skipped namespaces
        ns-regex (if (seq skip-nses)
                   (let [exclude-pattern (str "(?!" (str/join "|" (map #(str/replace % "." "\\.") skip-nses)) "$)")]
                     (str exclude-pattern "(" base-regex ")"))
                   base-regex)
        cream-cmd (if (= "false" (System/getenv "CREAM_NATIVE"))
                    ["java" "-jar" (str (fs/absolutize "target/cream-1.0.0-standalone.jar"))]
                    [(str (fs/absolutize "cream"))])
        cmd (cond-> (into cream-cmd ["-Scp" full-cp "-M"])
              skip-expr (into ["-e" skip-expr])
              true      (into (concat ["-m" "cognitect.test-runner"
                                       "-r" ns-regex
                                       "-e" "skip-cream"]
                                      (mapcat #(vector "-d" %) test-paths))))]
    (println (format "\nTesting %s" lib-str))
    (when (seq skips)
      (doseq [s skips] (println (str "  skipping test " s))))
    (when (seq skip-nses)
      (doseq [s skip-nses] (println (str "  skipping ns " s))))
    (let [proc (apply p/process {:inherit true :dir (or work-dir ".")} cmd)
          exit-code (:exit @proc)]
      (swap! results conj {:lib lib-str :status (if (zero? exit-code) :pass :fail)}))))

(doseq [[lib-name coord] (sort-by key lib-tests)
        :when (not (skip-libs lib-name))
        :let [lib-str (str lib-name)]
        :when (or (nil? filter-lib) (= filter-lib lib-str))]
  (if-let [{:keys [git-url test-dirs run-from-clone ns-regex]} (get clone-libs lib-name)]
    ;; Maven lib with separate clone for tests
    (let [clone-dir (str (fs/file (fs/temp-dir) (str "cream-test-" (name lib-name))))
          _ (when-not (fs/exists? clone-dir)
              (println (format "Cloning %s..." lib-str))
              (p/shell "git" "clone" "--depth" "1" git-url clone-dir))
          test-paths (mapv #(str (fs/file clone-dir %)) test-dirs)]
      (run-lib-test {:lib-name lib-name
                     :lib-str lib-str
                     :test-paths test-paths
                     :work-dir (when run-from-clone clone-dir)
                     :ns-regex-override ns-regex}))
    ;; Standard git dep
    (let [git-dir (str (gitlibs-dir lib-name (:git/sha coord)))
          test-dirs (find-test-dirs git-dir)]
      (if-not test-dirs
        (do (println (format "\nSkipping %s (no test dir found)" lib-str))
            (swap! results conj {:lib lib-str :status :skip}))
        (let [test-paths (mapv #(str (fs/file git-dir %)) test-dirs)]
          (run-lib-test {:lib-name lib-name
                         :lib-str lib-str
                         :test-paths test-paths}))))))

;; Summary
(println)
(let [{:keys [pass fail skip]} (group-by :status @results)
      total (count @results)]
  (when (seq fail)
    (println "Failures:")
    (doseq [{:keys [lib]} fail] (println (str "  " lib))))
  (println (format "%d libraries tested, %d failures" total (count fail)))
  (System/exit (if (seq fail) 1 0)))
