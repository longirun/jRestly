package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MultipartTest extends BaseWireMockTest {

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
}
