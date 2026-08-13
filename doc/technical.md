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

Preserved via `-H:Preserve=` in `build_native.clj`. A package missing here
shows up as `Fatal error: Unable to call AOT method` naming the method and the
package to add.

- `clojure.lang` — `creator` static field (functional interface support)
- `java.lang`, `java.lang.invoke`, `java.lang.reflect` — `java.lang` covers the
  `StringConcat` classes that `invokedynamic` string concatenation generates
- `java.io` — `FilterInputStream`, reading a subprocess's streams
- `java.net`, `javax.net`, `javax.net.ssl` — http-kit, including HTTPS
- `java.nio`, `java.nio.channels`, `java.nio.channels.spi`, `java.nio.charset`,
  `java.nio.file`
- `java.util`, `java.util.concurrent`, `java.util.concurrent.atomic`,
  `java.util.concurrent.locks`, `java.util.function`, `java.util.jar`,
  `java.util.regex`, `java.util.stream`, `java.util.zip` — `java.util.stream`
  for the Closure compiler's `Collector.of`
- `java.security`, `java.text`, `java.time`
- module `java.logging`, module `java.sql` — `java.sql` for `cljs.closure`.
  `--add-modules=java.sql` does nothing on its own, `Preserve=module` is what
  includes the classes
- module `jdk.unsupported` — `sun.misc.Unsafe`'s `ARRAY_*_BASE_OFFSET` static
  fields, needed by dtype-next (tech.ml.dataset, tablecloth, libpython-clj,
  clerk all depend on it transitively)
- `java.lang.foreign` — Panama FFI method table (`Arena`, `SymbolLookup`,
  `ValueLayout`), needed by dtype-next's `:jdk-21` FFI backend
