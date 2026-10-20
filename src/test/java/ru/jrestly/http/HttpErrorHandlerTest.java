package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.codec.PayloadCodec;
import ru.jrestly.problem.ProblemDetail;
import ru.jrestly.problem.ProblemDetailException;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HttpErrorHandler: status evaluation, unexpected status exception building, and @OnError dispatch")
class HttpErrorHandlerTest {

    // ---- isExpectedStatus tests ----

    @Test
    @DisplayName("isExpectedStatus: uses explicit accept list when provided")
    void isExpectedStatusExplicitList() {
        List<Integer> accepted = List.of(200, 201, 204);

        assertTrue(HttpErrorHandler.isExpectedStatus(accepted, false, false, 200));
        assertTrue(HttpErrorHandler.isExpectedStatus(accepted, false, false, 201));
        assertTrue(HttpErrorHandler.isExpectedStatus(accepted, false, false, 204));

        assertFalse(HttpErrorHandler.isExpectedStatus(accepted, false, false, 202));
        assertFalse(HttpErrorHandler.isExpectedStatus(accepted, true, true, 304));
        assertFalse(HttpErrorHandler.isExpectedStatus(accepted, true, true, 404));
        assertFalse(HttpErrorHandler.isExpectedStatus(accepted, false, false, 500));
    }

    @Test
    @DisplayName("isExpectedStatus: default accept 2xx when no explicit list")
    void isExpectedStatusDefault2xx() {
        assertFalse(HttpErrorHandler.isExpectedStatus(null, false, false, 199));
        assertTrue(HttpErrorHandler.isExpectedStatus(null, false, false, 200));
        assertTrue(HttpErrorHandler.isExpectedStatus(null, false, false, 204));
        assertTrue(HttpErrorHandler.isExpectedStatus(null, false, false, 299));
        assertFalse(HttpErrorHandler.isExpectedStatus(null, false, false, 300));
        assertFalse(HttpErrorHandler.isExpectedStatus(null, false, false, 304));
        assertFalse(HttpErrorHandler.isExpectedStatus(null, false, false, 404));
        assertFalse(HttpErrorHandler.isExpectedStatus(null, false, false, 500));
    }

    @Test
    @DisplayName("isExpectedStatus: conditional accepts 304")
    void isExpectedStatusConditional() {
        assertTrue(HttpErrorHandler.isExpectedStatus(null, true, false, 304));
        assertFalse(HttpErrorHandler.isExpectedStatus(null, false, false, 304));
        assertFalse(HttpErrorHandler.isExpectedStatus(null, true, false, 404));
        assertFalse(HttpErrorHandler.isExpectedStatus(null, true, false, 301));
    }

    @Test
    @DisplayName("isExpectedStatus: optional accepts 304 and 404")
    void isExpectedStatusOptional() {
        assertTrue(HttpErrorHandler.isExpectedStatus(null, false, true, 304));
        assertTrue(HttpErrorHandler.isExpectedStatus(null, false, true, 404));
        assertFalse(HttpErrorHandler.isExpectedStatus(null, false, true, 400));
        assertFalse(HttpErrorHandler.isExpectedStatus(null, false, true, 500));
        assertFalse(HttpErrorHandler.isExpectedStatus(null, false, false, 404));
    }

    // ---- createUnexpectedStatusException tests ----

    @Test
    @DisplayName("createUnexpectedStatusException: creates ClientErrorException for 4xx")
    void createUnexpectedStatusExceptionClientError() {
        List<Header> headers = List.of(new Header("X-Test", "1"));
        UnexpectedStatusException ex400 = HttpErrorHandler.createUnexpectedStatusException(400, "{\"title\":\"bad request\"}", headers, null);
        assertInstanceOf(ClientErrorException.class, ex400);
        assertEquals(400, ex400.getStatusCode());
        assertEquals("{\"title\":\"bad request\"}", ex400.getRawBody());
        assertEquals(headers, ex400.getResponseHeaders());
        assertNotNull(ex400.getProblemDetailPayload());

        UnexpectedStatusException exPlain = HttpErrorHandler.createUnexpectedStatusException(400, "plain text", headers, null);
        assertNull(exPlain.getProblemDetailPayload());

        UnexpectedStatusException ex499 = HttpErrorHandler.createUnexpectedStatusException(499, null, headers, null);
        assertInstanceOf(ClientErrorException.class, ex499);
        assertEquals(499, ex499.getStatusCode());
    }

