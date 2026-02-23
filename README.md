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

Use `-Scp` to add JARs to the classpath:

```sh
./cream -Scp "$(clojure -Spath -Sdeps '{:deps {org.clojure/data.json {:mvn/version "RELEASE"}}}')" \
  -M -e '(do (require (quote [clojure.data.json :as json])) (json/write-str {:a 1}))'
;; => "{\"a\":1}"
```

## Running Java files

Cream can run `.java` source files directly, compiling and caching them
automatically. This makes it a fast alternative to [JBang](https://www.jbang.dev/)
with fast startup.

```sh
$ cat /tmp/Hello.java
public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello from Java!");
        if (args.length > 0) {
            System.out.println("Args: " + String.join(", ", args));
        }
    }
}

$ ./cream /tmp/Hello.java
Hello from Java!

$ ./cream /tmp/Hello.java world
Hello from Java!
Args: world
```

### Dependencies

Use `//DEPS` comments (same syntax as JBang) to declare Maven dependencies:

```sh
$ cat /tmp/HelloCodec.java
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

Some Java interop may require `JAVA_HOME` pointing to a JDK installation, as
Crema loads certain classes at runtime from the JDK's `lib/modules` (JRT
filesystem). Pure Clojure code works without `JAVA_HOME`.

## Known limitations

- May need `JAVA_HOME` for Java interop (some JDK classes are loaded at runtime; pure Clojure works without it)
- Requires a lightly patched Clojure fork (minor workarounds for Crema
  quirks in `RT.java`, `Var.java`, and `Compiler.java`,
  [details](doc/technical.md#fork-changes))
- Java enum support broken ([oracle/graal#13034](https://github.com/oracle/graal/issues/13034):
  `enum.values()` and `EnumMap` crash in Crema's interpreter, affects
  http-kit, cheshire, clj-yaml)
- `Class.forName` not dispatchable ([oracle/graal#13031](https://github.com/oracle/graal/issues/13031):
  GraalVM inlines `Class.forName` substitutions at call sites, so Crema's
  interpreter can't dispatch to it; the Clojure fork redirects to
  `RT.classForName` as a workaround, but Java `.class` files calling
  `Class.forName` directly will still fail)
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

Some perceived difference in loading/parsing time of pure clojure code and runtime performance. Some of these could be addressed by the [addition](https://github.com/oracle/graal/issues/11327#issuecomment-3914916673) of a JIT to Crema:

```sh
$ bb -cp "$(clojure -Spath -Sdeps '{:deps {dev.weavejester/medley {:mvn/version "1.9.0"}}}')" -e '(time (require (quote [medley.core :as mc]))) (time (dotimes [i 100000] (mc/greatest 5 2 1 3 4)))'
"Elapsed time: 31.452928 msecs"
"Elapsed time: 347.639424 msecs"

$ ./cream -Scp "$(clojure -Spath -Sdeps '{:deps {dev.weavejester/medley {:mvn/version "1.9.0"}}}')" -M -e '(time (require (quote [medley.core :as mc]))) (time (dotimes [i 100000] (mc/greatest 5 2 1 3 4)))'

Reflection warning, medley/core.cljc:519:25 - call to java.util.ArrayList ctor can't be resolved.
"Elapsed time: 122.997416 msecs"
"Elapsed time: 785.789744 msecs"

$ ./cream -Scp "$(clojure -Spath -Sdeps '{:deps {camel-snake-kebab/camel-snake-kebab {:mvn/version "0.4.3"}}}')" -M -e '(time (require (quote [camel-snake-kebab.core :as csk]))) (time (dotimes [i 100000] (csk/->SCREAMING_SNAKE_CASE "I am constant")))'

"Elapsed time: 124.247888 msecs"
"Elapsed time: 11771.946679 msecs"

$ bb -cp "$(clojure -Spath -Sdeps '{:deps {camel-snake-kebab/camel-snake-kebab {:mvn/version "0.4.3"}}}')" -e '(time (require (quote [camel-snake-kebab.core :as csk]))) (time (dotimes [i 100000] (csk/->SCREAMING_SNAKE_CASE "I am constant")))'

"Elapsed time: 25.805933 msecs"
"Elapsed time: 3285.816775 msecs"
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
- Enum support: blocked on [oracle/graal#13034](https://github.com/oracle/graal/issues/13034),
  would unblock http-kit, cheshire, clj-yaml
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
