package ru.jrestly.benchmark.client;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.core.io.Resource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import ru.jrestly.benchmark.dto.UserDto;
import ru.jrestly.benchmark.feed.FeedEntry;
import ru.jrestly.benchmark.feed.FeedParser;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import javax.xml.stream.XMLStreamException;

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

        @GetExchange("/feed/{kind}")
        Resource feed(@PathVariable("kind") String kind);
    }

    private final SpringUserApi api;
    private final HttpClient rawHttpClient;
    private final FeedParser feedParser = new FeedParser();

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

    /**
     * Real-scenario fetch-and-parse arm (ADR-0068 &sect;4): the {@code Resource} return type
     * hands the response body over as a stream, which the shared parser reads before
     * the input stream is closed again.
     *
     * @param kind feed route suffix, one of {@code rss-20|200|1000} or {@code atom-*}
     * @return parsed entries in document order
     * @throws XMLStreamException when the fetched document cannot be parsed
     */
    public List<FeedEntry> feed(String kind) throws XMLStreamException {
        Resource resource = api.feed(kind);
        if (resource == null) {
            throw new IllegalStateException("Spring returned no response body for /feed/" + kind);
        }
        try (java.io.InputStream stream = resource.getInputStream()) {
            return feedParser.parse(stream);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        rawHttpClient.close();
    }
}
