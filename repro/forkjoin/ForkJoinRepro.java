import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Crema segfault: interpreter crashes under concurrent ForkJoinPool dispatch
 * with virtual threads parking and resuming.
 *
 * Expected: prints "Done: 1000"
 * Actual: segfault in Crema interpreter on ForkJoinPool worker
 */
public class ForkJoinRepro {
    public static void main(String[] args) throws Exception {
        int n = 1000;
        var queues = new SynchronousQueue[n];
        for (int i = 0; i < n; i++) {
            queues[i] = new SynchronousQueue<Integer>();
        }

        var done = new CountDownLatch(n);
        var sum = new AtomicInteger(0);

        // Start n producer virtual threads
        for (int i = 0; i < n; i++) {
            final int val = i;
            final SynchronousQueue<Integer> q = queues[i];
            Thread.startVirtualThread(() -> {
                try {
                    q.put(val);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        // Start n consumer virtual threads
        for (int i = 0; i < n; i++) {
            final SynchronousQueue<Integer> q = queues[i];
            Thread.startVirtualThread(() -> {
                try {
                    sum.addAndGet(q.take());
                    done.countDown();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        done.await(10, TimeUnit.SECONDS);
        System.out.println("Done: " + sum.get());
    }
}
