package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestModuleInfo;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Client-level HTTP version configuration: builder defaults, explicit pinning
 * and fail-fast on null. Pure unit tests, no HTTP traffic.
 */
class HttpVersionConfigTest {

    @Test
    @DisplayName("SimpleModuleInfo.Builder.httpVersion(HTTP_1_1) is returned by getHttpVersion()")
    void simpleModuleInfoBuilderPinsHttp11() {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:1")
                .httpVersion(HttpClient.Version.HTTP_1_1)
                .build();

        assertEquals(HttpClient.Version.HTTP_1_1, moduleInfo.getHttpVersion());
    }

    @Test
    @DisplayName("SimpleModuleInfo defaults to HTTP_2 when httpVersion() is not called")
    void simpleModuleInfoDefaultsToHttp2() {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:1")
                .build();

        assertEquals(HttpClient.Version.HTTP_2, moduleInfo.getHttpVersion());
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder.httpVersion(null) fails fast with NPE")
    void simpleModuleInfoNullHttpVersionFailsFast() {
        SimpleModuleInfo.Builder builder = SimpleModuleInfo.builder().baseUrl("http://localhost:1");

        assertThrows(NullPointerException.class, () -> builder.httpVersion(null));
    }

    @Test
    @DisplayName("ModuleInfo interface default getHttpVersion() is HTTP_2")
    void moduleInfoInterfaceDefaultIsHttp2() {
        // TestModuleInfo does not override getHttpVersion(), so the interface default applies
        assertEquals(HttpClient.Version.HTTP_2, new TestModuleInfo("http://localhost:1").getHttpVersion());
    }

    @Test
    @DisplayName("JRestlyClient.Builder.httpVersion(HTTP_1_1) is passed through to ModuleInfo")
    void clientBuilderPassthrough() {
        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:1")
                .httpVersion(HttpClient.Version.HTTP_1_1)
                .build();
        try {
            assertEquals(HttpClient.Version.HTTP_1_1, client.getModuleInfo().getHttpVersion());
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("JRestlyClient default (no httpVersion call) is HTTP_2")
    void clientBuilderDefaultsToHttp2() {
        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:1")
                .build();
        try {
            assertEquals(HttpClient.Version.HTTP_2, client.getModuleInfo().getHttpVersion());
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("JRestlyClient.Builder.httpVersion(null) fails fast with NPE")
    void clientBuilderNullHttpVersionFailsFast() {
        JRestlyClient.Builder builder = JRestlyClient.builder().baseUrl("http://localhost:1");

        assertThrows(NullPointerException.class, () -> builder.httpVersion(null));
    }
}
