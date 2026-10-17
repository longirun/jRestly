package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.auth.TokenManager;
import ru.jrestly.cache.InMemoryHttpCache;
import ru.jrestly.codec.CodecRegistry;
import ru.jrestly.compression.CompressionAlgorithm;
import ru.jrestly.compression.ContentCompressor;
import ru.jrestly.engine.HttpEngine;
import ru.jrestly.engine.HttpEngineOptions;
import ru.jrestly.http.HttpMethod;
import ru.jrestly.http.RetryPolicy;
import ru.jrestly.http.WireRequest;
import ru.jrestly.http.WireResponse;
import ru.jrestly.interceptor.RequestInterceptor;
import ru.jrestly.interceptor.ResponseInterceptor;
import ru.jrestly.interceptor.ShortCircuitInterceptor;
import ru.jrestly.resilience.CircuitBreaker;
import ru.jrestly.resilience.CircuitBreakerRegistry;
import ru.jrestly.resilience.RateLimiter;
import ru.jrestly.resilience.RateLimiterRegistry;
import ru.jrestly.tracing.TracePropagator;

import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests verifying that the {@link JRestlyClient.Builder} facade correctly forwards
 * all configuration settings to {@link SimpleModuleInfo.Builder} and produces a consistent
 * {@link ModuleInfo} across all functional subsystems.
 */
@DisplayName("JRestlyClient.Builder Facade & Configuration Tests")
class JRestlyClientBuilderTest {

    @Test
    @DisplayName("JRestlyClient.Builder inMemoryCache() configures default and custom capacities")
    void inMemoryCacheConfiguration() {
        JRestlyClient defaultClient = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .inMemoryCache()
                .build();
        ModuleInfo defaultInfo = defaultClient.getModuleInfo();
        assertNotNull(defaultInfo.getHttpCache());
        assertTrue(defaultInfo.getHttpCache() instanceof InMemoryHttpCache);
        assertEquals(100, ((InMemoryHttpCache) defaultInfo.getHttpCache()).maxEntries());
        defaultClient.close();

        JRestlyClient customClient = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .inMemoryCache(500)
                .build();
        ModuleInfo customInfo = customClient.getModuleInfo();
        assertNotNull(customInfo.getHttpCache());
        assertTrue(customInfo.getHttpCache() instanceof InMemoryHttpCache);
        assertEquals(500, ((InMemoryHttpCache) customInfo.getHttpCache()).maxEntries());
        customClient.close();
    }

    @Test
    @DisplayName("JRestlyClient.Builder virtualThreads and executor configuration")
    void virtualThreadsAndExecutorConfiguration() {
        Executor customExecutor = ForkJoinPool.commonPool();

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .virtualThreads(true)
                .executor(customExecutor)
                .build();

        ModuleInfo info = client.getModuleInfo();
        assertTrue(info.isVirtualThreads());
        assertSame(customExecutor, info.getExecutor());
        client.close();

        JRestlyClient clientShortcut = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .useVirtualThreads()
                .build();
        assertTrue(clientShortcut.getModuleInfo().isVirtualThreads());
        clientShortcut.close();
    }

    @Test
    @DisplayName("JRestlyClient.Builder cookie management options")
    void cookieManagementConfiguration() {
        CookieHandler customHandler = new CookieManager();
        JRestlyClient client1 = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .cookieHandler(customHandler)
                .build();
        assertSame(customHandler, client1.getModuleInfo().getCookieHandler());
        client1.close();

        JRestlyClient client2 = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .cookiePolicy(CookiePolicy.ACCEPT_ALL)
                .build();
        assertNotNull(client2.getModuleInfo().getCookieHandler());
        client2.close();
    }

