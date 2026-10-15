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
        @Get(path = "/api/users/${id}")
        UserDto getUser(@PathVariable(name = "id") long id);

        @Get(path = "/api/ping")
        String ping();

        @Post(path = "/api/users")
        UserDto createUser(@RequestBody UserDto user);
    }

    private final JRestlyClient client;
    private final UserApi api;

    public JRestlyBenchmarkClient(String baseUrl) {
        this.client = JRestlyClient.builder()
                .baseUrl(baseUrl)
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

    public UserDto createUser(UserDto user) {
        return api.createUser(user);
    }

    @Override
    public void close() {
        client.close();
    }
}
