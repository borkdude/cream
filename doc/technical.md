# Cream — Technical Notes

Implementation details and known issues.
See the [README](../README.md) for usage.

## Custom Clojure fork

Branch `crema` of [`github.com/borkdude/clojure`](https://github.com/borkdude/clojure)
(`1.13.0-cream-SNAPSHOT`).

```sh
git clone -b crema --depth 1 https://github.com/borkdude/clojure.git /tmp/clojure-fork
cd /tmp/clojure-fork && mvn install -Dmaven.test.skip=true
```

### Fork changes

`RT.java`:
- Skip loading `clojure/core` at native-image runtime (already loaded at build
  time) using `org.graalvm.nativeimage.imagecode` property check
- Skip `doInit()` at native-image runtime (user ns, refer, server were set up
  at build time)
- Wrap `clojure.core.server` loading in `!nativeImageRuntime` guard

`Var.java`:
- During build-time class init (`imagecode=buildtime`), `set!` falls back to
  `bindRoot()` instead of throwing. Fixes "Can't change/establish root binding
  of: *warn-on-reflection* with set".

`Compiler.java`:
- `StaticMethodExpr.emit()` redirects `Class.forName` calls to `RT.classForName`.
  See [Class.forName and GraalVM substitutions](#classforname-and-graalvm-substitutions).
- `FISupport.maybeFIMethod()` catches `UnsupportedOperationException` from
  `Class.getRawAnnotations()` for Crema runtime-loaded classes.

## Architecture

### GraalVM substitutions (`src-java/Target_jdk_internal_misc_VM.java`)

- `jdk.internal.misc.VM.initialize()` — no-op
- `jdk.internal.jrtfs.SystemImage.findHome()` — returns `System.getProperty("java.home")`
  to work around `getProtectionDomain().getCodeSource()` issue for boot classes
- `VM.getRuntimeArguments()` substitution removed — 25e1 EA provides it
  internally (duplicate causes "conflicts with previously registered" error)

### Class initialization

`--initialize-at-build-time=clojure` AOT-compiles Clojure core.
The `Var.set()` fork fix handles `*warn-on-reflection*`.
`jdk.internal.jrtfs.SystemImage` must be `--initialize-at-run-time` or the
analysis phase deadlocks.

### Deterministic class initialization (`ClojureFeature`)

`--initialize-at-build-time=clojure` eagerly initializes all `clojure.*` classes
in parallel. This causes circular deadlocks: compiled Clojure classes reference
`RT` in `<clinit>`, while `RT.<clinit>` loads core which needs those classes.

`ClojureFeature` (a GraalVM `Feature`) forces `RT.<clinit>` to complete in
`beforeAnalysis()` on a single thread before parallel analysis starts. All core
namespaces, fn classes, and deftype classes get initialized sequentially. When
analysis later discovers them, they're already done — no deadlocks.

Earlier approaches modified `PersistentTreeMap`, `MultiFn`, `Compiler`, and
`__init` class generation to break circular deps. All reverted — the Feature
makes them unnecessary since Java's reentrant class init allows same-thread
access to partially-initialized classes.

### Preserve packages

Packages preserved via `-H:Preserve=package=X` in `build_native.clj`, based on
babashka's `impl/classes.clj`:

- `clojure.lang` — `creator` static field (functional interface support)
- `java.lang`, `java.lang.invoke`, `java.lang.ref`, `java.lang.reflect`,
  `java.lang.runtime` — `SwitchBootstraps` (Java 21+ pattern matching switch)
- `java.io`, `java.math`, `java.net`, `java.net.http`
- `java.nio`, `java.nio.channels`, `java.nio.charset`, `java.nio.file`,
  `java.nio.file.attribute`
- `java.security`, `java.security.cert`, `java.security.spec`
- `java.sql`
- `java.text` — `SimpleDateFormat` (tools.reader etc.)
- `java.time`, `java.time.chrono`, `java.time.format`, `java.time.temporal`,
  `java.time.zone`
- `java.util.*`
- `javax.crypto`, `javax.crypto.spec`, `javax.net.ssl`
- `javax.xml.*`

### URL protocols

`--enable-url-protocols=http,https,jar,unix` — `jar:` is needed for
`JarClassLoader.getResource()` to construct `jar:file:...!/...` URLs.

### JarClassLoader

Custom classloader extending `DynamicClassLoader` for native images.
`URLClassLoader.findResource()` doesn't work in Crema, so this reads JARs
via `java.util.jar.JarFile` directly.

- Indexes all JAR entries at construction for O(1) lookup
- Supports both JAR files and directories on classpath
- Falls back to parent classloader

### Build-time namespace loading

Libraries that transitively depend on standard library namespaces (e.g.
`data.json` → `pprint` → `clojure.walk`) would fail because core fns like
`use` aren't reachable by native-image analysis.

All standard namespaces are required at build time in `src/cream/main.clj`,
so runtime `require` calls for them are no-ops.

### `clojure.reflect.java__init` ordering

`clojure.reflect.clj` loads `reflect/java` via `(load "reflect/java")` from
source, so `clojure.reflect.java__init` is never class-initialized during
normal loading. When native-image discovers it later, it fails because the
`TypeReference` protocol isn't visible yet.

`ClojureFeature.beforeAnalysis()` forces the right order: `RT` → `cream.main__init`
(loads all standard namespaces including `clojure.reflect`) → `clojure.reflect.java__init`.

### Reflection config

Generated by `bb bb/gen_reflect_config.clj` — ~470 classes based on babashka's
`impl/classes.clj`. All entries have `allPublicMethods`, `allPublicConstructors`,
`allPublicFields` enabled.

## Known issues

1. Crema method handle bug (seen with stock Clojure 1.12.3):
   `ClassCastException: Integer cannot be cast to Boolean` in
   `MethodHandleUtils.intUnbox` when `Reflector.canAccess()` calls
   `Method.canAccess(Object)` through a method handle.

2. `getRawAnnotations` not implemented — throws `UnsupportedOperationException`
   for runtime-loaded classes. Affects Clojure 1.13's `@FunctionalInterface`
   detection. The fork catches this in `Compiler$FISupport.maybeFIMethod()`.

3. Enum support broken ([oracle/graal#13034](https://github.com/oracle/graal/issues/13034)):
   `enum.values()` and `EnumMap` crash with NPE in
   `InterpreterResolvedObjectType.getDeclaredMethodsList()`. Blocks http-kit,
   cheshire, clj-yaml.

4. `Class.forName` not dispatchable ([oracle/graal#13031](https://github.com/oracle/graal/issues/13031)):
   see below.

5. `SwitchBootstraps.typeSwitch` not dispatchable — same pattern as
   `Class.forName`: the bootstrap method for Java 21+ pattern matching switch
   expressions is substituted/inlined and not compiled as a standalone entry
   point. Affects Java libraries using `switch` with pattern matching.

6. `JAVA_HOME` may be needed — Crema loads some classes at runtime from the
   JDK's `lib/modules`. Pure Clojure works without it.

## Class.forName and GraalVM substitutions

`Class.forName` is internally substituted by GraalVM native-image. The
substitution is inlined at each call site — the original method is never
compiled as a standalone entry point. When Crema encounters
`invokestatic java.lang.Class.forName(String)` in runtime bytecode, there's
no compiled code to dispatch to.

What doesn't help:
- Adding `Class.forName` calls in application code (inlined away)
- Adding it to `reflect-config.json` (reflection != method compilation)
- Custom `@Substitute` (conflicts with GraalVM's internal one)

What works: `RT.classForName(String)` — a non-substituted method that internally
calls the 3-arg `Class.forName` (inlined at compile time).

The Clojure fork redirects `(Class/forName ...)` interop to emit
`invokestatic RT.classForName`. Fixes all Clojure-emitted code. Java `.class`
files calling `Class.forName` directly still fail.
