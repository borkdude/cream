# ADR 0001: Load the Clojure tools jar from source

## Status

Accepted

## Context

Cream resolves `deps.edn` dependencies in its own process. deps.clj downloads
the Clojure tools jar, and cream runs `clojure.tools.deps.script.make-classpath2`
from it through deps.clj's `*make-classpath-fn*`.

The tools jar is an uberjar, AOT-compiled with direct linking against the
Clojure version it bundles. It contains the `.clj` sources next to the
`__init.class` files.

Loading its AOT classes in cream fails in two ways.

Direct-linked code calls `invokeStatic` on the clojure.core function classes.
Native-image analysis removes the `invokeStatic` methods that cream itself
never reaches. Crema cannot call a removed method, so the call crashes:

```
Fatal error: Unable to call AOT method: ...
```

The image contains the Clojure version of the cream fork, 1.13 at this time.
Classes in the image cannot be loaded again, so the tools jar runs against
that version instead of its own. Direct-linked code depends on exact method
signatures. Clojure 1.13 changed `merge` from `[& maps]` to fixed arities, so
the tools jar built against 1.12 fails:

```
java.lang.NoSuchMethodError: clojure.core$merge.invokeStatic(Lclojure/lang/ISeq;)Ljava/lang/Object;
```

## Decision

Load the tools jar through a `JarClassLoader` that leaves out the entries
ending in `__init.class`. `RT.load` then compiles each namespace from source,
without direct linking. Calls go through vars and `IFn.invoke`, which the image
contains for every clojure.core function.

Java classes in the jar, such as the Maven resolver, load from the jar as
usual.

After resolving, remove the namespaces the resolver loaded and restore
`*loaded-libs*`, so user code loads its own versions of libraries such as
core.async.

## Consequences

- Resolving needs no java. `CREAM_DEPS_RESOLVER=jvm` resolves with java.
- A cache miss compiles the tools namespaces from source. This took about 0.9
  seconds in a test, against about 0.15 seconds for the AOT classes.
- The tools.deps version is the one deps.clj pins, so cream keeps no copy of
  tools.deps in sync.
- Any tools jar version works, whatever Clojure version it was compiled
  against.

## Alternatives considered

- Preserve the Clojure packages (`-H:Preserve=package=clojure`,
  `clojure.core`, `clojure.java` and others). This compiles every clojure.core
  method into the image and fixes the first failure. The binary grew from
  222,199,432 to 240,428,824 bytes. The tools jar then fails with the
  `NoSuchMethodError` above.
- Compile tools.deps into the image. This grows the binary and the macOS build
  time, and ties the tools.deps version to cream releases.
- Use babashka's patched tools.deps and Maven client. Upstream tools.deps runs
  in cream from source, so the patches are not needed.
- Change Crema to interpret removed image methods from class file bytecode.
  This needs a GraalVM change and does not fix the version mismatch.
