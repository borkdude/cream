# Crema: Segfault with many virtual threads on ForkJoinPool

When many virtual threads park and resume on ForkJoinPool carrier threads, the
Crema interpreter segfaults. The PC jumps to a heap data address instead of
code.

This is the underlying cause of core.async `go` block crashes — go blocks are
state machines that park/resume on ForkJoinPool workers.

## Reproduce

Requires [Cream](https://github.com/borkdude/cream) (native binary using Crema).

```sh
# Segfault: 1000 virtual thread pairs communicating via SynchronousQueue
./cream ForkJoinRepro.java

# Works on JVM:
javac ForkJoinRepro.java && java ForkJoinRepro
# prints: Done: 499500
```

The crash is flaky — it depends on thread scheduling. 1000 pairs triggers it
reliably; lower counts (50-100) crash intermittently.

## Version

Oracle GraalVM 25.1.0-dev+10.1 (still reproduces on ea21, jvmci-25.1-b17)
