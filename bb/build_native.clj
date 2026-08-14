(ns build-native
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [babashka.tasks :as tasks]))

(tasks/clojure "-T:build" "uber")
(println "done with uberjar")

(p/shell (str (fs/file (System/getenv "GRAALVM_HOME") "bin"
                       (if (fs/windows?) "native-image.cmd" "native-image")))
         "-jar" "target/cream-1.0.0-standalone.jar"
         "-O1"
         "--initialize-at-run-time=com.sun.tools.javac.file.Locations,jdk.internal.jrtfs.SystemImage"
         "--initialize-at-build-time=clojure,cream,org.xml.sax,com.sun.tools.doclint,com.sun.tools.javac.parser.Tokens$TokenKind,com.sun.tools.javac.parser.Tokens$Token$Tag"
         "--features=ClojureFeature,clj_easy.graal_build_time.InitClojureClasses"
         "--add-modules=java.xml,java.logging"
         "-H:+UnlockExperimentalVMOptions"
         "-H:Name=cream"
         "-H:+RuntimeClassLoading"
         ;; Ristretto: JIT-compile runtime-loaded bytecode instead of interpreting
         "-H:+GraalJITCompileAtRuntime"
         "-H:ConfigurationFileDirectories=."
         "-H:IncludeResources=clojure/.*"
         ;; JNA's native dispatch library — must be embedded so it can be extracted at runtime.
         "-H:IncludeResources=com/sun/jna/.*"
         ;; Minimal Preserve set — verified by bb/test_preserve.clj
         "-H:Preserve=package=java.io"
         "-H:Preserve=package=java.lang"
         "-H:Preserve=package=clojure.lang"
         "-H:Preserve=package=java.lang.invoke"
         "-H:Preserve=package=java.lang.reflect"
         "-H:Preserve=package=java.util"
         "-H:Preserve=package=java.util.concurrent"
         "-H:Preserve=package=java.util.concurrent.atomic"
         "-H:Preserve=package=java.util.stream"
         "-H:Preserve=package=java.text"
         "-H:Preserve=package=java.util.concurrent.locks"
         "-H:Preserve=package=java.util.function"
         "-H:Preserve=package=java.util.regex"
         "-H:Preserve=package=java.util.zip"
         "-H:Preserve=package=java.util.jar"
         "-H:Preserve=package=java.nio.file"
         "-H:Preserve=package=java.nio.charset"
         "-H:Preserve=package=java.time"
         "-H:Preserve=package=java.security"
         "-H:Preserve=package=java.net"
         "-H:Preserve=package=javax.net"
         "-H:Preserve=package=javax.net.ssl"
         "-H:Preserve=package=java.nio"
         "-H:Preserve=package=java.nio.channels"
         "-H:Preserve=package=java.nio.channels.spi"
         "-H:Preserve=module=java.logging"
         "-H:Preserve=module=java.sql"
         ;; jdk.unsupported: sun.misc.Unsafe static fields (ARRAY_*_BASE_OFFSET) needed
         ;; by dtype-next / libpython-clj. In JDK 9+, sun.misc lives in jdk.unsupported.
         "-H:Preserve=module=jdk.unsupported"
         ;; Panama FFI method table (Arena, SymbolLookup, ValueLayout, etc.) needed by
         ;; dtype-next's :jdk-21 FFI backend, which libpython-clj uses to call libpython.
         "-H:Preserve=package=java.lang.foreign"
         ;; java.lang.annotation.RetentionPolicy/RUNTIME used by insn (dtype-ffi's
         ;; bytecode generator) when building the Panama FFI bridge at runtime.
         "-H:Preserve=package=java.lang.annotation"
         ;; sun.net.www.protocol.http(s).Handler constructors are AOT-compiled but
         ;; not reachable-analyzed by default, causing a Fatal error (process abort,
         ;; not a catchable exception) on the first HTTPS/HTTP URL connection.
         "-H:Preserve=package=sun.net.www.protocol.http"
         "-H:Preserve=package=sun.net.www.protocol.https"
         ;; JNA itself: the official metadata's typeReached conditions never fire
         ;; under Crema's dynamic invocation pattern — so force full class/field/
         ;; method reachability for the package unconditionally.
         "-H:Preserve=package=com.sun.jna"
         ;; UTF-32LE/BE charset needed by libpython-clj's string marshalling
         ;; (Python strings are internally UTF-32 on some platforms/builds).
         ;; Preserve alone isn't enough — Charset.forName's provider lookup
         ;; needs charsets other than the image's default made available too.
         "-H:Preserve=package=sun.nio.cs"
         "-H:+AddAllCharsets"
         ;; Silences JDK21+ restricted-method warnings from JNA's System.load
         ;; call; cosmetic only, not required for correctness.
         "--enable-native-access=ALL-UNNAMED"
         (str "-Djava.home=" (System/getenv "GRAALVM_HOME"))
         "-J-Djava.file.encoding=UTF-8"
         "-Djava.file.encoding=UTF-8"
         "--enable-url-protocols=http,https,jar,unix"
         "-H:+AllowJRTFileSystem"
         "--verbose")
