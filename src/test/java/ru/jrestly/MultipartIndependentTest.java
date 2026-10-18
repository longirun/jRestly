package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.jrestly.annotation.HttpVersion;
import ru.jrestly.annotation.MultipartFormFile;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.http.FileContent;
import ru.jrestly.http.MultipartPart;
import ru.jrestly.http.RequestType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent pass over ADR-0005 stages 1-4: covers aspects the author's
 * MultipartTest leaves open — factory validation, the open-SPI contract,
 * boundary shape/uniqueness, exact Content-Length for mixed part sets,
 * chunked fallback for mixed known/unknown lengths, empty content,
 * supplier failure, lazy disk reads, UTF-8 file names, mime-map spot checks,
 * cross-parameter part ordering, null collection/array elements, empty
 * varargs, and fresh-stream re-send with boundary preservation on redirects.
 */
class MultipartIndependentTest extends BaseWireMockTest {

    private static final Pattern BOUNDARY_PATTERN = Pattern.compile("^jrestly-[0-9a-f]{32}$");

    @TempDir
    Path tempDir;

    /** Only the fixtures missing from TestController live here; everything else reuses it. */
    public interface ProbeController {

        @Post(value = "/api/upload",  requestType = RequestType.MULTIPART_FORM_DATA)
        @HttpVersion(HttpClient.Version.HTTP_1_1) // chunked transfer encoding is HTTP/1.1 semantics
        String uploadStreamWithText(@MultipartFormFile(partName = "file") FileContent file,
                                    @RequestParam String comment);

        @Post(value = "/api/upload",  requestType = RequestType.MULTIPART_FORM_DATA)
        String uploadThreeParts(@MultipartFormFile(partName = "alpha") List<FileContent> alpha,
                                @RequestParam String note,
                                @MultipartFormFile(partName = "beta") List<FileContent> beta);
    }

    private ProbeController probeController() {
        return client.get(ProbeController.class);
    }

