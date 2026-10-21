package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.codec.PayloadCodec;
import ru.jrestly.problem.ProblemDetail;
import ru.jrestly.problem.ProblemDetailException;

import java.lang.reflect.Type;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link ProblemDetailResolver} (RFC 7807/9457 resolution and throwable instantiation).
 */
@SuppressWarnings({"unchecked", "serial"})
class ProblemDetailResolverTest {

    @Test
    @DisplayName("isProblemJsonContentType recognizes standard RFC problem media types")
    void isProblemJsonContentTypeRecognition() {
        assertFalse(ProblemDetailResolver.isProblemJsonContentType(null));
        assertFalse(ProblemDetailResolver.isProblemJsonContentType(List.of()));
        assertFalse(ProblemDetailResolver.isProblemJsonContentType(List.of(new Header("Content-Type", null))));
        assertFalse(ProblemDetailResolver.isProblemJsonContentType(List.of(new Header("Content-Type", "application/json"))));
        assertFalse(ProblemDetailResolver.isProblemJsonContentType(List.of(new Header("Accept", "application/problem+json"))));

        assertTrue(ProblemDetailResolver.isProblemJsonContentType(
                List.of(new Header("Content-Type", "application/problem+json; charset=utf-8"))));
        assertTrue(ProblemDetailResolver.isProblemJsonContentType(
                List.of(new Header("content-type", "application/problem+xml"))));
    }

    @Test
    @DisplayName("isLikelyProblemDetail evaluates shape heuristic across all fields")
    void isLikelyProblemDetailEvaluation() {
        assertFalse(ProblemDetailResolver.isLikelyProblemDetail(null));

        ProblemDetail empty = new ProblemDetail();
        empty.setType((URI) null);
        empty.setStatus(0);
        assertFalse(ProblemDetailResolver.isLikelyProblemDetail(empty));

        ProblemDetail defaultTypeOnly = new ProblemDetail();
        defaultTypeOnly.setStatus(0);
        defaultTypeOnly.setType(URI.create("about:blank"));
        assertFalse(ProblemDetailResolver.isLikelyProblemDetail(defaultTypeOnly));

        ProblemDetail customType = new ProblemDetail();
        customType.setStatus(0);
        customType.setType(URI.create("https://example.com/probs/custom-err"));
        assertTrue(ProblemDetailResolver.isLikelyProblemDetail(customType));

        ProblemDetail statusOnly = new ProblemDetail();
        statusOnly.setStatus(404);
        statusOnly.setType((URI) null);
        assertTrue(ProblemDetailResolver.isLikelyProblemDetail(statusOnly));

        ProblemDetail titleOnly = new ProblemDetail();
        titleOnly.setStatus(0);
        titleOnly.setType((URI) null);
        titleOnly.setTitle("Error Title");
        assertTrue(ProblemDetailResolver.isLikelyProblemDetail(titleOnly));

        ProblemDetail blankTitle = new ProblemDetail();
        blankTitle.setStatus(0);
        blankTitle.setType((URI) null);
        blankTitle.setTitle("   ");
        assertFalse(ProblemDetailResolver.isLikelyProblemDetail(blankTitle));

        ProblemDetail detailOnly = new ProblemDetail();
        detailOnly.setStatus(0);
        detailOnly.setType((URI) null);
        detailOnly.setDetail("Detailed error description");
        assertTrue(ProblemDetailResolver.isLikelyProblemDetail(detailOnly));

        ProblemDetail blankDetail = new ProblemDetail();
        blankDetail.setStatus(0);
        blankDetail.setType((URI) null);
        blankDetail.setDetail("   ");
        assertFalse(ProblemDetailResolver.isLikelyProblemDetail(blankDetail));

        ProblemDetail instanceOnly = new ProblemDetail();
        instanceOnly.setStatus(0);
        instanceOnly.setType((URI) null);
        instanceOnly.setInstance(URI.create("/account/123/tx/456"));
        assertTrue(ProblemDetailResolver.isLikelyProblemDetail(instanceOnly));
    }

    @Test
    @DisplayName("instantiateThrowable instantiates ProblemDetailException and various constructor shapes")
    void instantiateThrowableVariations() {
        List<Header> headers = List.of(new Header("X-Reason", "out-of-bounds"));

        // 1. ProblemDetailException directly
        Throwable t1 = ProblemDetailResolver.instantiateThrowable(ProblemDetailException.class, 422, headers);
        assertInstanceOf(ProblemDetailException.class, t1);
        ProblemDetailException pde = (ProblemDetailException) t1;
        assertEquals(422, pde.getStatusCode());
        assertEquals(422, pde.problemDetail().getStatus());

        // 2. 3-arg constructor (Object, int, List)
        Throwable t2 = ProblemDetailResolver.instantiateThrowable(ThreeArgException.class, 400, headers);
        assertInstanceOf(ThreeArgException.class, t2);
        ThreeArgException tae = (ThreeArgException) t2;
        assertEquals(400, tae.statusCode);
        assertEquals(headers, tae.headers);

        // 3. 2-arg constructor (String, int)
        Throwable t3 = ProblemDetailResolver.instantiateThrowable(TwoArgException.class, 404, headers);
        assertInstanceOf(TwoArgException.class, t3);
        TwoArgException twoArg = (TwoArgException) t3;
        assertEquals("HTTP 404", twoArg.message);
        assertEquals(404, twoArg.statusCode);

        // 4. 1-arg constructor (String)
        Throwable t4 = ProblemDetailResolver.instantiateThrowable(OneArgException.class, 500, headers);
        assertInstanceOf(OneArgException.class, t4);
        assertEquals("HTTP 500", t4.getMessage());

        // 5. 0-arg constructor ()
        Throwable t5 = ProblemDetailResolver.instantiateThrowable(ZeroArgException.class, 503, headers);
        assertInstanceOf(ZeroArgException.class, t5);

        // 6. Fallback to HandledException when reflection fails
        Throwable t6 = ProblemDetailResolver.instantiateThrowable(UnconstructibleException.class, 502, headers);
        assertInstanceOf(HandledException.class, t6);
        HandledException he = (HandledException) t6;
        assertEquals(502, he.getStatusCode());
    }

