(require '[clojure.core.async :as a])

;; Segfaults in the Crema interpreter (releaseInterpreterFrameLocks) within a
;; few rounds. onto-chan! runs a put loop on a virtual thread, >! parks and
;; resumes the vthread, and on frame exit the interpreter reads corrupted frame
;; lock metadata.

(dotimes [r 500]
  (let [n 500
        cs (repeatedly n a/chan)]
    (doseq [c cs]
      (a/onto-chan! c (range 100)))
    (doseq [c cs]
      (a/go-loop []
        (when (a/<! c)
          (recur))))
    (when (zero? (mod r 50))
      (println "round" r))))

(println "DONE")
(shutdown-agents)
