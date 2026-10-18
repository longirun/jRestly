package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.codec.CodecRegistry;
import ru.jrestly.codec.PayloadCodec;
import ru.jrestly.problem.ProblemDetail;
import ru.jrestly.problem.ProblemDetailException;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the Problem Details (RFC 9457) resolution cluster of
 * {@code HttpTemplate} (ADR-0064): the media-type override
 * ({@code isProblemJsonContentType}), the "likely problem" shape heuristic
 * ({@code isLikelyProblemDetail}) and the two-stage resolver
 * ({@code resolveProblemDetail}) that tries the response codec first and
 * falls back to the built-in zero-dependency parser.
 *
 * <p>No network: the three helpers are package-private on purpose, and the
 * codec is a stub installed through the package-private
 * {@code setCodecRegistry} seam so every branch (null/empty/override/
 * heuristic/codec-failure) is pinned directly.
 */
class HttpTemplateProblemDetailResolutionTest {

    private static final List<Header> PROBLEM_JSON =
            List.of(new Header("Content-Type", "application/problem+json; charset=UTF-8"));
    private static final List<Header> PROBLEM_XML =
            List.of(new Header("Content-Type", "application/problem+xml"));
    private static final List<Header> PLAIN = List.of(new Header("Content-Type", "text/plain"));

    private final StubCodec codec = new StubCodec();
    private final HttpTemplate template = new HttpTemplate();

    HttpTemplateProblemDetailResolutionTest() {
        template.setCodecRegistry(CodecRegistry.builder().defaultCodec(codec).build());
    }

    // ---- isProblemJsonContentType ----

    @Test
    @DisplayName("media type: null and empty header lists are not problem responses")
    void nullAndEmptyHeadersAreNotProblemContentType() {
        assertFalse(HttpTemplate.isProblemJsonContentType(null));
        assertFalse(HttpTemplate.isProblemJsonContentType(List.of()));
    }

    @Test
    @DisplayName("media type: missing or null-valued Content-Type is not a problem response")
    void missingOrNullValuedContentTypeIsNotProblemContentType() {
        assertFalse(HttpTemplate.isProblemJsonContentType(List.of(new Header("Accept", "application/json"))));
        assertFalse(HttpTemplate.isProblemJsonContentType(List.of(new Header("Content-Type", null))));
    }

    @Test
    @DisplayName("media type: problem+json and problem+xml are recognized case-insensitively")
    void problemMediaTypesAreRecognized() {
        assertTrue(HttpTemplate.isProblemJsonContentType(PROBLEM_JSON));
        assertTrue(HttpTemplate.isProblemJsonContentType(PROBLEM_XML));
        assertTrue(HttpTemplate.isProblemJsonContentType(List.of(new Header("content-type", "Application/Problem+Json"))));
    }

    @Test
    @DisplayName("media type: a non-problem Content-Type short-circuits to false")
    void nonProblemContentTypeIsFalse() {
        assertFalse(HttpTemplate.isProblemJsonContentType(PLAIN));
        assertFalse(HttpTemplate.isProblemJsonContentType(List.of(new Header("Content-Type", "application/json"))));
    }

    // ---- isLikelyProblemDetail ----

    @Test
    @DisplayName("heuristic: null and an all-defaults document are not likely problems")
    void nullAndDefaultDocumentsAreNotLikely() {
        assertFalse(HttpTemplate.isLikelyProblemDetail(null));
        assertFalse(HttpTemplate.isLikelyProblemDetail(new ProblemDetail()));
    }

    @Test
    @DisplayName("heuristic: a positive status or a non-default type is enough")
    void statusOrCustomTypeIsLikely() {
        ProblemDetail statusOnly = new ProblemDetail();
        statusOnly.setStatus(503);
        assertTrue(HttpTemplate.isLikelyProblemDetail(statusOnly));

        ProblemDetail typeOnly = new ProblemDetail();
        typeOnly.setType("https://example.com/probs/out-of-credit");
        assertTrue(HttpTemplate.isLikelyProblemDetail(typeOnly));
    }

