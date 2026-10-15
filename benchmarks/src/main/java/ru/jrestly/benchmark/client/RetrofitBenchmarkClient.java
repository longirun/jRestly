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

import java.io.IOException;

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

        @POST("/api/users")
        Call<UserDto> createUser(@Body UserDto user);
    }

    private final OkHttpClient okHttpClient;
    private final UserApi api;

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

    public UserDto createUser(UserDto user) {
        try {
            return api.createUser(user).execute().body();
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