    private void stubUpload() {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse().withStatus(200).withBody("uploaded")));
    }

    private static String boundaryOf(String contentType) {
        int index = contentType.indexOf("boundary=");
        if (index < 0) {
            throw new IllegalStateException("No boundary in Content-Type: " + contentType);
        }
        return contentType.substring(index + "boundary=".length());
    }

    // FileContent factories: validation and accessor contract

    @Test
    @DisplayName("FileContent factories reject null arguments")
    void factoriesRejectNullArguments() {
        assertThrows(NullPointerException.class, () -> FileContent.of((Path) null));
        assertThrows(NullPointerException.class, () -> FileContent.of(null, new byte[0]));
        assertThrows(NullPointerException.class, () -> FileContent.of("x.txt", (byte[]) null));
        assertThrows(NullPointerException.class,
                () -> FileContent.of(null, () -> new ByteArrayInputStream(new byte[0])));
        assertThrows(NullPointerException.class, () -> FileContent.of("x.txt", (Supplier<InputStream>) null));
    }

    @Test
    @DisplayName("A zero content length is valid — only negative values are rejected")
    void zeroContentLengthIsValid() {
        FileContent content = assertDoesNotThrow(() ->
                FileContent.of("empty.bin", () -> new ByteArrayInputStream(new byte[0]), 0L));
        assertEquals(0L, content.contentLength());
    }

    @Test
    @DisplayName("FileContent accessors expose the documented name, source and length")
    void accessorsExposeDocumentedValues() throws IOException {
        Path file = tempDir.resolve("doc.txt");
        Files.writeString(file, "disk");

        FileContent pathContent = FileContent.of(file);
        assertEquals("doc.txt", pathContent.fileName());
        assertEquals(file, pathContent.content());
        assertNull(pathContent.contentLength(), "disk length is derived at send time");

        byte[] bytes = "memory".getBytes(StandardCharsets.UTF_8);
        FileContent bytesContent = FileContent.of("memo.txt", bytes);
        assertEquals("memo.txt", bytesContent.fileName());
        assertEquals(bytes, bytesContent.content());
        assertNull(bytesContent.contentLength(), "byte[] length is derived from the array");

        Supplier<InputStream> source = () -> new ByteArrayInputStream(new byte[0]);
        FileContent streamContent = FileContent.of("flow.txt", source);
        assertEquals("flow.txt", streamContent.fileName());
        assertEquals(source, streamContent.content());
        assertNull(streamContent.contentLength());

        FileContent sizedStream = FileContent.of("flow.txt", source, 17L);
        assertEquals(17L, sizedStream.contentLength());
    }

    // Custom FileContent implementations

    /** A consumer-written implementation: not one of the library's hidden records. */
    private static final class CustomStreamFileContent implements FileContent {
        private final String name;
        private final Supplier<InputStream> source;
        private final long length;

        CustomStreamFileContent(String name, Supplier<InputStream> source, long length) {
            this.name = name;
            this.source = source;
            this.length = length;
        }

        @Override
        public String fileName() {
            return name;
        }

        @Override
        public Object content() {
            return source;
        }

        @Override
        public Long contentLength() {
            return length;
        }
    }

    /** Same, but without the contentLength override — the default (null) applies. */
    private static final class CustomSizedBytesFileContent implements FileContent {
        private final String name;
        private final byte[] bytes;

        CustomSizedBytesFileContent(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }

        @Override
        public String fileName() {
            return name;
        }

        @Override
        public Object content() {
            return bytes;
        }
    }

    @Test
    @DisplayName("A consumer FileContent implementation with a known length streams with exact Content-Length")
    void customFileContentImplementationIsAccepted() {
        stubUpload();

        byte[] payload = "SPIabc".getBytes(StandardCharsets.UTF_8);
        String response = controller.uploadFileContent(
                new CustomStreamFileContent("spi.bin", () -> new ByteArrayInputStream(payload), payload.length));

        assertEquals("uploaded", response);
        var event = wireMockServer.getAllServeEvents().stream()
                .filter(e -> "/api/upload".equals(e.getRequest().getUrl()))
                .findFirst()
                .orElseThrow();
        assertTrue(event.getRequest().containsHeader("Content-Length"),
                "explicit length through the SPI must yield exact Content-Length, not chunked");
        assertEquals(event.getRequest().getBody().length,
                Integer.parseInt(event.getRequest().getHeader("Content-Length")));
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("spi.bin")
                        .withBody(binaryEqualTo(payload))
                        .build()));
    }

    @Test
    @DisplayName("A consumer FileContent implementation with derived length (byte[]) is accepted")
    void customFileContentWithDerivedLengthIsAccepted() {
        stubUpload();

        byte[] payload = "derived length".getBytes(StandardCharsets.UTF_8);
        controller.uploadFileContent(new CustomSizedBytesFileContent("spi-bytes.txt", payload));

        var event = wireMockServer.getAllServeEvents().stream()
                .filter(e -> "/api/upload".equals(e.getRequest().getUrl()))
                .findFirst()
                .orElseThrow();
        assertTrue(event.getRequest().containsHeader("Content-Length"),
                "byte[] content length is derivable, so the body must not go chunked");
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("spi-bytes.txt")
                        .withBody(binaryEqualTo(payload))
                        .build()));
    }

    @Test
    @DisplayName("An unsupported content source from a custom implementation is rejected with a clear error")
    void unsupportedCustomContentIsRejected() {
        FileContent bogus = new FileContent() {
            @Override
            public String fileName() {
                return "bogus.txt";
            }

            @Override
            public Object content() {
                return "not a Path, byte[] or Supplier";
            }
        };

        var exception = assertThrows(IllegalStateException.class, () -> controller.uploadFileContent(bogus));
        assertTrue(exception.getMessage().contains("Unsupported multipart content"));
    }

    @Test
    @DisplayName("A consumer FileContent implementation returning a negative contentLength is rejected")
    void customFileContentWithNegativeLengthIsRejected() {
        stubUpload();

        var bad = new CustomStreamFileContent("neg.bin", () -> new ByteArrayInputStream(new byte[0]), -1L);
        var exception = assertThrows(IllegalArgumentException.class,
                () -> controller.uploadFileContentFromStream(bad));
        assertTrue(exception.getMessage().contains("contentLength"));
    }

    @Test
    @DisplayName("A Path with no filename is rejected by FileContent.of and MultipartPart.file")
    void pathWithoutFileNameIsRejected() {
        Path root = Path.of("/");
        assertThrows(IllegalArgumentException.class, () -> FileContent.of(root));
        assertThrows(IllegalArgumentException.class, () -> MultipartPart.file("part", root));
    }

    @Test
    @DisplayName("MultipartPart.contentTypeFor(null) falls back to application/octet-stream")
    void contentTypeForNullFileNameFallsBackToDefault() {
        assertEquals("application/octet-stream", MultipartPart.contentTypeFor(null));
    }

    @Test
    @DisplayName("Unsupported parameter object types in multipart file parameters are rejected")
    void unsupportedFileParameterTypeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> controller.uploadMixedFiles(List.of(12345)));
    }

    // Boundary and framing

    @Test
    @DisplayName("Boundary is jrestly- plus a 32-char undashed UUID hex, unique per request")
    void boundaryFormatAndUniqueness() {
        stubUpload();

        controller.uploadFileContent(FileContent.of("a.txt", "one".getBytes(StandardCharsets.UTF_8)));
        controller.uploadFileContent(FileContent.of("b.txt", "two".getBytes(StandardCharsets.UTF_8)));

        List<String> boundaries = wireMockServer.getAllServeEvents().stream()
                .filter(e -> "/api/upload".equals(e.getRequest().getUrl()))
                .map(e -> boundaryOf(e.getRequest().getHeader("Content-Type")))
                .toList();
        assertEquals(2, boundaries.size());
        for (String boundary : boundaries) {
            assertTrue(BOUNDARY_PATTERN.matcher(boundary).matches(),
                    "Boundary must be jrestly- + 32 hex chars, got: " + boundary);
        }
        assertNotEquals(boundaries.get(0), boundaries.get(1), "Each request gets its own boundary");
    }

    @Test
    @DisplayName("Mixed known-length parts send exact Content-Length covering the whole framing")
    void mixedKnownLengthSendsExactContentLength() {
        stubUpload();

        byte[] payload = "stream-part".getBytes(StandardCharsets.UTF_8);
        String response = probeController().uploadStreamWithText(
                FileContent.of("s.bin", () -> new ByteArrayInputStream(payload), payload.length),
                "hello");

        assertEquals("uploaded", response);
        var event = wireMockServer.getAllServeEvents().stream()
                .filter(e -> "/api/upload".equals(e.getRequest().getUrl()))
                .findFirst()
                .orElseThrow();
        assertTrue(event.getRequest().containsHeader("Content-Length"),
                "all lengths known -> exact Content-Length");
        assertEquals(event.getRequest().getBody().length,
                Integer.parseInt(event.getRequest().getHeader("Content-Length")),
                "Content-Length must count the entire multipart body, framing included");
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("s.bin")
                        .withBody(binaryEqualTo(payload))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("comment")
                        .withBody(equalTo("hello"))
                        .build()));
    }

    @Test
    @DisplayName("One unknown-length part among known ones forces chunked for the whole body")
    void mixedKnownAndUnknownLengthGoesChunked() {
        stubUpload();

        probeController().uploadStreamWithText(
                FileContent.of("u.bin", () -> new ByteArrayInputStream("unknown".getBytes(StandardCharsets.UTF_8))),
                "hello");

        var event = wireMockServer.getAllServeEvents().stream()
                .filter(e -> "/api/upload".equals(e.getRequest().getUrl()))
                .findFirst()
                .orElseThrow();
        assertFalse(event.getRequest().containsHeader("Content-Length"));
        assertTrue(event.getRequest().header("Transfer-Encoding").values().stream()
                .anyMatch(v -> v.contains("chunked")));
        // the chunked body still parses into complete parts
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("u.bin")
                        .withBody(equalTo("unknown"))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("comment")
                        .withBody(equalTo("hello"))
                        .build()));
    }

    @Test
    @DisplayName("Empty byte[] content sends a valid part with an empty body and exact Content-Length")
    void emptyBytesContentPart() {
        stubUpload();

        controller.uploadFileContent(FileContent.of("empty.bin", new byte[0]));

        var event = wireMockServer.getAllServeEvents().stream()
                .filter(e -> "/api/upload".equals(e.getRequest().getUrl()))
                .findFirst()
                .orElseThrow();
        assertTrue(event.getRequest().containsHeader("Content-Length"));
        assertEquals(event.getRequest().getBody().length,
                Integer.parseInt(event.getRequest().getHeader("Content-Length")));
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("empty.bin")
                        .withBody(equalTo(""))
                        .build()));
    }

    @Test
    @DisplayName("A zero-length stream with explicit length 0 sends with exact Content-Length")
    void zeroLengthStreamPart() {
        stubUpload();

        controller.uploadFileContent(FileContent.of(
                "empty-stream.bin", () -> new ByteArrayInputStream(new byte[0]), 0L));

        var event = wireMockServer.getAllServeEvents().stream()
                .filter(e -> "/api/upload".equals(e.getRequest().getUrl()))
                .findFirst()
                .orElseThrow();
        assertTrue(event.getRequest().containsHeader("Content-Length"));
        assertEquals(event.getRequest().getBody().length,
                Integer.parseInt(event.getRequest().getHeader("Content-Length")));
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("empty-stream.bin")
                        .withBody(equalTo(""))
                        .build()));
    }

    @Test
    @DisplayName("A failing stream supplier surfaces as RuntimeException instead of a silently truncated body")
    void failingSupplierSurfacesAsError() {
        stubUpload();

        FileContent bad = FileContent.of("boom.bin",
                () -> {
                    throw new IllegalStateException("supplier exploded");
                }, 10L);

        assertThrows(RuntimeException.class, () -> controller.uploadFileContent(bad));
    }

    @Test
    @DisplayName("of(Path) is lazy: the factory succeeds on a missing file, the send fails with context")
    void diskReadIsLazyAndMissingFileIsReported() {
        Path missing = tempDir.resolve("does-not-exist.txt");

        FileContent content = assertDoesNotThrow(() -> FileContent.of(missing),
                "the factory must not touch the disk");

        var exception = assertThrows(RuntimeException.class,
                () -> controller.uploadFileContentFromPath(content));
        assertTrue(exception.getMessage().contains("Cannot read multipart file"),
                "Unexpected message: " + exception.getMessage());
    }

    // Content-Type derivation

    @Test
    @DisplayName("Mime map spot checks: json, pdf, mp4, compound extension, no extension, trailing dot")
    void mimeMapSpotChecks() {
        stubUpload();

        controller.uploadFileContents(Arrays.asList(
                FileContent.of("doc.json", "1".getBytes(StandardCharsets.UTF_8)),
                FileContent.of("report.pdf", "2".getBytes(StandardCharsets.UTF_8)),
                FileContent.of("clip.mp4", "3".getBytes(StandardCharsets.UTF_8)),
                FileContent.of("archive.tar.gz", "4".getBytes(StandardCharsets.UTF_8)),
                FileContent.of("README", "5".getBytes(StandardCharsets.UTF_8)),
                FileContent.of("trailing.", "6".getBytes(StandardCharsets.UTF_8))));

        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withRequestBodyPart(aMultipart().withFileName("doc.json")
                        .withHeader("Content-Type", containing("application/json")).build())
                .withRequestBodyPart(aMultipart().withFileName("report.pdf")
                        .withHeader("Content-Type", containing("application/pdf")).build())
                .withRequestBodyPart(aMultipart().withFileName("clip.mp4")
                        .withHeader("Content-Type", containing("video/mp4")).build())
                .withRequestBodyPart(aMultipart().withFileName("archive.tar.gz")
                        .withHeader("Content-Type", containing("application/gzip")).build())
                .withRequestBodyPart(aMultipart().withFileName("README")
                        .withHeader("Content-Type", containing("application/octet-stream")).build())
                .withRequestBodyPart(aMultipart().withFileName("trailing.")
                        .withHeader("Content-Type", containing("application/octet-stream")).build()));
    }

    // Ordering and null semantics

    @Test
    @DisplayName("Part order follows parameter order, then element order")
    void partOrderFollowsParameterThenElementOrder() {
        stubUpload();

        probeController().uploadThreeParts(
                List.of(FileContent.of("a1.txt", "alpha-one".getBytes(StandardCharsets.UTF_8)),
                        FileContent.of("a2.txt", "alpha-two".getBytes(StandardCharsets.UTF_8))),
                "note-token",
                List.of(FileContent.of("b1.txt", "beta-one".getBytes(StandardCharsets.UTF_8))));

        String body = new String(wireMockServer.getAllServeEvents().stream()
                .filter(e -> "/api/upload".equals(e.getRequest().getUrl()))
                .findFirst()
                .orElseThrow()
                .getRequest()
                .getBody(), StandardCharsets.UTF_8);

        assertTrue(body.contains("alpha-one"));
        assertTrue(body.indexOf("alpha-one") < body.indexOf("alpha-two"),
                "first element of the first parameter");
        assertTrue(body.indexOf("alpha-two") < body.indexOf("note-token"),
                "text parameter declared after the first file parameter");
        assertTrue(body.indexOf("note-token") < body.indexOf("beta-one"),
                "second file parameter comes last");
    }

    @Test
    @DisplayName("Null elements in a collection are skipped, non-null neighbors keep their order")
    void nullCollectionElementsAreSkipped() {
        stubUpload();

        controller.uploadFileContents(Arrays.asList(
                FileContent.of("first.txt", "one".getBytes(StandardCharsets.UTF_8)),
                null,
                FileContent.of("second.txt", "two".getBytes(StandardCharsets.UTF_8))));

        String body = new String(wireMockServer.getAllServeEvents().stream()
                .filter(e -> "/api/upload".equals(e.getRequest().getUrl()))
                .findFirst()
                .orElseThrow()
                .getRequest()
                .getBody(), StandardCharsets.UTF_8);
        assertEquals(2, occurrences(body, "filename="), "exactly two file parts");
        assertTrue(body.indexOf("first.txt") < body.indexOf("second.txt"));
    }

    @Test
    @DisplayName("Null elements in an array are skipped")
    void nullArrayElementsAreSkipped() throws IOException {
        stubUpload();

        Path first = tempDir.resolve("one.txt");
        Files.writeString(first, "one");
        Path second = tempDir.resolve("two.txt");
        Files.writeString(second, "two");

        controller.uploadFilePathArray(new Path[]{first, null, second});

        String body = new String(wireMockServer.getAllServeEvents().stream()
                .filter(e -> "/api/upload".equals(e.getRequest().getUrl()))
                .findFirst()
                .orElseThrow()
                .getRequest()
                .getBody(), StandardCharsets.UTF_8);
        assertEquals(2, occurrences(body, "filename="), "exactly two file parts");
        assertTrue(body.indexOf("one.txt") < body.indexOf("two.txt"));
    }

    @Test
    @DisplayName("Varargs called with zero arguments yields zero file parts")
    void emptyVarargsYieldZeroParts() {
        stubUpload();

        controller.uploadFilePathVarargs();

        verify(1, postRequestedFor(urlEqualTo("/api/upload")));
        verify(0, postRequestedFor(urlEqualTo("/api/upload"))
                .withRequestBodyPart(aMultipart().withName("file").build()));
    }

    // Wire encoding

    @Test
    @DisplayName("Non-ASCII file names and content travel as UTF-8 bytes")
    void utf8FileNameAndContentOnWire() {
        stubUpload();

        controller.uploadFileContent(FileContent.of("отчёт-2026.txt", "кириллица".getBytes(StandardCharsets.UTF_8)));

        String body = new String(wireMockServer.getAllServeEvents().stream()
                .filter(e -> "/api/upload".equals(e.getRequest().getUrl()))
                .findFirst()
                .orElseThrow()
                .getRequest()
                .getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("filename=\"отчёт-2026.txt\""),
                "file name must be written in UTF-8, got: " + body);
        assertTrue(body.contains("кириллица"),
                "file content must be written in UTF-8");
    }

    // Redirects: fresh stream and boundary preservation

    @Test
    @DisplayName("A 307 redirect re-sends the stream through a fresh supplier invocation and keeps the boundary")
    void redirectResendsFreshStreamAndPreservesBoundary() {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(307)
                        .withHeader("Location", baseUrl() + "/api/upload-2")));
        stubFor(post(urlEqualTo("/api/upload-2"))
                .willReturn(aResponse().withStatus(200).withBody("uploaded-2")));

        AtomicInteger supplierCalls = new AtomicInteger();
        byte[] payload = "fresh-stream-body".getBytes(StandardCharsets.UTF_8);
        String response = controller.uploadFileContentFollowRedirect(FileContent.of(
                "redirected-stream.txt",
                () -> {
                    supplierCalls.incrementAndGet();
                    return new ByteArrayInputStream(payload);
                },
                payload.length));

        assertEquals("uploaded-2", response);
        verify(1, postRequestedFor(urlEqualTo("/api/upload")));
        verify(postRequestedFor(urlEqualTo("/api/upload-2"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("redirected-stream.txt")
                        .withBody(equalTo("fresh-stream-body"))
                        .build()));

        assertEquals(2, supplierCalls.get(),
                "each hop must get a fresh stream from the supplier");

        String boundaryBefore = boundaryOf(wireMockServer.getAllServeEvents().stream()
                .filter(e -> "/api/upload".equals(e.getRequest().getUrl()))
                .findFirst().orElseThrow().getRequest().getHeader("Content-Type"));
        String boundaryAfter = boundaryOf(wireMockServer.getAllServeEvents().stream()
                .filter(e -> "/api/upload-2".equals(e.getRequest().getUrl()))
                .findFirst().orElseThrow().getRequest().getHeader("Content-Type"));
        assertEquals(boundaryBefore, boundaryAfter, "the boundary must survive the redirect");
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