    @Test
    @DisplayName("heuristic: a non-blank title or detail is enough; a blank one is not")
    void nonBlankTitleOrDetailIsLikely() {
        ProblemDetail titled = new ProblemDetail();
        titled.setTitle("Out of credit");
        assertTrue(HttpTemplate.isLikelyProblemDetail(titled));

        ProblemDetail detailed = new ProblemDetail();
        detailed.setDetail("The card has no remaining balance.");
        assertTrue(HttpTemplate.isLikelyProblemDetail(detailed));

        ProblemDetail blankTitled = new ProblemDetail();
        blankTitled.setTitle("   ");
        assertFalse(HttpTemplate.isLikelyProblemDetail(blankTitled));

        ProblemDetail blankDetailed = new ProblemDetail();
        blankDetailed.setDetail("\t");
        assertFalse(HttpTemplate.isLikelyProblemDetail(blankDetailed));
    }

    @Test
    @DisplayName("heuristic: an instance URI alone is enough")
    void instanceUriIsLikely() {
        ProblemDetail pd = new ProblemDetail();
        pd.setInstance(URI.create("https://example.com/instances/42"));
        assertTrue(HttpTemplate.isLikelyProblemDetail(pd));
    }

    // ---- resolveProblemDetail ----

    @Test
    @DisplayName("resolver: null, blank and non-object bodies resolve to null")
    void nonObjectBodiesResolveToNull() {
        assertNull(template.resolveProblemDetail(500, null, PROBLEM_JSON));
        assertNull(template.resolveProblemDetail(500, "   ", PROBLEM_JSON));
        assertNull(template.resolveProblemDetail(500, "not json at all", PROBLEM_JSON));
        assertNull(template.resolveProblemDetail(500, "{\"title\":\"unterminated\"", PROBLEM_JSON));
    }

    @Test
    @DisplayName("resolver: a problem media type accepts the codec document, keeping its own status")
    void problemMediaTypeAcceptsCodecDocument() {
        ProblemDetail fromCodec = new ProblemDetail();
        fromCodec.setStatus(409);
        fromCodec.setTitle("Conflict");
        codec.result = fromCodec;

        ProblemDetail resolved = template.resolveProblemDetail(503, "{\"title\":\"Conflict\"}", PROBLEM_JSON);

        assertSame(fromCodec, resolved);
        assertEquals(409, resolved.getStatus());
        assertEquals(1, codec.calls);
    }

    @Test
    @DisplayName("resolver: an unrecognized media type needs the heuristic; a matching shape survives with the wire status")
    void unrecognizedMediaTypeUsesHeuristicAndFillsStatus() {
        ProblemDetail fromCodec = new ProblemDetail();
        fromCodec.setTitle("Out of credit");

        codec.result = fromCodec;
        ProblemDetail resolved = template.resolveProblemDetail(402, "{\"title\":\"Out of credit\"}", PLAIN);

        assertSame(fromCodec, resolved);
        assertEquals(402, resolved.getStatus());
    }

    @Test
    @DisplayName("resolver: an unrecognized media type and an all-defaults document resolve to null")
    void unrecognizedMediaTypeAndDefaultDocumentResolveToNull() {
        codec.result = new ProblemDetail();
        assertNull(template.resolveProblemDetail(500, "{}", PLAIN));
    }

    @Test
    @DisplayName("resolver: a failing codec falls back to the zero-dependency parser")
    void failingCodecFallsBackToBuiltInParser() {
        codec.failure = new IllegalStateException("codec cannot bind ProblemDetail");

        ProblemDetail resolved = template.resolveProblemDetail(503, "{\"title\":\"Service Unavailable\"}", PROBLEM_JSON);

        assertEquals("Service Unavailable", resolved.getTitle());
        assertEquals(503, resolved.getStatus());
    }

    @Test
    @DisplayName("resolver: a failing codec with an all-defaults body resolves to null")
    void failingCodecAndDefaultBodyResolveToNull() {
        codec.failure = new IllegalStateException("boom");
        assertNull(template.resolveProblemDetail(500, "{}", PLAIN));
    }

