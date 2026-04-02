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
         "-H:ConfigurationFileDirectories=."
         "-H:IncludeResources=clojure/.*"
         ;; Minimal Preserve set — verified by bb/test_preserve.clj
         "-H:Preserve=package=java.lang"
         "-H:Preserve=package=clojure.lang"
         "-H:Preserve=package=java.lang.invoke"
         "-H:Preserve=package=java.lang.reflect"
         "-H:Preserve=package=java.util"
         "-H:Preserve=package=java.util.concurrent"
         "-H:Preserve=package=java.util.concurrent.atomic"
         "-H:Preserve=package=java.net"
         "-H:Preserve=package=javax.net.ssl"
         "-H:Preserve=package=java.nio"
         "-H:Preserve=package=java.nio.channels"
         "-H:Preserve=package=java.nio.channels.spi"
         "-H:Preserve=module=java.logging"
         (str "-Djava.home=" (System/getenv "GRAALVM_HOME"))
         "-J-Djava.file.encoding=UTF-8"
         "-Djava.file.encoding=UTF-8"
         "--enable-url-protocols=http,https,jar,unix"
         "-H:+AllowJRTFileSystem"
         "--verbose")
