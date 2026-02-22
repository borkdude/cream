(ns cream.main
  (:gen-class)
  (:require [clojure.core.protocols]
            [clojure.core.reducers]
            [clojure.data]
            [clojure.datafy]
            [clojure.edn]
            [clojure.instant]
            [clojure.java.browse]
            [clojure.java.io]
            [clojure.java.javadoc]
            [clojure.java.process]
            [clojure.java.shell]
            [clojure.main]
            [clojure.math]
            [clojure.pprint]
            [clojure.reflect]
            [clojure.repl]
            [clojure.set]
            [clojure.spec.alpha]
            [clojure.stacktrace]
            [clojure.string]
            [clojure.template]
            [clojure.test]
            [clojure.uuid]
            [clojure.walk]
            [clojure.xml]
            [clojure.zip])
  (:import [cream JarClassLoader]))

(set! *warn-on-reflection* true)


(defn- parse-args
  "Parse -Scp <paths> from args. Returns [cp-string remaining-args]."
  [args]
  (loop [args args
         cp nil]
    (if (seq args)
      (let [[flag & rest-args] args]
        (if (= "-Scp" flag)
          (recur (rest rest-args) (first rest-args))
          [cp args]))
      [cp args])))

(defn -main [& args]
  ;; On Windows, *out* captured at build time has the wrong encoding.
  ;; https://github.com/babashka/babashka/issues/1009
  ;; https://github.com/oracle/graal/issues/12249
  (when (.contains (System/getProperty "os.name") "Windows")
    (alter-var-root #'*out* (constantly (java.io.OutputStreamWriter. System/out))))
  (let [[cp-str remaining] (parse-args args)
        _ (when cp-str
            (let [paths (.split ^String cp-str (System/getProperty "path.separator"))
                  cl (JarClassLoader. paths (.getContextClassLoader (Thread/currentThread)))]
              (.setContextClassLoader (Thread/currentThread) cl)))
        [flag & main-args] remaining]
    (if (= "-M" flag)
      (apply clojure.main/main main-args)
      (apply clojure.main/main args))))
