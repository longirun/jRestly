package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.PathVariable;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PathVariableEncodedTest extends BaseWireMockTest {

    interface StorageController {
        @Get(path = "/api/storage/${path}")
        String getRawPath(@PathVariable(name = "path", encoded = true) String path);

        @Get(path = "/api/tokens/${token}")
        String getRawToken(@PathVariable(name = "token", encoded = true) String token);

        @Get(path = "/api/default-encode/${name}")
        String getDefaultEncode(@PathVariable(name = "name") String name);
    }

    private StorageController storageController() {
        return client.get(StorageController.class);
    }

    @Test
    @DisplayName("encoded = true allows multi-segment paths with slashes without percent-encoding")
    void encodedTruePreservesSlashes() {
        stubFor(get(urlEqualTo("/api/storage/docs/2026/report.pdf"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("pdf-content")));

        String result = storageController().getRawPath("docs/2026/report.pdf");

        assertEquals("pdf-content", result);
    }

    @Test
    @DisplayName("encoded = true preserves pre-encoded percent sequences without double-encoding")
    void encodedTruePreservesPreEncodedStrings() {
        // "token%20123" must not become "token%2520123"
        stubFor(get(urlEqualTo("/api/tokens/token%20123"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("token-ok")));

        String result = storageController().getRawToken("token%20123");

        assertEquals("token-ok", result);
    }

    @Test
    @DisplayName("default encoded = false percent-encodes slashes and spaces as usual")
    void defaultEncodedFalseEncodesReservedChars() {
        stubFor(get(urlEqualTo("/api/default-encode/a%2Fb%20c"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("encoded-ok")));

        String result = storageController().getDefaultEncode("a/b c");

        assertEquals("encoded-ok", result);
    }

    @Test
    @DisplayName("null value with encoded = true still throws IllegalArgumentException fail-fast")
    void nullWithEncodedTrueThrowsException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                storageController().getRawPath(null));

        assertEquals("Path variable 'path' value cannot be null", ex.getMessage());
    }
}
