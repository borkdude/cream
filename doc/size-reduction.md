# Binary Size Reduction

Reduced native image from 300MB to 203MB (32% reduction). All 11 library tests pass.

## Methodology

Used `-verbose:class` on the JVM to trace which classes are actually loaded during
the full lib test suite, then pruned Preserve packages that were never touched.

```bash
# 1. Swap native binary for a JVM wrapper with -verbose:class
# 2. Run bb run-lib-tests, capture stderr
# 3. Extract loaded packages, diff against Preserve list
# 4. Remove unloaded packages, narrow wildcards to specific sub-packages
```

## Changes applied

### native-image flags (`bb/build_native.clj`)

- Added `-O1` optimization level
- Removed `--enable-all-security-services` (HTTPS still works via `--enable-url-protocols=https`)
- Removed duplicate `-H:ConfigurationFileDirectories=.`
- Removed `--add-modules` entries: `java.sql`, `java.net.http`
- Removed `-H:Preserve=package=` entries:
  - `java.sql` — no lib uses JDBC
  - `java.net.http` — no lib uses the HTTP client
  - `java.time.chrono` — exotic calendar systems
  - `java.time.zone` — zone rules; `java.time` itself suffices
  - `javax.crypto`, `javax.crypto.spec` — explicit crypto only
  - `java.text` — rarely used directly from Clojure
  - `java.math` — Clojure uses BigDecimal/BigInteger via clojure.lang
  - `java.lang.runtime` — not loaded during tests
  - `java.security.spec` — not loaded during tests
  - `javax.net.ssl` — not loaded during tests
- Narrowed `java.util.*` to specific sub-packages actually loaded:
  `java.util`, `java.util.concurrent`, `java.util.concurrent.atomic`,
  `java.util.concurrent.locks`, `java.util.function`, `java.util.jar`,
  `java.util.regex`, `java.util.stream`, `java.util.zip`
- Narrowed `javax.xml.*` to `javax.xml.parsers`

### Build-time clojure requires (`src/cream/main.clj`)

Removed 3 namespaces from the `:require` block (still available at runtime via `require`):

- `clojure.java.browse` — opens URLs in browser
- `clojure.java.javadoc` — javadoc lookup
- `clojure.template` — macro helper, rarely used directly

Note: `clojure.reflect` must stay -- its `clojure.reflect.java` class is needed at build time.

## Future ideas

- Remove `--verbose` for faster builds (no size impact)
- Try `-Os` (optimize for size) instead of `-O1`
- Profile-Guided Optimizations (`--pgo`) for throughput (not size)
- Re-run `-verbose:class` analysis when adding new libraries
