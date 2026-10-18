package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.SimpleModuleInfo;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.MultipartFormFile;
import ru.jrestly.annotation.PathVariable;
import ru.jrestly.annotation.Post;

import java.lang.reflect.Method;
import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@code HttpTemplateBuilder} progress-listener resolution
 * (ADR-0030 / ADR-0033): upload and download listeners are extracted from the
 * invocation arguments, with a null argument leaving the listener unset. No
 * network.
 */
class ProgressListenerResolutionTest {

    interface DownloadController {
        @Get("/files/${id}")
        byte[] download(@PathVariable String id, DownloadProgressListener listener);
    }

    interface UploadController {
        @Post("/upload")
        String upload(@MultipartFormFile(partName = "file") FileContent file, UploadProgressListener listener);
    }

    @Test
    @DisplayName("HttpTemplateBuilder extracts download listener when passed and handles null gracefully")
    void builderExtractsDownloadListener() throws NoSuchMethodException {
        Method downloadMethod = DownloadController.class.getMethod("download", String.class, DownloadProgressListener.class);
        DownloadProgressListener myListener = (read, total) -> {};

        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();
        HttpClient httpClient = HttpClient.newHttpClient();

        try {
            // Passed listener
            HttpTemplate templateWithListener = new HttpTemplateBuilder(
                    moduleInfo, httpClient, downloadMethod,
                    new Object[]{"file-123", myListener}, DownloadController.class).build();
            assertSame(myListener, templateWithListener.getDownloadProgressListener());

            // Passed null
            HttpTemplate templateWithNull = new HttpTemplateBuilder(
                    moduleInfo, httpClient, downloadMethod,
                    new Object[]{"file-123", null}, DownloadController.class).build();
            assertNull(templateWithNull.getDownloadProgressListener());
        } finally {
            httpClient.close();
        }
    }

    @Test
    @DisplayName("HttpTemplateBuilder extracts upload listener when passed and handles null gracefully")
    void builderExtractsUploadListener() throws NoSuchMethodException {
        Method uploadMethod = UploadController.class.getMethod("upload", FileContent.class, UploadProgressListener.class);
        UploadProgressListener myListener = (sent, total) -> {};
        FileContent dummyFile = FileContent.of("test.txt", "abc".getBytes());

        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();
        HttpClient httpClient = HttpClient.newHttpClient();

        try {
            // Passed listener
            HttpTemplate templateWithListener = new HttpTemplateBuilder(
                    moduleInfo, httpClient, uploadMethod,
                    new Object[]{dummyFile, myListener}, UploadController.class).build();
            assertSame(myListener, templateWithListener.getUploadProgressListener());

            // Passed null
            HttpTemplate templateWithNull = new HttpTemplateBuilder(
                    moduleInfo, httpClient, uploadMethod,
                    new Object[]{dummyFile, null}, UploadController.class).build();
            assertNull(templateWithNull.getUploadProgressListener());
        } finally {
            httpClient.close();
        }
    }
}
