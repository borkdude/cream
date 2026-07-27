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
            ;; clojure.main/repl-requires loads this at REPL startup. Without it
            ;; in the image it comes from a Clojure jar on the classpath, whose
            ;; direct-linked AOT classes crash Crema.
            [clojure.repl.deps]
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

(def ^:private path-sep (System/getProperty "path.separator"))

(defn- parse-args
  "Parse -Scp <paths> and -Sdeps <edn> from args. Returns
  [{:cp cp-string :deps deps-edn-string} remaining-args]."
  [args]
  (loop [args args
         opts {}]
    (let [[flag & rest-args] args]
      (case flag
        "-Scp" (recur (rest rest-args) (assoc opts :cp (first rest-args)))
        "-Sdeps" (recur (rest rest-args) (assoc opts :deps (first rest-args)))
        [opts args]))))

(defn- set-classpath!
  "Installs a JarClassLoader for cp-str as the context classloader."
  [^String cp-str]
  (let [paths (.split cp-str path-sep)
        cl (JarClassLoader. paths (.getContextClassLoader (Thread/currentThread)))]
    (.setContextClassLoader (Thread/currentThread) cl)
    ;; Re-scan data_readers.clj(c) now that library JARs are on the
    ;; classpath — Clojure's RT scanned at init time before our
    ;; JarClassLoader existed.
    (#'clojure.core/load-data-readers)))

(defn- java-cmd
  "Path to a java executable, or nil when there is none."
  []
  (or (System/getenv "JAVA_CMD")
      (fs/which "java")
      (when-let [home (System/getenv "JAVA_HOME")]
        (let [java (fs/path home "bin" "java")]
          (when (fs/executable? java) java)))))

(defn- run-deps
  "Runs deps.clj with args. Computing a classpath needs java, reading a
  cached one does not, so without java deps.clj gets a placeholder command
  and fails with an explanation only when it has to resolve."
  [args]
  (if (java-cmd)
    (apply deps/-main args)
    (binding [deps/*getenv-fn* (fn [env]
                                 (if (= "JAVA_CMD" env)
                                   "java"
                                   (System/getenv env)))
              deps/*aux-process-fn*
              (fn [_]
                (binding [*out* *err*]
                  (println (str "Resolving dependencies needs a JVM, but no java was found "
                                "on the PATH or under JAVA_HOME.")))
                (System/exit 1))]
      (apply deps/-main args))))

(def ^:private pom-dep
  {'cream/pom-project {:local/root "." :deps/manifest :pom}})

(defn- pom-project?
  "True when there is a pom.xml to take dependencies from and no deps.edn."
  []
  (and (fs/exists? "pom.xml") (not (fs/exists? "deps.edn"))))

(defn- merge-pom-dep
  "Adds pom.xml as a dependency to a -Sdeps map, so tools.deps reads the
  dependencies and source paths out of it."
  [deps-edn-string]
  (pr-str (update (if deps-edn-string
                    (clojure.edn/read-string deps-edn-string)
                    {})
                  :deps merge pom-dep)))

(defn- pom-args
  "Adds the pom.xml dependency to the -Sdeps argument of a deps.clj call."
  [args]
  (let [args (vec args)
        i (.indexOf ^java.util.List args "-Sdeps")]
    (if (neg? i)
      (into ["-Sdeps" (merge-pom-dep nil)] args)
      (assoc args (inc i) (merge-pom-dep (get args (inc i)))))))

(defn- deps-classpath
  "Computes a classpath from deps.edn, or from pom.xml when that is the only
  project file, with deps-edn-string (-Sdeps) merged on top of it."
  [deps-edn-string]
  (let [deps-edn-string (if (pom-project?)
                          (merge-pom-dep deps-edn-string)
                          deps-edn-string)
        cp (clojure.string/trim
             (with-out-str
               (run-deps (cond-> []
                           deps-edn-string (conj "-Sdeps" deps-edn-string)
                           true (conj "-Spath")))))]
    (when-not (clojure.string/blank? cp)
      cp)))

(defn- cli-flag?
  "True for args that deps.clj handles: alias modes, JVM options and the
  -S options cream does not parse itself. Bare -M is cream's own and skips
  deps.clj."
  [flag]
  (boolean
    (when flag
      (or (some #(clojure.string/starts-with? flag %) ["-M:" "-A" "-X" "-T" "-J" "-S"])
          (= "-P" flag)))))

(defn- classpath-arg
  "deps.clj writes an over-long classpath to a file and passes @the-file."
  [^String cp]
  (if (clojure.string/starts-with? cp "@")
    (clojure.edn/read-string (slurp (subs cp 1)))
    cp))

(defn- run-cli
  "Delegates to deps.clj, then runs the clojure.main invocation it produced
  in this process instead of spawning a JVM."
  [args]
  (binding [deps/*clojure-process-fn*
            (fn [{:keys [cmd]}]
              (let [cmd (vec cmd)
                    cp-idx (.indexOf ^java.util.List cmd "-classpath")
                    main-idx (.indexOf ^java.util.List cmd "clojure.main")]
                ;; Options before -classpath target the JVM cream does not start.
                (doseq [opt (subvec cmd 1 cp-idx)]
                  (if (clojure.string/starts-with? opt "-D")
                    (let [[k v] (clojure.string/split (subs opt 2) #"=" 2)]
                      (System/setProperty k (or v "")))
                    (when-not (= "-XX:-OmitStackTraceInFastThrow" opt)
                      (binding [*out* *err*]
                        (println "Ignoring JVM option:" opt)))))
                (set-classpath! (classpath-arg (nth cmd (inc cp-idx))))
                (apply clojure.main/main (subvec cmd (inc main-idx)))
                {:exit 0}))]
    (run-deps (if (pom-project?) (pom-args args) args))))

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
    (deps-classpath deps-edn)))

(defn- java-package
  "The package a Java source file declares, if any."
  [^String source-content]
  (second (re-find #"(?m)^\s*package\s+([\w.$]+)\s*;" source-content)))

(defn- source-root
  "The directory javac should search for the other sources of a project: the
  file's own directory, with the package directories stripped off."
  [source package]
  (let [dir (fs/parent (fs/absolutize source))]
    (if package
      (reduce (fn [d _] (fs/parent d))
              dir
              (clojure.string/split package #"\."))
      dir)))

(defn- classes-current?
  "True when every compiled class is at least as new as the source it came
  from. javac also compiles the sources a file references, and those are not
  part of the cache key, so they are checked here."
  [out-dir root]
  (every? (fn [class-file]
            (let [rel (str (fs/relativize out-dir class-file))
                  src (fs/path root (clojure.string/replace rel #"(\$.*)?\.class$" ".java"))]
              (or (not (fs/exists? src))
                  (not (pos? (compare (fs/last-modified-time src)
                                      (fs/last-modified-time class-file)))))))
          (fs/glob out-dir "**.class")))

(defn- run-java [^String java-file args cp-str]
  (let [source (fs/file java-file)
        source-content (slurp java-file)
        package (java-package source-content)
        class-name (cond->> (fs/strip-ext (fs/file-name source))
                     package (str package "."))
        class-file (str (clojure.string/replace class-name "." "/") ".class")
        deps (parse-deps java-file)
        hash (sha256-hex source-content)
        cache-base (fs/path (cache-dir) hash)
        out-dir (str (fs/path cache-base "classes"))
        cp-file (fs/path cache-base "classpath")
        root (source-root source package)
        cached? (and (fs/exists? (fs/path out-dir class-file))
                     (classes-current? out-dir root))
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
            cmd (cond-> [javac "-d" out-dir "-sourcepath" (str root)]
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

(defn- jimage-available?
  "True when a JDK jimage is reachable, so Crema can load boot classes."
  []
  (boolean (when-let [home (or (System/getenv "JAVA_HOME")
                               (System/getProperty "java.home"))]
             (fs/exists? (fs/path home "lib" "modules")))))

(defn- prime-boot-class-registry!
  "Crema prints a warning on stdout the first time it consults the boot class
  loader without a jimage. Trigger that lookup here with stdout swallowed, so
  it does not land in the middle of program output."
  []
  (let [out System/out]
    (try
      (System/setOut (java.io.PrintStream. (java.io.OutputStream/nullOutputStream)))
      ;; The name must sit in a package of a boot module, otherwise the lookup
      ;; returns before Crema touches the jimage.
      (Class/forName "java.lang.CreamPrimeBootClassRegistry" false nil)
      (catch Throwable _)
      (finally (System/setOut out)))))

(defn -main [& args]
  ;; EA28 / Crema runtime-inits jdk.internal.jimage.ImageReaderFactory.
  ;; Its <clinit> reads java.home, which is null in the native binary,
  ;; causing NPE on the first ClassLoader.getResources(...) walk to
  ;; BootLoader. Set a non-null placeholder; actual JRT access goes
  ;; through Target_jdk_internal_jrtfs_SystemImage.findHome.
  (when (nil? (System/getProperty "java.home"))
    (System/setProperty "java.home" "/"))
  (when-not (jimage-available?)
    (prime-boot-class-registry!))
  ;; On Windows, *out* captured at build time has the wrong encoding.
  ;; https://github.com/babashka/babashka/issues/1009
  ;; https://github.com/oracle/graal/issues/12249
  (when (.contains (System/getProperty "os.name") "Windows")
    (alter-var-root #'*out* (constantly (java.io.OutputStreamWriter. System/out))))
  (let [[opts remaining] (parse-args args)
        [flag & main-args] remaining]
    (if (cli-flag? flag)
      (run-cli args)
      ;; -Scp means: use this classpath, do not compute one.
      (let [cp-str (or (:cp opts)
                       (when (or (:deps opts)
                                 (fs/exists? "deps.edn")
                                 (fs/exists? "pom.xml"))
                         (deps-classpath (:deps opts))))]
        (if (and flag (.endsWith ^String flag ".java"))
          (do (run-java flag main-args cp-str)
              (shutdown-agents))
          (do (when cp-str
                (set-classpath! cp-str))
              (if (= "-M" flag)
                (apply clojure.main/main main-args)
                (do (apply clojure.main/main remaining)
                    (shutdown-agents)))))))))
