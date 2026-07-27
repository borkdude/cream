# Maven project

A `pom.xml` in the current directory is picked up automatically. Its
dependencies and source paths go on the classpath, no `deps.edn` needed.

`src/main/java/com/example/Hello.java` uses `commons-codec`, declared in the
pom:

```java
package com.example;

import org.apache.commons.codec.digest.DigestUtils;

public class Hello {
    public static void main(String[] args) {
        String input = args.length > 0 ? args[0] : "hello world";
        System.out.println(input + " -> " + DigestUtils.sha256Hex(input));
    }
}
```

```sh
$ cream src/main/java/com/example/Hello.java
hello world -> b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9

$ cream src/main/java/com/example/Hello.java cream
cream -> f3a22c1ce8e0a5a96393703806e4ad0b63031f0782c1b2d87dd175fc46a9d63d
```

The same dependencies are there from Clojure:

```sh
$ cream -M -e '(println (org.apache.commons.codec.digest.DigestUtils/sha256Hex "hello world"))'
b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9
```

Running a `.java` file needs a JDK for `javac`. A `deps.edn` takes precedence
over `pom.xml` when both are present.
