package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.compression.ContentCompressor;
import ru.jrestly.metrics.ClientMetricsListener;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.Authenticator;
import java.net.PasswordAuthentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests verifying builder boundary conditions and edge cases in {@link SimpleModuleInfo}.
 */
class SimpleModuleInfoTest {

    @Test
    @DisplayName("getContentCompressor(null) returns null")
    void getContentCompressorNull() {
        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("https://api.example.com")
                .build();
        assertNull(info.getContentCompressor(null));
    }

    @Test
    @DisplayName("builder.codec(null) is safely ignored")
    void builderCodecNullIgnored() {
        SimpleModuleInfo.Builder builder = SimpleModuleInfo.builder()
                .baseUrl("https://api.example.com");
        assertSame(builder, builder.codec(null));
    }

    @Test
    @DisplayName("builder chaining multiple metricsListener instances creates composite")
    void builderCompositeMetricsListener() {
        ClientMetricsListener l1 = metrics -> {};
        ClientMetricsListener l2 = metrics -> {};

        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("https://api.example.com")
                .metricsListener(l1)
                .metricsListener(l2)
                .build();

        assertNotNull(info.getMetricsListener());
        assertTrue(info.getMetricsListener().toString().contains("Composite") ||
                info.getMetricsListener().getClass().getSimpleName().contains("Composite"));
    }

    @Test
    @DisplayName("builder proxyCredentials creates Authenticator distinguishing PROXY vs SERVER")
    void builderProxyCredentialsAuthenticator() {
        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("https://api.example.com")
                .proxyCredentials("test-proxy-user", "test-proxy-pass")
                .build();

        Authenticator auth = info.getAuthenticator();
        assertNotNull(auth);

        Authenticator previous = Authenticator.getDefault();
        try {
            Authenticator.setDefault(auth);
            PasswordAuthentication proxyCreds = Authenticator.requestPasswordAuthentication(
                    "proxy.example.com", null, 8080, "http", "proxy prompt", "basic", null, Authenticator.RequestorType.PROXY);
            assertNotNull(proxyCreds);
            assertEquals("test-proxy-user", proxyCreds.getUserName());
            assertEquals("test-proxy-pass", new String(proxyCreds.getPassword()));

            PasswordAuthentication serverCreds = Authenticator.requestPasswordAuthentication(
                    "api.example.com", null, 443, "https", "server prompt", "basic", null, Authenticator.RequestorType.SERVER);
            assertNull(serverCreds, "Non-proxy request must return null password authentication");
        } finally {
            Authenticator.setDefault(previous);
        }
    }

    @Test
    @DisplayName("builder.contentCompressor rejects blank encoding")
    void builderContentCompressorRejectsBlank() {
        ContentCompressor blankCompressor = new ContentCompressor() {
            @Override public String encoding() { return "   "; }
            @Override public byte[] compress(byte[] uncompressed) { return uncompressed; }
        };

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                SimpleModuleInfo.builder().contentCompressor(blankCompressor));
        assertTrue(ex.getMessage().contains("must not be blank"));
    }

    @Test
    @DisplayName("builder.baseUrl rejects blank string")
    void builderBaseUrlRejectsBlank() {
        SimpleModuleInfo.Builder builder = SimpleModuleInfo.builder().baseUrl("   ");
        IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(ex.getMessage().contains("baseUrl is required"));
    }
}