    @Test
    @DisplayName("createUnexpectedStatusException: creates ServerErrorException for 5xx")
    void createUnexpectedStatusExceptionServerError() {
        List<Header> headers = List.of(new Header("X-Err", "crash"));
        UnexpectedStatusException ex500 = HttpErrorHandler.createUnexpectedStatusException(500, "internal error", headers, null);
        assertInstanceOf(ServerErrorException.class, ex500);
        assertEquals(500, ex500.getStatusCode());
        assertEquals("internal error", ex500.getRawBody());
        assertEquals(headers, ex500.getResponseHeaders());

        UnexpectedStatusException ex599 = HttpErrorHandler.createUnexpectedStatusException(599, "", headers, null);
        assertInstanceOf(ServerErrorException.class, ex599);
        assertEquals(599, ex599.getStatusCode());
    }

    @Test
    @DisplayName("createUnexpectedStatusException: creates UnexpectedStatusException for other statuses (<400 or >=600)")
    void createUnexpectedStatusExceptionOtherStatuses() {
        List<Header> headers = List.of();
        UnexpectedStatusException ex301 = HttpErrorHandler.createUnexpectedStatusException(301, "redirect", headers, null);
        assertEquals(UnexpectedStatusException.class, ex301.getClass());
        assertEquals(301, ex301.getStatusCode());

        UnexpectedStatusException ex600 = HttpErrorHandler.createUnexpectedStatusException(600, "unknown", headers, null);
        assertEquals(UnexpectedStatusException.class, ex600.getClass());
        assertEquals(600, ex600.getStatusCode());
    }

    // ---- handleOnError tests ----

    @Test
    @DisplayName("handleOnError: no body and a plain DTO target throw a HandledException with null details")
    void handleOnErrorNoBodyPlainTarget() {
        RecordingCodec codec = new RecordingCodec();
        OnErrorParser parser = parserFor(503, TestDto.class);

        HandledException thrown = assertThrows(HandledException.class,
                () -> HttpErrorHandler.handleOnError(parser, () -> codec, 503, null, List.of(), StandardCharsets.UTF_8));

        assertNull(thrown.getDetails());
        assertEquals(503, thrown.getStatusCode());
        assertEquals(0, codec.calls);
    }

    @Test
    @DisplayName("handleOnError: empty body and a plain DTO target throw a HandledException with null details")
    void handleOnErrorEmptyBodyPlainTarget() {
        RecordingCodec codec = new RecordingCodec();
        OnErrorParser parser = parserFor(503, TestDto.class);

        HandledException thrown = assertThrows(HandledException.class,
                () -> HttpErrorHandler.handleOnError(parser, () -> codec, 503, "", List.of(), StandardCharsets.UTF_8));

        assertNull(thrown.getDetails());
        assertEquals(0, codec.calls);
    }

    @Test
    @DisplayName("handleOnError: a Void target never deserializes the body")
    void handleOnErrorVoidTarget() {
        RecordingCodec codec = new RecordingCodec();
        OnErrorParser parser = parserFor(503, Void.class);

        HandledException thrown = assertThrows(HandledException.class,
                () -> HttpErrorHandler.handleOnError(parser, () -> codec, 503, "{\"key\":\"val\"}", List.of(), StandardCharsets.UTF_8));

        assertNull(thrown.getDetails());
        assertEquals(0, codec.calls);
    }

