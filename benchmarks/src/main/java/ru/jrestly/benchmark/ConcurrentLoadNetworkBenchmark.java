package ru.jrestly.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import ru.jrestly.benchmark.client.ApacheHttpClientBenchmarkClient;
import ru.jrestly.benchmark.client.FeignBenchmarkClient;
import ru.jrestly.benchmark.client.JRestlyBenchmarkClient;
import ru.jrestly.benchmark.client.RawHttpClientBenchmarkClient;
import ru.jrestly.benchmark.client.RetrofitBenchmarkClient;
import ru.jrestly.benchmark.client.SpringRestClientBenchmarkClient;
import ru.jrestly.benchmark.dto.UserDto;
import ru.jrestly.benchmark.server.BenchmarkHttpServer;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * High-concurrency throughput benchmark simulating intense multi-threaded production load (16 concurrent threads)
 * comparing jRestly against raw JDK HttpClient, Spring 6 RestClient, Apache HttpClient 5,
 * Square Retrofit 2, and OpenFeign.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@Threads(16)
public class ConcurrentLoadNetworkBenchmark {

    private BenchmarkHttpServer server;
    private JRestlyBenchmarkClient jrestlyClient;
    private JRestlyBenchmarkClient jrestlyOkHttpClient;
    private JRestlyBenchmarkClient jrestlyApache5Client;
    private JRestlyBenchmarkClient jrestlyHttp11Client;
    private RawHttpClientBenchmarkClient rawHttpClient;
    private SpringRestClientBenchmarkClient springClient;
    private ApacheHttpClientBenchmarkClient apacheClient;
    private RetrofitBenchmarkClient retrofitClient;
    private FeignBenchmarkClient feignClient;

    @Setup
    public void setup() throws IOException {
        java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.WARNING);
        server = BenchmarkHttpServer.start();
        String baseUrl = server.baseUrl();

        jrestlyClient = new JRestlyBenchmarkClient(baseUrl);
        jrestlyOkHttpClient = new JRestlyBenchmarkClient(baseUrl, new ru.jrestly.engine.okhttp.OkHttpEngine());
        jrestlyApache5Client = new JRestlyBenchmarkClient(baseUrl, new ru.jrestly.engine.apache5.Apache5HttpEngine());
        jrestlyHttp11Client = new JRestlyBenchmarkClient(baseUrl, java.net.http.HttpClient.Version.HTTP_1_1);
        rawHttpClient = new RawHttpClientBenchmarkClient(baseUrl);
        springClient = new SpringRestClientBenchmarkClient(baseUrl);
        apacheClient = new ApacheHttpClientBenchmarkClient(baseUrl);
        retrofitClient = new RetrofitBenchmarkClient(baseUrl);
        feignClient = new FeignBenchmarkClient(baseUrl);
    }

    @TearDown
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
        if (jrestlyHttp11Client != null) {
            jrestlyHttp11Client.close();
        }
        if (rawHttpClient != null) {
            rawHttpClient.close();
        }
        if (springClient != null) {
            springClient.close();
        }
        if (apacheClient != null) {
            apacheClient.close();
        }
        if (retrofitClient != null) {
            retrofitClient.close();
        }
        if (feignClient != null) {
            feignClient.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    // --- Concurrent JSON DTO Throughput (ops/sec) ---

    @Benchmark
    public UserDto jrestly_concurrentGetUser() {
        return jrestlyClient.getUser(42L);
    }

    @Benchmark
    public UserDto jrestlyOkHttp_concurrentGetUser() {
        return jrestlyOkHttpClient.getUser(42L);
    }

    @Benchmark
    public UserDto jrestlyApache5_concurrentGetUser() {
        return jrestlyApache5Client.getUser(42L);
    }

    @Benchmark
    public UserDto jrestlyHttp11_concurrentGetUser() {
        return jrestlyHttp11Client.getUser(42L);
    }

    @Benchmark
    public UserDto rawHttpClient_concurrentGetUser() {
        return rawHttpClient.getUser(42L);
    }

    @Benchmark
    public UserDto springRestClient_concurrentGetUser() {
        return springClient.getUser(42L);
    }

    @Benchmark
    public UserDto apacheHttpClient_concurrentGetUser() {
        return apacheClient.getUser(42L);
    }

    @Benchmark
    public UserDto retrofit_concurrentGetUser() {
        return retrofitClient.getUser(42L);
    }

    @Benchmark
    public UserDto feign_concurrentGetUser() {
        return feignClient.getUser(42L);
    }

    // --- Concurrent Ping Throughput (ops/sec) ---

    @Benchmark
    public String jrestly_concurrentPing() {
        return jrestlyClient.ping();
    }

    @Benchmark
    public String jrestlyOkHttp_concurrentPing() {
        return jrestlyOkHttpClient.ping();
    }

    @Benchmark
    public String jrestlyApache5_concurrentPing() {
        return jrestlyApache5Client.ping();
    }

    @Benchmark
    public String jrestlyHttp11_concurrentPing() {
        return jrestlyHttp11Client.ping();
    }

    @Benchmark
    public String rawHttpClient_concurrentPing() {
        return rawHttpClient.ping();
    }

    @Benchmark
    public String springRestClient_concurrentPing() {
        return springClient.ping();
    }

    @Benchmark
    public String apacheHttpClient_concurrentPing() {
        return apacheClient.ping();
    }

    @Benchmark
    public String retrofit_concurrentPing() {
        return retrofitClient.ping();
    }

    @Benchmark
    public String feign_concurrentPing() {
        return feignClient.ping();
    }
}