- `java.lang.annotation` — `RetentionPolicy/RUNTIME`, read by `insn` (dtype-
  next's FFI bytecode generator) at runtime
- `sun.net.www.protocol.http`, `sun.net.www.protocol.https` — these
  `Handler` constructors are AOT-compiled but not reachable-analyzed by
  default, which previously surfaced as a *process abort* (not a catchable
  exception — see Known issue 8) on the first HTTPS/HTTP `java.net.URL`
  connection. Affects `clj-http` and anything else using `java.net.URL`
  directly for HTTPS.
- `com.sun.jna` — same pattern as the rest of this list: the official JNA
  reachability metadata's `typeReached` conditions never fire under Crema's
  dynamic invocation pattern, so class/field/method reachability has to be
  forced unconditionally. Note this makes JNA's *classes* reachable; its
  actual native-method linkage is separately broken (Known issue 9).
- `sun.nio.cs`, plus `-H:+AddAllCharsets` — UTF-32LE/BE and other non-default
  charsets, needed by libpython-clj's Python string marshalling. Preserve
  alone isn't sufficient here; `Charset.forName`'s provider lookup needs the
  broader charset set explicitly enabled.

The `java.util.*` entries beyond `java.util` itself, plus `java.security`,
`java.text` and `java.time`, were added while chasing tools.deps in-process.
That is blocked by [oracle/graal#14075](https://github.com/oracle/graal/issues/14075),
so they are not carrying their weight yet. `bb/test_preserve.clj` removes each
entry in turn to find which are load-bearing.

### FFM downcall/upcall registration

GraalVM's Foreign Function & Memory API requires every downcall/upcall shape
(return type + parameter types) to be registered at build time, in
`reachability-metadata.json`'s `foreign.downcalls`/`foreign.upcalls` arrays.
The `native-image-agent` can capture these automatically for ordinary code
(confirmed against a hand-written test), but not for calls made through
dtype-next's `insn`-generated, runtime-defined FFI wrapper classes — the
agent traced zero `foreign` entries across a real `libpython-clj`
`initialize!` + several distinct calls.

Rather than register shapes per C function (unbounded, and undiscoverable
without a rebuild per missing shape — each missing shape produces a
`MissingForeignRegistrationError` naming exactly what to add, but only one at
a time), `bb/gen_ffm_shapes.clj` registers the full combinatorial space of a
small type alphabet (`void*`, `long`, `int`, `double`, `float`, 0-4 args) that
dominates C APIs like CPython's. `reachability-metadata.base.json` holds the
hand-curated entries (JNA metadata, etc.); `bb gen-ffm-shapes` merges in the
generated shapes and writes the final `reachability-metadata.json`.

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

2. `getRawAnnotations` not implemented for runtime-loaded classes, which threw
   `UnsupportedOperationException` and broke Clojure 1.13's
   `@FunctionalInterface` detection. Fixed upstream. The fork's guard was
   removed on ea.04.

3. Enum support broken ([oracle/graal#13034](https://github.com/oracle/graal/issues/13034)):
   `enum.values()` and `EnumMap` crash with NPE in
   `InterpreterResolvedObjectType.getDeclaredMethodsList()`. Blocks http-kit,
   cheshire, clj-yaml.

4. `Class.forName` not dispatchable ([oracle/graal#13031](https://github.com/oracle/graal/issues/13031)):
   fixed upstream by [oracle/graal#13187](https://github.com/oracle/graal/pull/13187).
   See below.

5. `SwitchBootstraps.typeSwitch` not dispatchable — same pattern as
   `Class.forName`: the bootstrap method for Java 21+ pattern matching switch
   expressions is substituted/inlined and not compiled as a standalone entry
   point. Affects Java libraries using `switch` with pattern matching.

6. Verifier NPE on a class/interface merge ([oracle/graal#14075](https://github.com/oracle/graal/issues/14075)):
   `findLeastCommonAncestor` walks both hierarchies in lockstep, so an
   interface operand, which has no superclass, becomes null and is
   dereferenced on the next iteration. Only class files without a
   `StackMapTable` (major < 50) reach it, since newer ones carry frames the
   verifier reads instead of inferring. commons-logging 1.2 (major 46) hits it
   in `LogFactory.getFactory`, which merges `SecurityException` with
   `Enumeration`, and that is on the Maven resolver's classpath, so `-X:deps`
   and `-T:build` crash. Repro at `repro/verifier-npe/`.

7. `JAVA_HOME` is needed for classes Crema loads at runtime from the JDK's
   `lib/modules`. Clojure code and the tested libraries do not hit this. Such
   classes fail with `ClassNotFoundException` when no jimage is reachable.

   `BootClassRegistry` warns on stdout the first time it consults the boot
   class loader without a jimage, through `LogUtils.warning`, which uses
   `System.out`. Class loading is parent-first, so any runtime-generated class
   triggers it and the warning lands in the middle of program output. `-main`
   forces that lookup at startup with stdout swallowed, which leaves the
   `ClassNotFoundException` as the only diagnostic.

8. JNA's native-method linkage fails under `-H:+RuntimeClassLoading`, even
   with JNA's classes AOT-compiled and reachability metadata correctly
   applied. Isolated with a 7-line repro (import + call one JNA method):
   identical JAR, identical official [Oracle JNA reachability
   metadata](https://github.com/oracle/graalvm-reachability-metadata/tree/master/metadata/net.java.dev.jna/jna),
   only `-H:+RuntimeClassLoading` toggled —

   ```
   UnsatisfiedLinkError: com.sun.jna.Native.getNativeVersion()Ljava/lang/String;
     [symbol: Java_com_sun_jna_Native_getNativeVersion or Java_com_sun_jna_Native_getNativeVersion__]
   ```

   Root cause, confirmed by reading GraalVM's own source
   (`ClassRegistries.java`, `JNINativeLinkage.java`): `-H:+RuntimeClassLoading`
   hard-requires `ClassForNameRespectsClassLoader=true`
   (`CremaFeature.afterRegistration` asserts this — attempting to override it
   with `-H:-ClassForNameRespectsClassLoader` crashes the *builder* with
   `guarantee failed`, not a normal build error). That flag changes native
   method symbol resolution from a global lookup to one scoped to the
   declaring class's classloader. AOT-compiled classes like `com.sun.jna.
   Native` end up in a classloader bucket that `System.load()`'s globally-
   registered native symbols aren't visible to. No cream-side flag or
   metadata fixes this; it needs an upstream GraalVM/Crema fix.

   Caller-side mitigation: try `dtype-ffi/set-ffi-impl! :jna` (dtype-next's
   own default), catch the failure — it surfaces as a normal, catchable
   `CompilerException` wrapping the `UnsatisfiedLinkError` at JNA namespace
   load time, not a fatal abort — and fall back to `:jdk-21` (Panama). See
   `examples/libpython-clj/README.md`'s `ensure-working-ffi-impl!`. The
   fallback becomes a no-op automatically once this is fixed upstream.

9. A dynamically-generated Clojure class reference resolves on HotSpot but
   not under Crema. `libpython-clj`'s `io-redirect!` (Python stdout/stderr
   capture) exercises dtype-next's `insn`-based FFI bytecode generator,
   which emits a raw `invokestatic` to `tech.v3.datatype.ffi.mmodel$ptr_value_q`
   — same identical code, same classpath, completes normally on plain
   HotSpot, fails on cream:

   ```
   Caused by: java.lang.ClassNotFoundException: tech.v3.datatype.ffi.mmodel$ptr_value_q
     at ... UserDefinedClassRegistry.doLoadClass ...
   Unrecoverable uncaught exception encountered. The VM will now exit
   ```

   Unlike issue 8, this is a fatal, non-catchable process abort — wrapping
   the call in `try`/`catch Throwable` does not help, so there is no
   adaptive caller-side workaround. Not yet isolated to a specific repo:
   could be a Crema class-resolution gap (parallel to issue 8's classloader-
   scoping bug) or a dtype-next fragility (`ffi_base.clj`'s `ptr->platform-ptr`
   hardcodes the target namespace string as `"tech.v3.datatype.ffi.mmodel"`
   regardless of which FFI backend is active, relying on HotSpot's lazy
   class-reference resolution). Mitigation: pass `:no-io-redirect? true` to
   `libpython-clj2.python/initialize!` — Python's own `print()` still writes
   to the real process stdout/stderr, this only disables capturing it into a
   Clojure `Writer`. Manual, one-line removal once fixed upstream (in
   whichever repo turns out to own it) — not automatable given the abort.

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

The Clojure fork redirected `(Class/forName ...)` interop to emit
`invokestatic RT.classForName`. That workaround was removed on ea.04, where
`Class.forName` dispatches from runtime-loaded bytecode.
