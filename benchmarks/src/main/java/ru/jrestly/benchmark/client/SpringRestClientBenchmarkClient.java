package ru.jrestly.benchmark.client;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import ru.jrestly.benchmark.dto.UserDto;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Spring 6 RestClient and HTTP Interface dynamic proxy client adapter
 * for comparative benchmarks.
 */
public final class SpringRestClientBenchmarkClient implements AutoCloseable {

    /**
     * Spring 6 declarative HTTP Interface definition.
     */
    @HttpExchange
    public interface SpringUserApi {
        @GetExchange("/api/users/{id}")
        UserDto getUser(@PathVariable("id") long id);

        @GetExchange("/api/ping")
        String ping();

        @GetExchange("/api/users/batch")
        java.util.List<UserDto> getUsersBatch();

        @PostExchange("/api/users")
        UserDto createUser(@RequestBody UserDto user);
    }

    private final SpringUserApi api;
    private final HttpClient rawHttpClient;

    public SpringRestClientBenchmarkClient(String baseUrl) {
        this.rawHttpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(rawHttpClient);

        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();

        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();
        this.api = factory.createClient(SpringUserApi.class);
    }

    public SpringUserApi api() {
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
        rawHttpClient.close();
    }
}
