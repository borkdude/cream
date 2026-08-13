# libpython-clj on cream

[libpython-clj](https://github.com/clj-python/libpython-clj) (via
[dtype-next](https://github.com/cnuernber/dtype-next)) runs on cream, calling
into a real CPython interpreter -- imports, function calls, exceptions,
user-defined Python functions/classes, and numpy arrays all round-trip
correctly. Two things are required beyond a normal `require` + `initialize!`:

1. **Force the Panama (`:jdk-21`) FFI backend.** dtype-next tries its JNA
   backend first by default. JNA's native-method linkage does not work under
   cream's `-H:+RuntimeClassLoading` today -- a GraalVM/Crema classloader-
   scoping bug, not something cream's build can work around (see
   `known-issues.md`). Panama does not hit this bug.
2. **Pass `:no-io-redirect? true` to `initialize!`.** libpython-clj's stdout/
   stderr capture feature hits a separate Crema class-resolution issue (also
   in `known-issues.md`). Python's own `print()` still writes to the real
   process stdout/stderr either way -- this only disables *capturing* that
   output into a Clojure `Writer`.

```clojure
(require '[tech.v3.datatype.ffi :as dtype-ffi])

(defn ensure-working-ffi-impl!
  "Tries dtype-next's default backend order (:jna first). JNA's native
   linkage fails under cream today (see known-issues.md); this falls back
   to :jdk-21 (Panama) when that happens. The day upstream fixes the
   underlying GraalVM/Crema bug, the try branch succeeds and this function
   never touches the fallback -- no code change needed here."
  []
  (try
    (dtype-ffi/set-ffi-impl! :jna)
    (catch Throwable _e
      (dtype-ffi/set-ffi-impl! :jdk-21))))

(ensure-working-ffi-impl!)

(require '[libpython-clj2.python :as py])
(py/initialize! :library-path "/path/to/libpython3.11.dylib"
                :no-io-redirect? true)

(let [sys (py/import-module "sys")]
  (println (py/->jvm (py/py.- sys version))))
```

Run it:

```sh
cream -Scp "$(clojure -Spath)" -M -e '<the code above>'
```

## What is verified working

- Module import (`sys`, `builtins`, `os`, `numpy`)
- Function calls with 0-3+ arguments, including `numpy.array`/`sum`/`mean`/
  `multiply`
- Unicode strings round-trip correctly (validates the UTF-32 charset fix --
  tested with accented Latin, CJK, and emoji)
- User-defined Python functions and classes (including mutable instance
  state across calls)
- Nested dict/list/tuple structures converting to/from Clojure data
- Exception propagation (Python exceptions surface as catchable Clojure
  exceptions with the original message intact)
- Native `print()` output to real stdout

## Known limitation: `numpy` import is slow

`import numpy` takes roughly a minute under cream's Crema interpreter --
correct, not hung, but dramatically slower than on the JVM (numpy's import
graph is unusually large, and this compounds with Crema's runtime
compilation overhead; see cream's own README for the general namespace-
loading cost comparison). Once imported, array operations run at normal
speed. If you need `numpy` on cream, budget for this at startup.

## Building the binary

Add the `bb.edn` `gen-ffm-shapes` task's output before building -- see
`bb/gen_ffm_shapes.clj` for what it generates and why. The standard
`bb build-native` picks up the regenerated `reachability-metadata.json`
automatically; no other steps are needed.
