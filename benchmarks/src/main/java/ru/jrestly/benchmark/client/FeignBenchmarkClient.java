package ru.jrestly.benchmark.client;

import feign.Feign;
import feign.Param;
import feign.RequestLine;
import feign.http2client.Http2Client;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import ru.jrestly.benchmark.dto.UserDto;

import java.net.http.HttpClient;

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

        @RequestLine("POST /api/users")
        UserDto createUser(UserDto user);
    }

    private final HttpClient httpClient;
    private final UserApi api;

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

    public UserDto createUser(UserDto user) {
        return api.createUser(user);
    }

    @Override
    public void close() {
        httpClient.close();
    }
}
