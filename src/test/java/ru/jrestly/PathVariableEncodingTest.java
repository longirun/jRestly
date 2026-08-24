package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.PathVariableController;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class PathVariableEncodingTest extends BaseWireMockTest {

    private PathVariableController pathController() {
        return client.get(PathVariableController.class);
    }

    @Test
    @DisplayName("path variable with non-ASCII characters is percent-encoded on the wire")
    void nonAsciiPathVariableIsPercentEncoded() {
        stubFor(get(urlEqualTo("/api/items/%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("encoded-ok")));

        String result = pathController().getItemBySlug("привет");

        assertEquals("encoded-ok", result);
    }

    @Test
    @DisplayName("path variable with reserved characters is percent-encoded keeping the path structure intact")
    void reservedCharactersPathVariableIsPercentEncoded() {
        // '/' must become %2F, otherwise a naive resolver would break the URL apart;
        // '&', '=', '?', '#' are equally unsafe in a path segment
        stubFor(get(urlEqualTo("/api/items/a%2Fb%26c%3Dd%3Fe%23f"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("reserved-ok")));

        String result = pathController().getItemBySlug("a/b&c=d?e#f");

        assertEquals("reserved-ok", result);
    }

    @Test
    @DisplayName("multiple path variables in one path are all substituted")
    void multiplePathVariablesAreAllSubstituted() {
        stubFor(get(urlEqualTo("/api/users/7/orders/42"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("order-ok")));

        String result = pathController().getUserOrder(7L, "42");

        assertEquals("order-ok", result);
    }

    @Test
    @DisplayName("placeholder without explicit name falls back to the method parameter name")
    void placeholderWithoutExplicitNameFallsBackToParameterName() {
        // no name = "..." on the annotation: the resolver must derive the placeholder key
        // from the Java parameter name ("sku"), which requires -parameters compilation
        stubFor(get(urlEqualTo("/api/sku/ABC-123"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("sku-ok")));

        String result = pathController().getBySku("ABC-123");

        assertEquals("sku-ok", result);
    }
}
