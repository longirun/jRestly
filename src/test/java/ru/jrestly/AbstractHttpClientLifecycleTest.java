package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.engine.HttpEngine;
import ru.jrestly.engine.HttpEngineOptions;
import ru.jrestly.engine.JdkHttpEngine;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AbstractHttpClient Lifecycle and Resource Management Tests")
class AbstractHttpClientLifecycleTest {

    interface TestApi {
        @Get(path = "/ping")
        String ping();
    }

    private static class CloseTrackingHttpClient extends HttpClient {
        final AtomicBoolean closed = new AtomicBoolean(false);

        @Override
        public void close() {
            closed.set(true);
        }

        @Override
        public Optional<java.net.CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<java.net.ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public javax.net.ssl.SSLContext sslContext() {
            return null;
        }

        @Override
        public javax.net.ssl.SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<java.net.Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<java.util.concurrent.Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) {
            return null;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) {
            return null;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return null;
        }
    }

    private static class CloseTrackingHttpEngine implements HttpEngine {
        final AtomicBoolean closed = new AtomicBoolean(false);

        @Override
        public WireResponse send(WireRequest request, HttpEngineOptions options) {
            return WireResponse.builder().statusCode(200).build();
        }

        @Override
        public CompletableFuture<WireResponse> sendAsync(WireRequest request, HttpEngineOptions options) {
            return CompletableFuture.completedFuture(WireResponse.builder().statusCode(200).build());
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    @Test
    @DisplayName("close() properly closes internal HttpClient without leaking sockets or threads")
    void testCloseClosesInternalHttpClient() {
        CloseTrackingHttpClient trackingClient = new CloseTrackingHttpClient();

        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("https://example.com")
                .build();

        AbstractHttpClient client = new AbstractHttpClient(moduleInfo) {
            @Override
            protected void createConnection() {
                this.httpClient = trackingClient;
                this.httpEngine = new JdkHttpEngine(trackingClient, true);
            }
        };

        assertFalse(trackingClient.closed.get(), "Client should not be closed initially");
        client.close();
        assertTrue(trackingClient.closed.get(), "Client must be closed when AbstractHttpClient.close() is invoked");
    }

    @Test
    @DisplayName("close() properly closes pluggable HttpEngine")
    void testCloseClosesHttpEngine() {
        CloseTrackingHttpEngine trackingEngine = new CloseTrackingHttpEngine();

        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("https://example.com")
                .httpEngine(trackingEngine)
                .build();

        JRestlyClient client = new JRestlyClient(moduleInfo);
        assertFalse(trackingEngine.closed.get());

        client.close();
        assertTrue(trackingEngine.closed.get(), "Pluggable HttpEngine must be closed when client is closed");
    }
}
