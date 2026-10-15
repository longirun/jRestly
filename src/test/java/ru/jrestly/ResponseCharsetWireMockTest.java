package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Get;
import ru.jrestly.http.UnexpectedStatusException;

import java.nio.charset.Charset;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Response charset resolution for String payloads (audit, RFC 9110 §8.3.2)")
class ResponseCharsetWireMockTest extends BaseWireMockTest {

    private static final Charset WINDOWS_1251 = Charset.forName("windows-1251");
    private static final String RUSSIAN_TEXT = "Привет, мир!";

    interface CharsetApi {

        @Get(path = "/api/charset/text")
        String text();

        @Get(path = "/api/charset/quoted")
        String quotedText();

        @Get(path = "/api/charset/unknown")
        String unknownCharset();

        @Get(path = "/api/charset/optional")
        Optional<String> optionalText();

        @Get(path = "/api/charset/failure")
        String failure();
    }

    @Test
    @DisplayName("String return decodes the body using the declared Content-Type charset")
    void stringReturnHonorsDeclaredCharset() {
        stubFor(get(urlEqualTo("/api/charset/text"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain; charset=windows-1251")
                        .withBody(RUSSIAN_TEXT.getBytes(WINDOWS_1251))));

        assertEquals(RUSSIAN_TEXT, client.get(CharsetApi.class).text());
    }

    @Test
    @DisplayName("Quoted charset parameter is honored for String returns")
    void quotedCharsetIsHonored() {
        stubFor(get(urlEqualTo("/api/charset/quoted"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain; charset=\"windows-1251\"")
                        .withBody(RUSSIAN_TEXT.getBytes(WINDOWS_1251))));

        assertEquals(RUSSIAN_TEXT, client.get(CharsetApi.class).quotedText());
    }

    @Test
    @DisplayName("Unknown charset name falls back to UTF-8 without failing")
    void unknownCharsetFallsBackToUtf8() {
        stubFor(get(urlEqualTo("/api/charset/unknown"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain; charset=no-such-charset-42")
                        .withBody(RUSSIAN_TEXT.getBytes(java.nio.charset.StandardCharsets.UTF_8))));

        assertEquals(RUSSIAN_TEXT, client.get(CharsetApi.class).unknownCharset());
    }

    @Test
    @DisplayName("Missing charset falls back to UTF-8")
    void missingCharsetFallsBackToUtf8() {
        stubFor(get(urlEqualTo("/api/charset/text"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody(RUSSIAN_TEXT.getBytes(java.nio.charset.StandardCharsets.UTF_8))));

        assertEquals(RUSSIAN_TEXT, client.get(CharsetApi.class).text());
    }

    @Test
    @DisplayName("Optional<String> return honors the declared charset")
    void optionalStringHonorsCharset() {
        stubFor(get(urlEqualTo("/api/charset/optional"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain; charset=windows-1251")
                        .withBody(RUSSIAN_TEXT.getBytes(WINDOWS_1251))));

        assertEquals(Optional.of(RUSSIAN_TEXT), client.get(CharsetApi.class).optionalText());
    }

    @Test
    @DisplayName("UnexpectedStatusException raw body honors the declared charset")
    void unexpectedStatusRawBodyHonorsCharset() {
        stubFor(get(urlEqualTo("/api/charset/failure"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "text/plain; charset=windows-1251")
                        .withBody(RUSSIAN_TEXT.getBytes(WINDOWS_1251))));

        UnexpectedStatusException exception =
                assertThrows(UnexpectedStatusException.class, () -> client.get(CharsetApi.class).failure());

        assertEquals(RUSSIAN_TEXT, exception.getRawBody());
    }
}
