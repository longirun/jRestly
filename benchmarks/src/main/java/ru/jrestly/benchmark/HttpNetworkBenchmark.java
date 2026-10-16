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
import ru.jrestly.benchmark.client.ApacheHttpClientBenchmarkClient;
import ru.jrestly.benchmark.client.FeignBenchmarkClient;
import ru.jrestly.benchmark.client.JRestlyBenchmarkClient;
import ru.jrestly.benchmark.client.OkHttpBenchmarkClient;
import ru.jrestly.benchmark.client.RawHttpClientBenchmarkClient;
import ru.jrestly.benchmark.client.RetrofitBenchmarkClient;
import ru.jrestly.benchmark.client.SpringRestClientBenchmarkClient;
import ru.jrestly.benchmark.dto.UserDto;
import ru.jrestly.benchmark.server.BenchmarkHttpServer;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end single-threaded HTTP network benchmark comparing jRestly against raw JDK HttpClient,
 * raw OkHttp, Spring 6 RestClient, Apache HttpClient 5, Square Retrofit 2, and OpenFeign
 * over loopback TCP sockets. Also profiles jRestly over pluggable engines
 * (OkHttp, Apache HttpClient 5, pinned HTTP/1.1).
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
    private JRestlyBenchmarkClient jrestlyOkHttpClient;
    private JRestlyBenchmarkClient jrestlyApache5Client;
    private JRestlyBenchmarkClient jrestlyHttp11Client;
    private RawHttpClientBenchmarkClient rawHttpClient;
    private OkHttpBenchmarkClient okHttpClient;
    private SpringRestClientBenchmarkClient springClient;
    private ApacheHttpClientBenchmarkClient apacheClient;
    private RetrofitBenchmarkClient retrofitClient;
    private FeignBenchmarkClient feignClient;

    private UserDto testUser;

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
        okHttpClient = new OkHttpBenchmarkClient(baseUrl);
        springClient = new SpringRestClientBenchmarkClient(baseUrl);
        apacheClient = new ApacheHttpClientBenchmarkClient(baseUrl);
        retrofitClient = new RetrofitBenchmarkClient(baseUrl);
        feignClient = new FeignBenchmarkClient(baseUrl);

        testUser = new UserDto(42L, "Alice", "alice@example.com", true, List.of("admin", "engineer"), "2026-10-15T12:00:00Z");
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
        if (okHttpClient != null) {
            okHttpClient.close();
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

    // --- GET /api/users/{id} (JSON DTO Deserialization) ---

    @Benchmark
    public UserDto jrestly_getUser() {
        return jrestlyClient.getUser(42L);
    }

    @Benchmark
    public UserDto jrestlyOkHttp_getUser() {
        return jrestlyOkHttpClient.getUser(42L);
    }

    @Benchmark
    public UserDto jrestlyApache5_getUser() {
        return jrestlyApache5Client.getUser(42L);
    }

    @Benchmark
    public UserDto jrestlyHttp11_getUser() {
        return jrestlyHttp11Client.getUser(42L);
    }

    @Benchmark
    public UserDto okHttp_getUser() {
        return okHttpClient.getUser(42L);
    }

    @Benchmark
    public UserDto rawHttpClient_getUser() {
        return rawHttpClient.getUser(42L);
    }

    @Benchmark
    public UserDto springRestClient_getUser() {
        return springClient.getUser(42L);
    }

    @Benchmark
    public UserDto apacheHttpClient_getUser() {
        return apacheClient.getUser(42L);
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
    public String jrestlyOkHttp_ping() {
        return jrestlyOkHttpClient.ping();
    }

    @Benchmark
    public String jrestlyApache5_ping() {
        return jrestlyApache5Client.ping();
    }

    @Benchmark
    public String jrestlyHttp11_ping() {
        return jrestlyHttp11Client.ping();
    }

    @Benchmark
    public String okHttp_ping() {
        return okHttpClient.ping();
    }

    @Benchmark
    public String rawHttpClient_ping() {
        return rawHttpClient.ping();
    }

    @Benchmark
    public String springRestClient_ping() {
        return springClient.ping();
    }

    @Benchmark
    public String apacheHttpClient_ping() {
        return apacheClient.ping();
    }

    @Benchmark
    public String retrofit_ping() {
        return retrofitClient.ping();
    }

    @Benchmark
    public String feign_ping() {
        return feignClient.ping();
    }

    // --- GET /api/users/batch (Batch Payload / List<UserDto>) ---

    @Benchmark
    public List<UserDto> jrestly_getUsersBatch() {
        return jrestlyClient.getUsersBatch();
    }

    @Benchmark
    public List<UserDto> rawHttpClient_getUsersBatch() {
        return rawHttpClient.getUsersBatch();
    }

    @Benchmark
    public List<UserDto> springRestClient_getUsersBatch() {
        return springClient.getUsersBatch();
    }

    @Benchmark
    public List<UserDto> apacheHttpClient_getUsersBatch() {
        return apacheClient.getUsersBatch();
    }

    @Benchmark
    public List<UserDto> retrofit_getUsersBatch() {
        return retrofitClient.getUsersBatch();
    }

    @Benchmark
    public List<UserDto> feign_getUsersBatch() {
        return feignClient.getUsersBatch();
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
    public UserDto springRestClient_createUser() {
        return springClient.createUser(testUser);
    }

    @Benchmark
    public UserDto apacheHttpClient_createUser() {
        return apacheClient.createUser(testUser);
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
