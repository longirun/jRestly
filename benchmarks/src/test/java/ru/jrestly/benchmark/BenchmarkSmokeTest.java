package ru.jrestly.benchmark;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.benchmark.client.ApacheHttpClientBenchmarkClient;
import ru.jrestly.benchmark.client.FeignBenchmarkClient;
import ru.jrestly.benchmark.client.JRestlyBenchmarkClient;
import ru.jrestly.benchmark.client.RawHttpClientBenchmarkClient;
import ru.jrestly.benchmark.client.RetrofitBenchmarkClient;
import ru.jrestly.benchmark.client.SpringRestClientBenchmarkClient;
import ru.jrestly.benchmark.dto.UserDto;
import ru.jrestly.benchmark.server.BenchmarkHttpServer;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke coverage of the JMH benchmark suite: every client adapter starts and returns
 * consistent responses over the shared raw-TCP server, guarding benchmark integrity.
 */
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
    @DisplayName("jRestly benchmark client correctly performs GET, POST, batch and ping operations")
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

            List<UserDto> batch = client.getUsersBatch();
            assertNotNull(batch);
            assertEquals(50, batch.size());
            assertEquals(0L, batch.get(0).id());
            assertEquals(49L, batch.get(49).id());

            UserDto created = client.createUser(user);
            assertNotNull(created);
            assertEquals(user, created);
        }
    }

    @Test
    @DisplayName("Raw HttpClient benchmark client correctly performs GET, POST, batch and ping operations")
    void testRawHttpClient() {
        try (RawHttpClientBenchmarkClient client = new RawHttpClientBenchmarkClient(baseUrl)) {
            UserDto user = client.getUser(42L);
            assertNotNull(user);
            assertEquals(42L, user.id());
            assertEquals("Alice", user.name());

            assertEquals("pong", client.ping());

            List<UserDto> batch = client.getUsersBatch();
            assertNotNull(batch);
            assertEquals(50, batch.size());

            UserDto created = client.createUser(user);
            assertNotNull(created);
            assertEquals(user, created);
        }
    }

    @Test
    @DisplayName("Spring 6 RestClient benchmark client correctly performs GET, POST, batch and ping operations")
    void testSpringRestClient() {
        try (SpringRestClientBenchmarkClient client = new SpringRestClientBenchmarkClient(baseUrl)) {
            UserDto user = client.getUser(42L);
            assertNotNull(user);
            assertEquals(42L, user.id());
            assertEquals("Alice", user.name());

            assertEquals("pong", client.ping());

            List<UserDto> batch = client.getUsersBatch();
            assertNotNull(batch);
            assertEquals(50, batch.size());

            UserDto created = client.createUser(user);
            assertNotNull(created);
            assertEquals(user, created);
        }
    }

    @Test
    @DisplayName("Apache HttpClient 5 benchmark client correctly performs GET, POST, batch and ping operations")
    void testApacheHttpClient() {
        try (ApacheHttpClientBenchmarkClient client = new ApacheHttpClientBenchmarkClient(baseUrl)) {
            UserDto user = client.getUser(42L);
            assertNotNull(user);
            assertEquals(42L, user.id());
            assertEquals("Alice", user.name());

            assertEquals("pong", client.ping());

            List<UserDto> batch = client.getUsersBatch();
            assertNotNull(batch);
            assertEquals(50, batch.size());

            UserDto created = client.createUser(user);
            assertNotNull(created);
            assertEquals(user, created);
        }
    }

    @Test
    @DisplayName("Retrofit benchmark client correctly performs GET, POST, batch and ping operations")
    void testRetrofitClient() {
        try (RetrofitBenchmarkClient client = new RetrofitBenchmarkClient(baseUrl)) {
            UserDto user = client.getUser(42L);
            assertNotNull(user);
            assertEquals(42L, user.id());
            assertEquals("Alice", user.name());

            assertEquals("pong", client.ping());

            List<UserDto> batch = client.getUsersBatch();
            assertNotNull(batch);
            assertEquals(50, batch.size());

            UserDto created = client.createUser(user);
            assertNotNull(created);
            assertEquals(user, created);
        }
    }

    @Test
    @DisplayName("OpenFeign benchmark client correctly performs GET, POST, batch and ping operations")
    void testFeignClient() {
        try (FeignBenchmarkClient client = new FeignBenchmarkClient(baseUrl)) {
            UserDto user = client.getUser(42L);
            assertNotNull(user);
            assertEquals(42L, user.id());
            assertEquals("Alice", user.name());

            assertEquals("pong", client.ping());

            List<UserDto> batch = client.getUsersBatch();
            assertNotNull(batch);
            assertEquals(50, batch.size());

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
    @DisplayName("HttpNetworkBenchmark end-to-end methods execute successfully across all 6 clients")
    void testHttpNetworkBenchmarkExecution() throws IOException {
        HttpNetworkBenchmark benchmark = new HttpNetworkBenchmark();
        benchmark.setup();
        try {
            assertEquals(42L, benchmark.jrestly_getUser().id());
            assertEquals(42L, benchmark.rawHttpClient_getUser().id());
            assertEquals(42L, benchmark.springRestClient_getUser().id());
            assertEquals(42L, benchmark.apacheHttpClient_getUser().id());
            assertEquals(42L, benchmark.retrofit_getUser().id());
            assertEquals(42L, benchmark.feign_getUser().id());

            assertEquals("pong", benchmark.jrestly_ping_zeroDepRaw());
            assertEquals("pong", benchmark.rawHttpClient_ping());
            assertEquals("pong", benchmark.springRestClient_ping());
            assertEquals("pong", benchmark.apacheHttpClient_ping());
            assertEquals("pong", benchmark.retrofit_ping());
            assertEquals("pong", benchmark.feign_ping());

            assertEquals(50, benchmark.jrestly_getUsersBatch().size());
            assertEquals(50, benchmark.rawHttpClient_getUsersBatch().size());
            assertEquals(50, benchmark.springRestClient_getUsersBatch().size());
            assertEquals(50, benchmark.apacheHttpClient_getUsersBatch().size());
            assertEquals(50, benchmark.retrofit_getUsersBatch().size());
            assertEquals(50, benchmark.feign_getUsersBatch().size());

            assertEquals(42L, benchmark.jrestly_createUser().id());
            assertEquals(42L, benchmark.rawHttpClient_createUser().id());
            assertEquals(42L, benchmark.springRestClient_createUser().id());
            assertEquals(42L, benchmark.apacheHttpClient_createUser().id());
            assertEquals(42L, benchmark.retrofit_createUser().id());
            assertEquals(42L, benchmark.feign_createUser().id());
        } finally {
            benchmark.tearDown();
        }
    }

    @Test
    @DisplayName("ConcurrentLoadNetworkBenchmark executes successfully across all 6 clients")
    void testConcurrentLoadNetworkBenchmarkExecution() throws IOException {
        ConcurrentLoadNetworkBenchmark benchmark = new ConcurrentLoadNetworkBenchmark();
        benchmark.setup();
        try {
            assertEquals(42L, benchmark.jrestly_concurrentGetUser().id());
            assertEquals(42L, benchmark.rawHttpClient_concurrentGetUser().id());
            assertEquals(42L, benchmark.springRestClient_concurrentGetUser().id());
            assertEquals(42L, benchmark.apacheHttpClient_concurrentGetUser().id());
            assertEquals(42L, benchmark.retrofit_concurrentGetUser().id());
            assertEquals(42L, benchmark.feign_concurrentGetUser().id());

            assertEquals("pong", benchmark.jrestly_concurrentPing());
            assertEquals("pong", benchmark.rawHttpClient_concurrentPing());
            assertEquals("pong", benchmark.springRestClient_concurrentPing());
            assertEquals("pong", benchmark.apacheHttpClient_concurrentPing());
            assertEquals("pong", benchmark.retrofit_concurrentPing());
            assertEquals("pong", benchmark.feign_concurrentPing());
        } finally {
            benchmark.tearDown();
        }
    }
}