    @Test
    @DisplayName("resolve resolves problem details with BOM, fills wire status, or safely returns null")
    void resolveProblemDetailPipeline() {
        assertNull(ProblemDetailResolver.resolve(400, null, List.of(), null, null));
        assertNull(ProblemDetailResolver.resolve(400, "", List.of(), null, null));
        assertNull(ProblemDetailResolver.resolve(400, "   ", List.of(), null, null));
        assertNull(ProblemDetailResolver.resolve(400, "not a json", List.of(), null, null));
        assertNull(ProblemDetailResolver.resolve(400, "[1, 2, 3]", List.of(), null, null));

        // Codec throws, falls back to zero-dep parser
        PayloadCodec failingCodec = new PayloadCodec() {
            @Override public <T> T deserialize(byte[] payload, Type type, String mediaType) {
                throw new IllegalStateException("failing codec");
            }
            @Override public byte[] serialize(Object value, String mediaType) { return new byte[0]; }
            @Override public boolean supports(String mediaType) { return true; }
        };

        String body = "{\"title\":\"Bad Request\",\"detail\":\"Param is required\"}";
        ProblemDetail pd1 = ProblemDetailResolver.resolve(400, body, List.of(), "application/json", failingCodec);
        assertNotNull(pd1);
        assertEquals(400, pd1.getStatus(), "Missing document status filled from wire status");
        assertEquals("Bad Request", pd1.getTitle());

        // Codec succeeds and carries document status
        PayloadCodec workingCodec = new PayloadCodec() {
            @Override public <T> T deserialize(byte[] payload, Type type, String mediaType) {
                ProblemDetail pd = new ProblemDetail();
                pd.setStatus(409);
                pd.setTitle("Conflict");
                return (T) pd;
            }
            @Override public byte[] serialize(Object value, String mediaType) { return new byte[0]; }
            @Override public boolean supports(String mediaType) { return true; }
        };
        ProblemDetail pd2 = ProblemDetailResolver.resolve(500, body, List.of(), "application/json", workingCodec);
        assertNotNull(pd2);
        assertEquals(409, pd2.getStatus(), "Explicit document status is preserved");
        assertEquals("Conflict", pd2.getTitle());

        // Null returning codec falls back to fromJson
        PayloadCodec nullReturningCodec = new PayloadCodec() {
            @Override public <T> T deserialize(byte[] payload, Type type, String mediaType) { return null; }
            @Override public byte[] serialize(Object value, String mediaType) { return new byte[0]; }
            @Override public boolean supports(String mediaType) { return true; }
        };
        ProblemDetail pdFallback = ProblemDetailResolver.resolve(400, body, List.of(), "application/json", nullReturningCodec);
        assertNotNull(pdFallback);
        assertEquals("Bad Request", pdFallback.getTitle());

        // Bare {} is rejected unless Content-Type indicates problem
        ProblemDetail pdBare = ProblemDetailResolver.resolve(500, "{}", List.of(), "application/json", null);
        assertNull(pdBare);

        // Bare {} accepted when header is application/problem+json
        ProblemDetail pdBareWithHeader = ProblemDetailResolver.resolve(422, "{}",
                List.of(new Header("Content-Type", "application/problem+json")), "application/problem+json", null);
        assertNotNull(pdBareWithHeader);
        assertEquals(422, pdBareWithHeader.getStatus());
    }

    public static class ThreeArgException extends RuntimeException {
        final Object details;
        final int statusCode;
        final List<Header> headers;

        public ThreeArgException(Object details, int statusCode, List<Header> headers) {
            this.details = details;
            this.statusCode = statusCode;
            this.headers = headers;
        }
    }

    public static class TwoArgException extends RuntimeException {
        final String message;
        final int statusCode;

        public TwoArgException(String message, int statusCode) {
            super(message);
            this.message = message;
            this.statusCode = statusCode;
        }
    }

    public static class OneArgException extends RuntimeException {
        public OneArgException(String message) {
            super(message);
        }
    }

    public static class ZeroArgException extends RuntimeException {
        public ZeroArgException() {}
    }

    public static class UnconstructibleException extends RuntimeException {
        private UnconstructibleException(int secret) {}
    }
}
