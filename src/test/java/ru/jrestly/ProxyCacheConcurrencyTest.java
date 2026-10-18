package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Concurrency guard for the {@code AbstractHttpClient} proxy cache: two threads
 * calling {@code get(Api.class)} at once create the proxy exactly once and receive the
 * same instance. Deterministic barrier; no network.
 */
@DisplayName("AbstractHttpClient proxy cache is safe under concurrent get()")
class ProxyCacheConcurrencyTest {

    interface TestApi {
        @Get("/ping")
        String ping();
    }

    /**
     * Widens the get()/insert race window deterministically: the first thread
     * that reaches the mapping function parks on a two-party barrier, so if the
     * cache lets a second thread run the mapping function too, both enter before
     * either inserts. With a single-computation cache the barrier simply times
     * out and the wait is bounded.
     */
    private static final class BarrierClient extends AbstractHttpClient {
        final AtomicInteger creations = new AtomicInteger();
        final CyclicBarrier barrier = new CyclicBarrier(2);

        BarrierClient(ModuleInfo moduleInfo) {
            super(moduleInfo);
        }

        @Override
        protected Proxy createController(Class<?> classInterface) {
            creations.incrementAndGet();
            try {
                barrier.await(500, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // A single-computation cache never lets the second party arrive.
            }
            return super.createController(classInterface);
        }
    }

    @Test
    @DisplayName("two concurrent get() calls for the same interface create the proxy exactly once and return the same instance")
    void concurrentGetCreatesProxyExactlyOnce() throws Exception {
        ModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("https://example.com")
                .build();
        BarrierClient client = new BarrierClient(moduleInfo);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        try {
            Callable<Object> task = () -> {
                start.await();
                return client.get(TestApi.class);
            };
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(task));
            }
            start.countDown();

            Object first = futures.get(0).get();
            Object second = futures.get(1).get();

            assertEquals(1, client.creations.get(),
                    "the controller proxy must be created exactly once under concurrent get()");
            assertSame(first, second,
                    "concurrent get() for the same interface must return the cached proxy instance");
        } finally {
            pool.shutdownNow();
            client.close();
        }
    }
}
