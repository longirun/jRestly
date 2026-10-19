package ru.jrestly.benchmark.feed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.infra.Blackhole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Verifies that the {@link FeedBenchmark} JMH harness properly executes all 13
 * benchmark arms across small, medium, and large sizes in a unit environment, and that
 * every arm yields the expected deterministic digest.
 */
@DisplayName("FeedBenchmark execution and digest parity")
class FeedBenchmarkTest {

    @Test
    @DisplayName("FeedBenchmark executes all 13 arms and yields identical digest on rss-20, rss-200, and rss-1000")
    void testBenchmarkExecutionAndParity() throws Exception {
        FeedBenchmark benchmark = new FeedBenchmark();
        benchmark.setup();
        try {
            FeedBenchmark.CpuProbe cpu = new FeedBenchmark.CpuProbe();
            Blackhole bh = null;

            for (String kind : new String[]{"rss-20", "rss-200", "rss-1000"}) {
                benchmark.feedKind = kind;

                long jrestlyBuf = benchmark.jrestly_buffered(bh, cpu);
                long jrestlyStream = benchmark.jrestly_streaming(bh, cpu);
                long jrestlyOkHttpBuf = benchmark.jrestlyOkHttp_buffered(bh, cpu);
                long jrestlyOkHttpStream = benchmark.jrestlyOkHttp_streaming(bh, cpu);
                long jrestlyApacheBuf = benchmark.jrestlyApache5_buffered(bh, cpu);
                long jrestlyApacheStream = benchmark.jrestlyApache5_streaming(bh, cpu);
                long jrestlyNettyBuf = benchmark.jrestlyNetty_buffered(bh, cpu);
                long jrestlyNettyStream = benchmark.jrestlyNetty_streaming(bh, cpu);
                long okHttp = benchmark.okHttp(bh, cpu);
                long retrofit = benchmark.retrofit(bh, cpu);
                long feign = benchmark.feign(bh, cpu);
                long spring = benchmark.springRestClient(bh, cpu);
                long rawJdk = benchmark.rawHttpClient(bh, cpu);

                assertNotEquals(0L, jrestlyBuf);
                assertEquals(jrestlyBuf, jrestlyStream, "jRestly stream parity: " + kind);
                assertEquals(jrestlyBuf, jrestlyOkHttpBuf, "jRestly OkHttp buffered parity: " + kind);
                assertEquals(jrestlyBuf, jrestlyOkHttpStream, "jRestly OkHttp stream parity: " + kind);
                assertEquals(jrestlyBuf, jrestlyApacheBuf, "jRestly Apache5 buffered parity: " + kind);
                assertEquals(jrestlyBuf, jrestlyApacheStream, "jRestly Apache5 stream parity: " + kind);
                assertEquals(jrestlyBuf, jrestlyNettyBuf, "jRestly Netty buffered parity: " + kind);
                assertEquals(jrestlyBuf, jrestlyNettyStream, "jRestly Netty stream parity: " + kind);
                assertEquals(jrestlyBuf, okHttp, "raw OkHttp parity: " + kind);
                assertEquals(jrestlyBuf, retrofit, "Retrofit parity: " + kind);
                assertEquals(jrestlyBuf, feign, "Feign parity: " + kind);
                assertEquals(jrestlyBuf, spring, "Spring parity: " + kind);
                assertEquals(jrestlyBuf, rawJdk, "raw JDK parity: " + kind);
            }
        } finally {
            benchmark.tearDown();
        }
    }
}
