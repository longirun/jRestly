package ru.jrestly.benchmark.client;

import feign.Feign;
import feign.Param;
import feign.RequestLine;
import feign.http2client.Http2Client;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import ru.jrestly.benchmark.dto.UserDto;
import ru.jrestly.benchmark.feed.FeedEntry;
import ru.jrestly.benchmark.feed.FeedParser;

import java.net.http.HttpClient;
import java.io.IOException;
import java.util.List;

import javax.xml.stream.XMLStreamException;

/**
 * OpenFeign adapter for comparative benchmarks running on {@link Java11HttpClient}.
 */
public final class FeignBenchmarkClient implements AutoCloseable {

    /**
     * OpenFeign declarative API interface definition.
     */
    public interface UserApi {
        @RequestLine("GET /api/users/{id}")
        UserDto getUser(@Param("id") long id);

        @RequestLine("GET /api/ping")
        String ping();

        @RequestLine("GET /api/users/batch")
        java.util.List<UserDto> getUsersBatch();

        @RequestLine("POST /api/users")
        UserDto createUser(UserDto user);

        @RequestLine("GET /feed/{kind}")
        feign.Response feed(@Param("kind") String kind);
    }

    private final HttpClient httpClient;
    private final UserApi api;
    private final FeedParser feedParser = new FeedParser();

    public FeignBenchmarkClient(String baseUrl) {
        this.httpClient = HttpClient.newBuilder().build();
        feign.codec.Decoder stringDecoder = new feign.codec.StringDecoder();
        feign.codec.Decoder jacksonDecoder = new JacksonDecoder();
        feign.codec.Decoder combinedDecoder = (response, type) -> {
            if (String.class.equals(type)) {
                return stringDecoder.decode(response, type);
            }
            return jacksonDecoder.decode(response, type);
        };

        this.api = Feign.builder()
                .client(new Http2Client(httpClient))
                .encoder(new JacksonEncoder())
                .decoder(combinedDecoder)
                .target(UserApi.class, baseUrl);
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
     * Real-scenario fetch-and-parse arm (ADR-0068 &sect;4): the raw {@code feign.Response}
     * is held open while the shared parser reads its body stream.
     *
     * @param kind feed route suffix, one of {@code rss-20|200|1000} or {@code atom-*}
     * @return parsed entries in document order
     * @throws XMLStreamException when the fetched document cannot be parsed
     */
    public List<FeedEntry> feed(String kind) throws XMLStreamException {
        try (feign.Response response = api.feed(kind)) {
            if (response.body() == null) {
                throw new IllegalStateException("Feign returned no response body for /feed/" + kind);
            }
            return feedParser.parse(response.body().asInputStream());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        httpClient.close();
    }
}
