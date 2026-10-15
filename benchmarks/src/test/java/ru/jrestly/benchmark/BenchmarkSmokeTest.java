package ru.jrestly.benchmark;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.benchmark.client.FeignBenchmarkClient;
import ru.jrestly.benchmark.client.JRestlyBenchmarkClient;
import ru.jrestly.benchmark.client.RawHttpClientBenchmarkClient;
import ru.jrestly.benchmark.client.RetrofitBenchmarkClient;
import ru.jrestly.benchmark.dto.UserDto;
import ru.jrestly.benchmark.server.BenchmarkHttpServer;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("JMH Benchmark Suite Smoke and Consistency Tests")
class BenchmarkSmokeTest {

    private static BenchmarkHttpServer server;
    private static String baseUrl;

    @BeforeAll
    static void startServer() throws IOException {
        server = BenchmarkHttpServer.start();
        baseUrl = server.baseUrl();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("jRestly benchmark client correctly performs GET, POST and ping operations")
    void testJRestlyClient() {
        try (JRestlyBenchmarkClient client = new JRestlyBenchmarkClient(baseUrl)) {
            UserDto user = client.getUser(42L);
            assertNotNull(user);
            assertEquals(42L, user.id());
            assertEquals("Alice", user.name());
            assertEquals("alice@example.com", user.email());
            assertTrue(user.active());
            assertEquals(List.of("admin", "engineer"), user.tags());
            assertEquals("2026-10-15T12:00:00Z", user.createdAt());

            assertEquals("pong", client.ping());

            UserDto created = client.createUser(user);
            assertNotNull(created);
            assertEquals(user, created);
        }
    }

    @Test
    @DisplayName("Raw HttpClient benchmark client correctly performs GET, POST and ping operations")
    void testRawHttpClient() {
        try (RawHttpClientBenchmarkClient client = new RawHttpClientBenchmarkClient(baseUrl)) {
            UserDto user = client.getUser(42L);
            assertNotNull(user);
            assertEquals(42L, user.id());
            assertEquals("Alice", user.name());

            assertEquals("pong", client.ping());

            UserDto created = client.createUser(user);
            assertNotNull(created);
            assertEquals(user, created);
        }
    }

    @Test
    @DisplayName("Retrofit benchmark client correctly performs GET, POST and ping operations")
    void testRetrofitClient() {
        try (RetrofitBenchmarkClient client = new RetrofitBenchmarkClient(baseUrl)) {
            UserDto user = client.getUser(42L);
            assertNotNull(user);
            assertEquals(42L, user.id());
            assertEquals("Alice", user.name());

            assertEquals("pong", client.ping());

            UserDto created = client.createUser(user);
            assertNotNull(created);
            assertEquals(user, created);
        }
    }

    @Test
    @DisplayName("OpenFeign benchmark client correctly performs GET, POST and ping operations")
    void testFeignClient() {
        try (FeignBenchmarkClient client = new FeignBenchmarkClient(baseUrl)) {
            UserDto user = client.getUser(42L);
            assertNotNull(user);
            assertEquals(42L, user.id());
            assertEquals("Alice", user.name());

            assertEquals("pong", client.ping());

            UserDto created = client.createUser(user);
            assertNotNull(created);
            assertEquals(user, created);
        }
    }

    @Test
    @DisplayName("DispatchOverheadBenchmark in-memory pipeline executes successfully")
    void testDispatchOverheadBenchmarkExecution() throws IOException {
        DispatchOverheadBenchmark benchmark = new DispatchOverheadBenchmark();
        benchmark.setup();
        try {
            UserDto baseline = benchmark.baseline_directJacksonParse();
            assertNotNull(baseline);
            assertEquals(42L, baseline.id());

            UserDto jrestly = benchmark.jrestly_inMemoryMockDispatch();
            assertNotNull(jrestly);
            assertEquals(42L, jrestly.id());
            assertEquals(baseline, jrestly);

            String baselineStr = benchmark.baseline_directStringConstruct();
            assertEquals("pong", baselineStr);

            String jrestlyStr = benchmark.jrestly_inMemoryMockPing();
            assertEquals("pong", jrestlyStr);
        } finally {
            benchmark.tearDown();
        }
    }

    @Test
    @DisplayName("HttpNetworkBenchmark end-to-end methods execute successfully")
    void testHttpNetworkBenchmarkExecution() throws IOException {
        HttpNetworkBenchmark benchmark = new HttpNetworkBenchmark();
        benchmark.setup();
        try {
            assertEquals(42L, benchmark.jrestly_getUser().id());
            assertEquals(42L, benchmark.rawHttpClient_getUser().id());
            assertEquals(42L, benchmark.retrofit_getUser().id());
            assertEquals(42L, benchmark.feign_getUser().id());

            assertEquals("pong", benchmark.jrestly_ping_zeroDepRaw());
            assertEquals("pong", benchmark.rawHttpClient_ping());
            assertEquals("pong", benchmark.retrofit_ping());
            assertEquals("pong", benchmark.feign_ping());

            assertEquals(42L, benchmark.jrestly_createUser().id());
            assertEquals(42L, benchmark.rawHttpClient_createUser().id());
            assertEquals(42L, benchmark.retrofit_createUser().id());
            assertEquals(42L, benchmark.feign_createUser().id());
        } finally {
            benchmark.tearDown();
        }
    }
}