    @Test
    @DisplayName("resolver: a problem media type accepts an all-defaults codec document and fills the wire status")
    void problemMediaTypeAcceptsDefaultsAndFillsStatus() {
        codec.result = new ProblemDetail();
        ProblemDetail resolved = template.resolveProblemDetail(503, "{}", PROBLEM_JSON);

        assertEquals(503, resolved.getStatus());
    }

    @Test
    @DisplayName("resolver: a problem media type lets the built-in fallback accept an all-defaults body")
    void problemMediaTypeAcceptsFallbackDefaults() {
        codec.failure = new IllegalStateException("boom");
        ProblemDetail resolved = template.resolveProblemDetail(503, "{}", PROBLEM_XML);

        assertEquals(503, resolved.getStatus());
    }

    // ---- handleOnError ----

    @Test
    @DisplayName("handleOnError: no body and a plain DTO target throw a HandledException with null details")
    void noBodyPlainTargetThrowsHandledException() {
        template.setOnErrorParser(parserFor(503, TestDto.class));

        HandledException thrown = assertThrows(HandledException.class,
                () -> template.handleOnError(503, null, List.of(), StandardCharsets.UTF_8));

        assertNull(thrown.getDetails());
        assertEquals(0, codec.calls);
    }

    @Test
    @DisplayName("handleOnError: a Void target never deserializes the body")
    void voidTargetNeverDeserializes() {
        template.setOnErrorParser(parserFor(503, Void.class));

        assertThrows(HandledException.class,
                () -> template.handleOnError(503, "{\"a\":1}", List.of(), StandardCharsets.UTF_8));

        assertEquals(0, codec.calls);
    }

    @Test
    @DisplayName("handleOnError: a decoded RuntimeException is rethrown as-is")
    void decodedRuntimeExceptionIsRethrown() {
        IllegalStateException decoded = new IllegalStateException("boom");
        codec.result = decoded;
        template.setOnErrorParser(parserFor(503, RuntimeException.class));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> template.handleOnError(503, "{}", List.of(), StandardCharsets.UTF_8));

