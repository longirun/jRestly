package ru.jrestly;

import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.*;
import ru.jrestly.http.FileContent;
import ru.jrestly.http.MediaType;
import ru.jrestly.http.RequestType;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Declarative @Accept and @ContentType WireMock integration tests (ADR-0031)")
class AcceptContentTypeWireMockTest extends BaseWireMockTest {

    @Accept(MediaType.APPLICATION_JSON)
    @ContentType(MediaType.APPLICATION_JSON)
    interface InterfaceLevelApi {

        @Get(path = "/api/default-accept")
        String defaultAccept();

        @Get(path = "/api/method-accept-override")
        @Accept(MediaType.TEXT_PLAIN)
        String methodAcceptOverride();

        @Get(path = "/api/multi-accept")
        @Accept({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, MediaType.TEXT_PLAIN})
        String multiAccept();

        @Get(path = "/api/runtime-accept-override")
        String runtimeAcceptOverride(@RequestHeader(name = "Accept") String acceptOverride);

        @Post(path = "/api/default-content-type")
        String defaultContentType(@RequestBody String body);

        @Post(path = "/api/method-content-type-override")
        @ContentType(MediaType.APPLICATION_XML)
        String methodContentTypeOverride(@RequestBody String body);

        @Post(path = "/api/custom-charset")
        @ContentType("text/plain; charset=windows-1251")
        String customCharset(@RequestBody String body);

        @Post(path = "/api/raw-bytes")
        @ContentType(MediaType.APPLICATION_OCTET_STREAM)
        String rawBytes(@RequestBody byte[] body);

        @Post(path = "/api/runtime-content-type-override")
        String runtimeContentTypeOverride(@RequestBody String body,
                                          @RequestHeader(name = "Content-Type") String contentTypeOverride);

        @Get(path = "/api/async-media-types")
        @Accept(MediaType.APPLICATION_JSON)
        CompletableFuture<String> asyncGet();

        @Post(path = "/api/multipart-related", requestType = RequestType.MULTIPART_FORM_DATA)
        @ContentType("multipart/related")
        String multipartRelated(@MultipartFormFile(partName = "file") FileContent file);
    }

    @Accept("application/vnd.company.v1+json")
    @ContentType("application/vnd.company.v1+json")
    interface ParentApi {
        @Get(path = "/api/parent")
        String parentCall();
    }

    interface ChildApi extends ParentApi {
        @Get(path = "/api/child-inherited")
        String childInherited();

        @Get(path = "/api/child-override")
        @Accept(MediaType.APPLICATION_JSON)
        String childOverride();

        @Post(path = "/api/child-post-override")
        @ContentType(MediaType.TEXT_PLAIN)
        String childPostOverride(@RequestBody String body);
    }

    @Test
    @DisplayName("Interface-level @Accept header is sent automatically on methods")
    void interfaceAcceptHeaderSent() {
        stubFor(get(urlEqualTo("/api/default-accept"))
                .withHeader("Accept", equalTo("application/json"))
                .willReturn(ok("OK:default-accept")));

        InterfaceLevelApi api = client.get(InterfaceLevelApi.class);
        String response = api.defaultAccept();

        assertEquals("OK:default-accept", response);
        verify(getRequestedFor(urlEqualTo("/api/default-accept"))
                .withHeader("Accept", equalTo("application/json")));
    }

    @Test
    @DisplayName("Method-level @Accept overrides interface-level declaration")
    void methodAcceptOverridesInterface() {
        stubFor(get(urlEqualTo("/api/method-accept-override"))
                .withHeader("Accept", equalTo("text/plain"))
                .willReturn(ok("OK:method-override")));

        InterfaceLevelApi api = client.get(InterfaceLevelApi.class);
        String response = api.methodAcceptOverride();

        assertEquals("OK:method-override", response);
        verify(getRequestedFor(urlEqualTo("/api/method-accept-override"))
                .withHeader("Accept", equalTo("text/plain")));
    }

    @Test
    @DisplayName("Multiple media types in @Accept are concatenated with comma into single header")
    void multipleAcceptConcatenatedWithComma() {
        stubFor(get(urlEqualTo("/api/multi-accept"))
                .withHeader("Accept", equalTo("application/json, application/xml, text/plain"))
                .willReturn(ok("OK:multi-accept")));

        InterfaceLevelApi api = client.get(InterfaceLevelApi.class);
        String response = api.multiAccept();

        assertEquals("OK:multi-accept", response);
        verify(getRequestedFor(urlEqualTo("/api/multi-accept"))
                .withHeader("Accept", equalTo("application/json, application/xml, text/plain")));
    }

