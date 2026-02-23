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
         "--initialize-at-run-time=com.sun.tools.javac.file.Locations,jdk.internal.jrtfs.SystemImage"
         "--initialize-at-build-time=clojure,cream,org.xml.sax,com.sun.tools.doclint,com.sun.tools.javac.parser.Tokens$TokenKind,com.sun.tools.javac.parser.Tokens$Token$Tag"
         "--features=ClojureFeature"
         "-H:+UnlockExperimentalVMOptions"
         "-H:Name=cream"
         "-H:+RuntimeClassLoading"
         "-H:ConfigurationFileDirectories=."
         "-H:IncludeResources=clojure/.*"
         "-H:Preserve=package=clojure.lang"
         ;; Preserve java/javax packages for Crema runtime
         ;; Based on babashka's impl/classes.clj coverage
         "-H:Preserve=package=java.io"
         "-H:Preserve=package=java.lang"
         "-H:Preserve=package=java.lang.invoke"
         "-H:Preserve=package=java.lang.runtime"
         "-H:Preserve=package=java.lang.ref"
         "-H:Preserve=package=java.lang.reflect"
         "-H:Preserve=package=java.math"
         "-H:Preserve=package=java.net"
         "-H:Preserve=package=java.net.http"
         "-H:Preserve=package=java.nio"
         "-H:Preserve=package=java.nio.channels"
         "-H:Preserve=package=java.nio.charset"
         "-H:Preserve=package=java.nio.file"
         "-H:Preserve=package=java.nio.file.attribute"
         "-H:Preserve=package=java.security"
         "-H:Preserve=package=java.security.cert"
         "-H:Preserve=package=java.security.spec"
         "-H:Preserve=package=java.sql"
         "-H:Preserve=package=java.text"
         "-H:Preserve=package=java.time"
         "-H:Preserve=package=java.time.chrono"
         "-H:Preserve=package=java.time.format"
         "-H:Preserve=package=java.time.temporal"
         "-H:Preserve=package=java.time.zone"
         "-H:Preserve=package=java.util.*"
         "-H:Preserve=package=javax.crypto"
         "-H:Preserve=package=javax.crypto.spec"
         "-H:Preserve=package=javax.net.ssl"
         "-H:Preserve=package=javax.xml.*"
         (str "-Djava.home=" (System/getenv "GRAALVM_HOME"))
         "-J-Djava.file.encoding=UTF-8"
         "-Djava.file.encoding=UTF-8"
         "--enable-url-protocols=http,https,jar,unix"
         "--enable-all-security-services"
         "-H:+AllowJRTFileSystem"
         "-H:ConfigurationFileDirectories=."
         "--verbose")
