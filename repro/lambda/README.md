# Crema: LambdaConversionException "is not a subtype of itself"

Runtime-loaded class with a lambda that captures a custom interface type crashes
in Crema with:

```
LambdaConversionException: Type mismatch for dynamic parameter 0:
interface LambdaRepro$Context is not a subtype of interface LambdaRepro$Context
```

Works on JVM, crashes in native image.

## Reproduce

Requires [Cream](https://github.com/borkdude/cream) (native binary that runs
JVM Clojure and Java files using Crema/RuntimeClassLoading).

```sh
./cream LambdaRepro.java
```

Expected: prints `HELLO`.
Actual:

```
Caused by: java.lang.invoke.LambdaConversionException: Type mismatch for
dynamic parameter 0: interface LambdaRepro$Context is not a subtype of
interface LambdaRepro$Context
```

The same file works on JVM:

```sh
javac LambdaRepro.java && java LambdaRepro
# prints: HELLO
```

## What triggers it

`LambdaRepro.java` mimics commonmark-java's `Parser$Builder.getInlineParserFactory()`:

```java
Objects.requireNonNullElseGet(factory, () -> (context) -> new Impl(context).toString());
```

The nested lambda captures `Context` (a custom interface). When Crema's
interpreter resolves the `invokedynamic` for this lambda, `LambdaMetafactory`
sees `Context` from two different classloader contexts — the same class is
treated as two different types.

## Affected libraries

- commonmark-java (`InlineParserContext`)
- flexmark-java (`DataHolder`)
- nextjournal/markdown (wraps commonmark-java)

## Version

Oracle GraalVM 25.1.0-dev EA (ea19)
