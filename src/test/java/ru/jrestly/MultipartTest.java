package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
