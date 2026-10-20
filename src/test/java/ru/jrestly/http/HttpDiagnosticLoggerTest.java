package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HttpDiagnosticLogger}.
 */
class HttpDiagnosticLoggerTest {

    private static final URI TARGET = URI.create("http://example.com/api/test");

    @Test
    @DisplayName("private constructor cannot be instantiated normally and can be invoked via reflection")
    void privateConstructor() throws Exception {
        Constructor<HttpDiagnosticLogger> constructor = HttpDiagnosticLogger.class.getDeclaredConstructor();
        assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        constructor.setAccessible(true);
        HttpDiagnosticLogger instance = constructor.newInstance();
        assertNotNull(instance);
    }

    @Test
    @DisplayName("null logger, disabled log level or null request/response are no-op without exceptions")
    void nullAndDisabledGates() {
        TestLogger loggerOff = new TestLogger(System.Logger.Level.OFF);
        WireRequest request = new WireRequest(HttpMethod.GET, TARGET, List.of(), null, null, null, null);
        WireResponse response = WireResponse.of(200, "ok");

        // logRequest guards
        assertDoesNotThrow(() -> HttpDiagnosticLogger.logRequest(null, request));
        assertDoesNotThrow(() -> HttpDiagnosticLogger.logRequest(loggerOff, request));
        assertDoesNotThrow(() -> HttpDiagnosticLogger.logRequest(new TestLogger(System.Logger.Level.ALL), null));
        assertEquals(0, loggerOff.count());

        // logResponse guards
        assertDoesNotThrow(() -> HttpDiagnosticLogger.logResponse(null, response, 10L));
        assertDoesNotThrow(() -> HttpDiagnosticLogger.logResponse(loggerOff, response, 10L));
        assertDoesNotThrow(() -> HttpDiagnosticLogger.logResponse(new TestLogger(System.Logger.Level.ALL), null, 10L));
        assertEquals(0, loggerOff.count());

        // logPerHop guards
        assertDoesNotThrow(() -> HttpDiagnosticLogger.logPerHop(null, request, 200, System.nanoTime()));
        assertDoesNotThrow(() -> HttpDiagnosticLogger.logPerHop(loggerOff, request, 200, System.nanoTime()));
        assertDoesNotThrow(() -> HttpDiagnosticLogger.logPerHop(new TestLogger(System.Logger.Level.ALL), null, 200, System.nanoTime()));
        assertEquals(0, loggerOff.count());

        // logRetry guards
        assertDoesNotThrow(() -> HttpDiagnosticLogger.logRetry(null, 1, 3, "fail", 50L));
        assertDoesNotThrow(() -> HttpDiagnosticLogger.logRetry(loggerOff, 1, 3, "fail", 50L));
        assertEquals(0, loggerOff.count());
    }

    @Test
    @DisplayName("logRequest with GET does not log body even if payload is present")
    void getExcludesBody() {
        TestLogger logger = new TestLogger(System.Logger.Level.INFO);
        byte[] payload = "{\"data\":\"secret_payload\"}".getBytes(StandardCharsets.UTF_8);

        WireRequest getRequest = new WireRequest(HttpMethod.GET, TARGET,
                List.of(new Header("Accept", "application/json")),
                "application/json", payload, null, null);
        HttpDiagnosticLogger.logRequest(logger, getRequest);

        String getLog = logger.last();
        assertTrue(getLog.startsWith("Request: GET " + TARGET));
        assertTrue(getLog.contains("name=Accept, value=application/json"));
        assertFalse(getLog.contains("secret_payload"));
        assertFalse(getLog.contains("No request body"));
    }

    @Test
    @DisplayName("logRequest with null headers formats request line without headers")
    void logRequestNullHeaders() {
        TestLogger logger = new TestLogger(System.Logger.Level.INFO);
        WireRequest request = new WireRequest(HttpMethod.GET, TARGET, null, null, null, null, null);
        HttpDiagnosticLogger.logRequest(logger, request);

        String log = logger.last();
        assertEquals("Request: GET " + TARGET + "\n", log);
    }

