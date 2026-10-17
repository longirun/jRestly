package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestModuleInfo;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proxy configuration surface (ADR-0037): {@code ProxySelector}, host/port,
 * {@code NO_PROXY}, proxy and universal {@code Authenticator} credentials flow from the
 * builder into {@code ModuleInfo}, with defaults left null. No network.
 */
class ProxyConfigTest {

    @Test
    @DisplayName("ModuleInfo interface default getProxySelector() and getAuthenticator() return null")
    void defaultProxySelectorAndAuthenticatorAreNullInModuleInfo() {
        TestModuleInfo info = new TestModuleInfo("http://localhost:8080");

        assertNull(info.getProxySelector());
        assertNull(info.getAuthenticator());
    }

    @Test
    @DisplayName("SimpleModuleInfo defaults getProxySelector() and getAuthenticator() to null")
    void defaultProxySelectorAndAuthenticatorAreNullInSimpleModuleInfo() {
        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();

        assertNull(info.getProxySelector());
        assertNull(info.getAuthenticator());
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder stores custom ProxySelector")
    void configureProxySelectorExplicitly() {
        ProxySelector customSelector = ProxySelector.of(null);
        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .proxy(customSelector)
                .build();

        assertSame(customSelector, info.getProxySelector());
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder stores custom proxy host and port")
    void configureProxyHostAndPort() {
        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .proxy("127.0.0.1", 8888)
                .build();

        assertNotNull(info.getProxySelector());
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder noProxy() sets HttpClient.Builder.NO_PROXY")
    void configureNoProxyExplicitly() {
        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .noProxy()
                .build();

        assertSame(HttpClient.Builder.NO_PROXY, info.getProxySelector());
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder stores custom Authenticator")
    void configureAuthenticatorExplicitly() {
        Authenticator auth = new Authenticator() {};
        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .authenticator(auth)
                .build();

        assertSame(auth, info.getAuthenticator());
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder proxyCredentials() configures proxy authenticator")
    void configureProxyCredentialsHelper() {
        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .proxyCredentials("proxyUser", "proxySecret")
                .build();

        assertNotNull(info.getAuthenticator());
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder credentials() configures universal authenticator")
    void configureUniversalCredentialsHelper() {
        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .credentials("admin", "secret123")
                .build();

        assertNotNull(info.getAuthenticator());
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder proxy(null) fails fast with NPE")
    void failFastOnNullProxySelector() {
        SimpleModuleInfo.Builder builder = SimpleModuleInfo.builder().baseUrl("http://localhost:8080");
        assertThrows(NullPointerException.class, () -> builder.proxy((ProxySelector) null));
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder proxy(host, port) fails fast on null or blank host")
    void failFastOnNullOrBlankHost() {
        SimpleModuleInfo.Builder builder = SimpleModuleInfo.builder().baseUrl("http://localhost:8080");
        assertThrows(NullPointerException.class, () -> builder.proxy(null, 8080));
        assertThrows(IllegalArgumentException.class, () -> builder.proxy("", 8080));
        assertThrows(IllegalArgumentException.class, () -> builder.proxy("   ", 8080));
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder proxy(host, port) fails fast on invalid port")
    void failFastOnInvalidPort() {
        SimpleModuleInfo.Builder builder = SimpleModuleInfo.builder().baseUrl("http://localhost:8080");
        assertThrows(IllegalArgumentException.class, () -> builder.proxy("127.0.0.1", 0));
        assertThrows(IllegalArgumentException.class, () -> builder.proxy("127.0.0.1", -1));
        assertThrows(IllegalArgumentException.class, () -> builder.proxy("127.0.0.1", 65536));
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder authenticator(null) fails fast with NPE")
    void failFastOnNullAuthenticator() {
        SimpleModuleInfo.Builder builder = SimpleModuleInfo.builder().baseUrl("http://localhost:8080");
        assertThrows(NullPointerException.class, () -> builder.authenticator(null));
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder credentials helpers fail fast on null args")
    void failFastOnNullCredentials() {
        SimpleModuleInfo.Builder builder = SimpleModuleInfo.builder().baseUrl("http://localhost:8080");
        assertThrows(NullPointerException.class, () -> builder.proxyCredentials(null, "pass"));
        assertThrows(NullPointerException.class, () -> builder.proxyCredentials("user", null));
        assertThrows(NullPointerException.class, () -> builder.credentials(null, "pass"));
        assertThrows(NullPointerException.class, () -> builder.credentials("user", null));
    }

    @Test
    @DisplayName("JRestlyClient forwards proxy and authenticator settings")
    void forwardProxyAndAuthenticatorToJRestlyClient() {
        Authenticator auth = new Authenticator() {};
        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .proxy("proxy.internal.net", 3128)
                .authenticator(auth)
                .build();

        try {
            assertNotNull(client.getProxySelector());
            assertSame(auth, client.getAuthenticator());
            assertNotNull(client.getModuleInfo().getProxySelector());
            assertSame(auth, client.getModuleInfo().getAuthenticator());
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("JRestlyClient forwards noProxy() setting")
    void forwardNoProxyToJRestlyClient() {
        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .noProxy()
                .build();

        try {
            assertSame(HttpClient.Builder.NO_PROXY, client.getProxySelector());
            assertSame(HttpClient.Builder.NO_PROXY, client.getModuleInfo().getProxySelector());
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("JRestlyClient fail-fast validations mirror SimpleModuleInfo")
    void jRestlyClientFailFastValidations() {
        JRestlyClient.Builder builder = JRestlyClient.builder().baseUrl("http://localhost:8080");
        assertThrows(NullPointerException.class, () -> builder.proxy((ProxySelector) null));
        assertThrows(NullPointerException.class, () -> builder.proxy(null, 8080));
        assertThrows(IllegalArgumentException.class, () -> builder.proxy("", 8080));
        assertThrows(IllegalArgumentException.class, () -> builder.proxy("127.0.0.1", -5));
        assertThrows(NullPointerException.class, () -> builder.authenticator(null));
        assertThrows(NullPointerException.class, () -> builder.proxyCredentials(null, "p"));
        assertThrows(NullPointerException.class, () -> builder.credentials("u", null));
    }
}
