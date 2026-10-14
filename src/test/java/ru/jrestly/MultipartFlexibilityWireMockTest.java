package ru.jrestly;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestParam;
import ru.jrestly.annotation.MultipartFormFile;
import ru.jrestly.http.FileContent;
import ru.jrestly.http.RequestType;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultipartFlexibilityWireMockTest extends BaseWireMockTest {

    public record ItemDto(String name, int quantity) {}

    interface MultipartFlexibilityController {
        @Post(path = "/api/upload/json-dto", requestType = RequestType.MULTIPART_FORM_DATA)
        String uploadWithJsonDto(@RequestParam(name = "item", contentType = "application/json") ItemDto item,
                                 @MultipartFormFile(partName = "file") FileContent file);

        @Post(path = "/api/upload/json-string", requestType = RequestType.MULTIPART_FORM_DATA)
        String uploadWithJsonString(@RequestParam(name = "config", contentType = "application/json") String json,
                                    @RequestParam(name = "tag") String tag);

        @Post(path = "/api/upload/charset-windows", requestType = RequestType.MULTIPART_FORM_DATA)
        String uploadWithWindows1251(@RequestParam(name = "doc", contentType = "text/plain; charset=windows-1251") String text);

        @Post(path = "/api/upload/charset-quoted", requestType = RequestType.MULTIPART_FORM_DATA)
        String uploadWithQuotedCharset(@RequestParam(name = "note", contentType = "text/plain; charset=\"ISO-8859-1\"") String text);

        @Post(path = "/api/upload/byte-array", requestType = RequestType.MULTIPART_FORM_DATA)
        String uploadWithRawBytes(@RequestParam(name = "raw", contentType = "application/octet-stream") byte[] data);

        @Post(path = "/api/upload/collection-json", requestType = RequestType.MULTIPART_FORM_DATA)
        String uploadWithJsonCollection(@RequestParam(name = "items", contentType = "application/json") List<ItemDto> items);

        @Post(path = "/api/form/submit", requestType = RequestType.APPLICATION_FORM_URLENCODED)
        String submitForm(@RequestParam(name = "name") String name, @RequestParam(name = "extra") String extra);
    }

    private MultipartFlexibilityController controller() {
        return client.get(MultipartFlexibilityController.class);
    }

    @Test
    @DisplayName("Multipart request serializes DTO object as application/json part alongside file")
    void multipartWithJsonDtoPart() {
        stubFor(post(urlEqualTo("/api/upload/json-dto"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        ItemDto item = new ItemDto("widget", 42);
        FileContent file = FileContent.of("doc.txt", "file content".getBytes(StandardCharsets.UTF_8));

        String response = controller().uploadWithJsonDto(item, file);

        assertEquals("ok", response);
        verify(postRequestedFor(urlEqualTo("/api/upload/json-dto"))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBodyPart(aMultipart()
                        .withName("item")
                        .withHeader("Content-Type", equalTo("application/json"))
                        .withBody(containing("\"name\":\"widget\""))
                        .withBody(containing("\"quantity\":42"))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withFileName("doc.txt")
                        .withBody(equalTo("file content"))
                        .build()));
    }

    @Test
    @DisplayName("Multipart request sends String JSON with application/json Content-Type")
    void multipartWithJsonStringPart() {
        stubFor(post(urlEqualTo("/api/upload/json-string"))
                .willReturn(aResponse().withStatus(200).withBody("accepted")));

        String json = "{\"enabled\":true,\"mode\":\"fast\"}";
        String response = controller().uploadWithJsonString(json, "v1");

        assertEquals("accepted", response);
        verify(postRequestedFor(urlEqualTo("/api/upload/json-string"))
                .withRequestBodyPart(aMultipart()
                        .withName("config")
                        .withHeader("Content-Type", equalTo("application/json"))
                        .withBody(equalTo(json))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("tag")
                        .withHeader("Content-Type", equalTo("text/plain; charset=UTF-8"))
                        .withBody(equalTo("v1"))
                        .build()));
    }

    @Test
    @DisplayName("Multipart request encodes text with explicit windows-1251 charset")
    void multipartWithWindows1251Charset() {
        stubFor(post(urlEqualTo("/api/upload/charset-windows"))
                .willReturn(aResponse().withStatus(200).withBody("saved")));

        String cyrillic = "Привет, мир!";
        Charset cp1251 = Charset.forName("windows-1251");
        byte[] expectedBytes = cyrillic.getBytes(cp1251);

        String response = controller().uploadWithWindows1251(cyrillic);

        assertEquals("saved", response);

        List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/api/upload/charset-windows")));
        assertEquals(1, requests.size());
        byte[] body = requests.get(0).getBody();

        // The body must contain the windows-1251 encoded bytes
        assertTrue(containsSubsequence(body, expectedBytes));
        verify(postRequestedFor(urlEqualTo("/api/upload/charset-windows"))
                .withRequestBodyPart(aMultipart()
                        .withName("doc")
                        .withHeader("Content-Type", equalTo("text/plain; charset=windows-1251"))
                        .build()));
    }

    @Test
    @DisplayName("Multipart request handles quoted charset attribute in Content-Type")
    void multipartWithQuotedCharset() {
        stubFor(post(urlEqualTo("/api/upload/charset-quoted"))
                .willReturn(aResponse().withStatus(200).withBody("done")));

        String text = "Café & naïve";
        String response = controller().uploadWithQuotedCharset(text);

        assertEquals("done", response);

        List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/api/upload/charset-quoted")));
        assertEquals(1, requests.size());
        byte[] bodyBytes = requests.get(0).getBody();
        String rawBodyIso = new String(bodyBytes, StandardCharsets.ISO_8859_1);
        assertTrue(rawBodyIso.contains("Content-Type: text/plain; charset=\"ISO-8859-1\""));
        assertTrue(rawBodyIso.contains(text));
    }

    @Test
    @DisplayName("Multipart request sends raw byte[] with custom Content-Type")
    void multipartWithRawBytes() {
        stubFor(post(urlEqualTo("/api/upload/byte-array"))
                .willReturn(aResponse().withStatus(200).withBody("received")));

        byte[] binary = new byte[] { 0x00, 0x01, 0x02, (byte) 0xFF };
        String response = controller().uploadWithRawBytes(binary);

        assertEquals("received", response);
        verify(postRequestedFor(urlEqualTo("/api/upload/byte-array"))
                .withRequestBodyPart(aMultipart()
                        .withName("raw")
                        .withHeader("Content-Type", equalTo("application/octet-stream"))
                        .withBody(binaryEqualTo(binary))
                        .build()));
    }

    @Test
    @DisplayName("Multipart request serializes collection of DTOs with application/json")
    void multipartWithCollectionOfDtos() {
        stubFor(post(urlEqualTo("/api/upload/collection-json"))
                .willReturn(aResponse().withStatus(200).withBody("all-saved")));

        List<ItemDto> items = List.of(new ItemDto("first", 1), new ItemDto("second", 2));
        String response = controller().uploadWithJsonCollection(items);

        assertEquals("all-saved", response);
        verify(postRequestedFor(urlEqualTo("/api/upload/collection-json"))
                .withRequestBodyPart(aMultipart()
                        .withName("items")
                        .withHeader("Content-Type", equalTo("application/json"))
                        .withBody(containing("\"name\":\"first\""))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("items")
                        .withHeader("Content-Type", equalTo("application/json"))
                        .withBody(containing("\"name\":\"second\""))
                        .build()));
    }

    @Test
    @DisplayName("Form-urlencoded handles empty and omitted parameters cleanly")
    void formUrlencodedEmptyParam() {
        stubFor(post(urlEqualTo("/api/form/submit"))
                .willReturn(aResponse().withStatus(200).withBody("submitted")));

        String response = controller().submitForm("alpha", "");

        assertEquals("submitted", response);
        verify(postRequestedFor(urlEqualTo("/api/form/submit"))
                .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded"))
                .withRequestBody(equalTo("name=alpha&extra=")));
    }

    private static boolean containsSubsequence(byte[] target, byte[] sub) {
        if (sub.length == 0) return true;
        outer:
        for (int i = 0; i <= target.length - sub.length; i++) {
            for (int j = 0; j < sub.length; j++) {
                if (target[i + j] != sub[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
