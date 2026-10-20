package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
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

    // ------------------------------------------------------------------
    // dispatch-tail log gates (ADR-0076): the per-hop and the retry-schedule
    // DEBUG lines must honor the logger's level — with DEBUG disabled the
    // message string must not be built at all (allocation on the
    // zero-garbage hot path, the same invariant logRequest/logResponse
    // already implement via their isLoggable pre-check)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("logPerHop builds no message when DEBUG is disabled (ADR-0076)")
    void logPerHopSkipsStringBuildWhenDebugDisabled() throws Exception {
        LevelGatedLogger off = new LevelGatedLogger(System.Logger.Level.TRACE);
        CountingEngine engine = new CountingEngine(WireResponse.of(200, "ok"));
        HttpTemplate t = sendTemplate(off, engine);

        t.send(request(HttpMethod.GET));

        assertEquals(1, engine.calls());
        assertEquals(0, off.count(), "a disabled DEBUG level must not capture any per-hop line");
    }

    @Test
    @DisplayName("logPerHop emits the per-hop line when DEBUG is enabled")
    void logPerHopEmitsPerHopLineWhenDebugEnabled() throws Exception {
        LevelGatedLogger on = new LevelGatedLogger(System.Logger.Level.DEBUG);
        CountingEngine engine = new CountingEngine(WireResponse.of(200, "ok"));
        HttpTemplate t = sendTemplate(on, engine);

        t.send(request(HttpMethod.GET));

        assertEquals(1, on.count());
        String line = on.last();
        assertTrue(line.startsWith("GET " + TARGET), line);
        assertTrue(line.contains("-> 200 in"), line);
        assertTrue(line.endsWith(" ms"), line);
    }

    @Test
    @DisplayName("the retry-schedule DEBUG line is skipped when DEBUG is disabled (ADR-0076)")
    void retryScheduleSkipsDebugLineWhenDebugDisabled() throws Exception {
        LevelGatedLogger off = new LevelGatedLogger(System.Logger.Level.TRACE);
        CountingEngine engine = new CountingEngine(
                WireResponse.of(503, "busy"), WireResponse.of(200, "ok"));
        HttpTemplate t = sendTemplate(off, engine);
        t.setUrl(TARGET.toString());
        t.setHttpMethod(HttpMethod.GET);
        t.setReturnType(String.class);
        t.setRetryPolicy(RetryPolicy.builder()
                .maxAttempts(2)
                .initialBackoff(Duration.ZERO)
                .build());

        // the full dispatch: attempt 1 (503) schedules the retry,
        // attempt 2 (200) completes the call
        String result = t.exchange();

        assertEquals("ok", result);
        assertEquals(2, engine.calls());
        assertEquals(0, off.count(), "a disabled DEBUG level must not capture any retry-schedule line");
    }

    @Test
    @DisplayName("the retry-schedule DEBUG line is built when DEBUG is enabled (ADR-0076)")
    void retryScheduleEmitsDebugLineWhenDebugEnabled() throws Exception {
        LevelGatedLogger on = new LevelGatedLogger(System.Logger.Level.DEBUG);
        CountingEngine engine = new CountingEngine(
                WireResponse.of(503, "busy"), WireResponse.of(200, "ok"));
        HttpTemplate t = sendTemplate(on, engine);
        t.setUrl(TARGET.toString());
        t.setHttpMethod(HttpMethod.GET);
        t.setReturnType(String.class);
        t.setRetryPolicy(RetryPolicy.builder()
                .maxAttempts(2)
                .initialBackoff(Duration.ZERO)
                .build());

        String result = t.exchange();

        assertEquals("ok", result);
        List<String> retryLines = on.messages().stream()
                .filter(line -> line.startsWith("Attempt 1/2 failed"))
                .toList();
        assertEquals(1, retryLines.size(), "the retry-schedule DEBUG line must be built exactly once");
        assertTrue(retryLines.get(0).endsWith("retrying in 0 ms"), retryLines.get(0));
    }

    @Test
    @DisplayName("the async retry-schedule DEBUG line is built when DEBUG is enabled (ADR-0076)")
    void asyncRetryScheduleEmitsDebugLineWhenDebugEnabled() throws Exception {
        LevelGatedLogger on = new LevelGatedLogger(System.Logger.Level.DEBUG);
        CountingEngine engine = new CountingEngine(
                WireResponse.of(503, "busy"), WireResponse.of(200, "ok"));
        HttpTemplate t = sendTemplate(on, engine);
        t.setUrl(TARGET.toString());
        t.setHttpMethod(HttpMethod.GET);
        t.setReturnType(String.class);
        t.setAsync(true);
        t.setRetryPolicy(RetryPolicy.builder()
                .maxAttempts(2)
                .initialBackoff(Duration.ZERO)
                .build());

        CompletableFuture<?> future = t.exchange();
        Object result = future.join();

        assertEquals("ok", result);
        List<String> retryLines = on.messages().stream()
                .filter(line -> line.startsWith("Attempt 1/2 failed"))
                .toList();
        assertEquals(1, retryLines.size(), "the retry-schedule DEBUG line must be built exactly once");
        assertTrue(retryLines.get(0).endsWith("retrying in 0 ms"), retryLines.get(0));
    }

    private static HttpTemplate sendTemplate(System.Logger logger, ru.jrestly.engine.HttpEngine engine) {
        HttpTemplate t = new HttpTemplate();
        t.setLogger(logger);
        t.setHttpEngine(engine);
        t.setEngineOptions(new ru.jrestly.engine.HttpEngineOptions(null, null, false, null, null));
        return t;
    }

    private static WireRequest request(HttpMethod method) {
        return new WireRequest(method, TARGET, List.of(), null, null, null, null);
    }

    // The template's per-hop / retry-schedule log call-sites
    static final class CountingEngine implements ru.jrestly.engine.HttpEngine {
        private final Queue<WireResponse> responses = new ArrayDeque<>();
        private int calls;

        CountingEngine(WireResponse... queued) {
            Collections.addAll(responses, queued);
        }

        @Override
        public WireResponse send(WireRequest request, ru.jrestly.engine.HttpEngineOptions options) {
            calls++;
            WireResponse next = responses.size() > 1 ? responses.poll() : responses.peek();
            if (next == null) {
                throw new IllegalStateException("no queued response");
            }
            return next;
        }

        @Override
        public CompletableFuture<WireResponse> sendAsync(WireRequest request, ru.jrestly.engine.HttpEngineOptions options) {
            return CompletableFuture.completedFuture(send(request, options));
        }

        int calls() {
            return calls;
        }
    }

    // CapturingLogger variant with a configurable level gate: isLoggable
    // honors the max level, so a disabled level must never see a record
    // (the gate itself — not the capture — is the behavior under test)
    static final class LevelGatedLogger implements System.Logger {
        private final Level maxLevel;
        private final List<String> messages = new CopyOnWriteArrayList<>();

        LevelGatedLogger(Level maxLevel) {
            this.maxLevel = maxLevel;
        }

        @Override
        public String getName() {
            return "jrestly-gated";
        }

        @Override
        public boolean isLoggable(Level level) {
            return level.compareTo(maxLevel) <= 0;
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

        List<String> messages() {
            return List.copyOf(messages);
        }

        String last() {
            assertFalse(messages.isEmpty(), "no log records captured");
            return messages.get(messages.size() - 1);
        }
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
