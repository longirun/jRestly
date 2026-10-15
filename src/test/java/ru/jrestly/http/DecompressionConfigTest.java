package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.JRestlyClient;
import ru.jrestly.ModuleInfo;
import ru.jrestly.SimpleModuleInfo;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.RequestHeader;
import ru.jrestly.fixtures.TestModuleInfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for transparent response decompression configuration (ADR-0021):
 * default settings in ModuleInfo/SimpleModuleInfo/JRestlyClient, Accept-Encoding
 * negotiation in HttpTemplateBuilder, and engine decompression logic in HttpTemplate.
 */
class DecompressionConfigTest {

    interface SampleController {
        @Get(path = "/data")
        String getData();

        @Get(path = "/custom")
        String getCustom(@RequestHeader(name = "Accept-Encoding") String encoding);
    }

    @Test
    @DisplayName("ModuleInfo interface default isDecompressionEnabled() returns true")
    void moduleInfoInterfaceDefaultIsTrue() {
        TestModuleInfo moduleInfo = new TestModuleInfo("http://localhost:1");
        assertTrue(moduleInfo.isDecompressionEnabled());
    }

    @Test
    @DisplayName("SimpleModuleInfo defaults isDecompressionEnabled() to true")
    void simpleModuleInfoDefaultIsTrue() {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:1")
                .build();
        assertTrue(moduleInfo.isDecompressionEnabled());
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder stores custom decompression flag")
    void simpleModuleInfoStoresCustomDecompression() {
        SimpleModuleInfo disabled = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:1")
                .decompression(false)
                .build();
        assertFalse(disabled.isDecompressionEnabled());

        SimpleModuleInfo enabled = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:1")
                .decompression(true)
                .build();
        assertTrue(enabled.isDecompressionEnabled());
    }

    @Test
    @DisplayName("JRestlyClient.Builder propagates decompression setting")
    void jRestlyClientBuilderPropagatesDecompression() {
        JRestlyClient clientDefault = JRestlyClient.builder()
                .baseUrl("http://localhost:1")
                .build();
        try {
            assertTrue(clientDefault.getModuleInfo().isDecompressionEnabled());
        } finally {
            clientDefault.close();
        }

        JRestlyClient clientDisabled = JRestlyClient.builder()
                .baseUrl("http://localhost:1")
                .decompression(false)
                .build();
        try {
            assertFalse(clientDisabled.getModuleInfo().isDecompressionEnabled());
        } finally {
            clientDisabled.close();
        }
    }

    @Test
    @DisplayName("HttpTemplateBuilder automatically appends Accept-Encoding: gzip, deflate when enabled")
    void httpTemplateBuilderAppendsAcceptEncodingWhenEnabled() throws NoSuchMethodException {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:1")
                .decompression(true)
                .build();

        Method method = SampleController.class.getMethod("getData");
        HttpTemplateBuilder builder = new HttpTemplateBuilder(
                moduleInfo,
                HttpClient.newHttpClient(),
                method,
                new Object[0],
                SampleController.class
        );

        HttpTemplate template = builder.build();
        assertNotNull(template);
        List<Header> headers = template.getHeaders();
        assertTrue(headers.stream().anyMatch(h -> "Accept-Encoding".equalsIgnoreCase(h.name())
                && "gzip, deflate".equals(h.value())));
    }

    @Test
    @DisplayName("HttpTemplateBuilder does not append Accept-Encoding when decompression is disabled")
    void httpTemplateBuilderOmitsAcceptEncodingWhenDisabled() throws NoSuchMethodException {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:1")
                .decompression(false)
                .build();

        Method method = SampleController.class.getMethod("getData");
        HttpTemplateBuilder builder = new HttpTemplateBuilder(
                moduleInfo,
                HttpClient.newHttpClient(),
                method,
                new Object[0],
                SampleController.class
        );

        HttpTemplate template = builder.build();
        List<Header> headers = template.getHeaders();
        assertFalse(headers.stream().anyMatch(h -> "Accept-Encoding".equalsIgnoreCase(h.name())));
    }

    @Test
    @DisplayName("HttpTemplateBuilder preserves caller's explicit Accept-Encoding header")
    void httpTemplateBuilderPreservesExplicitAcceptEncoding() throws NoSuchMethodException {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:1")
                .decompression(true)
                .build();

        Method method = SampleController.class.getMethod("getCustom", String.class);
        HttpTemplateBuilder builder = new HttpTemplateBuilder(
                moduleInfo,
                HttpClient.newHttpClient(),
                method,
                new Object[]{"identity"},
                SampleController.class
        );

        HttpTemplate template = builder.build();
        List<Header> headers = template.getHeaders();
        List<Header> acceptHeaders = headers.stream()
                .filter(h -> "Accept-Encoding".equalsIgnoreCase(h.name()))
                .toList();
        assertEquals(1, acceptHeaders.size());
        assertEquals("identity", acceptHeaders.get(0).value());
    }

    @Test
    @DisplayName("handleDecompression unpacks gzip body and strips Content-Encoding and Content-Length")
    void handleDecompressionGzip() throws IOException {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder().baseUrl("http://localhost:1").build();
        HttpTemplate template = new HttpTemplate();
        template.setModuleInfo(moduleInfo);

        byte[] original = "Hello GZIP World!".getBytes(StandardCharsets.UTF_8);
        byte[] gzipped = gzip(original);

        List<Header> headers = List.of(
                new Header("Content-Type", "text/plain"),
                new Header("Content-Encoding", "gzip"),
                new Header("Content-Length", String.valueOf(gzipped.length))
        );
        WireResponse raw = new WireResponse(200, headers, gzipped, null);
        WireResponse decompressed = template.handleDecompression(raw);

        assertEquals(200, decompressed.statusCode());
        assertArrayEquals(original, decompressed.body());
        assertNull(decompressed.stream());
        assertFalse(decompressed.headers().stream().anyMatch(h -> "Content-Encoding".equalsIgnoreCase(h.name())));
        assertFalse(decompressed.headers().stream().anyMatch(h -> "Content-Length".equalsIgnoreCase(h.name())));
        assertTrue(decompressed.headers().stream().anyMatch(h -> "Content-Type".equalsIgnoreCase(h.name())));
    }

    @Test
    @DisplayName("handleDecompression unpacks x-gzip body")
    void handleDecompressionXGzip() throws IOException {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder().baseUrl("http://localhost:1").build();
        HttpTemplate template = new HttpTemplate();
        template.setModuleInfo(moduleInfo);

        byte[] original = "Hello x-gzip World!".getBytes(StandardCharsets.UTF_8);
        byte[] gzipped = gzip(original);

        WireResponse raw = new WireResponse(200, List.of(new Header("Content-Encoding", "x-gzip")), gzipped, null);
        WireResponse decompressed = template.handleDecompression(raw);

        assertArrayEquals(original, decompressed.body());
    }

    @Test
    @DisplayName("handleDecompression unpacks standard RFC 1950 zlib deflate body")
    void handleDecompressionDeflateZlib() throws IOException {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder().baseUrl("http://localhost:1").build();
        HttpTemplate template = new HttpTemplate();
        template.setModuleInfo(moduleInfo);

        byte[] original = "Hello Deflate Zlib!".getBytes(StandardCharsets.UTF_8);
        byte[] deflated = deflateZlib(original);

        WireResponse raw = new WireResponse(200, List.of(new Header("Content-Encoding", "deflate")), deflated, null);
        WireResponse decompressed = template.handleDecompression(raw);

        assertArrayEquals(original, decompressed.body());
    }

    @Test
    @DisplayName("handleDecompression unpacks raw RFC 1951 deflate body via fallback")
    void handleDecompressionDeflateRaw() throws IOException {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder().baseUrl("http://localhost:1").build();
        HttpTemplate template = new HttpTemplate();
        template.setModuleInfo(moduleInfo);

        byte[] original = "Hello Raw Deflate!".getBytes(StandardCharsets.UTF_8);
        byte[] rawDeflated = deflateRaw(original);

        WireResponse raw = new WireResponse(200, List.of(new Header("Content-Encoding", "deflate")), rawDeflated, null);
        WireResponse decompressed = template.handleDecompression(raw);

        assertArrayEquals(original, decompressed.body());
    }

    @Test
    @DisplayName("handleDecompression leaves uncompressed and identity encodings untouched")
    void handleDecompressionUntouchedForIdentityOrNoEncoding() throws IOException {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder().baseUrl("http://localhost:1").build();
        HttpTemplate template = new HttpTemplate();
        template.setModuleInfo(moduleInfo);

        byte[] original = "plain text".getBytes(StandardCharsets.UTF_8);
        WireResponse noEncoding = new WireResponse(200, List.of(new Header("Content-Type", "text/plain")), original, null);
        assertSameResponse(noEncoding, template.handleDecompression(noEncoding));

        WireResponse identity = new WireResponse(200, List.of(new Header("Content-Encoding", "identity")), original, null);
        assertSameResponse(identity, template.handleDecompression(identity));
    }

    @Test
    @DisplayName("handleDecompression does nothing when disabled")
    void handleDecompressionDisabledLeavesBodyUntouched() throws IOException {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:1")
                .decompression(false)
                .build();
        HttpTemplate template = new HttpTemplate();
        template.setModuleInfo(moduleInfo);

        byte[] gzipped = gzip("do not touch".getBytes(StandardCharsets.UTF_8));
        WireResponse raw = new WireResponse(200, List.of(new Header("Content-Encoding", "gzip")), gzipped, null);
        WireResponse result = template.handleDecompression(raw);

        assertSameResponse(raw, result);
    }

    @Test
    @DisplayName("handleDecompression leaves empty body untouched without exception")
    void handleDecompressionEmptyBody() throws IOException {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder().baseUrl("http://localhost:1").build();
        HttpTemplate template = new HttpTemplate();
        template.setModuleInfo(moduleInfo);

        WireResponse raw = new WireResponse(204, List.of(new Header("Content-Encoding", "gzip")), new byte[0], null);
        WireResponse result = template.handleDecompression(raw);

        assertEquals(204, result.statusCode());
        assertEquals(0, result.body().length);
    }

    @Test
    @DisplayName("handleDecompression wraps live streaming body in decompressing stream")
    void handleDecompressionStreaming() throws IOException {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder().baseUrl("http://localhost:1").build();
        HttpTemplate template = new HttpTemplate();
        template.setModuleInfo(moduleInfo);

        byte[] original = "Streaming decompressed content".getBytes(StandardCharsets.UTF_8);
        byte[] gzipped = gzip(original);

        InputStream inputStream = new ByteArrayInputStream(gzipped);
        WireResponse raw = new WireResponse(200, List.of(new Header("Content-Encoding", "gzip")), null, inputStream);
        WireResponse decompressed = template.handleDecompression(raw);

        assertNull(decompressed.body());
        assertNotNull(decompressed.stream());
        try (InputStream stream = decompressed.stream()) {
            byte[] read = stream.readAllBytes();
            assertArrayEquals(original, read);
        }
    }

    @Test
    @DisplayName("handleDecompression on corrupted gzip payload throws IOException")
    void handleDecompressionCorruptedGzipThrowsIOException() {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder().baseUrl("http://localhost:1").build();
        HttpTemplate template = new HttpTemplate();
        template.setModuleInfo(moduleInfo);

        byte[] corrupted = "this is not a valid gzip payload".getBytes(StandardCharsets.UTF_8);
        WireResponse raw = new WireResponse(200, List.of(new Header("Content-Encoding", "gzip")), corrupted, null);

        assertThrows(IOException.class, () -> template.handleDecompression(raw));
    }

    private static void assertSameResponse(WireResponse expected, WireResponse actual) {
        assertEquals(expected.statusCode(), actual.statusCode());
        assertEquals(expected.headers(), actual.headers());
        assertArrayEquals(expected.body(), actual.body());
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(data);
        }
        return baos.toByteArray();
    }

    private static byte[] deflateZlib(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos)) {
            dos.write(data);
        }
        return baos.toByteArray();
    }

    private static byte[] deflateRaw(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos, deflater)) {
            dos.write(data);
        } finally {
            deflater.end();
        }
        return baos.toByteArray();
    }
}
