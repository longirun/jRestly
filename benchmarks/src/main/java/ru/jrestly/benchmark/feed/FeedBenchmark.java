package ru.jrestly.benchmark.feed;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import ru.jrestly.benchmark.client.FeignBenchmarkClient;
import ru.jrestly.benchmark.client.JRestlyBenchmarkClient;
import ru.jrestly.benchmark.client.OkHttpBenchmarkClient;
import ru.jrestly.benchmark.client.RawHttpClientBenchmarkClient;
import ru.jrestly.benchmark.client.RetrofitBenchmarkClient;
import ru.jrestly.benchmark.client.SpringRestClientBenchmarkClient;
import ru.jrestly.benchmark.server.BenchmarkHttpServer;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end real-scenario benchmark (ADR-0068 &sect;5): compares jRestly against raw
 * JDK HttpClient, raw OkHttp, Square Retrofit 2, OpenFeign, and Spring 6 HTTP Interface
 * over a realistic RSS/Atom feed fetch-and-parse workload.
 *
 * <p>Key architectural cut: buffered ({@code @Get byte[]}) vs streaming
 * ({@code @Get InputStream}) across the default JDK transport and pluggable engines
 * (OkHttp, Apache HttpClient 5, Netty). The XML parsing phase is strictly identical across
 * all competitors via {@link FeedParser}, isolating the transport and dispatch contribution.</p>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class FeedBenchmark {

    /**
     * In-harness CPU time probe via {@link AuxCounters} (ADR-0068 &sect;5):
     * samples process CPU time at iteration boundaries to report approximate
     * CPU-nanoseconds per operation.
     */
    @AuxCounters(AuxCounters.Type.OPERATIONS)
    @State(Scope.Thread)
    public static class CpuProbe {
        public long processCpuNs;
        private long startNs;

        @Setup(Level.Iteration)
        public void beforeIteration() {
            startNs = readProcessCpuTime();
        }

        @TearDown(Level.Iteration)
        public void afterIteration() {
            long stopNs = readProcessCpuTime();
            if (startNs > 0 && stopNs >= startNs) {
                processCpuNs = stopNs - startNs;
            } else {
                processCpuNs = 0;
            }
        }

        private static long readProcessCpuTime() {
            java.lang.management.OperatingSystemMXBean os =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
                return sunOs.getProcessCpuTime();
            }
            return -1L;
        }
    }

    @Param({"rss-20", "rss-200", "rss-1000"})
    public String feedKind;

    private BenchmarkHttpServer server;
    private JRestlyBenchmarkClient jrestlyClient;
    private JRestlyBenchmarkClient jrestlyOkHttpClient;
    private JRestlyBenchmarkClient jrestlyApache5Client;
    private JRestlyBenchmarkClient jrestlyNettyClient;
    private RawHttpClientBenchmarkClient rawHttpClient;
    private OkHttpBenchmarkClient okHttpClient;
    private RetrofitBenchmarkClient retrofitClient;
    private FeignBenchmarkClient feignClient;
    private SpringRestClientBenchmarkClient springClient;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.WARNING);
        server = BenchmarkHttpServer.start();
        String baseUrl = server.baseUrl();

        jrestlyClient = new JRestlyBenchmarkClient(baseUrl);
        jrestlyOkHttpClient = new JRestlyBenchmarkClient(baseUrl, new ru.jrestly.engine.okhttp.OkHttpEngine());
        jrestlyApache5Client = new JRestlyBenchmarkClient(baseUrl, new ru.jrestly.engine.apache5.Apache5HttpEngine());
        jrestlyNettyClient = new JRestlyBenchmarkClient(baseUrl, new ru.jrestly.engine.netty.NettyHttpEngine());
        rawHttpClient = new RawHttpClientBenchmarkClient(baseUrl);
        okHttpClient = new OkHttpBenchmarkClient(baseUrl);
        retrofitClient = new RetrofitBenchmarkClient(baseUrl);
        feignClient = new FeignBenchmarkClient(baseUrl);
        springClient = new SpringRestClientBenchmarkClient(baseUrl);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (jrestlyClient != null) {
            jrestlyClient.close();
        }
        if (jrestlyOkHttpClient != null) {
            jrestlyOkHttpClient.close();
        }
        if (jrestlyApache5Client != null) {
            jrestlyApache5Client.close();
        }
        if (jrestlyNettyClient != null) {
            jrestlyNettyClient.close();
        }
        if (rawHttpClient != null) {
            rawHttpClient.close();
        }
        if (okHttpClient != null) {
            okHttpClient.close();
        }
        if (retrofitClient != null) {
            retrofitClient.close();
        }
        if (feignClient != null) {
            feignClient.close();
        }
        if (springClient != null) {
            springClient.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    // --- jRestly JDK Transport (Default) ---

    @Benchmark
    public long jrestly_buffered(Blackhole bh, CpuProbe cpu) throws Exception {
        long digest = FeedSummary.of(jrestlyClient.feedBuffered(feedKind)).digest();
        if (bh != null) {
            bh.consume(digest);
        }
        return digest;
    }

    @Benchmark
    public long jrestly_streaming(Blackhole bh, CpuProbe cpu) throws Exception {
        long digest = FeedSummary.of(jrestlyClient.feedStreaming(feedKind)).digest();
        if (bh != null) {
            bh.consume(digest);
        }
        return digest;
    }

    // --- jRestly Pluggable Engine: OkHttp ---

    @Benchmark
    public long jrestlyOkHttp_buffered(Blackhole bh, CpuProbe cpu) throws Exception {
        long digest = FeedSummary.of(jrestlyOkHttpClient.feedBuffered(feedKind)).digest();
        if (bh != null) {
            bh.consume(digest);
        }
        return digest;
    }

    @Benchmark
    public long jrestlyOkHttp_streaming(Blackhole bh, CpuProbe cpu) throws Exception {
        long digest = FeedSummary.of(jrestlyOkHttpClient.feedStreaming(feedKind)).digest();
        if (bh != null) {
            bh.consume(digest);
        }
        return digest;
    }

    // --- jRestly Pluggable Engine: Apache HttpClient 5 ---

    @Benchmark
    public long jrestlyApache5_buffered(Blackhole bh, CpuProbe cpu) throws Exception {
        long digest = FeedSummary.of(jrestlyApache5Client.feedBuffered(feedKind)).digest();
        if (bh != null) {
            bh.consume(digest);
        }
        return digest;
    }

    @Benchmark
    public long jrestlyApache5_streaming(Blackhole bh, CpuProbe cpu) throws Exception {
        long digest = FeedSummary.of(jrestlyApache5Client.feedStreaming(feedKind)).digest();
        if (bh != null) {
            bh.consume(digest);
        }
        return digest;
    }

    // --- jRestly Pluggable Engine: Netty (ADR-0070) ---

    @Benchmark
    public long jrestlyNetty_buffered(Blackhole bh, CpuProbe cpu) throws Exception {
        long digest = FeedSummary.of(jrestlyNettyClient.feedBuffered(feedKind)).digest();
        if (bh != null) {
            bh.consume(digest);
        }
        return digest;
    }

    @Benchmark
    public long jrestlyNetty_streaming(Blackhole bh, CpuProbe cpu) throws Exception {
        long digest = FeedSummary.of(jrestlyNettyClient.feedStreaming(feedKind)).digest();
        if (bh != null) {
            bh.consume(digest);
        }
        return digest;
    }

    // --- Competitor Arms ---

    @Benchmark
    public long okHttp(Blackhole bh, CpuProbe cpu) throws Exception {
        long digest = FeedSummary.of(okHttpClient.feed(feedKind)).digest();
        if (bh != null) {
            bh.consume(digest);
        }
        return digest;
    }

    @Benchmark
    public long retrofit(Blackhole bh, CpuProbe cpu) throws Exception {
        long digest = FeedSummary.of(retrofitClient.feed(feedKind)).digest();
        if (bh != null) {
            bh.consume(digest);
        }
        return digest;
    }

    @Benchmark
    public long feign(Blackhole bh, CpuProbe cpu) throws Exception {
        long digest = FeedSummary.of(feignClient.feed(feedKind)).digest();
        if (bh != null) {
            bh.consume(digest);
        }
        return digest;
    }

    @Benchmark
    public long springRestClient(Blackhole bh, CpuProbe cpu) throws Exception {
        long digest = FeedSummary.of(springClient.feed(feedKind)).digest();
        if (bh != null) {
            bh.consume(digest);
        }
        return digest;
    }

    @Benchmark
    public long rawHttpClient(Blackhole bh, CpuProbe cpu) throws Exception {
        long digest = FeedSummary.of(rawHttpClient.feed(feedKind)).digest();
        if (bh != null) {
            bh.consume(digest);
        }
        return digest;
    }
}
