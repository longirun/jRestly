package ru.jrestly.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import ru.jrestly.JRestlyClient;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.PathVariable;
import ru.jrestly.benchmark.dto.UserDto;
import ru.jrestly.benchmark.server.BenchmarkHttpServer;
import ru.jrestly.http.WireResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * In-memory benchmark isolating the pure abstraction overhead of jRestly's dynamic proxy
 * and request pipeline dispatch without any OS network socket latency.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class DispatchOverheadBenchmark {

    public interface MockUserApi {
        @Get("/api/users/{id}")
        UserDto getUser(@PathVariable long id);

        @Get("/api/ping")
        String ping();
    }

    private byte[] sampleJsonBytes;
    private byte[] pingBytes;
    private ObjectMapper objectMapper;

    private JRestlyClient jrestlyClient;
    private MockUserApi jrestlyMockApi;

    @Setup
    public void setup() {
        java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.WARNING);
        sampleJsonBytes = BenchmarkHttpServer.SAMPLE_USER_JSON.getBytes(StandardCharsets.UTF_8);
        pingBytes = "pong".getBytes(StandardCharsets.UTF_8);
        objectMapper = new ObjectMapper();

        WireResponse mockUserResponse = WireResponse.builder()
                .statusCode(200)
                .header("Content-Type", "application/json; charset=utf-8")
                .body(sampleJsonBytes)
                .build();

        WireResponse mockPingResponse = WireResponse.builder()
                .statusCode(200)
                .header("Content-Type", "text/plain; charset=utf-8")
                .body(pingBytes)
                .build();

        jrestlyClient = JRestlyClient.builder()
                .baseUrl("http://mock-in-memory.local")
                .logger(ru.jrestly.benchmark.util.NoOpLogger.INSTANCE)
                // Pinned: auto-detection (ADR-0058) would otherwise create a third-party
                // engine that mock dispatch never needs.
                .httpEngine(ru.jrestly.engine.JdkHttpEngine.create(
                        ru.jrestly.SimpleModuleInfo.builder().baseUrl("http://mock-in-memory.local").build()))
                .mock(req -> req.uri().getPath().contains("/ping"), mockPingResponse)
                .mock(mockUserResponse)
                .build();

        jrestlyMockApi = jrestlyClient.get(MockUserApi.class);
    }

    @TearDown
    public void tearDown() {
        if (jrestlyClient != null) {
            jrestlyClient.close();
        }
    }

    // --- JSON Model Decoding: Baseline vs jRestly Dynamic Proxy ---

    @Benchmark
    public UserDto baseline_directJacksonParse() throws IOException {
        return objectMapper.readValue(sampleJsonBytes, UserDto.class);
    }

    @Benchmark
    public UserDto jrestly_inMemoryMockDispatch() {
        return jrestlyMockApi.getUser(42L);
    }

    // --- Raw Text: Baseline vs jRestly Raw Zero-Dep Dispatch ---

    @Benchmark
    public String baseline_directStringConstruct() {
        return new String(pingBytes, StandardCharsets.UTF_8);
    }

    @Benchmark
    public String jrestly_inMemoryMockPing() {
        return jrestlyMockApi.ping();
    }
}
