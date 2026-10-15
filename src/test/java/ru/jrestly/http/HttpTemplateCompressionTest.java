package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.SimpleModuleInfo;
import ru.jrestly.annotation.Compress;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestBody;
import ru.jrestly.compression.CompressionAlgorithm;
import ru.jrestly.compression.GzipContentCompressor;

import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpTemplateCompressionTest {

    interface CompressApi {
        @Post(path = "/compress")
        @Compress(algorithm = CompressionAlgorithm.GZIP, minSizeBytes = 10)
        String sendData(@RequestBody String payload);
    }

    @Test
    @DisplayName("HttpTemplateBuilder compresses body and sets Content-Encoding header")
    void testHttpTemplateDirectCompressionBytes() throws Exception {
        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();
        Method method = CompressApi.class.getMethod("sendData", String.class);
        String largeData = "Repeated large payload ".repeat(20);

        HttpTemplateBuilder builder = new HttpTemplateBuilder(
                info,
                HttpClient.newHttpClient(),
                method,
                new Object[]{largeData},
                CompressApi.class
        );
        HttpTemplate template = builder.build();

        assertNotNull(template.getBody());
        assertTrue(template.getBody().length < largeData.getBytes(StandardCharsets.UTF_8).length);
        byte[] decompressed = GzipContentCompressor.INSTANCE.decompress(template.getBody());
        assertEquals(largeData, new String(decompressed, StandardCharsets.UTF_8));

        boolean hasEncodingHeader = template.getHeaders().stream()
                .anyMatch(h -> "Content-Encoding".equalsIgnoreCase(h.name()) && "gzip".equalsIgnoreCase(h.value()));
        assertTrue(hasEncodingHeader, "Template must carry Content-Encoding: gzip header");
    }
}