    @Test
    @DisplayName("Runtime @RequestHeader(name = 'Accept') overrides static @Accept annotation")
    void runtimeHeaderOverridesAccept() {
        stubFor(get(urlEqualTo("/api/runtime-accept-override"))
                .withHeader("Accept", equalTo("text/html"))
                .willReturn(ok("OK:runtime-override")));

        InterfaceLevelApi api = client.get(InterfaceLevelApi.class);
        String response = api.runtimeAcceptOverride("text/html");

        assertEquals("OK:runtime-override", response);
        verify(getRequestedFor(urlEqualTo("/api/runtime-accept-override"))
                .withHeader("Accept", equalTo("text/html")));
    }

    @Test
    @DisplayName("Runtime @RequestHeader(name = 'Accept') with null falls back to static @Accept")
    void runtimeNullHeaderFallsBackToStaticAccept() {
        stubFor(get(urlEqualTo("/api/runtime-accept-override"))
                .withHeader("Accept", equalTo("application/json"))
                .willReturn(ok("OK:fallback-to-static")));

        InterfaceLevelApi api = client.get(InterfaceLevelApi.class);
        String response = api.runtimeAcceptOverride(null);

        assertEquals("OK:fallback-to-static", response);
        verify(getRequestedFor(urlEqualTo("/api/runtime-accept-override"))
                .withHeader("Accept", equalTo("application/json")));
    }

