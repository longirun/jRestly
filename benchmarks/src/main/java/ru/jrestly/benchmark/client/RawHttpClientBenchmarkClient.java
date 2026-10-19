package ru.jrestly.benchmark.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import ru.jrestly.benchmark.dto.UserDto;
import ru.jrestly.benchmark.feed.FeedEntry;
import ru.jrestly.benchmark.feed.FeedParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import javax.xml.stream.XMLStreamException;

/**
 * Raw JDK {@link HttpClient} implementation serving as the zero-abstraction baseline.
 */
public final class RawHttpClientBenchmarkClient implements AutoCloseable {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final FeedParser feedParser = new FeedParser();

    public RawHttpClientBenchmarkClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder().build();
        this.objectMapper = new ObjectMapper();
    }

    public UserDto getUser(long id) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/users/" + id))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return objectMapper.readValue(response.body(), UserDto.class);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public String ping() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ping"))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public java.util.List<UserDto> getUsersBatch() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/users/batch"))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return objectMapper.readValue(response.body(), new com.fasterxml.jackson.core.type.TypeReference<java.util.List<UserDto>>() {});
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public UserDto createUser(UserDto user) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(user);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/users"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return objectMapper.readValue(response.body(), UserDto.class);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Real-scenario fetch-and-parse arm (ADR-0068 &sect;4): the body is received as a live
     * stream ({@code BodyHandlers.ofInputStream()}) and fed straight into the shared parser.
     *
     * @param kind feed route suffix, one of {@code rss-20|200|1000} or {@code atom-*}
     * @return parsed entries in document order
     * @throws XMLStreamException when the fetched document cannot be parsed
     */
    public List<FeedEntry> feed(String kind) throws XMLStreamException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/feed/" + kind))
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try (InputStream stream = response.body()) {
            return feedParser.parse(stream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        httpClient.close();
    }
}