        assertSame(decoded, thrown);
    }

    @Test
    @DisplayName("handleOnError: a decoded Error is rethrown as-is")
    void decodedErrorIsRethrown() {
        TestError decoded = new TestError("codec exploded");
        codec.result = decoded;
        template.setOnErrorParser(parserFor(503, Error.class));

        TestError thrown = assertThrows(TestError.class,
                () -> template.handleOnError(503, "{}", List.of(), StandardCharsets.UTF_8));

        assertSame(decoded, thrown);
    }

    @Test
    @DisplayName("handleOnError: a decoded checked Throwable is wrapped in RuntimeException")
    void decodedCheckedThrowableIsWrapped() {
        IOException decoded = new IOException("io failure");
        codec.result = decoded;
        template.setOnErrorParser(parserFor(503, Throwable.class));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> template.handleOnError(503, "{}", List.of(), StandardCharsets.UTF_8));

        assertSame(decoded, thrown.getCause());
    }

    @Test
    @DisplayName("handleOnError: a bodyless RuntimeException target is instantiated from the wire status")
    void bodylessRuntimeExceptionTargetIsInstantiated() {
        template.setOnErrorParser(parserFor(503, RuntimeException.class));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> template.handleOnError(503, null, List.of(), StandardCharsets.UTF_8));

        assertEquals("HTTP 503", thrown.getMessage());
    }

    @Test
    @DisplayName("handleOnError: a bodyless Error target is instantiated and rethrown")
    void bodylessErrorTargetIsInstantiated() {
        template.setOnErrorParser(parserFor(503, TestError.class));

        TestError thrown = assertThrows(TestError.class,
                () -> template.handleOnError(503, null, List.of(), StandardCharsets.UTF_8));

        assertEquals("HTTP 503", thrown.getMessage());
    }

    @Test
    @DisplayName("handleOnError: a bodyless checked target is instantiated then wrapped")
    void bodylessCheckedTargetIsWrapped() {
        template.setOnErrorParser(parserFor(503, TestCheckedException.class));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> template.handleOnError(503, null, List.of(), StandardCharsets.UTF_8));

        assertInstanceOf(TestCheckedException.class, thrown.getCause());
    }

    @Test
    @DisplayName("handleOnError: a ProblemDetailException target wraps the decoded document")
    void problemDetailExceptionTargetWrapsDecodedDocument() {
        ProblemDetail decoded = new ProblemDetail();
        decoded.setTitle("Conflict");
        codec.result = decoded;
        template.setOnErrorParser(parserFor(409, ProblemDetailException.class));

        ProblemDetailException thrown = assertThrows(ProblemDetailException.class,
                () -> template.handleOnError(409, "{\"title\":\"Conflict\"}", List.of(), StandardCharsets.UTF_8));

        assertEquals("Conflict", thrown.getProblemDetailPayload().getTitle());
    }

    @Test
    @DisplayName("handleOnError: a bodyless ProblemDetailException target falls back to the wire status")
    void bodylessProblemDetailExceptionUsesWireStatus() {
        template.setOnErrorParser(parserFor(503, ProblemDetailException.class));

        ProblemDetailException thrown = assertThrows(ProblemDetailException.class,
                () -> template.handleOnError(503, null, List.of(), StandardCharsets.UTF_8));

        assertEquals(503, thrown.getProblemDetailPayload().getStatus());
    }

    @Test
    @DisplayName("handleOnError: a ProblemDetail target uses the built-in fallback and fills a missing status")
    void problemDetailTargetFallbackFillsStatus() {
        codec.failure = new IllegalStateException("codec cannot bind the document");
        template.setOnErrorParser(parserFor(503, ProblemDetail.class));

        HandledException thrown = assertThrows(HandledException.class,
                () -> template.handleOnError(503, "{\"title\":\"Boom\"}", List.of(), StandardCharsets.UTF_8));

        ProblemDetail details = thrown.getDetails();
        assertEquals("Boom", details.getTitle());
        assertEquals(503, details.getStatus());
    }

    @Test
    @DisplayName("handleOnError: a ProblemDetail target keeps an explicit status from the document")
    void problemDetailTargetKeepsExplicitStatus() {
        codec.failure = new IllegalStateException("codec cannot bind the document");
        template.setOnErrorParser(parserFor(503, ProblemDetail.class));

        HandledException thrown = assertThrows(HandledException.class,
                () -> template.handleOnError(503, "{\"title\":\"Boom\",\"status\":429}",
                        List.of(), StandardCharsets.UTF_8));

        assertEquals(429, thrown.<ProblemDetail>getDetails().getStatus());
    }

    @Test
    @DisplayName("handleOnError: a blank body skips the ProblemDetail fallback")
    void blankBodySkipsFallback() {
        codec.failure = new IllegalStateException("codec cannot bind the document");
        template.setOnErrorParser(parserFor(503, ProblemDetail.class));

        HandledException thrown = assertThrows(HandledException.class,
                () -> template.handleOnError(503, "   ", List.of(), StandardCharsets.UTF_8));

        assertNull(thrown.getDetails());
    }

    private OnErrorParser parserFor(int status, Class<?> errorClass) {
        OnErrorParser parser = new OnErrorParser();
        parser.register(status, errorClass);
        return parser;
    }

    /** An {@link Error} with the {@code (String)} constructor shape the instantiator probes. */
    public static class TestError extends Error {

        private static final long serialVersionUID = 1L;

        public TestError(String message) {
            super(message);
        }
    }

    /** A checked {@link Exception} with the {@code (String)} constructor shape. */
    public static class TestCheckedException extends Exception {

        private static final long serialVersionUID = 1L;

        public TestCheckedException(String message) {
            super(message);
        }
    }

    /** Minimal {@code @OnError} payload type. */
    static final class TestDto {
    }

    /**
     * A recording codec stub: returns a configured document, or throws a
     * configured failure. Installed as the registry default so the resolver
     * picks it for any media type.
     */
    private static final class StubCodec implements PayloadCodec {

        Object result;
        RuntimeException failure;
        int calls;

        @Override
        public boolean supports(String mediaType) {
            return true;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(byte[] payload, Type type, String mediaType) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return (T) result;
        }

        @Override
        public byte[] serialize(Object value, String mediaType) {
            return new byte[0];
        }
    }
}
