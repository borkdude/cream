# Crema: segfault in releaseInterpreterFrameLocks under core.async on virtual threads

The Crema interpreter segfaults when core.async runs channel operations on
virtual threads. A virtual thread parks and resumes inside interpreted Clojure
frames, and on frame exit the interpreter reads corrupted frame lock metadata.

The faulting frame (`clojure.core.async.impl.dispatch$in_vthread_QMARK_`) holds
no monitor, so the crash is corrupted lock-count metadata, not an actual lock
release.

## Version

Oracle GraalVM 25.1.3+9.1 (build 25.0.3+9-LTS-jvmci-25.1-b19), native image,
`-H:+RuntimeClassLoading`.

Related: the older `repro/forkjoin` (virtual threads on ForkJoinPool) segfault
is fixed in this build. This is a different crash.

## Crash signature

```
PC  com.oracle.svm.interpreter.InterpreterToVM.releaseInterpreterFrameLocks(InterpreterToVM.java)
    com.oracle.svm.interpreter.Interpreter.execute0(Interpreter.java:485)
```

Failing thread is a `java.lang.VirtualThread` on a ForkJoinPool carrier. Top
interpreted frames:

```
clojure.core.async.impl.dispatch$in_vthread_QMARK_.invoke(dispatch.clj:107)
clojure.core.async$_GT__BANG_.invokeStatic(async.clj:250)          ; >!
clojure.core.async$onto_chan_BANG_$fn__1089.invoke(async.clj:773)
```

Full dump: [crash_dump.txt](crash_dump.txt).

## Reproduce

Requires [Cream](https://github.com/borkdude/cream) (native binary using Crema)
with core.async on the classpath.

```sh
cream -Scp "$(clojure -Spath -A:lib-tests)" -M mini_repro.clj
```

Segfaults within a few rounds. On the JVM it prints `DONE`.

## Notes

- Not reproduced in pure Java: virtual threads parking inside synchronized
  blocks, SynchronousQueue handoff, exception unwind through monitors, deep
  nested monitors, and single-carrier deep park all run clean. The crash
  appears specific to deep runtime-loaded and `eval`-generated interpreted
  Clojure frames parking on virtual threads.
- `in-vthread?` calls a fn built at runtime via `(eval '(fn [t] (.isVirtual t)))`
  (dispatch.clj), so the crashing call chain includes runtime-`eval`'d classes.
