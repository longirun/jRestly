package ru.jrestly.benchmark.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import ru.jrestly.benchmark.dto.UserDto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Raw OkHttp benchmark adapter (plain HTTP client usage, no declarative layer)
 * serving as the comparison baseline for jRestly running on a plugged OkHttp engine.
 */
public final class OkHttpBenchmarkClient implements AutoCloseable {

    private static final MediaType JSON = MediaType.get("application/json");

    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OkHttpBenchmarkClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = new OkHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    public UserDto getUser(long id) {
        byte[] body = executeBytes(get(baseUrl + "/api/users/" + id));
        try {
            return objectMapper.readValue(body, UserDto.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String ping() {
        try (Response response = httpClient.newCall(get(baseUrl + "/api/ping")).execute()) {
            ResponseBody body = response.body();
            return body != null ? body.string() : "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<UserDto> getUsersBatch() {
        byte[] body = executeBytes(get(baseUrl + "/api/users/batch"));
        try {
            return objectMapper.readValue(body, new TypeReference<List<UserDto>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public UserDto createUser(UserDto user) {
        try {
            byte[] jsonBytes = objectMapper.writeValueAsBytes(user);
            Request request = new Request.Builder()
                    .url(baseUrl + "/api/users")
                    .post(RequestBody.create(jsonBytes, JSON))
                    .build();
            return objectMapper.readValue(executeBytes(request), UserDto.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Request get(String url) {
        return new Request.Builder().url(url).build();
    }

    private byte[] executeBytes(Request request) {
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            return body != null ? body.bytes() : new byte[0];
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}
