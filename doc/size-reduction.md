# Binary Size Reduction

Reduced native image from 300MB to 244MB (19% reduction). All 11 library tests pass.

## Changes applied

### native-image flags (`bb/build_native.clj`)

- Added `-O1` optimization level
- Removed `--enable-all-security-services` (HTTPS still works via `--enable-url-protocols=https`)
- Removed duplicate `-H:ConfigurationFileDirectories=.`
- Removed `--add-modules` entries: `java.sql`, `java.net.http`
- Removed `-H:Preserve=package=` entries:
  - `java.sql` — no lib uses JDBC
  - `java.net.http` — no lib uses the HTTP client
  - `java.time.chrono` — exotic calendar systems (Thai, Japanese, etc.)
  - `java.time.zone` — zone rules; `java.time` itself suffices
  - `javax.crypto`, `javax.crypto.spec` — explicit crypto ops only; HTTPS handled elsewhere
  - `java.text` — Clojure code rarely uses `java.text` directly
  - `java.math` — Clojure uses `BigDecimal`/`BigInteger` via `clojure.lang`, not reflection

### Build-time clojure requires (`src/cream/main.clj`)

Removed 3 namespaces from the `:require` block (still available at runtime via `require`):

- `clojure.java.browse` — opens URLs in browser
- `clojure.java.javadoc` — javadoc lookup
- `clojure.template` — macro helper, rarely used directly

Note: `clojure.reflect` must stay — its `clojure.reflect.java` class is needed at build time.

## Future ideas

- Remove `--verbose` for faster builds (no size impact)
- Try `-Os` (optimize for size) instead of `-O1`
- Profile-Guided Optimizations (`--pgo`) for throughput (not size)
- Further trim Preserve packages as Crema matures