    @Test
    @DisplayName("handleOnError: deserializes plain DTO when payload is present")
    void handleOnErrorPlainDtoDeserializes() {
        TestDto dto = new TestDto("err_code", 42);
        RecordingCodec codec = new RecordingCodec();
        codec.result = dto;
        OnErrorParser parser = parserFor(422, TestDto.class);

        HandledException thrown = assertThrows(HandledException.class,
                () -> HttpErrorHandler.handleOnError(parser, () -> codec, 422, "{\"code\":\"err_code\"}", List.of(), StandardCharsets.UTF_8));

        assertSame(dto, thrown.getDetails());
        assertEquals(422, thrown.getStatusCode());
        assertEquals(1, codec.calls);
    }

    @Test
    @DisplayName("handleOnError: ProblemDetail target deserializes and fills missing status <= 0")
    void handleOnErrorProblemDetailFillsMissingStatus() {
        ProblemDetail pd = new ProblemDetail();
        pd.setTitle("Out of memory");
        pd.setStatus(0);
        RecordingCodec codec = new RecordingCodec();
        codec.result = pd;
        OnErrorParser parser = parserFor(503, ProblemDetail.class);

        HandledException thrown = assertThrows(HandledException.class,
                () -> HttpErrorHandler.handleOnError(parser, () -> codec, 503, "{\"title\":\"Out of memory\"}", List.of(), StandardCharsets.UTF_8));

        assertSame(pd, thrown.getDetails());
        assertEquals(503, pd.getStatus());
    }

    @Test
    @DisplayName("handleOnError: ProblemDetail target keeps explicit status from document")
    void handleOnErrorProblemDetailKeepsExplicitStatus() {
        ProblemDetail pd = new ProblemDetail();
        pd.setTitle("Rate limit");
        pd.setStatus(429);
        RecordingCodec codec = new RecordingCodec();
        codec.result = pd;
        OnErrorParser parser = parserFor(503, ProblemDetail.class);

        HandledException thrown = assertThrows(HandledException.class,
                () -> HttpErrorHandler.handleOnError(parser, () -> codec, 503, "{\"title\":\"Rate limit\",\"status\":429}", List.of(), StandardCharsets.UTF_8));

        assertSame(pd, thrown.getDetails());
        assertEquals(429, pd.getStatus());
    }

    @Test
    @DisplayName("handleOnError: ProblemDetail target falls back to ProblemDetail.fromJson when codec fails")
    void handleOnErrorProblemDetailCodecFailureFallback() {
        RecordingCodec codec = new RecordingCodec();
        codec.failure = new RuntimeException("Malformed JSON for codec");
        OnErrorParser parser = parserFor(503, ProblemDetail.class);

        HandledException thrown = assertThrows(HandledException.class,
                () -> HttpErrorHandler.handleOnError(parser, () -> codec, 503, "{\"title\":\"Fallback\",\"status\":429}", List.of(), StandardCharsets.UTF_8));

        assertInstanceOf(ProblemDetail.class, thrown.getDetails());
        ProblemDetail pd = (ProblemDetail) thrown.getDetails();
        assertEquals("Fallback", pd.getTitle());
        assertEquals(429, pd.getStatus());
    }

    @Test
    @DisplayName("handleOnError: ProblemDetail target skips fromJson fallback when payload is blank")
    void handleOnErrorProblemDetailBlankPayload() {
        RecordingCodec codec = new RecordingCodec();
        OnErrorParser parser = parserFor(503, ProblemDetail.class);

        HandledException thrown = assertThrows(HandledException.class,
                () -> HttpErrorHandler.handleOnError(parser, () -> codec, 503, "   ", List.of(), StandardCharsets.UTF_8));

        assertNull(thrown.getDetails());
    }

    @Test
    @DisplayName("handleOnError: ProblemDetailException target wraps decoded ProblemDetail")
    void handleOnErrorProblemDetailExceptionWithBody() {
        ProblemDetail pd = new ProblemDetail();
        pd.setTitle("Conflict");
        RecordingCodec codec = new RecordingCodec();
        codec.result = pd;
        OnErrorParser parser = parserFor(409, ProblemDetailException.class);

        ProblemDetailException thrown = assertThrows(ProblemDetailException.class,
                () -> HttpErrorHandler.handleOnError(parser, () -> codec, 409, "{\"title\":\"Conflict\"}", List.of(), StandardCharsets.UTF_8));

        assertSame(pd, thrown.getProblemDetailPayload());
    }

