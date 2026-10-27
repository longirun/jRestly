package ru.jrestly;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.annotation.*;
import ru.jrestly.http.FileContent;
import ru.jrestly.http.MediaType;
import ru.jrestly.http.RequestType;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end for declarative media-type negotiation: {@code @Accept} and
 * {@code @ContentType} resolved across interface, method and runtime-header levels,
 * multiple accepted types, custom charset, raw byte bodies.
 */
@DisplayName("Declarative @Accept and @ContentType WireMock integration tests")
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

    interface ClientLevelApi {
        @Get(path = "/api/client-accept")
        String plainGet();

        @Get(path = "/api/client-runtime-accept")
        String runtimeAccept(@RequestHeader(name = "Accept") String acceptOverride);

        @Post(path = "/api/client-content-type")
        String plainPost(@RequestBody String body);

        @Post(path = "/api/client-runtime-content-type")
        String runtimeContentType(@RequestHeader(name = "Content-Type") String contentTypeOverride,
                                  @RequestBody String body);

        @Post(path = "/api/client-dto-body")
        String dtoPost(@RequestBody Map<String, String> body);

        @Post(path = "/api/client-unknown-charset")
        String unknownCharsetPost(@RequestBody String body);

        @Post(path = "/api/client-form", requestType = RequestType.APPLICATION_FORM_URLENCODED)
        String formPost(@RequestParam(name = "name") String name);

        @Post(path = "/api/client-multipart", requestType = RequestType.MULTIPART_FORM_DATA)
        String plainMultipart(@MultipartFormFile(partName = "file") FileContent file);
    }

    private JRestlyClient clientWith(String accept, String contentType) {
        JRestlyClient.Builder builder = JRestlyClient.builder().baseUrl(baseUrl());
        if (accept != null) {
            builder.defaultHeader("Accept", accept);
        }
        if (contentType != null) {
            builder.defaultHeader("Content-Type", contentType);
        }
        return builder.build();
    }

    @Test
    @DisplayName("Client-level accept is sent on methods without any @Accept declaration")
    void clientLevelAcceptSent() {
        stubFor(get(urlEqualTo("/api/client-accept"))
                .withHeader("Accept", equalTo(MediaType.TEXT_HTML))
                .willReturn(ok("OK:client-accept")));

        JRestlyClient mediaClient = clientWith(MediaType.TEXT_HTML, null);
        try {
            assertEquals("OK:client-accept", mediaClient.get(ClientLevelApi.class).plainGet());
        } finally {
            mediaClient.close();
        }
    }

    @Test
    @DisplayName("Interface and method @Accept override the client-level accept")
    void annotationsOverrideClientLevelAccept() {
        stubFor(get(urlEqualTo("/api/default-accept"))
                .withHeader("Accept", equalTo(MediaType.APPLICATION_JSON))
                .willReturn(ok("OK:interface-wins")));
        stubFor(get(urlEqualTo("/api/method-accept-override"))
                .withHeader("Accept", equalTo(MediaType.TEXT_PLAIN))
                .willReturn(ok("OK:method-wins")));

        JRestlyClient mediaClient = clientWith(MediaType.TEXT_HTML, null);
        try {
            InterfaceLevelApi api = mediaClient.get(InterfaceLevelApi.class);
            assertEquals("OK:interface-wins", api.defaultAccept());
            assertEquals("OK:method-wins", api.methodAcceptOverride());
        } finally {
            mediaClient.close();
        }
    }

    @Test
    @DisplayName("Client-level contentType is sent and its charset encodes String bodies")
    void clientLevelContentTypeSent() {
        String russianText = "Привет, мир!";
        byte[] expectedBytes = russianText.getBytes(Charset.forName("windows-1251"));

        stubFor(post(urlEqualTo("/api/client-content-type"))
                .withHeader("Content-Type", equalTo("text/plain; charset=windows-1251"))
                .withRequestBody(binaryEqualTo(expectedBytes))
                .willReturn(ok("OK:client-ct")));

        JRestlyClient mediaClient = clientWith(null, "text/plain; charset=windows-1251");
        try {
            assertEquals("OK:client-ct", mediaClient.get(ClientLevelApi.class).plainPost(russianText));
        } finally {
            mediaClient.close();
        }
    }

    @Test
    @DisplayName("Interface-level @ContentType overrides the client-level contentType")
    void annotationOverridesClientLevelContentType() {
        stubFor(post(urlEqualTo("/api/default-content-type"))
                .withHeader("Content-Type", equalTo(MediaType.APPLICATION_JSON))
                .willReturn(ok("OK:annotation-wins")));

        JRestlyClient mediaClient = clientWith(null, MediaType.TEXT_PLAIN);
        try {
            InterfaceLevelApi api = mediaClient.get(InterfaceLevelApi.class);
            assertEquals("OK:annotation-wins", api.defaultContentType("{\"foo\":\"bar\"}"));
        } finally {
            mediaClient.close();
        }
    }

    @Test
    @DisplayName("Client-level accept with multiple values is sent verbatim")
    void clientLevelMultiValueAcceptSentVerbatim() {
        stubFor(get(urlEqualTo("/api/client-accept"))
                .withHeader("Accept", equalTo(MediaType.APPLICATION_JSON + ", " + MediaType.TEXT_PLAIN))
                .willReturn(ok("OK:client-multi-accept")));

        JRestlyClient mediaClient = clientWith(MediaType.APPLICATION_JSON + ", " + MediaType.TEXT_PLAIN, null);
        try {
            assertEquals("OK:client-multi-accept", mediaClient.get(ClientLevelApi.class).plainGet());
        } finally {
            mediaClient.close();
        }
    }

    @Test
    @DisplayName("Default headers are sent on every request of the client")
    void defaultHeadersSentOnEveryRequest() {
        stubFor(get(urlEqualTo("/api/client-accept"))
                .withHeader("X-Tenant", equalTo("tenant-1"))
                .willReturn(ok("OK:default-header-1")));
        stubFor(post(urlEqualTo("/api/client-content-type"))
                .withHeader("X-Tenant", equalTo("tenant-1"))
                .willReturn(ok("OK:default-header-2")));

        JRestlyClient mediaClient = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .defaultHeader("X-Tenant", "tenant-1")
                .build();
        try {
            ClientLevelApi api = mediaClient.get(ClientLevelApi.class);
            assertEquals("OK:default-header-1", api.plainGet());
            assertEquals("OK:default-header-2", api.plainPost("body"));
        } finally {
            mediaClient.close();
        }
    }

    @Test
    @DisplayName("Auth headers replace default headers with the same name")
    void authHeaderReplacesSameNameDefaultHeader() {
        stubFor(get(urlEqualTo("/api/client-accept"))
                .withHeader("X-Api-Key", equalTo("from-auth"))
                .willReturn(ok("OK:auth-wins")));

        JRestlyClient mediaClient = JRestlyClient.builder()
                .baseUrl(baseUrl())
                .defaultHeader("X-Api-Key", "from-default")
                .authHeader("X-Api-Key", "from-auth")
                .build();
        try {
            assertEquals("OK:auth-wins", mediaClient.get(ClientLevelApi.class).plainGet());
        } finally {
            mediaClient.close();
        }
    }

    @Test
    @DisplayName("Runtime @RequestHeader('Accept') overrides the client-level accept")
    void runtimeHeaderOverridesClientLevelAccept() {
        stubFor(get(urlEqualTo("/api/client-runtime-accept"))
                .withHeader("Accept", equalTo(MediaType.TEXT_HTML))
                .willReturn(ok("OK:runtime-over-client")));

        JRestlyClient mediaClient = clientWith(MediaType.TEXT_PLAIN, null);
        try {
            assertEquals("OK:runtime-over-client",
                    mediaClient.get(ClientLevelApi.class).runtimeAccept(MediaType.TEXT_HTML));
        } finally {
            mediaClient.close();
        }
    }

    @Test
    @DisplayName("Null runtime @RequestHeader('Accept') falls back to the client-level accept")
    void runtimeNullHeaderFallsBackToClientAccept() {
        stubFor(get(urlEqualTo("/api/client-runtime-accept"))
                .withHeader("Accept", equalTo(MediaType.TEXT_PLAIN))
                .willReturn(ok("OK:fallback-to-client")));

        JRestlyClient mediaClient = clientWith(MediaType.TEXT_PLAIN, null);
        try {
            assertEquals("OK:fallback-to-client",
                    mediaClient.get(ClientLevelApi.class).runtimeAccept(null));
        } finally {
            mediaClient.close();
        }
    }

    @Test
    @DisplayName("Runtime @RequestHeader('Content-Type') overrides the client-level contentType")
    void runtimeHeaderOverridesClientLevelContentType() {
        stubFor(post(urlEqualTo("/api/client-runtime-content-type"))
                .withHeader("Content-Type", equalTo("application/x-custom-v2"))
                .withRequestBody(equalTo("custom-payload"))
                .willReturn(ok("OK:runtime-ct-over-client")));

        JRestlyClient mediaClient = clientWith(null, MediaType.TEXT_PLAIN);
        try {
            assertEquals("OK:runtime-ct-over-client",
                    mediaClient.get(ClientLevelApi.class).runtimeContentType("application/x-custom-v2", "custom-payload"));
        } finally {
            mediaClient.close();
        }
    }

    @Test
    @DisplayName("Null runtime @RequestHeader('Content-Type') falls back to the client-level contentType")
    void runtimeNullHeaderFallsBackToClientContentType() {
        stubFor(post(urlEqualTo("/api/client-runtime-content-type"))
                .withHeader("Content-Type", equalTo(MediaType.APPLICATION_XML))
                .withRequestBody(equalTo("plain body"))
                .willReturn(ok("OK:fallback-to-client-ct")));

        JRestlyClient mediaClient = clientWith(null, MediaType.APPLICATION_XML);
        try {
            assertEquals("OK:fallback-to-client-ct",
                    mediaClient.get(ClientLevelApi.class).runtimeContentType(null, "plain body"));
        } finally {
            mediaClient.close();
        }
    }

    @Test
    @DisplayName("Client-level contentType keeps DTO bodies JSON-serialized")
    void clientLevelContentTypeKeepsDtoBodyJson() {
        stubFor(post(urlEqualTo("/api/client-dto-body"))
                .withHeader("Content-Type", equalTo("application/vnd.company.v2+json"))
                .withRequestBody(equalTo("{\"foo\":\"bar\"}"))
                .willReturn(ok("OK:dto-json")));

        JRestlyClient mediaClient = clientWith(null, "application/vnd.company.v2+json");
        try {
            assertEquals("OK:dto-json",
                    mediaClient.get(ClientLevelApi.class).dtoPost(Map.of("foo", "bar")));
        } finally {
            mediaClient.close();
        }
    }

    @Test
    @DisplayName("Unknown charset in the client-level contentType falls back to UTF-8")
    void clientLevelUnknownCharsetFallsBackToUtf8() throws Exception {
        // WireMock dies with 500 on any request whose Content-Type names a charset
        // it cannot decode, so this wire case runs against a bare JDK server.
        AtomicReference<String> receivedContentType = new AtomicReference<>();
        AtomicReference<byte[]> receivedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/client-unknown-charset", exchange -> {
            receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            receivedBody.set(exchange.getRequestBody().readAllBytes());
            byte[] response = "OK:utf8-fallback".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            JRestlyClient mediaClient = JRestlyClient.builder()
                    .baseUrl("http://localhost:" + server.getAddress().getPort())
                    .defaultHeader("Content-Type", "text/plain; charset=does-not-exist")
                    .build();
            try {
                assertEquals("OK:utf8-fallback",
                        mediaClient.get(ClientLevelApi.class).unknownCharsetPost("Привет"));
            } finally {
                mediaClient.close();
            }

            assertEquals("text/plain; charset=does-not-exist", receivedContentType.get());
            assertArrayEquals("Привет".getBytes(StandardCharsets.UTF_8), receivedBody.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Client-level contentType never overrides form and multipart request types")
    void clientLevelContentTypeNeverOverridesFormAndMultipart() {
        stubFor(post(urlEqualTo("/api/client-form"))
                .withHeader("Content-Type", equalTo(MediaType.APPLICATION_FORM_URLENCODED))
                .withRequestBody(containing("name=value"))
                .willReturn(ok("OK:form-kept")));
        stubFor(post(urlEqualTo("/api/client-multipart"))
                .withHeader("Content-Type", containing("multipart/form-data; boundary=jrestly-"))
                .willReturn(ok("OK:multipart-kept")));

        JRestlyClient mediaClient = clientWith(null, MediaType.TEXT_PLAIN);
        try {
            ClientLevelApi api = mediaClient.get(ClientLevelApi.class);
            assertEquals("OK:form-kept", api.formPost("value"));
            assertEquals("OK:multipart-kept",
                    api.plainMultipart(FileContent.of("doc.txt", "hello".getBytes(StandardCharsets.UTF_8))));
        } finally {
            mediaClient.close();
        }
    }

    @Test
    @DisplayName("No Accept header is sent when no layer configures it")
    void noAcceptHeaderSentWhenUnconfigured() {
        stubFor(get(urlEqualTo("/api/client-accept"))
                .willReturn(ok("OK:no-accept")));

        JRestlyClient mediaClient = clientWith(null, null);
        try {
            assertEquals("OK:no-accept", mediaClient.get(ClientLevelApi.class).plainGet());
        } finally {
            mediaClient.close();
        }

        List<LoggedRequest> requests = findAll(getRequestedFor(urlEqualTo("/api/client-accept")));
        assertEquals(1, requests.size());
        assertFalse(requests.get(0).getHeaders().getHeader("Accept").isPresent());
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
