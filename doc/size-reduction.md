# Binary Size Reduction

Reduced native image from 300MB to 183MB (39% reduction). All 11 library tests pass.

## Results

| Stage | Size | Reduction |
|---|---|---|
| Original | 300MB | — |
| Round 1 (`-O1` + initial pruning) | 244MB | 19% |
| Round 2 (narrow wildcards via `-verbose:class`) | 203MB | 32% |
| Round 3 (minimal Preserve set via brute-force) | 183MB | 39% |

## Methodology

1. Used `-verbose:class` on JVM to trace which classes are loaded during tests,
   pruned Preserve packages never touched.
2. Ran `bb/test_preserve.clj` to brute-force test each remaining package individually
   (remove one, build, run tests, log result).
3. Confirmed the minimal 5-package set works when all 22 removable packages are
   dropped simultaneously.

## Brute-force results (bb/test_preserve.clj)

Tested removing each of 27 Preserve packages individually from the build.

### REQUIRED (tests fail without these — the final 5):

| Package |
|---|
| `clojure.lang` |
| `java.lang.invoke` |
| `java.lang.reflect` |
| `java.util` |
| `java.util.concurrent` |

### REMOVABLE (tests pass without these):

`java.io`, `java.lang`, `java.lang.ref`, `java.net`,
`java.nio`, `java.nio.channels`, `java.nio.charset`,
`java.nio.file`, `java.nio.file.attribute`,
`java.security`, `java.security.cert`,
`java.time`, `java.time.format`, `java.time.temporal`,
`java.util.concurrent.atomic`, `java.util.concurrent.locks`,
`java.util.function`, `java.util.jar`, `java.util.regex`,
`java.util.stream`, `java.util.zip`, `javax.xml.parsers`

## All changes applied

### native-image flags (`bb/build_native.clj`)

- Added `-O1` optimization level
- Removed `--enable-all-security-services`
- Removed duplicate `-H:ConfigurationFileDirectories=.`
- Removed `--add-modules` entries: `java.sql`, `java.net.http`
- Reduced Preserve packages from 30 to 5

### Build-time clojure requires (`src/cream/main.clj`)

Removed 3 namespaces (still available at runtime via `require`):
`clojure.java.browse`, `clojure.java.javadoc`, `clojure.template`

Note: `clojure.reflect` must stay — needed at build time.

## Future ideas

- Remove `--verbose` for faster builds (no size impact)
- Try `-Os` (optimize for size) instead of `-O1`
- Profile-Guided Optimizations (`--pgo`) for throughput (not size)
- Re-run `bb/test_preserve.clj` when adding new libraries
