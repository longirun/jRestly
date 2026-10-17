package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The request-log gate ({@link HttpTemplate#logRequest(WireRequest)}),
 * ADR-0009 candidate 1: the body section is logged for POST, PATCH, DELETE
 * and PUT; GET/HEAD stay out deliberately (RFC 9110 leaves a GET body
 * semantically undefined).
 *
 * <p>{@code logRequest} is exercised directly through the public
 * {@code setLogger} seam with a capturing {@link System.Logger} — no wire
 * involved, the gate itself is the behavior under test.
 */
class HttpTemplateLoggingTest {

    private static final URI TARGET = URI.create("http://example.com/api/items/7");

    private final CapturingLogger logger = new CapturingLogger();
    private final HttpTemplate template;

    HttpTemplateLoggingTest() {
        template = new HttpTemplate();
        template.setLogger(logger);
    }

    @Test
    @DisplayName("PUT with a body logs the body")
    void putWithBodyLogsTheBody() {
        String body = "{\"id\":7,\"name\":\"replaced\"}";

        logRequest(HttpMethod.PUT, "application/json", body);

        String log = logger.last();
        assertTrue(log.startsWith("Request: PUT http://example.com/api/items/7"), log);
        assertTrue(log.contains(body), log);
    }

    @Test
    @DisplayName("PUT without a body logs the 'No request body' fallback")
    void putWithoutBodyLogsTheFallback() {
        logRequest(HttpMethod.PUT, null, null);

        assertTrue(logger.last().contains("No request body"), logger.last());
    }

    @Test
    @DisplayName("PUT multipart logs the parts listing and the send size")
    void putMultipartLogsPartsAndSize() {
        WireRequest request = new WireRequest(HttpMethod.PUT, TARGET, List.of(),
                "multipart/form-data; boundary=BOUNDARY", null,
                List.of(MultipartPart.text("file", "content")), "BOUNDARY");

        template.logRequest(request);

        String log = logger.last();
        assertTrue(log.contains("name=file"), log);
        assertTrue(log.contains("Sending size:"), log);
    }

    @Test
    @DisplayName("GET with a body keeps the scope: no body section at all")
    void getWithBodyHasNoBodySection() {
        String body = "{\"unexpected\":\"a get body\"}";

        logRequest(HttpMethod.GET, "application/json", body);

        String log = logger.last();
        assertTrue(log.startsWith("Request: GET http://example.com/api/items/7"), log);
        assertFalse(log.contains(body), log);
        assertFalse(log.contains("No request body"), log);
    }

    @Test
    @DisplayName("POST with a body logs the body")
    void postWithBodyLogsTheBody() {
        assertBodyLogged(HttpMethod.POST, "{\"id\":7}");
    }

    @Test
    @DisplayName("PATCH with a body logs the body")
    void patchWithBodyLogsTheBody() {
        assertBodyLogged(HttpMethod.PATCH, "{\"name\":\"patched\"}");
    }

    @Test
    @DisplayName("DELETE with a body logs the body")
    void deleteWithBodyLogsTheBody() {
        assertBodyLogged(HttpMethod.DELETE, "{\"id\":7}");
    }

    @Test
    @DisplayName("Streaming body logs the <streaming body> placeholder without invoking the supplier (ADR-0035 §3)")
    void streamingBodyLogsPlaceholderWithoutConsumingSupplier() {
        AtomicInteger invocations = new AtomicInteger();
        WireRequest request = new WireRequest(HttpMethod.POST, TARGET, List.of(),
                "application/octet-stream", null, null, null,
                () -> {
                    invocations.incrementAndGet();
                    return HttpRequest.BodyPublishers.ofString("live-stream");
                });

        template.logRequest(request);

        String log = logger.last();
        assertTrue(log.contains("<streaming body>"), log);
        assertFalse(log.contains("live-stream"), log);
        assertEquals(0, invocations.get(), "logRequest must not invoke the body publisher supplier");
    }

    private void assertBodyLogged(HttpMethod method, String body) {
        logRequest(method, "application/json", body);

        String log = logger.last();
        assertTrue(log.startsWith("Request: " + method + " http://example.com/api/items/7"), log);
        assertTrue(log.contains(body), log);
    }

    private void logRequest(HttpMethod method, String contentType, String body) {
        template.logRequest(new WireRequest(method, TARGET, List.of(), contentType,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8), null, null));
    }

    // JDK 21 (late revision): System.Logger is an interface — the default
    // log(Level, String, ...) methods funnel into the abstract
    // log(Level, ResourceBundle, String, Object.../Throwable) pair, which
    // this logger overrides to capture. A parameterless log call arrives
    // with a null varargs array.
    static final class CapturingLogger implements System.Logger {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        @Override
        public String getName() {
            return "jrestly-capture";
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            messages.add(params == null || params.length == 0 ? format : String.format(format, params));
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Throwable thrown) {
            messages.add(format + (thrown == null ? "" : " cause=" + thrown));
        }

        String last() {
            assertFalse(messages.isEmpty(), "no log records captured");
            return messages.get(messages.size() - 1);
        }
    }
}
