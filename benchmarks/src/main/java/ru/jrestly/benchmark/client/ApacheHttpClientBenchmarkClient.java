package ru.jrestly.benchmark.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.io.CloseMode;
import ru.jrestly.benchmark.dto.UserDto;

import java.io.IOException;

/**
 * Apache HttpClient 5 benchmark adapter for comparative profiling.
 */
public final class ApacheHttpClientBenchmarkClient implements AutoCloseable {

    private final String baseUrl;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ApacheHttpClientBenchmarkClient(String baseUrl) {
        this.baseUrl = baseUrl;
        PoolingHttpClientConnectionManager connManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(200)
                .setMaxConnPerRoute(200)
                .build();

        this.httpClient = HttpClients.custom()
                .setConnectionManager(connManager)
                .disableAutomaticRetries()
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public UserDto getUser(long id) {
        HttpGet request = new HttpGet(baseUrl + "/api/users/" + id);
        try {
            return httpClient.execute(request, response -> {
                byte[] body = EntityUtils.toByteArray(response.getEntity());
                return objectMapper.readValue(body, UserDto.class);
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String ping() {
        HttpGet request = new HttpGet(baseUrl + "/api/ping");
        try {
            return httpClient.execute(request, response -> EntityUtils.toString(response.getEntity()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public java.util.List<UserDto> getUsersBatch() {
        HttpGet request = new HttpGet(baseUrl + "/api/users/batch");
        try {
            return httpClient.execute(request, response -> {
                byte[] body = EntityUtils.toByteArray(response.getEntity());
                return objectMapper.readValue(body, new com.fasterxml.jackson.core.type.TypeReference<java.util.List<UserDto>>() {});
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public UserDto createUser(UserDto user) {
        HttpPost request = new HttpPost(baseUrl + "/api/users");
        try {
            byte[] jsonBytes = objectMapper.writeValueAsBytes(user);
            request.setEntity(new StringEntity(new String(jsonBytes), ContentType.APPLICATION_JSON));
            return httpClient.execute(request, response -> {
                byte[] body = EntityUtils.toByteArray(response.getEntity());
                return objectMapper.readValue(body, UserDto.class);
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        httpClient.close(CloseMode.GRACEFUL);
    }
}
