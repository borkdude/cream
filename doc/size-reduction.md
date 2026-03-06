# Binary Size Reduction

Reduced native image from 300MB to 203MB (32% reduction). All 11 library tests pass.

## Methodology

1. Used `-verbose:class` on JVM to trace which classes are loaded during tests,
   pruned Preserve packages never touched.
2. Ran `bb/test_preserve.clj` to brute-force test each remaining package individually
   (remove one, build, run tests, log result).

## Brute-force results (bb/test_preserve.clj)

Phase 1 minimal build (only clojure.lang + java.lang + java.io): TESTS FAILED.

Phase 2 — remove each package individually from the 27-package set:

### REQUIRED (tests fail without these):

| Package | Failure |
|---|---|
| `clojure.lang` | test-fail |
| `java.lang.invoke` | test-fail |
| `java.lang.reflect` | test-fail |
| `java.util` | test-fail |
| `java.util.concurrent` | test-fail |

### REMOVABLE (tests still pass without these):

| Package | Binary size without it |
|---|---|
| `java.io` | 200.9MB |
| `java.lang` | 197.1MB |
| `java.lang.ref` | 202.2MB |
| `java.net` | 202.5MB |
| `java.nio` | 201.2MB |
| `java.nio.channels` | 201.4MB |
| `java.nio.charset` | 202.6MB |
| `java.nio.file` | 205.6MB |
| `java.nio.file.attribute` | 202.2MB |
| `java.security` | 200.8MB |
| `java.security.cert` | 202.5MB |
| `java.time` | 202.2MB |
| `java.time.format` | 202.1MB |
| `java.time.temporal` | 202.2MB |
| `java.util.concurrent.atomic` | 202.0MB |
| `java.util.concurrent.locks` | 202.1MB |
| `java.util.function` | 202.2MB |
| `java.util.jar` | 202.2MB |
| `java.util.regex` | 202.1MB |
| `java.util.stream` | 199.4MB |
| `java.util.zip` | 202.1MB |
| `javax.xml.parsers` | 200.8MB |

Note: each package was tested individually. Removing all 22 at once may fail
due to combined dependencies — needs a separate test.

## Changes applied so far (203MB baseline)

### native-image flags (`bb/build_native.clj`)

- Added `-O1` optimization level
- Removed `--enable-all-security-services`
- Removed duplicate `-H:ConfigurationFileDirectories=.`
- Removed `--add-modules` entries: `java.sql`, `java.net.http`
- Removed `-H:Preserve=package=` entries:
  `java.sql`, `java.net.http`, `java.time.chrono`, `java.time.zone`,
  `javax.crypto`, `javax.crypto.spec`, `java.text`, `java.math`,
  `java.lang.runtime`, `java.security.spec`, `javax.net.ssl`
- Narrowed `java.util.*` to 9 specific sub-packages
- Narrowed `javax.xml.*` to `javax.xml.parsers`

### Build-time clojure requires (`src/cream/main.clj`)

Removed 3 namespaces (still available at runtime via `require`):
`clojure.java.browse`, `clojure.java.javadoc`, `clojure.template`

Note: `clojure.reflect` must stay — needed at build time.

## Future ideas

- Remove `--verbose` for faster builds (no size impact)
- Try `-Os` (optimize for size) instead of `-O1`
- Profile-Guided Optimizations (`--pgo`) for throughput (not size)
- Re-run analysis when adding new libraries
