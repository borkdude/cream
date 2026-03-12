(ns cream.main
  (:gen-class)
  (:require [clojure.core.protocols]
            [clojure.core.reducers]
            [clojure.data]
            [clojure.datafy]
            [clojure.edn]
            [clojure.instant]
            [clojure.java.io]
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
            [clojure.test]
            [clojure.uuid]
            [clojure.walk]
            [clojure.xml]
            [clojure.zip]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [borkdude.deps :as deps])
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

(defn- parse-deps
  "Parse //DEPS lines from a Java source file. Returns a seq of
  groupId:artifactId:version strings."
  [^String java-file]
  (let [lines (clojure.string/split-lines (slurp java-file))]
    (into []
      (comp
        (take-while #(or (clojure.string/blank? %)
                         (clojure.string/starts-with? % "//")
                         (clojure.string/starts-with? % "package")
                         (clojure.string/starts-with? % "import")))
        (filter #(clojure.string/starts-with? % "//DEPS "))
        (mapcat (fn [line]
                  (-> line
                      (subs (count "//DEPS "))
                      clojure.string/trim
                      (clojure.string/split #"[,\s]+")))))
      lines)))

(defn- cache-dir []
  (let [xdg (System/getenv "XDG_CACHE_HOME")
        base (if (clojure.string/blank? xdg)
               (fs/path (System/getProperty "user.home") ".cache")
               (fs/path xdg))]
    (str (fs/path base "cream"))))

(defn- sha256-hex [^String s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bytes (.digest md (.getBytes s "UTF-8"))]
    (clojure.string/join (map #(format "%02x" %) bytes))))

(defn- resolve-deps
  "Resolve Maven deps via deps.clj. Takes a seq of g:a:v strings,
  returns a classpath string."
  [deps]
  (let [deps-map (into {}
                   (map (fn [gav]
                          (let [parts (clojure.string/split gav #":")
                                [g a v] parts
                                sym (symbol (str g "/" a))]
                            [sym {:mvn/version v}])))
                   deps)
        deps-edn (pr-str {:deps deps-map})]
    (clojure.string/trim
      (with-out-str (deps/-main "-Sdeps" deps-edn "-Spath")))))

(defn- run-java [^String java-file args cp-str]
  (let [source (fs/file java-file)
        class-name (fs/strip-ext (fs/file-name source))
        deps (parse-deps java-file)
        source-content (slurp java-file)
        hash (sha256-hex source-content)
        cache-base (fs/path (cache-dir) hash)
        out-dir (str (fs/path cache-base "classes"))
        cp-file (fs/path cache-base "classpath")
        cached? (fs/exists? (fs/path out-dir (str class-name ".class")))
        sep (System/getProperty "path.separator")
        deps-cp (if (and cached? (fs/exists? cp-file))
                  (let [cp (clojure.string/trim (slurp (str cp-file)))]
                    (when-not (clojure.string/blank? cp) cp))
                  (when (seq deps) (resolve-deps deps)))
        cp-str (cond
                 (and cp-str deps-cp) (str cp-str sep deps-cp)
                 deps-cp deps-cp
                 :else cp-str)]
    (when-not cached?
      (fs/create-dirs out-dir)
      (when deps-cp
        (spit (str cp-file) deps-cp))
      (let [javac (str (fs/path (or (System/getenv "JAVA_HOME")
                                    (System/getProperty "java.home"))
                                "bin" "javac"))
            cmd (cond-> [javac "-d" out-dir]
                  cp-str (into ["-cp" cp-str])
                  true (conj (str (fs/absolutize source))))]
        @(process/process cmd {:inherit true})))
    ;; Add output dir + deps to classloader
    (let [cp-paths (cond-> [out-dir]
                     cp-str (into (.split ^String cp-str sep)))
          paths (into-array String cp-paths)
          cl (JarClassLoader. paths (.getContextClassLoader (Thread/currentThread)))]
      (.setContextClassLoader (Thread/currentThread) cl))
    ;; Load and invoke main
    (let [cls (.loadClass (.getContextClassLoader (Thread/currentThread)) class-name)
          main-method (.getMethod cls "main"
                        (into-array Class [String/1]))]
      (.invoke main-method nil
        (into-array Object [(into-array String (vec args))])))))

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
              (.setContextClassLoader (Thread/currentThread) cl)
              ;; Load data_readers.clj(c) from library JARs — Clojure's RT
              ;; scans these at init time, before our JarClassLoader exists.
              ;; Values must be Vars (not symbols), matching clojure.core/load-data-reader-file.
              (let [urls (enumeration-seq (.getResources cl "data_readers.clj"))
                    urlsc (enumeration-seq (.getResources cl "data_readers.cljc"))
                    raw (into {}
                          (mapcat (fn [^java.net.URL url]
                                    (with-open [rdr (java.io.PushbackReader.
                                                      (java.io.InputStreamReader.
                                                        (.openStream url)))]
                                      (clojure.edn/read rdr))))
                          (concat urls urlsc))
                    readers (into {}
                              (map (fn [[tag sym]]
                                     [tag (intern (create-ns (symbol (namespace sym)))
                                                  (symbol (name sym)))]))
                              raw)]
                (when (seq readers)
                  (alter-var-root #'*data-readers* merge readers)))))
        [flag & main-args] remaining]
    (cond
      (= "-M" flag)
      (apply clojure.main/main main-args)

      (and flag (.endsWith ^String flag ".java"))
      (do (run-java flag main-args cp-str)
          (shutdown-agents))

      :else
      (do (apply clojure.main/main args)
          (shutdown-agents)))))
