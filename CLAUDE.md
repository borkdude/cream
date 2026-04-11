# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Cream is a native binary that runs full JVM Clojure with fast startup (~20ms), using GraalVM's experimental Crema (RuntimeClassLoading) feature. It supports runtime `eval`, `require`, library loading from JARs, and can also run `.java` source files directly (like JBang). It depends on a custom Clojure fork (`1.13.0-cream-SNAPSHOT`) with minor patches for Crema compatibility.

## Build Commands

```sh
# Install the custom Clojure fork (prerequisite)
git clone -b crema --depth 1 https://github.com/borkdude/clojure.git /tmp/clojure-fork
cd /tmp/clojure-fork && mvn install -Dmaven.test.skip=true

# Build uberjar only
bb uber

# Build native binary (requires GRAALVM_HOME env var pointing to GraalVM EA)
GRAALVM_HOME=/path/to/graalvm bb build-native

# Generate reflect-config.json
bb gen-reflect

# Clean target directory
bb clean

# Run library tests (all)
bb run-lib-tests

# Run library tests (single library)
bb run-lib-tests medley/medley
```

## Architecture

- **`src/cream/main.clj`** — Entry point. Handles CLI arg parsing (`-Scp`, `-M`, `.java` files), sets up `JarClassLoader` for runtime classpath, delegates to `clojure.main` for Clojure execution, and compiles/caches/runs Java source files with `//DEPS` support.
- **`src-java/cream/JarClassLoader.java`** — Custom classloader extending `DynamicClassLoader` that reads JARs and directories directly (workaround for `URLClassLoader.findResource()` not working in Crema native images).
- **`src-java/ClojureFeature.java`** — GraalVM Feature that forces sequential Clojure core initialization in `beforeAnalysis()` to prevent circular class init deadlocks.
- **`src-java/Target_jdk_internal_misc_VM.java`** — GraalVM substitutions for JDK internals (VM.initialize no-op, JRT filesystem home resolution).
- **`build.clj`** — tools.build script for uberjar/jar/deploy. Java sources compiled with `-source 25 -target 25`.
- **`bb/build_native.clj`** — Babashka script that builds uberjar then runs `native-image` with Crema flags (`-H:+RuntimeClassLoading`, `-H:Preserve=package=...` for many java/javax packages).
- **`bb/run_lib_tests.clj`** — Babashka script that tests libraries against the cream binary using cognitect test-runner. Test config (skipped tests/namespaces) is defined inline.
- **`bb.edn`** — Babashka task definitions (build-native, gen-reflect, uber, clean, run-lib-tests).

## Key Constraints

- Requires GraalVM EA builds with RuntimeClassLoading support
- Java enums were broken in Crema (`enum.values()` NPE) — fixed in ea17 (PR #13081)
- `Class.forName` not dispatchable in Crema from runtime-loaded Java bytecode; the Clojure fork redirects to `RT.classForName` for Clojure code, but pure Java libraries (e.g. SnakeYAML) still hit this — [GH-13031](https://github.com/oracle/graal/issues/13031). Fixed upstream by [GH-13187](https://github.com/oracle/graal/pull/13187) (merged 2026-03-24). clj-yaml (SnakeYAML) works in ea20 after adding `Preserve=package=java.lang` — the actual blocker was `StringConcat` generated classes needing `java.lang.String` methods compiled, not `Class.forName`.
- Java lambdas across classloader boundaries — fixed in ea20 by [GH-13190](https://github.com/oracle/graal/pull/13190). Lambda repro at `repro/lambda/` now prints `HELLO`. nextjournal.markdown (wrapping commonmark-java) works and is in the lib test suite.
- http-kit: fully works (server + client including HTTPS) after adding preserves for `java.net`, `javax.net.ssl`, `java.nio`, `java.nio.channels`, `java.nio.channels.spi`, and `java.util.concurrent.atomic`.
- `Preserve=package=java.lang` was added in ea20 to fix `StringConcat` generated classes crashing with "Method not compiled". Without it, any runtime-loaded Java code using string concatenation (`+` on strings, which compiles to `invokedynamic makeConcatWithConstants`) would segfault. This fixed both clj-yaml and commonmark-java's inline parsing.
- Some tests are skipped per-library due to Crema limitations (see `skip-tests` and `skip-namespaces` in `bb/run_lib_tests.clj`)
- The binary is ~175MB due to preserved packages and Crema interpreter (shrunk from ~195MB on ea20 to ~175MB on ea21)
- ForkJoinPool / virtual thread segfault still reproduces on ea21 (jvmci-25.1-b17) — see `repro/forkjoin/`. Blocks the skipped core.async namespaces.