    @Test
    @DisplayName("JRestlyClient.Builder proxy and authenticator options")
    void proxyAndAuthenticatorConfiguration() {
        ProxySelector customProxy = ProxySelector.of(new InetSocketAddress("proxy.local", 8080));
        Authenticator customAuth = new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication("user", "pass".toCharArray());
            }
        };

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .proxy(customProxy)
                .authenticator(customAuth)
                .build();

        assertSame(customProxy, client.getModuleInfo().getProxySelector());
        assertSame(customAuth, client.getModuleInfo().getAuthenticator());
        client.close();

        JRestlyClient proxyHostPort = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .proxy("proxy.internal", 3128)
                .build();
        assertNotNull(proxyHostPort.getModuleInfo().getProxySelector());
        proxyHostPort.close();

        JRestlyClient noProxyClient = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .noProxy()
                .build();
        assertSame(HttpClient.Builder.NO_PROXY, noProxyClient.getModuleInfo().getProxySelector());
        noProxyClient.close();

        JRestlyClient credsClient = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .proxyCredentials("puser", "ppass")
                .credentials("uuser", "upass")
                .build();
        assertNotNull(credsClient.getModuleInfo().getAuthenticator());
        credsClient.close();
    }

    @Test
    @DisplayName("JRestlyClient.Builder request compression and custom content compressor")
    void requestCompressionConfiguration() {
        ContentCompressor dummyCompressor = new ContentCompressor() {
            @Override public String encoding() { return "custom"; }
            @Override public byte[] compress(byte[] uncompressed) { return uncompressed; }
            @Override public byte[] decompress(byte[] compressed) { return compressed; }
        };

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .requestCompression(CompressionAlgorithm.GZIP)
                .contentCompressor(dummyCompressor)
                .decompression(false)
                .build();

        ModuleInfo info = client.getModuleInfo();
        assertEquals(CompressionAlgorithm.GZIP, info.getRequestCompressionAlgorithm());
        assertEquals(0, info.getRequestCompressionThreshold());
        assertEquals(dummyCompressor, info.getContentCompressors().get("custom"));
        assertFalse(info.isDecompressionEnabled());
        client.close();

        JRestlyClient clientWithThreshold = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .requestCompression(CompressionAlgorithm.DEFLATE, 2048)
                .build();
        assertEquals(CompressionAlgorithm.DEFLATE, clientWithThreshold.getModuleInfo().getRequestCompressionAlgorithm());
        assertEquals(2048, clientWithThreshold.getModuleInfo().getRequestCompressionThreshold());
        clientWithThreshold.close();
    }

    @Test
    @DisplayName("JRestlyClient.Builder tracing and correlation ID options")
    void tracingAndCorrelationIdConfiguration() {
        TracePropagator customPropagator = context -> Map.of("X-Trace", "val");

        JRestlyClient client1 = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .tracePropagator(customPropagator)
                .build();
        assertSame(customPropagator, client1.getModuleInfo().getTracePropagator());
        client1.close();

        JRestlyClient client2 = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .correlationId("X-Trace-Id")
                .build();
        assertNotNull(client2.getModuleInfo().getTracePropagator());
        client2.close();

        JRestlyClient client3 = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .correlationId("X-Custom-Id", () -> "fixed-id")
                .build();
        assertNotNull(client3.getModuleInfo().getTracePropagator());
        client3.close();

        ThreadLocal<String> tl = new ThreadLocal<>();
        JRestlyClient client4 = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .correlationId(tl)
                .build();
        assertNotNull(client4.getModuleInfo().getTracePropagator());
        client4.close();

        JRestlyClient client5 = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .correlationId("X-TL-Id", tl, true)
                .build();
        assertNotNull(client5.getModuleInfo().getTracePropagator());
        client5.close();

        JRestlyClient client6 = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .w3cTracing()
                .build();
        assertNotNull(client6.getModuleInfo().getTracePropagator());
        client6.close();

        JRestlyClient client7 = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .w3cTracing(true)
                .build();
        assertNotNull(client7.getModuleInfo().getTracePropagator());
        client7.close();
    }

    @Test
    @DisplayName("JRestlyClient.Builder resilience: circuit breaker and rate limiter")
    void resilienceConfiguration() {
        CircuitBreaker cb = CircuitBreaker.of("test-cb");
        CircuitBreakerRegistry cbr = CircuitBreakerRegistry.ofDefaults();
        RateLimiter rl = RateLimiter.of("test-rl", ru.jrestly.resilience.RateLimiterConfig.ofDefaults());
        RateLimiterRegistry rlr = RateLimiterRegistry.ofDefaults();

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .circuitBreaker(cb)
                .circuitBreakerRegistry(cbr)
                .rateLimiter(rl)
                .rateLimiterRegistry(rlr)
                .build();

        ModuleInfo info = client.getModuleInfo();
        assertSame(cb, info.getCircuitBreaker());
        assertSame(cbr, info.getCircuitBreakerRegistry());
        assertSame(rl, info.getRateLimiter());
        assertSame(rlr, info.getRateLimiterRegistry());
        client.close();
    }

    @Test
    @DisplayName("JRestlyClient.Builder authentication helpers")
    void authHelpersConfiguration() {
        TokenManager tm = new TokenManager() {
            @Override public String getToken() { return "tok"; }
            @Override public void invalidate() {}
        };

        JRestlyClient c1 = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .tokenManager(tm)
                .retryOnUnauthorized(true)
                .build();
        assertSame(tm, c1.getModuleInfo().getTokenManager());
        assertTrue(c1.getModuleInfo().isRetryOnUnauthorized());
        c1.close();

        JRestlyClient cBearerStr = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .bearerAuth("static-token")
                .build();
        assertFalse(cBearerStr.getModuleInfo().getRequestInterceptors().isEmpty());
        cBearerStr.close();

        JRestlyClient cBearerSup = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .bearerAuth(() -> "dynamic-token")
                .build();
        assertFalse(cBearerSup.getModuleInfo().getRequestInterceptors().isEmpty());
        cBearerSup.close();

        JRestlyClient cBearerTm = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .bearerAuth(tm)
                .build();
        assertSame(tm, cBearerTm.getModuleInfo().getTokenManager());
        assertTrue(cBearerTm.getModuleInfo().isRetryOnUnauthorized());
        cBearerTm.close();

        JRestlyClient cBasicStr = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .basicAuth("user", "pass")
                .build();
        assertFalse(cBasicStr.getModuleInfo().getRequestInterceptors().isEmpty());
        cBasicStr.close();

        JRestlyClient cBasicSup = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .basicAuth(() -> "user", () -> "pass")
                .build();
        assertFalse(cBasicSup.getModuleInfo().getRequestInterceptors().isEmpty());
        cBasicSup.close();

        JRestlyClient cApiHeader = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .apiKeyHeader("X-API-Key", "secret")
                .build();
        assertFalse(cApiHeader.getModuleInfo().getRequestInterceptors().isEmpty());
        cApiHeader.close();

        JRestlyClient cApiHeaderSup = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .apiKeyHeader("X-API-Key", () -> "dynamic-secret")
                .build();
        assertFalse(cApiHeaderSup.getModuleInfo().getRequestInterceptors().isEmpty());
        cApiHeaderSup.close();

        JRestlyClient cApiQuery = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .apiKeyQuery("api_key", "secret")
                .build();
        assertFalse(cApiQuery.getModuleInfo().getRequestInterceptors().isEmpty());
        cApiQuery.close();

        JRestlyClient cApiQuerySup = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .apiKeyQuery("api_key", () -> "dynamic-secret")
                .build();
        assertFalse(cApiQuerySup.getModuleInfo().getRequestInterceptors().isEmpty());
        cApiQuerySup.close();
    }

    @Test
    @DisplayName("JRestlyClient.Builder short-circuit interceptors and mock helpers")
    void shortCircuitAndMockConfiguration() {
        ShortCircuitInterceptor customSci = (req, ctx) -> java.util.Optional.empty();
        WireResponse mockRes = WireResponse.builder().statusCode(200).body("mocked").build();

        JRestlyClient c1 = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .shortCircuit(customSci)
                .build();
        assertTrue(c1.getModuleInfo().getRequestInterceptors().contains(customSci));
        c1.close();

        JRestlyClient c2 = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .mock(mockRes)
                .build();
        assertEquals(1, c2.getModuleInfo().getRequestInterceptors().size());
        c2.close();

        JRestlyClient c3 = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .mock(req -> req.method() == HttpMethod.GET, mockRes)
                .build();
        assertEquals(1, c3.getModuleInfo().getRequestInterceptors().size());
        c3.close();

        JRestlyClient c4 = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .mock(req -> true, req -> mockRes)
                .build();
        assertEquals(1, c4.getModuleInfo().getRequestInterceptors().size());
        c4.close();
    }

    @Test
    @DisplayName("JRestlyClient.Builder timeouts, SSL, retry, codecs, and engine options")
    void variousBuilderOptions() {
        CodecRegistry registry = CodecRegistry.builder().build();
        HttpEngine customEngine = new HttpEngine() {
            @Override public WireResponse send(WireRequest request, HttpEngineOptions options) { return null; }
            @Override public CompletableFuture<WireResponse> sendAsync(WireRequest request, HttpEngineOptions options) { return null; }
        };
        RetryPolicy retryPolicy = RetryPolicy.defaults();
        RequestInterceptor reqInt = (req, ctx) -> req;
        ResponseInterceptor resInt = ctx -> {};

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("https://api.example.com")
                .codecs(registry)
                .connectTimeout(3000)
                .socketTimeout(15000)
                .httpVersion(HttpClient.Version.HTTP_1_1)
                .validateControllers(false)
                .retry(retryPolicy)
                .addRequestInterceptor(reqInt)
                .addResponseInterceptor(resInt)
                .httpEngine(customEngine)
                .build();

        ModuleInfo info = client.getModuleInfo();
        assertSame(registry, info.getCodecRegistry());
        assertEquals(3000, info.getConnectTimeout());
        assertEquals(15000, info.getSocketTimeout());
        assertEquals(HttpClient.Version.HTTP_1_1, info.getHttpVersion());
        assertFalse(info.isValidateControllers());
        assertSame(retryPolicy, info.getRetryPolicy());
        assertSame(customEngine, info.getHttpEngine());
        client.close();
    }
}
