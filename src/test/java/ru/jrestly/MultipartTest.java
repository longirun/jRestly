package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.jrestly.annotation.MultipartFormFile;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.http.FileContent;
import ru.jrestly.http.MultipartPart;
import ru.jrestly.http.RequestType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end for multipart/form-data requests: file and {@code Path} uploads, custom
 * part names, dynamic maps, boundary handling and the wire format of composed bodies.
 */
class MultipartTest extends BaseWireMockTest {

    interface DynamicMultipartController {
        @Post(path = "/api/upload/dynamic", requestType = RequestType.MULTIPART_FORM_DATA)
        String uploadWithMap(@MultipartFormFile(partName = "file") FileContent file,
                             @RequestParam Map<String, ?> meta);

        @Post(path = "/api/upload/prefixed", requestType = RequestType.MULTIPART_FORM_DATA)
        String uploadWithPrefixedMap(@MultipartFormFile(partName = "file") FileContent file,
                                     @RequestParam(name = "meta") Map<String, ?> meta);
    }

    private DynamicMultipartController dynamicMultipartController() {
        return client.get(DynamicMultipartController.class);
    }

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("MULTIPART upload uses the String path as-is, without directory prefix")
    void uploadFileFromPath() throws IOException {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded")));

        Path file = tempDir.resolve("report.txt");
        Files.writeString(file, "multipart payload");

        String response = controller.uploadFile(file.toAbsolutePath().toString());

        assertEquals("uploaded", response);
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("report.txt")
                        .withBody(equalTo("multipart payload"))
                        .build()));
    }

    @Test
    @DisplayName("MULTIPART upload accepts a File param")
    void uploadFileObject() throws IOException {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded")));

        Path file = tempDir.resolve("avatar.png");
        Files.writeString(file, "binary-ish content");

        String response = controller.uploadFileObject(file.toFile());

        assertEquals("uploaded", response);
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("avatar.png")
                        .withBody(equalTo("binary-ish content"))
                        .build()));
    }

    @Test
    @DisplayName("MULTIPART sends @RequestParam values as text parts alongside the file part")
    void uploadFileWithTextPart() throws IOException {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded")));

        Path file = tempDir.resolve("report.txt");
        Files.writeString(file, "multipart payload");

        String response = controller.uploadFileWithText(file.toAbsolutePath().toString(), "hello world");

        assertEquals("uploaded", response);
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("report.txt")
                        .withBody(equalTo("multipart payload"))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("comment")
                        .withBody(equalTo("hello world"))
                        .build()));
    }

    @Test
    @DisplayName("MULTIPART upload accepts a Path param")
    void uploadFilePathParam() throws IOException {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded")));

        Path file = tempDir.resolve("notes.txt");
        Files.writeString(file, "path payload");

        String response = controller.uploadFilePath(file);

        assertEquals("uploaded", response);
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("notes.txt")
                        .withBody(equalTo("path payload"))
                        .build()));
    }

    @Test
    @DisplayName("A Collection @RequestParam becomes several text parts sharing one name")
    void uploadFileWithCollectionTextParts() throws IOException {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded")));

        Path file = tempDir.resolve("report.txt");
        Files.writeString(file, "multipart payload");

        String response = controller.uploadFileWithTextParts(
                file.toAbsolutePath().toString(), List.of("alpha", "beta"));

        assertEquals("uploaded", response);
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("report.txt")
                        .withBody(equalTo("multipart payload"))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("tags")
                        .withBody(equalTo("alpha"))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("tags")
                        .withBody(equalTo("beta"))
                        .build()));
    }

    @Test
    @DisplayName("A Collection @RequestParam with null elements skips nulls in multipart text parts")
    void uploadFileWithCollectionTextPartsContainingNullsSkipsNullElements() throws IOException {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded")));

        Path file = tempDir.resolve("report2.txt");
        Files.writeString(file, "multipart payload");

        String response = controller.uploadFileWithTextParts(
                file.toAbsolutePath().toString(), Arrays.asList("alpha", null, "beta"));

        assertEquals("uploaded", response);
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("report2.txt")
                        .withBody(equalTo("multipart payload"))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("tags")
                        .withBody(equalTo("alpha"))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("tags")
                        .withBody(equalTo("beta"))
                        .build()));
    }

    @Test
    @DisplayName("MULTIPART upload accepts an in-memory FileContent; the file name comes from the value")
    void uploadFileContentFromBytes() {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded")));

        String response = controller.uploadFileContent(
                FileContent.of("memo.txt", "in-memory content".getBytes(StandardCharsets.UTF_8)));

        assertEquals("uploaded", response);
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("memo.txt")
                        .withBody(equalTo("in-memory content"))
                        .build()));
    }

    @Test
    @DisplayName("MULTIPART upload accepts a FileContent backed by a stream supplier")
    void uploadFileContentFromStream() {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded")));

        String response = controller.uploadFileContentFromStream(FileContent.of(
                "streamed.txt",
                () -> new ByteArrayInputStream("streamed content".getBytes(StandardCharsets.UTF_8))));

        assertEquals("uploaded", response);
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("streamed.txt")
                        .withBody(equalTo("streamed content"))
                        .build()));
    }

    @Test
    @DisplayName("Known-length multipart body is byte-identical to the pre-streaming writer output")
    void knownLengthBodyIsByteIdentical() throws IOException {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded")));

        Path file = tempDir.resolve("report.txt");
        Files.writeString(file, "multipart payload");

        controller.uploadFileWithText(file.toAbsolutePath().toString(), "hello world");

        var event = wireMockServer.getAllServeEvents().stream()
                .filter(e -> "/api/upload".equals(e.getRequest().getUrl()))
                .findFirst()
                .orElseThrow();
        String boundary = boundaryOf(event.getRequest().getHeader("Content-Type"));

        assertArrayEquals(
                expectedLegacyBody(boundary, "file", "report.txt", "multipart payload", "comment", "hello world"),
                event.getRequest().getBody());
    }

    @Test
    @DisplayName("A stream source without a known length sends chunked, without Content-Length")
    void unknownLengthBodyGoesChunked() {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded")));

        controller.uploadFileContentFromStream(FileContent.of(
                "streamed.txt",
                () -> new ByteArrayInputStream("chunked content".getBytes(StandardCharsets.UTF_8))));

        var event = wireMockServer.getAllServeEvents().stream()
                .filter(e -> "/api/upload".equals(e.getRequest().getUrl()))
                .findFirst()
                .orElseThrow();

        assertFalse(event.getRequest().containsHeader("Content-Length"));
        assertTrue(event.getRequest().header("Transfer-Encoding").values().stream()
                .anyMatch(v -> v.contains("chunked")));
    }

    @Test
    @DisplayName("A large stream source with an explicit length streams with exact Content-Length")
    void largeFileStreams() {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded")));

        byte[] pattern = new byte[4 * 1024 * 1024];
        new Random(42).nextBytes(pattern);

        controller.uploadFileContent(FileContent.of(
                "big.bin", () -> new ByteArrayInputStream(pattern), pattern.length));

        var event = wireMockServer.getAllServeEvents().stream()
                .filter(e -> "/api/upload".equals(e.getRequest().getUrl()))
                .findFirst()
                .orElseThrow();

        // exact Content-Length for the whole multipart payload — not chunked
        assertTrue(event.getRequest().containsHeader("Content-Length"));
        assertEquals(event.getRequest().getBody().length,
                Integer.parseInt(event.getRequest().getHeader("Content-Length")));
        // the parsed part carries the full pattern byte-for-byte
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("big.bin")
                        .withBody(binaryEqualTo(pattern))
                        .build()));
    }

    private static String boundaryOf(String contentType) {
        int index = contentType.indexOf("boundary=");
        if (index < 0) {
            throw new IllegalStateException("No boundary in Content-Type: " + contentType);
        }
        return contentType.substring(index + "boundary=".length());
    }

    // Expected RFC 7578 multipart body layout, used as regression reference.
    // Stage 4 wire change applied: the file part carries the extension-derived type
    // instead of the pre-0.6.0 blanket application/octet-stream.
    private static byte[] expectedLegacyBody(String boundary,
                                             String filePartName,
                                             String fileName,
                                             String fileContent,
                                             String textPartName,
                                             String textValue) {
        String crlf = "\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        out.writeBytes(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.writeBytes(("Content-Disposition: form-data; name=\"" + filePartName + "\"; filename=\"" + fileName + "\"" + crlf)
                .getBytes(StandardCharsets.UTF_8));
        out.writeBytes(("Content-Type: text/plain" + crlf).getBytes(StandardCharsets.UTF_8));
        out.writeBytes(crlf.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(fileContent.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(crlf.getBytes(StandardCharsets.UTF_8));

        out.writeBytes(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.writeBytes(("Content-Disposition: form-data; name=\"" + textPartName + "\"" + crlf)
                .getBytes(StandardCharsets.UTF_8));
        out.writeBytes(("Content-Type: text/plain; charset=UTF-8" + crlf).getBytes(StandardCharsets.UTF_8));
        out.writeBytes(crlf.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(textValue.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(crlf.getBytes(StandardCharsets.UTF_8));

        out.writeBytes(("--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    @Test
    @DisplayName("MULTIPART upload accepts a FileContent read from disk; the file name comes from the path")
    void uploadFileContentFromDiskPath() throws IOException {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded")));

        Path file = tempDir.resolve("disk-content.txt");
        Files.writeString(file, "disk content");

        String response = controller.uploadFileContentFromPath(FileContent.of(file));

        assertEquals("uploaded", response);
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("disk-content.txt")
                        .withBody(equalTo("disk content"))
                        .build()));
    }

    @Test
    @DisplayName("The multipart body is re-sent on a 307 redirect")
    void multipartRedirectPreservesBody() {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(307)
                        .withHeader("Location", baseUrl() + "/api/upload-2")));
        stubFor(post(urlEqualTo("/api/upload-2"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded-2")));

        String response = controller.uploadFileContentFollowRedirect(
                FileContent.of("memo.txt", "redirected content".getBytes(StandardCharsets.UTF_8)));

        assertEquals("uploaded-2", response);
        verify(1, postRequestedFor(urlEqualTo("/api/upload")));
        verify(postRequestedFor(urlEqualTo("/api/upload-2"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("memo.txt")
                        .withBody(equalTo("redirected content"))
                        .build()));
    }

    @Test
    @DisplayName("FileContent.of rejects a negative content length")
    void negativeContentLengthIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> FileContent.of("x.txt", () -> new ByteArrayInputStream(new byte[0]), -1));
    }

    @Test
    @DisplayName("A Collection of paths yields one part per element, in element order")
    void uploadFilePathCollection() throws IOException {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded")));

        Path first = tempDir.resolve("first.txt");
        Files.writeString(first, "first file");
        Path second = tempDir.resolve("second.txt");
        Files.writeString(second, "second file");

        String response = controller.uploadFilePaths(List.of(first, second));

        assertEquals("uploaded", response);
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("first.txt")
                        .withBody(equalTo("first file"))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("second.txt")
                        .withBody(equalTo("second file"))
                        .build()));

        var event = wireMockServer.getAllServeEvents().stream()
                .filter(e -> "/api/upload".equals(e.getRequest().getUrl()))
                .findFirst()
                .orElseThrow();
        String body = new String(event.getRequest().getBody(), StandardCharsets.UTF_8);
        assertTrue(body.indexOf("first file") < body.indexOf("second file"),
                "Parts must follow element order");
    }

    @Test
    @DisplayName("A Collection of FileContent values keeps each element's own file name")
    void uploadFileContentCollection() {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded")));

        String response = controller.uploadFileContents(List.of(
                FileContent.of("a.txt", "alpha content".getBytes(StandardCharsets.UTF_8)),
                FileContent.of("b.txt", "beta content".getBytes(StandardCharsets.UTF_8))));

        assertEquals("uploaded", response);
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("a.txt")
                        .withBody(equalTo("alpha content"))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("b.txt")
                        .withBody(equalTo("beta content"))
                        .build()));
    }

    @Test
    @DisplayName("An empty file collection yields zero file parts")
    void emptyCollectionYieldsZeroParts() {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded")));

        controller.uploadFileContents(List.of());

        verify(1, postRequestedFor(urlEqualTo("/api/upload")));
        verify(0, postRequestedFor(urlEqualTo("/api/upload"))
                .withRequestBodyPart(aMultipart().withName("file").build()));
    }

    @Test
    @DisplayName("A null collection argument is skipped entirely")
    void nullCollectionArgumentIsSkipped() {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded")));

        controller.uploadFilePaths(null);

        verify(1, postRequestedFor(urlEqualTo("/api/upload")));
        verify(0, postRequestedFor(urlEqualTo("/api/upload"))
                .withRequestBodyPart(aMultipart().withName("file").build()));
    }

    @Test
    @DisplayName("Varargs and arrays expand to one part per element")
    void uploadFilePathVarargsAndArray() throws IOException {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded")));

        Path first = tempDir.resolve("one.txt");
        Files.writeString(first, "one");
        Path second = tempDir.resolve("two.txt");
        Files.writeString(second, "two");

        assertEquals("uploaded", controller.uploadFilePathVarargs(first, second));
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("one.txt")
                        .withBody(equalTo("one"))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("two.txt")
                        .withBody(equalTo("two"))
                        .build()));

        assertEquals("uploaded", controller.uploadFilePathArray(new Path[]{first, second}));
    }

    @Test
    @DisplayName("Disk and FileContent sources mix freely in one collection")
    void uploadMixedFileSources() throws IOException {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded")));

        Path diskFile = tempDir.resolve("mixed-disk.txt");
        Files.writeString(diskFile, "from disk");

        String response = controller.uploadMixedFiles(List.of(
                diskFile,
                FileContent.of("mixed-memory.txt", "from memory".getBytes(StandardCharsets.UTF_8))));

        assertEquals("uploaded", response);
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("mixed-disk.txt")
                        .withBody(equalTo("from disk"))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("mixed-memory.txt")
                        .withBody(equalTo("from memory"))
                        .build()));
    }

    @Test
    @DisplayName("A known extension maps to its mime type, case-insensitively")
    void knownExtensionDerivesContentType() {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded")));

        String response = controller.uploadFileContent(FileContent.of(
                "photo.png", "png bytes".getBytes(StandardCharsets.UTF_8)));

        assertEquals("uploaded", response);
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("photo.png")
                        .withHeader("Content-Type", containing("image/png"))
                        .withBody(equalTo("png bytes"))
                        .build()));

        controller.uploadFileContent(FileContent.of(
                "PHOTO.PNG", "png bytes".getBytes(StandardCharsets.UTF_8)));
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("PHOTO.PNG")
                        .withHeader("Content-Type", containing("image/png"))
                        .withBody(equalTo("png bytes"))
                        .build()));
    }

    @Test
    @DisplayName("An unknown extension falls back to application/octet-stream")
    void unknownExtensionFallsBackToOctetStream() {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded")));

        controller.uploadFileContent(FileContent.of(
                "data.blob", "blob bytes".getBytes(StandardCharsets.UTF_8)));

        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("data.blob")
                        .withHeader("Content-Type", containing("application/octet-stream"))
                        .withBody(equalTo("blob bytes"))
                        .build()));
    }

    @Test
    @DisplayName("contentType() overrides the derived type for every part of the parameter")
    void explicitContentTypeOverrides() {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded")));

        String response = controller.uploadFileContentTyped(FileContent.of(
                "notes.txt", "typed content".getBytes(StandardCharsets.UTF_8)));

        assertEquals("uploaded", response);
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("notes.txt")
                        .withHeader("Content-Type", containing("application/xml"))
                        .withBody(equalTo("typed content"))
                        .build()));

        controller.uploadFileContentsTyped(List.of(
                FileContent.of("one.txt", "first".getBytes(StandardCharsets.UTF_8)),
                FileContent.of("two.txt", "second".getBytes(StandardCharsets.UTF_8))));
        verify(postRequestedFor(urlEqualTo("/api/upload"))
                .withRequestBodyPart(aMultipart()
                        .withName("files")
                        .withFileName("one.txt")
                        .withHeader("Content-Type", containing("text/xml"))
                        .withBody(equalTo("first"))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("files")
                        .withFileName("two.txt")
                        .withHeader("Content-Type", containing("text/xml"))
                        .withBody(equalTo("second"))
                        .build()));
    }

    @Test
    @DisplayName("Quotes and backslashes in name/filename are escaped per RFC 2045 quoted-string")
    void quotedNameAndFilenameAreEscapedOnWire() {
        stubFor(post(urlEqualTo("/api/upload"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded")));

        controller.uploadFileContentQuoted(FileContent.of(
                "we\"ird\\name.txt", "payload".getBytes(StandardCharsets.UTF_8)));

        var event = wireMockServer.getAllServeEvents().stream()
                .filter(e -> "/api/upload".equals(e.getRequest().getUrl()))
                .findFirst()
                .orElseThrow();
        String body = new String(event.getRequest().getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("name=\"a\\\"b\""),
                "Part name quote must be escaped: " + body);
        assertTrue(body.contains("filename=\"we\\\"ird\\\\name.txt\""),
                "Filename quotes/backslashes must be escaped: " + body);
    }

    @Test
    @DisplayName("CR/LF in a part name, filename, or contentType is rejected (header-injection guard)")
    void crLfInNameOrFilenameIsRejected() {
        FileContent crFilename = FileContent.of("bad\rname.txt", "x".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> controller.uploadFileContent(crFilename));

        FileContent lfFilename = FileContent.of("bad\nname.txt", "x".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> controller.uploadFileContent(lfFilename));

        assertThrows(IllegalArgumentException.class, () -> MultipartPart.text("bad\nname", "value"));
        assertThrows(IllegalArgumentException.class, () ->
                new MultipartPart("file", "good.txt", "text/plain\r\nInjected: true", "content", null));
        assertThrows(IllegalArgumentException.class, () ->
                new MultipartPart("file", "good.txt", "text/plain\nInjected: true", "content", null));

        assertThrows(NullPointerException.class, () ->
                new MultipartPart(null, "good.txt", "text/plain", "content", null));
    }

    @Test
    @DisplayName("MULTIPART upload with @RequestParam Map sends entries as text parts")
    void uploadWithMapRequestParam() {
        stubFor(post(urlEqualTo("/api/upload/dynamic"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded-dynamic")));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("author", "john");
        meta.put("version", 1);

        FileContent file = FileContent.of("doc.txt", "content".getBytes(StandardCharsets.UTF_8));
        String response = dynamicMultipartController().uploadWithMap(file, meta);

        assertEquals("uploaded-dynamic", response);
        verify(postRequestedFor(urlEqualTo("/api/upload/dynamic"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("doc.txt")
                        .withBody(equalTo("content"))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("author")
                        .withBody(equalTo("john"))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("version")
                        .withBody(equalTo("1"))
                        .build()));
    }

    @Test
    @DisplayName("MULTIPART upload with @RequestParam(name) Map sends prefixed text parts name[key]")
    void uploadWithPrefixedMapRequestParam() {
        stubFor(post(urlEqualTo("/api/upload/prefixed"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded-prefixed")));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("category", "reports");

        FileContent file = FileContent.of("doc.txt", "content".getBytes(StandardCharsets.UTF_8));
        String response = dynamicMultipartController().uploadWithPrefixedMap(file, meta);

        assertEquals("uploaded-prefixed", response);
        verify(postRequestedFor(urlEqualTo("/api/upload/prefixed"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("doc.txt")
                        .withBody(equalTo("content"))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("meta[category]")
                        .withBody(equalTo("reports"))
                        .build()));
    }

    @Test
    @DisplayName("MULTIPART upload with @RequestParam Map containing Collection sends multiple text parts")
    void uploadWithMapContainingCollection() {
        stubFor(post(urlEqualTo("/api/upload/dynamic"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded-multi")));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tags", List.of("finance", "quarterly"));

        FileContent file = FileContent.of("doc.txt", "content".getBytes(StandardCharsets.UTF_8));
        String response = dynamicMultipartController().uploadWithMap(file, meta);

        assertEquals("uploaded-multi", response);
        verify(postRequestedFor(urlEqualTo("/api/upload/dynamic"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBodyPart(aMultipart()
                        .withName("tags")
                        .withBody(equalTo("finance"))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("tags")
                        .withBody(equalTo("quarterly"))
                        .build()));
    }

    @Test
    @DisplayName("MULTIPART upload with @RequestParam Map skips null keys, null values, and null collection elements")
    void uploadWithMapSkipsNullKeysAndValues() {
        stubFor(post(urlEqualTo("/api/upload/dynamic"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("uploaded-clean")));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(null, "skipped");
        meta.put("ignored", null);
        meta.put("valid", "present");
        meta.put("tags", Arrays.asList("alpha", null, "beta"));

        FileContent file = FileContent.of("doc.txt", "content".getBytes(StandardCharsets.UTF_8));
        String response = dynamicMultipartController().uploadWithMap(file, meta);

        assertEquals("uploaded-clean", response);
        verify(postRequestedFor(urlEqualTo("/api/upload/dynamic"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBody(notContaining("name=\"null\""))
                .withRequestBody(notContaining("name=\"ignored\""))
                .withRequestBodyPart(aMultipart()
                        .withName("valid")
                        .withBody(equalTo("present"))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("tags")
                        .withBody(equalTo("alpha"))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("tags")
                        .withBody(equalTo("beta"))
                        .build()));
    }
}
