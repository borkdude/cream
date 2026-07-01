import java.util.concurrent.CountDownLatch;

/**
 * Pure Java repro of the Crema virtual-thread segfault behind the core.async
 * crashes.
 *
 * Ingredients:
 * - a runtime-loaded class overrides an Object-returning method of an AOT
 *   class (toString), called from AOT code (String.valueOf)
 * - the interpreted override parks the virtual thread on a latch and resumes
 * - pairs of virtual threads rendezvous, several cycles per thread
 *
 * Crash sites vary per run (memory corruption): TLAB allocation
 * (HeapAllocation.attemptAllocationInNewChunk), InterpreterFrameUtil.putKind,
 * InterpreterToVM.releaseInterpreterFrameLocks, wild PC into heap data from
 * monitor code. Usually crashes within the first rounds.
 *
 * Variants that do NOT crash: same rendezvous with plain Runnable lambda
 * bodies (interface dispatch only), or with a primitive-returning override
 * (InputStream.read). The AOT-to-interpreted virtual dispatch with reference
 * return around the park appears essential.
 *
 * Expected: prints "Done"
 * Actual: segfault
 */
public class PureJavaRepro {
    static class Parker {
        final CountDownLatch p, q;
        final boolean first;
        Parker(CountDownLatch p, CountDownLatch q, boolean first) {
            this.p = p; this.q = q; this.first = first;
        }
        @Override
        public String toString() {
            try {
                if (first) { q.countDown(); p.await(); }
                else { q.await(); p.countDown(); }
            } catch (InterruptedException e) { throw new RuntimeException(e); }
            return "x";
        }
    }

    public static void main(String[] args) throws Exception {
        int rounds = 500;
        int pairs = 400;
        int cycles = 8;
        for (int r = 0; r < rounds; r++) {
            var threads = new Thread[pairs * 2];
            for (int i = 0; i < pairs; i++) {
                var s1 = new Parker[cycles];
                var s2 = new Parker[cycles];
                for (int k = 0; k < cycles; k++) {
                    var p = new CountDownLatch(1);
                    var q = new CountDownLatch(1);
                    s1[k] = new Parker(p, q, true);
                    s2[k] = new Parker(p, q, false);
                }
                threads[i * 2] = Thread.startVirtualThread(() -> {
                    for (Parker s : s1) String.valueOf(s);
                });
                threads[i * 2 + 1] = Thread.startVirtualThread(() -> {
                    for (Parker s : s2) String.valueOf(s);
                });
            }
            for (Thread t : threads) t.join();
            if (r % 100 == 0) System.out.println("round " + r);
        }
        System.out.println("Done");
    }
}
