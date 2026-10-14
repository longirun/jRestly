package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.fixtures.TestModuleInfo;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Client-level SSL/TLS configuration: builder defaults, explicit custom
 * SSLContext / SSLParameters, and fail-fast on null.
 */
class SslConfigTest {

    @Test
    @DisplayName("ModuleInfo interface default getSslContext() and getSslParameters() return null")
    void moduleInfoInterfaceDefaultsAreNull() {
        TestModuleInfo moduleInfo = new TestModuleInfo("http://localhost:1");
        assertNull(moduleInfo.getSslContext());
        assertNull(moduleInfo.getSslParameters());
    }

    @Test
    @DisplayName("SimpleModuleInfo defaults getSslContext() and getSslParameters() to null")
    void simpleModuleInfoDefaultsToNull() {
        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:1")
                .build();

        assertNull(moduleInfo.getSslContext());
        assertNull(moduleInfo.getSslParameters());
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder stores custom SSLContext and SSLParameters")
    void simpleModuleInfoStoresCustomSslSettings() throws NoSuchAlgorithmException {
        SSLContext sslContext = SSLContext.getDefault();
        SSLParameters sslParameters = new SSLParameters();

        SimpleModuleInfo moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:1")
                .sslContext(sslContext)
                .sslParameters(sslParameters)
                .build();

        assertSame(sslContext, moduleInfo.getSslContext());
        assertSame(sslParameters, moduleInfo.getSslParameters());
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder.sslContext(null) fails fast with NPE")
    void simpleModuleInfoNullSslContextFailsFast() {
        SimpleModuleInfo.Builder builder = SimpleModuleInfo.builder().baseUrl("http://localhost:1");
        assertThrows(NullPointerException.class, () -> builder.sslContext(null));
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder.sslParameters(null) fails fast with NPE")
    void simpleModuleInfoNullSslParametersFailsFast() {
        SimpleModuleInfo.Builder builder = SimpleModuleInfo.builder().baseUrl("http://localhost:1");
        assertThrows(NullPointerException.class, () -> builder.sslParameters(null));
    }

    @Test
    @DisplayName("JRestlyClient defaults getSslContext() and getSslParameters() to null")
    void jRestlyClientDefaultsToNull() {
        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:1")
                .build();
        try {
            assertNull(client.getModuleInfo().getSslContext());
            assertNull(client.getModuleInfo().getSslParameters());
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("JRestlyClient.Builder forwards custom SSLContext and SSLParameters")
    void jRestlyClientForwardsCustomSslSettings() throws NoSuchAlgorithmException {
        SSLContext sslContext = SSLContext.getDefault();
        SSLParameters sslParameters = new SSLParameters();

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:1")
                .sslContext(sslContext)
                .sslParameters(sslParameters)
                .build();
        try {
            assertSame(sslContext, client.getModuleInfo().getSslContext());
            assertSame(sslParameters, client.getModuleInfo().getSslParameters());
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("JRestlyClient.Builder.sslContext(null) fails fast with NPE")
    void jRestlyClientNullSslContextFailsFast() {
        JRestlyClient.Builder builder = JRestlyClient.builder().baseUrl("http://localhost:1");
        assertThrows(NullPointerException.class, () -> builder.sslContext(null));
    }

    @Test
    @DisplayName("JRestlyClient.Builder.sslParameters(null) fails fast with NPE")
    void jRestlyClientNullSslParametersFailsFast() {
        JRestlyClient.Builder builder = JRestlyClient.builder().baseUrl("http://localhost:1");
        assertThrows(NullPointerException.class, () -> builder.sslParameters(null));
    }
}