    @Test
    @DisplayName("handleOnError: ProblemDetailException target falls back to wire status when bodyless")
    void handleOnErrorProblemDetailExceptionBodyless() {
        RecordingCodec codec = new RecordingCodec();
        OnErrorParser parser = parserFor(503, ProblemDetailException.class);

        ProblemDetailException thrown = assertThrows(ProblemDetailException.class,
                () -> HttpErrorHandler.handleOnError(parser, () -> codec, 503, null, List.of(), StandardCharsets.UTF_8));

        assertEquals(503, thrown.getProblemDetailPayload().getStatus());
    }

    @Test
    @DisplayName("handleOnError: a decoded RuntimeException is rethrown as-is")
    void handleOnErrorDecodedRuntimeException() {
        IllegalStateException decoded = new IllegalStateException("Invalid state");
        RecordingCodec codec = new RecordingCodec();
        codec.result = decoded;
        OnErrorParser parser = parserFor(503, RuntimeException.class);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> HttpErrorHandler.handleOnError(parser, () -> codec, 503, "{}", List.of(), StandardCharsets.UTF_8));

        assertSame(decoded, thrown);
    }

    @Test
    @DisplayName("handleOnError: a decoded Error is rethrown as-is")
    void handleOnErrorDecodedError() {
        AssertionError decoded = new AssertionError("Boom");
        RecordingCodec codec = new RecordingCodec();
        codec.result = decoded;
        OnErrorParser parser = parserFor(503, Error.class);

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> HttpErrorHandler.handleOnError(parser, () -> codec, 503, "{}", List.of(), StandardCharsets.UTF_8));

        assertSame(decoded, thrown);
    }

    @Test
    @DisplayName("handleOnError: a decoded checked Throwable is wrapped in RuntimeException")
    void handleOnErrorDecodedCheckedThrowable() {
        IOException decoded = new IOException("Disk failure");
        RecordingCodec codec = new RecordingCodec();
        codec.result = decoded;
        OnErrorParser parser = parserFor(503, Throwable.class);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> HttpErrorHandler.handleOnError(parser, () -> codec, 503, "{}", List.of(), StandardCharsets.UTF_8));

        assertSame(decoded, thrown.getCause());
    }

    @Test
    @DisplayName("handleOnError: a bodyless RuntimeException target is instantiated from wire status")
    void handleOnErrorBodylessRuntimeException() {
        RecordingCodec codec = new RecordingCodec();
        OnErrorParser parser = parserFor(503, CustomRuntimeException.class);

        CustomRuntimeException thrown = assertThrows(CustomRuntimeException.class,
                () -> HttpErrorHandler.handleOnError(parser, () -> codec, 503, null, List.of(), StandardCharsets.UTF_8));

        assertEquals("HTTP 503", thrown.getMessage());
    }

    @Test
    @DisplayName("handleOnError: a bodyless Error target is instantiated and rethrown")
    void handleOnErrorBodylessError() {
        RecordingCodec codec = new RecordingCodec();
        OnErrorParser parser = parserFor(503, CustomError.class);

        CustomError thrown = assertThrows(CustomError.class,
                () -> HttpErrorHandler.handleOnError(parser, () -> codec, 503, null, List.of(), StandardCharsets.UTF_8));

        assertEquals("HTTP 503", thrown.getMessage());
    }

    @Test
    @DisplayName("handleOnError: a bodyless checked target is instantiated then wrapped in RuntimeException")
    void handleOnErrorBodylessCheckedException() {
        RecordingCodec codec = new RecordingCodec();
        OnErrorParser parser = parserFor(503, CustomCheckedException.class);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> HttpErrorHandler.handleOnError(parser, () -> codec, 503, null, List.of(), StandardCharsets.UTF_8));

        assertInstanceOf(CustomCheckedException.class, thrown.getCause());
    }

    @Test
    @DisplayName("handleOnError: overload accepting direct PayloadCodec works identically")
    void handleOnErrorDirectCodecOverload() {
        TestDto dto = new TestDto("code1", 100);
        RecordingCodec codec = new RecordingCodec();
        codec.result = dto;
        OnErrorParser parser = parserFor(400, TestDto.class);

        HandledException thrown = assertThrows(HandledException.class,
                () -> HttpErrorHandler.handleOnError(parser, codec, 400, "{\"code\":\"code1\"}", List.of(), StandardCharsets.UTF_8));

        assertSame(dto, thrown.getDetails());

        HandledException thrownNullCodec = assertThrows(HandledException.class,
                () -> HttpErrorHandler.handleOnError(parser, (PayloadCodec) null, 400, "{\"code\":\"code1\"}", List.of(), StandardCharsets.UTF_8));
        assertNull(thrownNullCodec.getDetails());
    }

    @Test
    @DisplayName("handleOnError: null codec supplier skips decoding")
    void handleOnErrorNullCodecSupplier() {
        OnErrorParser parser = parserFor(400, TestDto.class);

        HandledException thrown = assertThrows(HandledException.class,
                () -> HttpErrorHandler.handleOnError(parser, (java.util.function.Supplier<PayloadCodec>) null, 400, "{\"code\":\"code1\"}", List.of(), StandardCharsets.UTF_8));

        assertNull(thrown.getDetails());
    }

    @Test
    @DisplayName("handleOnError: ProblemDetail target catches unparseable JSON from fromJson fallback")
    void handleOnErrorProblemDetailUnparseableJsonCatches() {
        RecordingCodec codec = new RecordingCodec();
        codec.failure = new RuntimeException("Codec error");
        OnErrorParser parser = parserFor(503, ProblemDetail.class);

        // invalid JSON string that causes ProblemDetail.fromJson to throw
        HandledException thrown = assertThrows(HandledException.class,
                () -> HttpErrorHandler.handleOnError(parser, () -> codec, 503, "{broken json: true", List.of(), StandardCharsets.UTF_8));

        assertNull(thrown.getDetails());
    }

    @Test
    @DisplayName("HttpErrorHandler: private constructor reflection invocation")
    void privateConstructor() throws Exception {
        Constructor<HttpErrorHandler> ctor = HttpErrorHandler.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        HttpErrorHandler instance = ctor.newInstance();
        assertNotNull(instance);
    }

    // ---- Helpers ----

    private static OnErrorParser parserFor(int status, Class<?> errorClass) {
        OnErrorParser parser = new OnErrorParser();
        parser.register(status, errorClass);
        return parser;
    }

    public static class TestDto {
        private String code;
        private int val;

        public TestDto() {
        }

        public TestDto(String code, int val) {
            this.code = code;
            this.val = val;
        }

        public String getCode() {
            return code;
        }

        public int getVal() {
            return val;
        }
    }

    @SuppressWarnings("serial")
    public static class CustomRuntimeException extends RuntimeException {
        public CustomRuntimeException(String message) {
            super(message);
        }
    }

    @SuppressWarnings("serial")
    public static class CustomError extends Error {
        public CustomError(String message) {
            super(message);
        }
    }

    @SuppressWarnings("serial")
    public static class CustomCheckedException extends Exception {
        public CustomCheckedException(String message) {
            super(message);
        }
    }

    private static class RecordingCodec implements PayloadCodec {
        int calls;
        Object result;
        RuntimeException failure;

        @Override
        public byte[] serialize(Object value, String mediaType) {
            return new byte[0];
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(byte[] payload, Type targetType, String mediaType) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return (T) result;
        }

        @Override
        public boolean supports(String mediaType) {
            return true;
        }
    }
}
