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
import org.openjdk.jmh.annotations.Warmup;
import ru.jrestly.benchmark.client.FeignBenchmarkClient;
import ru.jrestly.benchmark.client.JRestlyBenchmarkClient;
import ru.jrestly.benchmark.client.RawHttpClientBenchmarkClient;
import ru.jrestly.benchmark.client.RetrofitBenchmarkClient;
import ru.jrestly.benchmark.dto.UserDto;
import ru.jrestly.benchmark.server.BenchmarkHttpServer;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end HTTP network benchmark comparing jRestly against raw JDK HttpClient,
 * Square Retrofit 2, and OpenFeign over loopback TCP sockets.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class HttpNetworkBenchmark {

    private BenchmarkHttpServer server;
    private JRestlyBenchmarkClient jrestlyClient;
    private RawHttpClientBenchmarkClient rawHttpClient;
    private RetrofitBenchmarkClient retrofitClient;
    private FeignBenchmarkClient feignClient;

    private UserDto testUser;

    @Setup
    public void setup() throws IOException {
        java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.WARNING);
        server = BenchmarkHttpServer.start();
        String baseUrl = server.baseUrl();

        jrestlyClient = new JRestlyBenchmarkClient(baseUrl);
        rawHttpClient = new RawHttpClientBenchmarkClient(baseUrl);
        retrofitClient = new RetrofitBenchmarkClient(baseUrl);
        feignClient = new FeignBenchmarkClient(baseUrl);

        testUser = new UserDto(42L, "Alice", "alice@example.com", true, List.of("admin", "engineer"), "2026-10-15T12:00:00Z");
    }

    @TearDown
    public void tearDown() {
        if (jrestlyClient != null) {
            jrestlyClient.close();
        }
        if (rawHttpClient != null) {
            rawHttpClient.close();
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

    // --- GET /api/users/{id} (JSON DTO Deserialization) ---

    @Benchmark
    public UserDto jrestly_getUser() {
        return jrestlyClient.getUser(42L);
    }

    @Benchmark
    public UserDto rawHttpClient_getUser() {
        return rawHttpClient.getUser(42L);
    }

    @Benchmark
    public UserDto retrofit_getUser() {
        return retrofitClient.getUser(42L);
    }

    @Benchmark
    public UserDto feign_getUser() {
        return feignClient.getUser(42L);
    }

    // --- GET /api/ping (Raw String Throughput / Zero-Dep) ---

    @Benchmark
    public String jrestly_ping_zeroDepRaw() {
        return jrestlyClient.ping();
    }

    @Benchmark
    public String rawHttpClient_ping() {
        return rawHttpClient.ping();
    }

    @Benchmark
    public String retrofit_ping() {
        return retrofitClient.ping();
    }

    @Benchmark
    public String feign_ping() {
        return feignClient.ping();
    }

    // --- POST /api/users (JSON Serialization & Echo) ---

    @Benchmark
    public UserDto jrestly_createUser() {
        return jrestlyClient.createUser(testUser);
    }

    @Benchmark
    public UserDto rawHttpClient_createUser() {
        return rawHttpClient.createUser(testUser);
    }

    @Benchmark
    public UserDto retrofit_createUser() {
        return retrofitClient.createUser(testUser);
    }

    @Benchmark
    public UserDto feign_createUser() {
        return feignClient.createUser(testUser);
    }
}
