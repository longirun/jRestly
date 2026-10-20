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
