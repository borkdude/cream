# Crema: NullPointerException verifying a class/interface merge

Upstream: [GH-14075](https://github.com/oracle/graal/issues/14075)

Verifying a runtime-loaded class crashes when a control-flow join merges a
class type with an interface type and the class file has no `StackMapTable`:

```
java.lang.NullPointerException
  at com.oracle.svm.interpreter.metadata.InterpreterResolvedObjectType.findLeastCommonAncestor(InterpreterResolvedObjectType.java:670)
  at com.oracle.svm.espresso.shared.verifier.ReferenceOperand.mergeWith(Operand.java:334)
  at com.oracle.svm.espresso.shared.verifier.MethodVerifier.mergeFrames(MethodVerifier.java:2434)
  at com.oracle.svm.interpreter.CremaVerifier.verifyClass(CremaVerifier.java:51)
```

Works on JVM, crashes in native image.

## Reproduce

Requires [Cream](https://github.com/borkdude/cream) (native binary that runs
JVM Clojure and Java files using Crema/RuntimeClassLoading).

```sh
./cream VerifierRepro.java
```

Expected, and what a JVM prints:

```
m(true) = {}
m(false) = []
```

Actual: the NullPointerException above.

## What the repro does

`VerifierRepro` defines one class at runtime, `OldMerge`, of major version 49,
so it carries no `StackMapTable` and the verifier infers frames instead of
reading them. Its single method is:

```java
public static Object m(boolean b) {
    Object o;
    if (b) { o = new java.util.HashMap(); }
    else   { o = java.util.Collections.emptyList(); }
    return o;
}
```

Verifying the join after the `if` merges `java/util/HashMap` with the
interface `java/util/List`.

## Cause

`InterpreterResolvedObjectType.findLeastCommonAncestor` walks both hierarchies
in lockstep:

```java
while (true) {
    if (t1.isAssignableFrom(t2)) return t1;   // line 670
    if (t2.isAssignableFrom(t1)) return t2;
    t1 = t1.getSuperclass();
    t2 = t2.getSuperclass();
}
```

An interface has no superclass, so `t1` becomes null and the next iteration
dereferences it. The method opens with `assert !isInterface()` on both
operands, but assertions are disabled in a production image, so the interface
operand arrives silently and the assertion never fires.

Order matters: merging class then interface crashes, interface then class
returns normally.

## Impact

Any pre-`StackMapTable` class file with such a merge. Found via
`org.apache.commons.logging.LogFactory.getFactory` (commons-logging 1.2 and
1.1.3 are major version 46), which merges `java.lang.SecurityException` with
`java.util.Enumeration`. That reaches cream through the Maven resolver, so
`clojure -X:deps` and `-T:build` style commands crash. commons-logging 1.3.6
is compiled newer and is unaffected.