    @Test
    @DisplayName("Interface-level @ContentType sets Content-Type header on POST requests")
    void interfaceContentTypeSetsHeader() {
        stubFor(post(urlEqualTo("/api/default-content-type"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalTo("{\"foo\":\"bar\"}"))
                .willReturn(ok("OK:default-ct")));

        InterfaceLevelApi api = client.get(InterfaceLevelApi.class);
        String response = api.defaultContentType("{\"foo\":\"bar\"}");

        assertEquals("OK:default-ct", response);
        verify(postRequestedFor(urlEqualTo("/api/default-content-type"))
                .withHeader("Content-Type", equalTo("application/json")));
    }

    @Test
    @DisplayName("Method-level @ContentType overrides interface-level declaration")
    void methodContentTypeOverridesInterface() {
        stubFor(post(urlEqualTo("/api/method-content-type-override"))
                .withHeader("Content-Type", equalTo("application/xml"))
                .withRequestBody(equalTo("<root>data</root>"))
                .willReturn(ok("OK:xml-override")));

        InterfaceLevelApi api = client.get(InterfaceLevelApi.class);
        String response = api.methodContentTypeOverride("<root>data</root>");

        assertEquals("OK:xml-override", response);
        verify(postRequestedFor(urlEqualTo("/api/method-content-type-override"))
                .withHeader("Content-Type", equalTo("application/xml")));
    }

    @Test
    @DisplayName("@ContentType with charset parameter encodes String body using declared charset")
    void contentTypeWithCharsetEncodesBody() {
        String russianText = "Привет, мир!";
        Charset cp1251 = Charset.forName("windows-1251");
        byte[] expectedBytes = russianText.getBytes(cp1251);

        stubFor(post(urlEqualTo("/api/custom-charset"))
                .withHeader("Content-Type", equalTo("text/plain; charset=windows-1251"))
                .withRequestBody(binaryEqualTo(expectedBytes))
                .willReturn(ok("OK:custom-charset")));

        InterfaceLevelApi api = client.get(InterfaceLevelApi.class);
        String response = api.customCharset(russianText);

        assertEquals("OK:custom-charset", response);
        verify(postRequestedFor(urlEqualTo("/api/custom-charset"))
                .withHeader("Content-Type", equalTo("text/plain; charset=windows-1251")));
    }

    @Test
    @DisplayName("@ContentType with byte[] payload sends raw binary bytes directly without JSON conversion")
    void rawBytesBodyPassedDirectly() {
        byte[] rawBytes = new byte[]{0x01, 0x02, (byte) 0xFF, (byte) 0xFE, 0x00, 0x42};

        stubFor(post(urlEqualTo("/api/raw-bytes"))
                .withHeader("Content-Type", equalTo("application/octet-stream"))
                .withRequestBody(binaryEqualTo(rawBytes))
                .willReturn(ok("OK:raw-bytes")));

        InterfaceLevelApi api = client.get(InterfaceLevelApi.class);
        String response = api.rawBytes(rawBytes);

        assertEquals("OK:raw-bytes", response);
        verify(postRequestedFor(urlEqualTo("/api/raw-bytes"))
                .withHeader("Content-Type", equalTo("application/octet-stream")));
    }

    @Test
    @DisplayName("Request contains exactly one Content-Type header with no duplicate entries")
    void exactlyOneContentTypeHeaderSent() {
        stubFor(post(urlEqualTo("/api/default-content-type"))
                .willReturn(ok("OK:unique-header")));

        InterfaceLevelApi api = client.get(InterfaceLevelApi.class);
        api.defaultContentType("test-payload");

        List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/api/default-content-type")));
        assertEquals(1, requests.size());
        LoggedRequest req = requests.get(0);

        List<String> ctValues = req.getHeaders().getHeader("Content-Type").values();
        assertEquals(1, ctValues.size(), "Request must contain strictly one Content-Type header value: " + ctValues);
        assertEquals("application/json", ctValues.get(0));
    }

    @Test
    @DisplayName("Runtime @RequestHeader(name = 'Content-Type') overrides static @ContentType")
    void runtimeContentTypeOverridesStatic() {
        stubFor(post(urlEqualTo("/api/runtime-content-type-override"))
                .withHeader("Content-Type", equalTo("application/x-custom-v2"))
                .withRequestBody(equalTo("custom-payload"))
                .willReturn(ok("OK:runtime-ct")));

        InterfaceLevelApi api = client.get(InterfaceLevelApi.class);
        String response = api.runtimeContentTypeOverride("custom-payload", "application/x-custom-v2");

        assertEquals("OK:runtime-ct", response);
        verify(postRequestedFor(urlEqualTo("/api/runtime-content-type-override"))
                .withHeader("Content-Type", equalTo("application/x-custom-v2")));
    }

    @Test
    @DisplayName("Interface inheritance respects parent @Accept and @ContentType and permits sub-interface overrides")
    void interfaceInheritanceRespectsDeclarations() {
        stubFor(get(urlEqualTo("/api/parent"))
                .withHeader("Accept", equalTo("application/vnd.company.v1+json"))
                .willReturn(ok("OK:parent")));

        stubFor(get(urlEqualTo("/api/child-inherited"))
                .withHeader("Accept", equalTo("application/vnd.company.v1+json"))
                .willReturn(ok("OK:child-inherited")));

        stubFor(get(urlEqualTo("/api/child-override"))
                .withHeader("Accept", equalTo("application/json"))
                .willReturn(ok("OK:child-override")));

        stubFor(post(urlEqualTo("/api/child-post-override"))
                .withHeader("Content-Type", equalTo("text/plain"))
                .withRequestBody(equalTo("plain text"))
                .willReturn(ok("OK:child-post")));

        ChildApi child = client.get(ChildApi.class);

        assertEquals("OK:parent", child.parentCall());
        assertEquals("OK:child-inherited", child.childInherited());
        assertEquals("OK:child-override", child.childOverride());
        assertEquals("OK:child-post", child.childPostOverride("plain text"));
    }

    @Test
    @DisplayName("Asynchronous call via CompletableFuture respects @Accept and @ContentType")
    void asyncCallRespectsMediaTypes() throws Exception {
        stubFor(get(urlEqualTo("/api/async-media-types"))
                .withHeader("Accept", equalTo("application/json"))
                .willReturn(ok("OK:async")));

        InterfaceLevelApi api = client.get(InterfaceLevelApi.class);
        CompletableFuture<String> future = api.asyncGet();

        assertEquals("OK:async", future.get());
    }

    @Test
    @DisplayName("Multipart method with @ContentType preserves multipart boundary")
    void multipartWithCustomContentTypePreservesBoundary() {
        stubFor(post(urlEqualTo("/api/multipart-related"))
                .withHeader("Content-Type", containing("multipart/related; boundary=jrestly-"))
                .willReturn(ok("OK:multipart-related")));

        InterfaceLevelApi api = client.get(InterfaceLevelApi.class);
        FileContent file = FileContent.of("doc.txt", "hello multipart".getBytes(StandardCharsets.UTF_8));
        String response = api.multipartRelated(file);

        assertEquals("OK:multipart-related", response);
        verify(postRequestedFor(urlEqualTo("/api/multipart-related"))
                .withHeader("Content-Type", containing("multipart/related; boundary=jrestly-")));
    }
}
