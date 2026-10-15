package ru.jrestly.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.ExpectStatus;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.OnError;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.Timeout;
import ru.jrestly.annotation.WithInterceptor;
import ru.jrestly.interceptor.RequestContext;
import ru.jrestly.interceptor.RequestInterceptor;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodMetadataTest {

    static class DummyError {}

    static class SampleRequestInterceptor implements RequestInterceptor {
        @Override
        public WireRequest intercept(WireRequest request, RequestContext context) {
            return request;
        }
    }

    interface SuperApi {
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @Get(path = "/super")
        String superMethod();
    }

    @WithInterceptor(SampleRequestInterceptor.class)
    interface SampleController extends SuperApi {
        @Get(path = "/users/{id}")
        @ExpectStatus(statuses = {200, 201})
        @OnError(statuses = {404}, errorObject = DummyError.class)
        String getUser(String id, ResponseHeaders headers);

        @Post(path = "/upload")
        @Timeout(value = 100, unit = TimeUnit.MILLISECONDS)
        CompletableFuture<Optional<byte[]>> upload(byte[] data, UploadProgressListener listener);

        @Get(path = "/stream")
        InputStream stream();
    }

    @BeforeEach
    void setUp() {
        MethodMetadata.clearCache();
    }

    @Test
    @DisplayName("MethodMetadata caches instances across invocations (assertSame)")
    void testCaching() throws NoSuchMethodException {
        Method getUser = SampleController.class.getMethod("getUser", String.class, ResponseHeaders.class);

        MethodMetadata meta1 = MethodMetadata.of(getUser, SampleController.class);
        MethodMetadata meta2 = MethodMetadata.of(getUser, SampleController.class);

        assertNotNull(meta1);
        assertSame(meta1, meta2);
    }

    @Test
    @DisplayName("Resolves HTTP verb, path, and request metadata")
    void testRequestMetaResolution() throws NoSuchMethodException {
        Method getUser = SampleController.class.getMethod("getUser", String.class, ResponseHeaders.class);
        MethodMetadata meta = MethodMetadata.of(getUser, SampleController.class);

        assertEquals(HttpMethod.GET, meta.requestMeta().httpMethod());
        assertEquals("/users/{id}", meta.requestMeta().path());
        assertEquals(RequestType.APPLICATION_JSON, meta.requestMeta().requestType());
    }

    @Test
    @DisplayName("Resolves expected statuses and OnErrorParser")
    void testExpectStatusesAndOnError() throws NoSuchMethodException {
        Method getUser = SampleController.class.getMethod("getUser", String.class, ResponseHeaders.class);
        MethodMetadata meta = MethodMetadata.of(getUser, SampleController.class);

        assertEquals(List.of(200, 201), meta.expectStatuses());
        assertNotNull(meta.onErrorParser());
    }

    @Test
    @DisplayName("Resolves parameter indexes for ResponseHeaders and UploadProgressListener")
    void testParamIndexes() throws NoSuchMethodException {
        Method getUser = SampleController.class.getMethod("getUser", String.class, ResponseHeaders.class);
        MethodMetadata userMeta = MethodMetadata.of(getUser, SampleController.class);
        assertEquals(1, userMeta.responseHeadersParamIndex());
        assertEquals(-1, userMeta.uploadProgressParamIndex());

        Method upload = SampleController.class.getMethod("upload", byte[].class, UploadProgressListener.class);
        MethodMetadata uploadMeta = MethodMetadata.of(upload, SampleController.class);
        assertEquals(-1, uploadMeta.responseHeadersParamIndex());
        assertEquals(1, uploadMeta.uploadProgressParamIndex());
    }

    @Test
    @DisplayName("Resolves return type info (async, optional, streamBody)")
    void testReturnTypeInfo() throws NoSuchMethodException {
        Method upload = SampleController.class.getMethod("upload", byte[].class, UploadProgressListener.class);
        MethodMetadata uploadMeta = MethodMetadata.of(upload, SampleController.class);

        MethodMetadata.ReturnTypeInfo rt = uploadMeta.returnTypeInfo();
        assertTrue(rt.async());
        assertTrue(rt.optional());
        assertFalse(rt.streamBody());
        assertEquals(byte[].class, rt.effectiveReturnType());

        Method stream = SampleController.class.getMethod("stream");
        MethodMetadata streamMeta = MethodMetadata.of(stream, SampleController.class);
        assertFalse(streamMeta.returnTypeInfo().async());
        assertFalse(streamMeta.returnTypeInfo().optional());
        assertTrue(streamMeta.returnTypeInfo().streamBody());
    }

    @Test
    @DisplayName("Resolves timeout precedence: method overrides super-interface")
    void testTimeoutPrecedence() throws NoSuchMethodException {
        Method upload = SampleController.class.getMethod("upload", byte[].class, UploadProgressListener.class);
        MethodMetadata uploadMeta = MethodMetadata.of(upload, SampleController.class);
        assertEquals(Duration.ofMillis(100), uploadMeta.timeout());

        Method superMethod = SampleController.class.getMethod("superMethod");
        MethodMetadata superMeta = MethodMetadata.of(superMethod, SampleController.class);
        assertEquals(Duration.ofSeconds(5), superMeta.timeout());
    }

    @Test
    @DisplayName("Resolves declarative interceptors from interface hierarchy")
    void testDeclarativeInterceptors() throws NoSuchMethodException {
        Method getUser = SampleController.class.getMethod("getUser", String.class, ResponseHeaders.class);
        MethodMetadata meta = MethodMetadata.of(getUser, SampleController.class);

        assertEquals(1, meta.declarativeRequestInterceptorClasses().size());
        assertEquals(SampleRequestInterceptor.class, meta.declarativeRequestInterceptorClasses().getFirst());
    }
}
