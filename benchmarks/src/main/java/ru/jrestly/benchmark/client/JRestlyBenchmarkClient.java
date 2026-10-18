package ru.jrestly.benchmark.client;

import ru.jrestly.JRestlyClient;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.PathVariable;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.benchmark.dto.UserDto;

/**
 * jRestly declarative client adapter for comparative benchmarks.
 */
public final class JRestlyBenchmarkClient implements AutoCloseable {

    /**
     * Declarative API interface definition for jRestly.
     */
    public interface UserApi {
        @Get("/api/users/${id}")
        UserDto getUser(@PathVariable long id);

        @Get("/api/ping")
        String ping();

        @Get("/api/users/batch")
        java.util.List<UserDto> getUsersBatch();

        @Post("/api/users")
        UserDto createUser(@RequestBody UserDto user);
    }

    private final JRestlyClient client;
    private final UserApi api;

    /**
     * Creates a client pinned to the zero-dependency JDK transport: with the engine
     * submodules on the classpath a default client would auto-detect a third-party
     * engine via {@code ServiceLoader} (ADR-0058), silently hijacking the
     * "jrestly (JDK)" benchmark row.
     *
     * @param baseUrl the benchmark server base URL
     */
    public JRestlyBenchmarkClient(String baseUrl) {
        this(baseUrl, ru.jrestly.engine.JdkHttpEngine.create(
                ru.jrestly.SimpleModuleInfo.builder().baseUrl(baseUrl).build()));
    }

    public JRestlyBenchmarkClient(String baseUrl, ru.jrestly.engine.HttpEngine engine) {
        var builder = JRestlyClient.builder()
                .baseUrl(baseUrl)
                .logger(ru.jrestly.benchmark.util.NoOpLogger.INSTANCE);
        if (engine != null) {
            builder.httpEngine(engine);
        }
        this.client = builder.build();
        this.api = client.get(UserApi.class);
    }

    public JRestlyBenchmarkClient(String baseUrl, java.net.http.HttpClient.Version version) {
        this.client = JRestlyClient.builder()
                .baseUrl(baseUrl)
                .httpVersion(version)
                .logger(ru.jrestly.benchmark.util.NoOpLogger.INSTANCE)
                .build();
        this.api = client.get(UserApi.class);
    }

    public UserApi api() {
        return api;
    }

    public UserDto getUser(long id) {
        return api.getUser(id);
    }

    public String ping() {
        return api.ping();
    }

    public java.util.List<UserDto> getUsersBatch() {
        return api.getUsersBatch();
    }

    public UserDto createUser(UserDto user) {
        return api.createUser(user);
    }

    @Override
    public void close() {
        client.close();
    }
}
