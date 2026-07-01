# Crema: memory corruption with virtual threads parking in interpreted code

The Crema interpreter corrupts memory when runtime-loaded code parks and
resumes virtual threads. The corruption surfaces as segfaults at varying
sites, or occasionally as a hang:

- `InterpreterToVM.releaseInterpreterFrameLocks` reading a garbage locks array
- wild PC into heap data from `EnterpriseMonitorSupport.newMonitorLock` during
  `VirtualThread.afterDone`
- `HeapAllocation.attemptAllocationInNewChunk` / `JavaSpinLockUtils.tryLock`
  in the TLAB slow path from `InterpreterFrameUtil.popArguments`
- `InterpreterFrameUtil.putKind`

This is the cause of the core.async test suite crashes.

## Version

Oracle GraalVM 25.1.3+9.1 and 25i2-25.0.3-ea.01 (both jvmci-25.1-b19), native
image, `-H:+RuntimeClassLoading`. The older `repro/forkjoin` segfault is fixed
on these builds. This is a different bug.

## Reproduce (pure Java)

Requires [Cream](https://github.com/borkdude/cream) (native binary using Crema).

```sh
cream PureJavaRepro.java
```

Segfaults within the first rounds on most runs, occasionally hangs. On the JVM
it prints `Done`.

Ingredients: a runtime-loaded class overrides `toString()` (Object-returning,
called from AOT `String.valueOf`), the interpreted override parks the virtual
thread on a `CountDownLatch`, pairs of virtual threads rendezvous repeatedly.

Variants that do NOT crash:

- same rendezvous with the latch code directly in `Runnable` lambda bodies
  (interface dispatch only, no AOT-to-interpreted virtual call around the park)
- parking inside a primitive-returning override (`InputStream.read()`) driven
  through AOT `InputStream.read(byte[],int,int)`

The AOT-to-interpreted virtual dispatch with reference return surrounding the
park appears essential.

## Reproduce (Clojure, original finding)

```sh
cream -Scp "$(clojure -Spath -A:lib-tests)" -M mini_repro.clj
```

Crashes every run within a few rounds through the same corruption. The
faulting interpreted frame chain is core.async `onto-chan!` -> `>!` parking a
virtual thread. `crash_dump.txt` holds a full dump of this variant. Clojure
functions extend AOT `clojure.lang.AFn`, whose `run()` virtual-dispatches to
the interpreted `invoke()` returning Object, matching the pure Java shape.
