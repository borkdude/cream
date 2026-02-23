# Cream

Clojure + [Crema](https://github.com/oracle/graal/issues/11327): a native
binary that runs full JVM Clojure with fast startup.

Cream uses GraalVM's Crema (RuntimeClassLoading) to enable runtime `eval`,
`require`, and library loading in a native binary.

> Warning: Cream is very alpha. It depends on GraalVM Crema (EA) and a
> custom Clojure fork. Do not use in production. Issues and ideas are welcome
> though: https://github.com/borkdude/cream/issues

## Install

Download from the [latest dev release](https://github.com/borkdude/cream/releases/tag/dev):

```sh
# macOS (Apple Silicon)
curl -sL https://github.com/borkdude/cream/releases/download/dev/cream-macos-aarch64.tar.gz | tar xz
# Linux (x86_64)
curl -sL https://github.com/borkdude/cream/releases/download/dev/cream-linux-amd64.tar.gz | tar xz
# Windows (PowerShell)
# Invoke-WebRequest -Uri https://github.com/borkdude/cream/releases/download/dev/cream-windows-amd64.zip -OutFile cream.zip
# Expand-Archive cream.zip -DestinationPath .

sudo mv cream /usr/local/bin/  # macOS/Linux
```

Or build from source (see [Building from source](#building-from-source)).

## Quick start

```sh
$ ./cream -M -e '(+ 1 2 3)'
6
```

## Runtime type creation

Unlike babashka, cream supports `definterface`, `deftype`, `gen-class`, and
other constructs that generate JVM bytecode at runtime:

```sh
$ ./cream -M -e '(do (definterface IGreet (greet [name]))
                     (deftype Greeter [] IGreet (greet [_ name] (str "Hello, " name)))
                     (.greet (Greeter.) "world"))'
"Hello, world"
```

## Loading libraries at runtime

Use `-Scp` to add JARs to the classpath:

```sh
./cream -Scp "$(clojure -Spath -Sdeps '{:deps {org.clojure/data.json {:mvn/version "RELEASE"}}}')" \
  -M -e '(do (require (quote [clojure.data.json :as json])) (json/write-str {:a 1}))'
;; => "{\"a\":1}"
```

## Requirements

Some Java interop may require `JAVA_HOME` pointing to a JDK installation, as
Crema loads certain classes at runtime from the JDK's `lib/modules` (JRT
filesystem). Pure Clojure code works without `JAVA_HOME`.

## Known limitations

- May need `JAVA_HOME` for Java interop (some JDK classes are loaded at runtime; pure Clojure works without it)
- Requires a lightly patched Clojure fork (minor workarounds for Crema
  quirks in `RT.java`, `Var.java`, and `Compiler.java`,
  [details](doc/technical.md#fork-changes))
- Java enum support broken (`enum.values()` and `EnumMap` crash in
  Crema's interpreter, affects http-kit, cheshire, clj-yaml)
- `Class.forName` not dispatchable (GraalVM inlines `Class.forName`
  substitutions at call sites, so Crema's interpreter can't dispatch to it;
  the Clojure fork redirects to `RT.classForName` as a workaround, but Java
  `.class` files calling `Class.forName` directly will still fail)
- Large binary (~300MB, includes Crema interpreter and preserved packages)
- Crema is EA (GraalVM's RuntimeClassLoading is experimental and only
  available in [EA builds](https://github.com/graalvm/oracle-graalvm-ea-builds))

See [doc/technical.md](doc/technical.md) for the full list of known issues and
workarounds.

## Cream vs Babashka

| | Cream | [Babashka](https://babashka.org) |
|---|---|---|
| Clojure | Full JVM Clojure (1.13 fork) | SCI interpreter (subset) |
| Library loading | Any library from JARs at runtime (except enum/Class.forName issues) | Any library (with built-in classes, SCI/deftype limitations) |
| Java interop | Full (runtime class loading) | Limited to compiled-in classes |
| Startup | ~20ms | ~20ms |
| Binary size | ~300MB | ~70MB |
| Standalone | Mostly (may need `JAVA_HOME` for Java interop) | Yes |
| Loop 10M iterations* | ~720ms | ~270ms |
| Compile time (GitHub Actions, linux-amd64) | ~10min | ~3min |
| Maturity | Experimental | Production-ready |

\* `(time (loop [i 0] (when (< i 10000000) (recur (inc i)))))`

Java interop is faster in cream since it calls methods directly rather than
through SCI's reflection layer:

```sh
# 100K StringBuilder appends, cream is ~2x faster
$ ./cream -M -e '(time (let [sb (StringBuilder.)] (dotimes [i 100000] (.append sb (str i))) (.length sb)))'
"Elapsed time: 32 msecs"

$ bb -e '(time (let [sb (StringBuilder.)] (dotimes [i 100000] (.append sb (str i))) (.length sb)))'
"Elapsed time: 72 msecs"
```

Pure clojure loading/parsing time from libraries would be slower than bb/SCI in cream as here it needs to first compile the clojure code into bytecodes and then run it.
Whereas bb interprets it directly through SCI without compilation.
This may have an impact on the startup time expectations.

```sh
$ ./cream -Scp "$(clojure -Spath -Sdeps '{:deps {camel-snake-kebab/camel-snake-kebab {:mvn/version "0.4.3"}}}')" -M -e '(time (require (quote [camel-snake-kebab.core :as csk])))'
"Elapsed time: 125.03336 msecs"

$ bb -cp "$(clojure -Spath -Sdeps '{:deps {camel-snake-kebab/camel-snake-kebab {:mvn/version "0.4.3"}}}')" -e '(time (require (quote [camel-snake-kebab.core :as csk])))'
"Elapsed time: 26.231804 msecs"

$ ./cream -Scp "$(clojure -Spath -Sdeps '{:deps {dev.weavejester/medley {:mvn/version "1.9.0"}}}')" -M -e '(time (require (quote [medley.core :as mc])))'
Reflection warning, medley/core.cljc:519:25 - call to java.util.ArrayList ctor can't be resolved.
"Elapsed time: 120.566124 msecs"

$ bb -cp "$(clojure -Spath -Sdeps '{:deps {dev.weavejester/medley {:mvn/version "1.9.0"}}}')" -e '(time (require (quote [medley.core :as mc])))'
"Elapsed time: 32.24462 msecs"
```

When cream might make sense: you need full Clojure compatibility, arbitrary
library loading, or Java interop beyond what babashka offers.

When babashka is better: scripting, tasks, CI glue, or anything where a
standalone binary, fast startup, and a mature ecosystem matter.

## Tested libraries

Libraries are tested against the cream binary using `bb run-lib-tests`.

| Library | CI | Status | Notes |
|---------|:--:|--------|-------|
| [data.csv](https://github.com/clojure/data.csv) | :white_check_mark: | Works | |
| [data.json](https://github.com/clojure/data.json) | :white_check_mark: | Works | |
| [data.xml](https://github.com/clojure/data.xml) | | Works | |
| [core.async](https://github.com/clojure/core.async) | :white_check_mark: | Works | Some test ns skipped (ForkJoinPool segfault) |
| [math.combinatorics](https://github.com/clojure/math.combinatorics) | :white_check_mark: | Works | |
| [tools.reader](https://github.com/clojure/tools.reader) | :white_check_mark: | Works | |
| [medley](https://github.com/weavejester/medley) | :white_check_mark: | Works | |
| [camel-snake-kebab](https://github.com/clj-commons/camel-snake-kebab) | :white_check_mark: | Works | |
| [hiccup](https://github.com/weavejester/hiccup) | :white_check_mark: | Works | |
| [deep-diff2](https://github.com/lambdaisland/deep-diff2) | :white_check_mark: | Works | |
| [malli](https://github.com/metosin/malli) | | Works | |
| [meander](https://github.com/noprompt/meander) | | Works | |
| [selmer](https://github.com/yogthos/Selmer) | | Works | |
| [specter](https://github.com/redplanetlabs/specter) | | Works | |
| [tick](https://github.com/juxt/tick) | | Works | |
| [clj-commons/fs](https://github.com/clj-commons/fs) | | Works | |
| [prismatic/schema](https://github.com/plumatic/schema) | :white_check_mark: | Works | |
| [flatland/useful](https://github.com/flatland/useful) | | Works | |
| [http-kit](https://github.com/http-kit/http-kit) | | Fails | Enum `values()` NPE in Crema |
| [cheshire](https://github.com/dakrone/cheshire) | | Fails | Jackson enum `values()` NPE in Crema |
| [clj-yaml](https://github.com/clj-commons/clj-yaml) | | Fails | SnakeYaml enum NPE in Crema |

Pure Clojure libraries generally work. Libraries using Java interop work when
the relevant packages are preserved. Libraries using Java enums fail due to a
Crema bug ([known limitation](#known-limitations)).

## Building from source

Requires a GraalVM EA build with RuntimeClassLoading support.

1. Install the [custom Clojure fork](https://github.com/borkdude/clojure/tree/crema):
   ```sh
   git clone -b crema https://github.com/borkdude/clojure.git /tmp/clojure-fork
   cd /tmp/clojure-fork && mvn install -Dmaven.test.skip=true
   ```

2. Build the native binary:
   ```sh
   GRAALVM_HOME=/path/to/graalvm bb build-native
   ```

## Future work

- Fully standalone binary: investigate whether JRT metadata can be bundled
  in the binary to eliminate the `JAVA_HOME` requirement for Java interop
- Enum support: blocked on Crema fixing `enum.values()` /
  `InterpreterResolvedObjectType.getDeclaredMethodsList()` NPE, would unblock
  http-kit, cheshire, clj-yaml
- Reduce binary size: currently ~300MB due to preserved packages and
  Crema interpreter overhead
- nREPL support: enable interactive development with editor integration

## Documentation

See [doc/technical.md](doc/technical.md) for implementation details,
architecture decisions, and known issues.

## License

Distributed under the EPL License. See [LICENSE](LICENSE).

This project contains code from:
- [Clojure](https://clojure.org), which is licensed under the same EPL License.
