package ru.jrestly.benchmark.client;

import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;
import ru.jrestly.benchmark.dto.UserDto;
import ru.jrestly.benchmark.feed.FeedEntry;
import ru.jrestly.benchmark.feed.FeedParser;

import java.io.IOException;
import java.util.List;

import javax.xml.stream.XMLStreamException;

/**
 * Square Retrofit 2 adapter for comparative benchmarks.
 */
public final class RetrofitBenchmarkClient implements AutoCloseable {

    /**
     * Retrofit declarative API interface definition.
     */
    public interface UserApi {
        @GET("/api/users/{id}")
        Call<UserDto> getUser(@Path("id") long id);

        @GET("/api/ping")
        Call<ResponseBody> ping();

        @GET("/api/users/batch")
        Call<java.util.List<UserDto>> getUsersBatch();

        @POST("/api/users")
        Call<UserDto> createUser(@Body UserDto user);

        @GET("/feed/{kind}")
        Call<ResponseBody> feed(@Path("kind") String kind);
    }

    private final OkHttpClient okHttpClient;
    private final UserApi api;
    private final FeedParser feedParser = new FeedParser();

    public RetrofitBenchmarkClient(String baseUrl) {
        String normalizedUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.okHttpClient = new OkHttpClient.Builder().build();
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(normalizedUrl)
                .client(okHttpClient)
                .addConverterFactory(JacksonConverterFactory.create())
                .build();
        this.api = retrofit.create(UserApi.class);
    }

    public UserDto getUser(long id) {
        try {
            return api.getUser(id).execute().body();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String ping() {
        try (ResponseBody body = api.ping().execute().body()) {
            return body != null ? body.string() : null;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public java.util.List<UserDto> getUsersBatch() {
        try {
            return api.getUsersBatch().execute().body();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public UserDto createUser(UserDto user) {
        try {
            return api.createUser(user).execute().body();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Real-scenario fetch-and-parse arm (ADR-0068 &sect;4): the {@code ResponseBody} is
     * streamed into the shared parser through its byte stream while held open.
     *
     * @param kind feed route suffix, one of {@code rss-20|200|1000} or {@code atom-*}
     * @return parsed entries in document order
     * @throws XMLStreamException when the fetched document cannot be parsed
     */
    public List<FeedEntry> feed(String kind) throws XMLStreamException {
        try (ResponseBody body = api.feed(kind).execute().body()) {
            if (body == null) {
                throw new IllegalStateException("Retrofit returned no response body for /feed/" + kind);
            }
            return feedParser.parse(body.byteStream());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        okHttpClient.dispatcher().executorService().shutdown();
        okHttpClient.connectionPool().evictAll();
    }
}
