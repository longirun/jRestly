package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.ModuleInfo;
import ru.jrestly.SimpleModuleInfo;
import ru.jrestly.compression.ContentCompressor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ResponseDecompressor — response decompression collaborator (ADR-0087)")
class ResponseDecompressorTest {

    private static byte[] gzip(byte[] input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(input);
        }
        return baos.toByteArray();
    }

    private static byte[] deflateZlib(byte[] input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos)) {
            dos.write(input);
        }
        return baos.toByteArray();
    }

    private static byte[] deflateRaw(byte[] input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos, deflater)) {
            dos.write(input);
        } finally {
            deflater.end();
        }
        return baos.toByteArray();
    }

    @Test
    @DisplayName("Private constructor invocation satisfies utility-class contract")
    void privateConstructor() throws Exception {
        Constructor<ResponseDecompressor> ctor = ResponseDecompressor.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertNotNull(ctor.newInstance());
    }

    @Test
    @DisplayName("decompress with null response returns null")
    void nullResponse() throws IOException {
        ModuleInfo info = SimpleModuleInfo.builder().baseUrl("http://localhost").build();
        assertNull(ResponseDecompressor.decompress(null, info));
    }

    @Test
    @DisplayName("decompress with null moduleInfo returns response as-is")
    void nullModuleInfo() throws IOException {
        WireResponse resp = new WireResponse(200, List.of(new Header("Content-Encoding", "gzip")), new byte[]{1, 2, 3});
        assertSame(resp, ResponseDecompressor.decompress(resp, null));
    }

    @Test
    @DisplayName("decompress when decompression disabled returns response as-is")
    void decompressionDisabled() throws IOException {
        ModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost")
                .decompression(false)
                .build();
        WireResponse resp = new WireResponse(200, List.of(new Header("Content-Encoding", "gzip")), new byte[]{1, 2, 3});
        assertSame(resp, ResponseDecompressor.decompress(resp, info));
    }

    @Test
    @DisplayName("decompress without Content-Encoding header returns response as-is")
    void noContentEncodingHeader() throws IOException {
        ModuleInfo info = SimpleModuleInfo.builder().baseUrl("http://localhost").build();
        WireResponse resp = new WireResponse(200, List.of(new Header("Content-Type", "text/plain")), "hello".getBytes(StandardCharsets.UTF_8));
        assertSame(resp, ResponseDecompressor.decompress(resp, info));
    }

    @Test
    @DisplayName("decompress with identity Content-Encoding returns response as-is")
    void identityContentEncoding() throws IOException {
        ModuleInfo info = SimpleModuleInfo.builder().baseUrl("http://localhost").build();
        WireResponse resp = new WireResponse(200, List.of(new Header("Content-Encoding", "identity")), "hello".getBytes(StandardCharsets.UTF_8));
        assertSame(resp, ResponseDecompressor.decompress(resp, info));
    }

    @Test
    @DisplayName("decompress with unrecognized encoding without custom compressor returns response as-is")
    void unrecognizedEncoding() throws IOException {
        ModuleInfo info = SimpleModuleInfo.builder().baseUrl("http://localhost").build();
        WireResponse resp = new WireResponse(200, List.of(new Header("Content-Encoding", "br")), "brotli-data".getBytes(StandardCharsets.UTF_8));
        assertSame(resp, ResponseDecompressor.decompress(resp, info));
    }

    @Test
    @DisplayName("decompress gzip bytes: uncompresses body and strips Content-Encoding and Content-Length")
    void gzipBytes() throws IOException {
        ModuleInfo info = SimpleModuleInfo.builder().baseUrl("http://localhost").build();
        byte[] payload = "Hello Gzip World!".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = gzip(payload);

        List<Header> headers = List.of(
                new Header(null, "Nameless"),
                new Header("content-encoding", "gzip"),
                new Header("Content-Length", String.valueOf(compressed.length)),
                new Header("X-Custom", "kept")
        );
        WireResponse raw = new WireResponse(200, headers, compressed);
        WireResponse result = ResponseDecompressor.decompress(raw, info);

        assertEquals(200, result.statusCode());
        assertArrayEquals(payload, result.body());
        assertNull(result.stream());

        List<Header> stripped = result.headers();
        assertEquals(2, stripped.size());
        assertEquals("Nameless", stripped.get(0).value());
        assertEquals("X-Custom", stripped.get(1).name());
        assertEquals("kept", stripped.get(1).value());

        assertThrows(UnsupportedOperationException.class, () -> stripped.add(new Header("foo", "bar")));
    }

    @Test
    @DisplayName("decompress x-gzip bytes works identically to gzip")
    void xGzipBytes() throws IOException {
        ModuleInfo info = SimpleModuleInfo.builder().baseUrl("http://localhost").build();
        byte[] payload = "Hello X-Gzip!".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = gzip(payload);

        WireResponse raw = new WireResponse(200, List.of(new Header("Content-Encoding", "x-gzip")), compressed);
        WireResponse result = ResponseDecompressor.decompress(raw, info);

        assertArrayEquals(payload, result.body());
    }

    @Test
    @DisplayName("decompress deflate zlib (RFC 1950) bytes")
    void deflateZlibBytes() throws IOException {
        ModuleInfo info = SimpleModuleInfo.builder().baseUrl("http://localhost").build();
        byte[] payload = "Hello Deflate Zlib!".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = deflateZlib(payload);

        WireResponse raw = new WireResponse(200, List.of(new Header("Content-Encoding", "deflate")), compressed);
        WireResponse result = ResponseDecompressor.decompress(raw, info);

        assertArrayEquals(payload, result.body());
    }

    @Test
    @DisplayName("decompress deflate raw (RFC 1951) fallback bytes")
    void deflateRawFallbackBytes() throws IOException {
        ModuleInfo info = SimpleModuleInfo.builder().baseUrl("http://localhost").build();
        byte[] payload = "Hello Deflate Raw RFC 1951!".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = deflateRaw(payload);

        WireResponse raw = new WireResponse(200, List.of(new Header("Content-Encoding", "deflate")), compressed);
        WireResponse result = ResponseDecompressor.decompress(raw, info);

        assertArrayEquals(payload, result.body());
    }

    @Test
    @DisplayName("decompress with custom ContentCompressor bytes")
    void customCompressorBytes() throws IOException {
        ContentCompressor dummy = new ContentCompressor() {
            @Override
            public String encoding() {
                return "custom";
            }

            @Override
            public byte[] compress(byte[] data) {
                return data;
            }

            @Override
            public byte[] decompress(byte[] data) {
                return ("custom:" + new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            }
        };

        ModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost")
                .contentCompressor(dummy)
                .build();

        WireResponse raw = new WireResponse(200, List.of(new Header("Content-Encoding", "custom")), "foo".getBytes(StandardCharsets.UTF_8));
        WireResponse result = ResponseDecompressor.decompress(raw, info);

        assertEquals("custom:foo", new String(result.body(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("decompress streaming body wraps into decompressing InputStream (gzip, deflate, custom)")
    void streamingBodies() throws IOException {
        byte[] payload = "Streaming data chunk".getBytes(StandardCharsets.UTF_8);
        byte[] gz = gzip(payload);
        byte[] def = deflateZlib(payload);

        ModuleInfo info = SimpleModuleInfo.builder().baseUrl("http://localhost").build();

        // Gzip stream
        WireResponse gzRaw = new WireResponse(200, List.of(new Header("Content-Encoding", "gzip")), null, new ByteArrayInputStream(gz), null, null, null);
        WireResponse gzResult = ResponseDecompressor.decompress(gzRaw, info);
        assertNotNull(gzResult.stream());
        assertArrayEquals(payload, gzResult.stream().readAllBytes());

        // Deflate stream
        WireResponse defRaw = new WireResponse(200, List.of(new Header("Content-Encoding", "deflate")), null, new ByteArrayInputStream(def), null, null, null);
        WireResponse defResult = ResponseDecompressor.decompress(defRaw, info);
        assertNotNull(defResult.stream());
        assertArrayEquals(payload, defResult.stream().readAllBytes());

        // Custom stream
        ContentCompressor custom = new ContentCompressor() {
            @Override public String encoding() { return "my-stream"; }
            @Override public byte[] compress(byte[] d) { return d; }
            @Override public byte[] decompress(byte[] d) { return d; }
            @Override public InputStream decompress(InputStream s) {
                return new ByteArrayInputStream("custom-stream".getBytes(StandardCharsets.UTF_8));
            }
        };
        ModuleInfo customInfo = SimpleModuleInfo.builder().baseUrl("http://localhost").contentCompressor(custom).build();
        WireResponse customRaw = new WireResponse(200, List.of(new Header("Content-Encoding", "my-stream")), null, new ByteArrayInputStream(new byte[0]), null, null, null);
        WireResponse customResult = ResponseDecompressor.decompress(customRaw, customInfo);
        assertNotNull(customResult.stream());
        assertEquals("custom-stream", new String(customResult.stream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("decompress with empty or null body returns stripped headers without error")
    void emptyOrNullBody() throws IOException {
        ModuleInfo info = SimpleModuleInfo.builder().baseUrl("http://localhost").build();

        WireResponse nullBody = new WireResponse(200, List.of(new Header("Content-Encoding", "gzip")), (byte[]) null);
        WireResponse r1 = ResponseDecompressor.decompress(nullBody, info);
        assertNull(r1.body());
        assertTrue(r1.headers().isEmpty());

        WireResponse emptyBody = new WireResponse(200, List.of(new Header("Content-Encoding", "gzip")), new byte[0]);
        WireResponse r2 = ResponseDecompressor.decompress(emptyBody, info);
        assertEquals(0, r2.body().length);
        assertTrue(r2.headers().isEmpty());
    }

    @Test
    @DisplayName("corrupted gzip payload throws IOException")
    void corruptedGzipThrows() {
        ModuleInfo info = SimpleModuleInfo.builder().baseUrl("http://localhost").build();
        WireResponse corrupted = new WireResponse(200, List.of(new Header("Content-Encoding", "gzip")), "not a gzip".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> ResponseDecompressor.decompress(corrupted, info));
    }
}
