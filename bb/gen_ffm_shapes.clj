;; Generates the `foreign.downcalls` / `foreign.upcalls` sections of
;; reachability-metadata.json and merges them into the hand-curated
;; reachability-metadata.base.json, writing the final reachability-metadata.json
;; that native-image actually reads.
;;
;; Why this exists: GraalVM's Foreign Function & Memory API support requires
;; every downcall/upcall shape (return type + parameter types) to be registered
;; at build time. The native-image-agent can capture these automatically when
;; run against ordinary (non-Crema) code -- confirmed working against a
;; hand-written test -- but does not capture calls made through dtype-next's
;; `insn`-generated, runtime-defined FFI wrapper classes. Rather than register
;; per-C-function shapes (unbounded, and undiscoverable without a rebuild per
;; missing shape), this registers the full combinatorial space of the small
;; type alphabet (pointer/int/long/float/double) that dominates C APIs like
;; CPython's -- a few thousand cheap, static, additive entries that make any
;; call using a "boring" shape work without further build changes.
;;
;; Usage: bb bb/gen_ffm_shapes.clj
;; Regenerate whenever reachability-metadata.base.json changes.

(require '[cheshire.core :as json])

(def types ["void*" "long" "int" "double" "float"])
(def max-args 4)

(defn cartesian-product [n coll]
  (if (zero? n)
    [[]]
    (for [x coll
          rest-combo (cartesian-product (dec n) coll)]
      (vec (cons x rest-combo)))))

(defn all-shapes []
  (distinct
   (for [nargs (range 0 (inc max-args))
         combo (cartesian-product nargs types)
         ret (conj types "void")]
     {"returnType" ret "parameterTypes" combo})))

(defn -main []
  (let [base   (json/parse-string (slurp "reachability-metadata.base.json"))
        shapes (all-shapes)
        merged (assoc base "foreign" {"downcalls" shapes "upcalls" shapes})]
    (spit "reachability-metadata.json" (json/generate-string merged {:pretty true}))
    (println "Generated" (count shapes) "downcall/upcall shapes ->" "reachability-metadata.json")))

(-main)
