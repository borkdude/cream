# Cream

Clojure + [Crema](https://github.com/oracle/graal/issues/11327): a native
binary that runs full JVM Clojure with fast startup.

Cream uses GraalVM's Crema (RuntimeClassLoading) to enable runtime `eval`,
`require`, and library loading in a native binary. It can also [run Java source
files](#running-java-files) directly, as a fast alternative to
[JBang](https://www.jbang.dev/).

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

Use `-Sdeps` to add dependencies:

```sh
./cream -Sdeps '{:deps {org.clojure/data.json {:mvn/version "RELEASE"}}}' \
  -M -e '(do (require (quote [clojure.data.json :as json])) (json/write-str {:a 1}))'
;; => "{\"a\":1}"
```

A `deps.edn` in the current directory is picked up automatically:

```sh
$ cat deps.edn
{:paths ["src"] :deps {dev.weavejester/medley {:mvn/version "1.9.0"}}}
$ ./cream -M -m my.app
```

Use `-M:alias` to add `:extra-deps`, `:extra-paths` and `:main-opts` from an
alias. `-A`, `-X` and `-T` work as in the Clojure CLI:

```sh
./cream -M:test
```

Use `-Scp` to pass a classpath directly. Nothing is resolved and `deps.edn` is
ignored:

```sh
./cream -Scp "$(clojure -Spath)" -M -m my.app
```

Dependencies are resolved with [deps.clj](https://github.com/borkdude/deps.clj),
which is built into the binary. Resolving needs `java` on the `PATH` or
`JAVA_HOME` set, reading the cached classpath from `.cpcache` does not. The
first resolution downloads the Clojure tools jar to `~/.deps.clj`.

## Running Java files

Cream can run `.java` source files directly, compiling and caching them
automatically. This makes it a fast alternative to [JBang](https://www.jbang.dev/).

```java
public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello from Java!");
        if (args.length > 0) {
            System.out.println("Args: " + String.join(", ", args));
        }
    }
}
```

```sh
$ ./cream /tmp/Hello.java
Hello from Java!

$ ./cream /tmp/Hello.java world
Hello from Java!
Args: world
```

### Dependencies

Use `//DEPS` comments (same syntax as JBang) to declare Maven dependencies:

```java
//DEPS commons-codec:commons-codec:1.17.1

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;

public class HelloCodec {
    public static void main(String[] args) {
        String input = args.length > 0 ? args[0] : "hello world";
        System.out.println("Input: " + input);
        System.out.println("Hex: " + Hex.encodeHexString(input.getBytes()));
        System.out.println("SHA-256: " + DigestUtils.sha256Hex(input));
    }
}
```

```sh
$ time ./cream /tmp/HelloCodec.java
Input: hello world
Hex: 68656c6c6f20776f726c64
SHA-256: b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9
./cream /tmp/HelloCodec.java  0.02s user 0.01s system 93% cpu 0.031 total
```

Dependencies are resolved from Maven Central using
[deps.clj](https://github.com/borkdude/deps.clj), with no external tooling
required. Compiled classes and resolved classpaths are cached under
`$XDG_CACHE_HOME/cream/` (defaulting to `~/.cache/cream/`) so subsequent runs
skip compilation and dependency resolution.

## Requirements

Clojure code runs from the binary alone, with no JDK on the system. This
covers Java interop and every library in [Tested libraries](#tested-libraries),
including HTTPS.

Running `.java` files requires `JAVA_HOME` pointing to a JDK, since cream
shells out to `javac`. A JDK is also needed for classes that are neither in the
image nor on the classpath, which Crema loads from `lib/modules` (JRT
filesystem).

Resolving `deps.edn` or `-Sdeps` dependencies needs `java` on the `PATH` or
`JAVA_HOME` set. A cached classpath is read without either.

## Known limitations

- Running `.java` files needs a JDK on the system for `javac`. Clojure code
  does not
- Loading a class from `lib/modules` needs `JAVA_HOME`. Without it the class
  is not found
- Requires a lightly patched Clojure fork (minor workarounds for Crema
  quirks in `RT.java` and `Var.java`,
  [details](doc/technical.md#fork-changes))
- Java enum support fixed in GraalVM ea17 ([oracle/graal#13034](https://github.com/oracle/graal/issues/13034))
- Large binary (~208MiB, includes Crema interpreter, Ristretto JIT and
  preserved packages)
- Crema is EA (GraalVM's RuntimeClassLoading is experimental and only
  available in [EA builds](https://github.com/graalvm/oracle-graalvm-ea-builds))

See [doc/technical.md](doc/technical.md) for the full list of known issues and
workarounds.

## Cream vs Babashka

| | Cream | [Babashka](https://babashka.org) |
|---|---|---|
| Clojure | Full JVM Clojure (1.13 fork) | SCI interpreter (subset) |
| Library loading | Any library from JARs at runtime | Any library (with built-in classes, SCI/deftype limitations) |
| Java interop | Full (runtime class loading) | Limited to compiled-in classes |
| Startup | ~7ms | ~10ms |
| Binary size | ~208MiB | ~68MiB |
| Standalone | Yes for Clojure, JDK needed to run `.java` files | Yes |
| Loop 10M iterations* | ~21ms | ~173ms |
| Compile time (GitHub Actions, linux-amd64) | ~10min | ~3min |
| Maturity | Experimental | Production-ready |

\* `(time (loop [i 0] (when (< i 10000000) (recur (inc i)))))`

Java interop is faster in cream since it calls methods directly rather than
through SCI's reflection layer:

```sh
# 100K StringBuilder appends, cream is ~3x faster
$ ./cream -M -e '(time (let [sb (StringBuilder.)] (dotimes [i 100000] (.append sb (str i))) (.length sb)))'
"Elapsed time: 20.593417 msecs"

$ bb -e '(time (let [sb (StringBuilder.)] (dotimes [i 100000] (.append sb (str i))) (.length sb)))'
"Elapsed time: 65.012708 msecs"
```

Babashka still loads pure Clojure code faster. Cream runs it faster once
loaded, since `-H:+GraalJITCompileAtRuntime` compiles runtime-loaded bytecode
instead of interpreting it:

```sh
$ bb -cp "$(clojure -Spath -Sdeps '{:deps {dev.weavejester/medley {:mvn/version "1.9.0"}}}')" -e '(time (require (quote [medley.core :as mc]))) (time (dotimes [i 100000] (mc/greatest 5 2 1 3 4)))'
"Elapsed time: 4.9255 msecs"
"Elapsed time: 62.024166 msecs"

$ ./cream -Scp "$(clojure -Spath -Sdeps '{:deps {dev.weavejester/medley {:mvn/version "1.9.0"}}}')" -M -e '(time (require (quote [medley.core :as mc]))) (time (dotimes [i 100000] (mc/greatest 5 2 1 3 4)))'

Reflection warning, medley/core.cljc:519:25 - call to java.util.ArrayList ctor can't be resolved.
"Elapsed time: 45.207875 msecs"
"Elapsed time: 30.8955 msecs"

$ ./cream -Scp "$(clojure -Spath -Sdeps '{:deps {camel-snake-kebab/camel-snake-kebab {:mvn/version "0.4.3"}}}')" -M -e '(time (require (quote [camel-snake-kebab.core :as csk]))) (time (dotimes [i 100000] (csk/->SCREAMING_SNAKE_CASE "I am constant")))'

"Elapsed time: 63.072834 msecs"
"Elapsed time: 246.68125 msecs"

$ bb -cp "$(clojure -Spath -Sdeps '{:deps {camel-snake-kebab/camel-snake-kebab {:mvn/version "0.4.3"}}}')" -e '(time (require (quote [camel-snake-kebab.core :as csk]))) (time (dotimes [i 100000] (csk/->SCREAMING_SNAKE_CASE "I am constant")))'

"Elapsed time: 4.244083 msecs"
"Elapsed time: 551.424208 msecs"
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
| [instaparse](https://github.com/Engelberg/instaparse) | :white_check_mark: | Works | |
| [flatland/useful](https://github.com/flatland/useful) | | Works | |
| [cheshire](https://github.com/dakrone/cheshire) | :white_check_mark: | Works | Enum fix in ea17 |
| [clj-yaml](https://github.com/clj-commons/clj-yaml) | :white_check_mark: | Works | Fixed in ea20 (`java.lang` preserve) |
| [nextjournal/markdown](https://github.com/nextjournal/markdown) | :white_check_mark: | Works | Lambda fix in ea20 + `java.lang` preserve |
| [Jsoup](https://jsoup.org/) | | Works | HTML parsing |
| [http-kit](https://github.com/http-kit/http-kit) | | Works | Server + client including HTTPS |

Pure Clojure libraries generally work. Libraries using Java interop work when
the relevant packages are preserved.

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

- Bundle JRT metadata in the binary so classes from `lib/modules` resolve
  without a JDK
- Reduce binary size: currently ~208MiB due to preserved packages, the
  Crema interpreter and the Ristretto JIT
- nREPL support: enable interactive development with editor integration

## Documentation

See [doc/technical.md](doc/technical.md) for implementation details,
architecture decisions, and known issues.

## License

Distributed under the EPL License. See [LICENSE](LICENSE).

This project contains code from:
- [Clojure](https://clojure.org), which is licensed under the same EPL License.