    @Test
    @DisplayName("logRequest for POST/PATCH/DELETE/PUT logs byte array body")
    void logRequestBodyMethods() {
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.PUT)) {
            TestLogger logger = new TestLogger(System.Logger.Level.INFO);
            byte[] body = ("body-for-" + method).getBytes(StandardCharsets.UTF_8);
            WireRequest request = new WireRequest(method, TARGET, List.of(), "text/plain", body, null, null);

            HttpDiagnosticLogger.logRequest(logger, request);

            String log = logger.last();
            assertTrue(log.startsWith("Request: " + method + " " + TARGET));
            assertTrue(log.contains("body-for-" + method));
        }
    }

    @Test
    @DisplayName("logRequest with no body prints 'No request body'")
    void logRequestNoBody() {
        TestLogger logger = new TestLogger(System.Logger.Level.INFO);
        WireRequest request = new WireRequest(HttpMethod.POST, TARGET, List.of(), "application/json", null, null, null);

        HttpDiagnosticLogger.logRequest(logger, request);

        String log = logger.last();
        assertTrue(log.contains("No request body"));
    }

    @Test
    @DisplayName("logRequest with streaming publisher supplier prints '<streaming body>' and does not invoke supplier")
    void logRequestStreamingBody() {
        TestLogger logger = new TestLogger(System.Logger.Level.INFO);
        AtomicInteger invocations = new AtomicInteger();
        WireRequest request = new WireRequest(HttpMethod.POST, TARGET, List.of(),
                "application/octet-stream", null, null, null,
                () -> {
                    invocations.incrementAndGet();
                    return HttpRequest.BodyPublishers.ofString("streaming-content");
                });

        HttpDiagnosticLogger.logRequest(logger, request);

        String log = logger.last();
        assertTrue(log.contains("<streaming body>"));
        assertFalse(log.contains("streaming-content"));
        assertEquals(0, invocations.get());
    }

    @Test
    @DisplayName("logRequest multipart with null parts outputs size 0")
    void logRequestMultipartNullParts() {
        TestLogger logger = new TestLogger(System.Logger.Level.INFO);
        WireRequest request = new WireRequest(HttpMethod.POST, TARGET, List.of(),
                "multipart/form-data; boundary=BND", null, null, "BND");

        HttpDiagnosticLogger.logRequest(logger, request);

        String log = logger.last();
        assertTrue(log.contains("Sending size: 0"));
    }

    @Test
    @DisplayName("logRequest multipart with parts (with filename and without filename) logs parts and size")
    void logRequestMultipartWithParts() {
        TestLogger logger = new TestLogger(System.Logger.Level.INFO);
        MultipartPart textPart = MultipartPart.text("fieldName", "text-value");
        MultipartPart filePart = MultipartPart.file("fileUpload", "test.txt", "file-content".getBytes(StandardCharsets.UTF_8));

        WireRequest request = new WireRequest(HttpMethod.POST, TARGET, List.of(),
                "multipart/form-data; boundary=BOUND123", null,
                List.of(textPart, filePart), "BOUND123");

        HttpDiagnosticLogger.logRequest(logger, request);

        String log = logger.last();
        assertTrue(log.contains("name=fieldName"));
        assertTrue(log.contains("name=fileUpload, filename=test.txt"));
        assertTrue(log.contains("Sending size: "));
        assertFalse(log.contains("unknown (chunked)"));
    }

    @Test
    @DisplayName("logRequest multipart with chunked stream part logs 'unknown (chunked)'")
    void logRequestMultipartChunked() {
        TestLogger logger = new TestLogger(System.Logger.Level.INFO);
        Supplier<InputStream> streamSupplier = () -> new ByteArrayInputStream(new byte[]{1, 2, 3});
        MultipartPart chunkedPart = MultipartPart.file("streamField", "stream.bin", streamSupplier);

        WireRequest request = new WireRequest(HttpMethod.POST, TARGET, List.of(),
                "multipart/form-data; boundary=BND999", null,
                List.of(chunkedPart), "BND999");

        HttpDiagnosticLogger.logRequest(logger, request);

        String log = logger.last();
        assertTrue(log.contains("Sending size: unknown (chunked)"));
    }

    @Test
    @DisplayName("multipartSendSize handles null request, null parts, known size and unknown chunked size")
    void multipartSendSizeHelper() {
        assertEquals("0", HttpDiagnosticLogger.multipartSendSize(null));

        WireRequest reqNullParts = new WireRequest(HttpMethod.POST, TARGET, List.of(), null, null, null, null);
        assertEquals("0", HttpDiagnosticLogger.multipartSendSize(reqNullParts));

        WireRequest reqKnown = new WireRequest(HttpMethod.POST, TARGET, List.of(),
                "multipart/form-data; boundary=BND", null,
                List.of(MultipartPart.text("a", "b")), "BND");
        assertNotEquals("0", HttpDiagnosticLogger.multipartSendSize(reqKnown));
        assertNotEquals("unknown (chunked)", HttpDiagnosticLogger.multipartSendSize(reqKnown));

        Supplier<InputStream> streamSupplier = () -> new ByteArrayInputStream(new byte[10]);
        WireRequest reqChunked = new WireRequest(HttpMethod.POST, TARGET, List.of(),
                "multipart/form-data; boundary=BND", null,
                List.of(MultipartPart.file("s", "name.bin", streamSupplier)), "BND");
        assertEquals("unknown (chunked)", HttpDiagnosticLogger.multipartSendSize(reqChunked));
    }

    @Test
    @DisplayName("logResponse formats HTTP/1.1 and HTTP/2 versions and elapsed time")
    void logResponseFormatsVersionAndHeaders() {
        TestLogger logger = new TestLogger(System.Logger.Level.INFO);

        WireResponse resHttp1 = new WireResponse(200,
                List.of(new Header("Content-Type", "application/json")),
                new byte[0], null, HttpClient.Version.HTTP_1_1);
        HttpDiagnosticLogger.logResponse(logger, resHttp1, 42L);

        String log1 = logger.last();
        assertTrue(log1.startsWith("Response: HTTP/1.1 200 in 42 ms\nHeader[name=Content-Type, value=application/json]"), log1);

        WireResponse resHttp2 = new WireResponse(204,
                List.of(), new byte[0], null, HttpClient.Version.HTTP_2);
        HttpDiagnosticLogger.logResponse(logger, resHttp2, 105L);

        String log2 = logger.last();
        assertTrue(log2.startsWith("Response: HTTP/2 204 in 105 ms"));
    }

    @Test
    @DisplayName("logPerHop logs method, uri, statusCode and elapsed ms when DEBUG is enabled")
    void logPerHopFormatsHopTiming() {
        TestLogger logger = new TestLogger(System.Logger.Level.DEBUG);
        WireRequest request = new WireRequest(HttpMethod.GET, TARGET, List.of(), null, null, null, null);
        long startNanos = System.nanoTime() - 5_000_000L; // ~5 ms ago

        HttpDiagnosticLogger.logPerHop(logger, request, 302, startNanos);

        String log = logger.last();
        assertTrue(log.startsWith("GET " + TARGET + " -> 302 in "));
        assertTrue(log.endsWith(" ms"));
    }

    @Test
    @DisplayName("logRetry logs attempt, maxAttempts, failure and delayMillis when DEBUG is enabled")
    void logRetryFormatsMessage() {
        TestLogger logger = new TestLogger(System.Logger.Level.DEBUG);

        HttpDiagnosticLogger.logRetry(logger, 2, 5, "Connection reset", 1500L);

        String log = logger.last();
        assertEquals("Attempt 2/5 failed: Connection reset — retrying in 1500 ms", log);
    }

    @Test
    @DisplayName("logRequest sanitizes Authorization, Cookie, query parameters and sensitive body fields (ADR-0095)")
    void logRequestSanitizesSensitiveData() {
        TestLogger logger = new TestLogger(System.Logger.Level.INFO);
        URI sensitiveUri = URI.create("http://example.com/api?token=sec_secret_123&page=2");
        byte[] sensitiveBody = "{\"name\":\"alice\",\"password\":\"super_secret_pw\"}".getBytes(StandardCharsets.UTF_8);

        WireRequest req = new WireRequest(
                HttpMethod.POST,
                sensitiveUri,
                List.of(
                        new Header("Authorization", "Bearer jwt_token_value"),
                        new Header("Cookie", "session_id=abcdef123"),
                        new Header("Accept", "application/json")
                ),
                "application/json",
                sensitiveBody,
                null,
                null
        );

        HttpDiagnosticLogger.logRequest(logger, req);

        String log = logger.last();
        assertTrue(log.contains("token=[REDACTED]&page=2"));
        assertFalse(log.contains("sec_secret_123"));

        assertTrue(log.contains("Header[name=Authorization, value=Bearer [REDACTED]]"));
        assertFalse(log.contains("jwt_token_value"));

        assertTrue(log.contains("Header[name=Cookie, value=[REDACTED]]"));
        assertFalse(log.contains("abcdef123"));

        assertTrue(log.contains("Header[name=Accept, value=application/json]"));

        assertTrue(log.contains("\"password\":\"[REDACTED]\""));
        assertFalse(log.contains("super_secret_pw"));
    }

    @Test
    @DisplayName("logResponse and logPerHop sanitize Set-Cookie and sensitive query params (ADR-0095)")
    void logResponseAndPerHopSanitize() {
        TestLogger logger = new TestLogger(System.Logger.Level.ALL);

        WireResponse resp = new WireResponse(
                200,
                List.of(
                        new Header("Set-Cookie", "session=top_secret_cookie; HttpOnly"),
                        new Header("Content-Type", "application/json")
                ),
                new byte[0],
                null,
                HttpClient.Version.HTTP_2
        );

        HttpDiagnosticLogger.logResponse(logger, resp, 15L);
        String respLog = logger.last();
        assertTrue(respLog.contains("Header[name=Set-Cookie, value=[REDACTED]]"));
        assertFalse(respLog.contains("top_secret_cookie"));
        assertTrue(respLog.contains("Header[name=Content-Type, value=application/json]"));

        WireRequest hopReq = new WireRequest(
                HttpMethod.GET,
                URI.create("http://example.com/hop?secret=xyz456"),
                List.of(), null, null, null, null
        );
        HttpDiagnosticLogger.logPerHop(logger, hopReq, 302, System.nanoTime() - 1_000_000L);
        String hopLog = logger.last();
        assertTrue(hopLog.contains("GET http://example.com/hop?secret=[REDACTED] -> 302"));
        assertFalse(hopLog.contains("xyz456"));
    }

    @Test
    @DisplayName("traceId correlation prefix is injected into request, response, hop, and retry logs (ADR-0096)")
    void traceIdCorrelationPrefix() {
        TestLogger logger = new TestLogger(System.Logger.Level.ALL);
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

        WireRequest req = new WireRequest(
                HttpMethod.GET,
                TARGET,
                List.of(new Header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")),
                null, null, null, null
        );

        // Auto extraction from request
        HttpDiagnosticLogger.logRequest(logger, req);
        assertTrue(logger.last().startsWith("Request: [4bf92f3577b34da6a3ce929d0e0e4736] GET"));

        // Explicit traceId override
        HttpDiagnosticLogger.logRequest(logger, req, "custom-trace-999", true);
        assertTrue(logger.last().startsWith("Request: [custom-trace-999] GET"));

        // Response with traceId
        WireResponse resp = WireResponse.of(200, "ok");
        HttpDiagnosticLogger.logResponse(logger, resp, 42L, traceId, true);
        assertTrue(logger.last().startsWith("Response: [4bf92f3577b34da6a3ce929d0e0e4736] HTTP/1.1 200 in 42 ms"));

        // Per hop with traceId
        HttpDiagnosticLogger.logPerHop(logger, req, 200, System.nanoTime() - 1_000_000L, traceId, true);
        assertTrue(logger.last().startsWith("[4bf92f3577b34da6a3ce929d0e0e4736] GET"));

        // Retry with traceId
        HttpDiagnosticLogger.logRetry(logger, 1, 3, "timeout", 100L, traceId);
        assertTrue(logger.last().startsWith("[4bf92f3577b34da6a3ce929d0e0e4736] Attempt 1/3 failed: timeout"));

        // Blank traceId is treated as absent
        HttpDiagnosticLogger.logRequest(logger, req, "   ", true);
        assertFalse(logger.last().contains("[   ]"));
        HttpDiagnosticLogger.logResponse(logger, resp, 10L, "   ", true);
        assertFalse(logger.last().contains("[   ]"));
        HttpDiagnosticLogger.logPerHop(logger, req, 200, System.nanoTime(), "   ", true);
        assertFalse(logger.last().contains("[   ]"));
        HttpDiagnosticLogger.logRetry(logger, 1, 3, "timeout", 50L, "   ");
        assertFalse(logger.last().contains("[   ]"));

        // Null URI when sanitize=false
        WireRequest nullUriReq = new WireRequest(HttpMethod.GET, null, List.of(), null, null, null, null);
        HttpDiagnosticLogger.logRequest(logger, nullUriReq, null, false);
        HttpDiagnosticLogger.logPerHop(logger, nullUriReq, 200, System.nanoTime(), null, false);

        // Non-null URI when sanitize=false
        HttpDiagnosticLogger.logPerHop(logger, req, 200, System.nanoTime(), null, false);

        // Null contentType with non-null body
        WireRequest nullCtReq = new WireRequest(HttpMethod.POST, TARGET, List.of(), null, "hello".getBytes(StandardCharsets.UTF_8), null, null);
        HttpDiagnosticLogger.logRequest(logger, nullCtReq, null, true);
    }

    @Test
    @DisplayName("disabled sanitization (sanitize=false) outputs raw credentials, tokens, query params, and body")
    void disabledSanitizationRawOutput() {
        TestLogger logger = new TestLogger(System.Logger.Level.ALL);

        WireRequest req = new WireRequest(
                HttpMethod.POST,
                URI.create("http://example.com/login?token=raw_token_value&phone=+79991234567"),
                List.of(
                        new Header("Authorization", "Bearer raw_jwt_secret"),
                        new Header("Cookie", "session=raw_session_id")
                ),
                "application/json",
                "{\"password\":\"raw_secret_password\"}".getBytes(StandardCharsets.UTF_8),
                null, null
        );

        // sanitize = false
        HttpDiagnosticLogger.logRequest(logger, req, "trace-raw", false);
        String reqLog = logger.last();
        assertTrue(reqLog.contains("token=raw_token_value"));
        assertTrue(reqLog.contains("phone=+79991234567"));
        assertTrue(reqLog.contains("Authorization: Bearer raw_jwt_secret") || reqLog.contains("value=Bearer raw_jwt_secret"));
        assertTrue(reqLog.contains("Cookie: session=raw_session_id") || reqLog.contains("value=session=raw_session_id"));
        assertTrue(reqLog.contains("\"password\":\"raw_secret_password\""));
        assertFalse(reqLog.contains("[REDACTED]"));

        WireResponse resp = new WireResponse(
                200,
                List.of(new Header("Set-Cookie", "session=super_secret_cookie")),
                new byte[0], null, HttpClient.Version.HTTP_1_1
        );
        HttpDiagnosticLogger.logResponse(logger, resp, 10L, "trace-raw", false);
        String respLog = logger.last();
        assertTrue(respLog.contains("session=super_secret_cookie"));
        assertFalse(respLog.contains("[REDACTED]"));
    }

    @Test
    @DisplayName("isSanitizingEnabled toggles via system property")
    void isSanitizingEnabledProperty() {
        try {
            System.setProperty("jrestly.logging.sanitize", "false");
            assertFalse(HttpDiagnosticLogger.isSanitizingEnabled());
            System.setProperty("jrestly.logging.sanitize", "true");
            assertTrue(HttpDiagnosticLogger.isSanitizingEnabled());
        } finally {
            System.clearProperty("jrestly.logging.sanitize");
        }
    }

    static final class TestLogger implements System.Logger {
        private final Level minLevel;
        private final List<String> messages = new CopyOnWriteArrayList<>();

        TestLogger(Level minLevel) {
            this.minLevel = minLevel;
        }

        @Override
        public String getName() {
            return "test-diagnostic-logger";
        }

        @Override
        public boolean isLoggable(Level level) {
            return level.getSeverity() >= minLevel.getSeverity();
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            messages.add(params == null || params.length == 0 ? format : String.format(format, params));
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Throwable thrown) {
            messages.add(format + (thrown == null ? "" : " cause=" + thrown));
        }

        int count() {
            return messages.size();
        }

        String last() {
            assertFalse(messages.isEmpty(), "no messages captured");
            return messages.get(messages.size() - 1);
        }
    }
}
