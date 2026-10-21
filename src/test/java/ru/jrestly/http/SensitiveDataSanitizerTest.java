package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD test suite for {@link SensitiveDataSanitizer} (ADR-0095).
 * Validates enterprise PII, credential masking for GDPR / 152-FZ / PCI-DSS compliance.
 */
class SensitiveDataSanitizerTest {

    @Test
    @DisplayName("private constructor cannot be instantiated normally and can be invoked via reflection")
    void privateConstructor() throws Exception {
        Constructor<SensitiveDataSanitizer> constructor = SensitiveDataSanitizer.class.getDeclaredConstructor();
        assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        constructor.setAccessible(true);
        SensitiveDataSanitizer instance = constructor.newInstance();
        assertNotNull(instance);
    }

    @Test
    @DisplayName("isSensitiveHeader: correctly identifies standard and substring sensitive headers")
    void isSensitiveHeaderIdentification() {
        assertFalse(SensitiveDataSanitizer.isSensitiveHeader(null));
        assertFalse(SensitiveDataSanitizer.isSensitiveHeader(""));
        assertFalse(SensitiveDataSanitizer.isSensitiveHeader("   "));
        assertFalse(SensitiveDataSanitizer.isSensitiveHeader("Content-Type"));
        assertFalse(SensitiveDataSanitizer.isSensitiveHeader("Accept"));
        assertFalse(SensitiveDataSanitizer.isSensitiveHeader("User-Agent"));

        // Standard exact sensitive headers
        assertTrue(SensitiveDataSanitizer.isSensitiveHeader("Authorization"));
        assertTrue(SensitiveDataSanitizer.isSensitiveHeader("authorization"));
        assertTrue(SensitiveDataSanitizer.isSensitiveHeader("Proxy-Authorization"));
        assertTrue(SensitiveDataSanitizer.isSensitiveHeader("Cookie"));
        assertTrue(SensitiveDataSanitizer.isSensitiveHeader("cookie"));
        assertTrue(SensitiveDataSanitizer.isSensitiveHeader("Set-Cookie"));
        assertTrue(SensitiveDataSanitizer.isSensitiveHeader("set-cookie"));
        assertTrue(SensitiveDataSanitizer.isSensitiveHeader("X-Api-Key"));
        assertTrue(SensitiveDataSanitizer.isSensitiveHeader("api-key"));
        assertTrue(SensitiveDataSanitizer.isSensitiveHeader("apikey"));
        assertTrue(SensitiveDataSanitizer.isSensitiveHeader("Secret-Token"));
        assertTrue(SensitiveDataSanitizer.isSensitiveHeader("x-auth-token"));

        // Substring sensitive headers
        assertTrue(SensitiveDataSanitizer.isSensitiveHeader("Custom-Secret-Header"));
        assertTrue(SensitiveDataSanitizer.isSensitiveHeader("User-Password"));
        assertTrue(SensitiveDataSanitizer.isSensitiveHeader("Client-Credential"));
        assertTrue(SensitiveDataSanitizer.isSensitiveHeader("X-Request-Signature"));
    }

    @Test
    @DisplayName("sanitizeHeader: masks Bearer token, basic cookies, keys and preserves non-sensitive headers")
    void sanitizeHeaderMasking() {
        assertNull(SensitiveDataSanitizer.sanitizeHeader(null));

        Header nullName = new Header(null, "val");
        assertSame(nullName, SensitiveDataSanitizer.sanitizeHeader(nullName));

        Header nonSensitive = new Header("Accept", "application/json");
        assertSame(nonSensitive, SensitiveDataSanitizer.sanitizeHeader(nonSensitive));

        // Bearer token masking preserves prefix
        Header authBearer = new Header("Authorization", "Bearer eyJhbGciOi...");
        Header maskedBearer = SensitiveDataSanitizer.sanitizeHeader(authBearer);
        assertEquals("Authorization", maskedBearer.name());
        assertEquals("Bearer [REDACTED]", maskedBearer.value());

        // Basic / custom authorization
        Header authBasic = new Header("Authorization", "Basic dXNlcjpwYXNz");
        assertEquals("[REDACTED]", SensitiveDataSanitizer.sanitizeHeader(authBasic).value());

        // Null value handled
        Header nullValHeader = new Header("Authorization", null);
        assertEquals("[REDACTED]", SensitiveDataSanitizer.sanitizeHeader(nullValHeader).value());

        // Cookies and API keys
        Header cookie = new Header("Cookie", "session=12345; auth=abc");
        assertEquals("[REDACTED]", SensitiveDataSanitizer.sanitizeHeader(cookie).value());

        Header setCookie = new Header("Set-Cookie", "token=secret_jwt; Secure; HttpOnly");
        assertEquals("[REDACTED]", SensitiveDataSanitizer.sanitizeHeader(setCookie).value());

        Header apiKey = new Header("X-Api-Key", "sk-1234567890abcdef");
        assertEquals("[REDACTED]", SensitiveDataSanitizer.sanitizeHeader(apiKey).value());
    }

    @Test
    @DisplayName("isSensitiveParam: identifies sensitive query parameters")
    void isSensitiveParamIdentification() {
        assertFalse(SensitiveDataSanitizer.isSensitiveParam(null));
        assertFalse(SensitiveDataSanitizer.isSensitiveParam(""));
        assertFalse(SensitiveDataSanitizer.isSensitiveParam("page"));
        assertFalse(SensitiveDataSanitizer.isSensitiveParam("limit"));
        assertFalse(SensitiveDataSanitizer.isSensitiveParam("sort"));

        assertTrue(SensitiveDataSanitizer.isSensitiveParam("token"));
        assertTrue(SensitiveDataSanitizer.isSensitiveParam("access_token"));
        assertTrue(SensitiveDataSanitizer.isSensitiveParam("refresh_token"));
        assertTrue(SensitiveDataSanitizer.isSensitiveParam("secret"));
        assertTrue(SensitiveDataSanitizer.isSensitiveParam("password"));
        assertTrue(SensitiveDataSanitizer.isSensitiveParam("key"));
        assertTrue(SensitiveDataSanitizer.isSensitiveParam("api_key"));
        assertTrue(SensitiveDataSanitizer.isSensitiveParam("apikey"));
        assertTrue(SensitiveDataSanitizer.isSensitiveParam("phone"));
        assertTrue(SensitiveDataSanitizer.isSensitiveParam("email"));
        assertTrue(SensitiveDataSanitizer.isSensitiveParam("passport"));
        assertTrue(SensitiveDataSanitizer.isSensitiveParam("code"));
        assertTrue(SensitiveDataSanitizer.isSensitiveParam("client_secret"));
        assertTrue(SensitiveDataSanitizer.isSensitiveParam("app_secret"));
        assertTrue(SensitiveDataSanitizer.isSensitiveParam("user_password"));
        assertTrue(SensitiveDataSanitizer.isSensitiveParam("api_token_custom"));
    }

    @Test
    @DisplayName("sanitizeUri: masks query params containing secrets or PII without touching harmless params")
    void sanitizeUriMasking() {
        assertEquals("", SensitiveDataSanitizer.sanitizeUri(null));

        URI noQuery = URI.create("https://example.com/api/v1/users");
        assertEquals("https://example.com/api/v1/users", SensitiveDataSanitizer.sanitizeUri(noQuery));

        URI emptyQuery = URI.create("https://example.com/api?");
        assertEquals("https://example.com/api?", SensitiveDataSanitizer.sanitizeUri(emptyQuery));

        URI harmless = URI.create("https://example.com/search?page=1&limit=20&sort=asc");
        assertEquals("https://example.com/search?page=1&limit=20&sort=asc", SensitiveDataSanitizer.sanitizeUri(harmless));

        URI withToken = URI.create("https://api.gateway.ru/pay?token=sec_12345&amount=1000");
        assertEquals("https://api.gateway.ru/pay?token=[REDACTED]&amount=1000", SensitiveDataSanitizer.sanitizeUri(withToken));

        URI withPiiAndFragment = URI.create("https://api.gateway.ru/profile?email=user@test.ru&phone=+79991234567#summary");
        assertEquals("https://api.gateway.ru/profile?email=[REDACTED]&phone=[REDACTED]#summary", SensitiveDataSanitizer.sanitizeUri(withPiiAndFragment));

        URI emptyValAndFlag = URI.create("https://api.gateway.ru/endpoint?flag&token=");
        assertEquals("https://api.gateway.ru/endpoint?flag&token=[REDACTED]", SensitiveDataSanitizer.sanitizeUri(emptyValAndFlag));

        URI multipleCleanAndDirty = URI.create("https://api.gateway.ru/endpoint?a=1&password=123&b=2");
        assertEquals("https://api.gateway.ru/endpoint?a=1&password=[REDACTED]&b=2", SensitiveDataSanitizer.sanitizeUri(multipleCleanAndDirty));
    }

    @Test
    @DisplayName("sanitizeBody: truncates oversized payloads and masks known json credentials")
    void sanitizeBodyBehavior() {
        assertEquals("No request body", SensitiveDataSanitizer.sanitizeBody(null, 100));
        assertEquals("", SensitiveDataSanitizer.sanitizeBody(new byte[0], 100));

        // Normal payload under limit
        String cleanJson = "{\"name\":\"Alice\",\"role\":\"admin\"}";
        assertEquals(cleanJson, SensitiveDataSanitizer.sanitizeBody(cleanJson.getBytes(StandardCharsets.UTF_8), 100));

        // Masking password and secret in JSON
        String sensitiveJson = "{\"user\":\"john\",\"password\":\"super_secret\",\"secret\":\"xyz123\",\"cvv\":\"999\"}";
        String sanitized = SensitiveDataSanitizer.sanitizeBody(sensitiveJson.getBytes(StandardCharsets.UTF_8), 500);
        assertTrue(sanitized.contains("\"password\":\"[REDACTED]\""));
        assertTrue(sanitized.contains("\"secret\":\"[REDACTED]\""));
        assertTrue(sanitized.contains("\"cvv\":\"[REDACTED]\""));
        assertFalse(sanitized.contains("super_secret"));

        // Truncation when exceeding maxLogBytes
        byte[] largeBytes = new byte[200];
        java.util.Arrays.fill(largeBytes, (byte) 'A');
        String truncated = SensitiveDataSanitizer.sanitizeBody(largeBytes, 50);
        assertTrue(truncated.contains("... [truncated, total 200 bytes]"));
        assertEquals(50, truncated.indexOf("\n... [truncated"));
    }
}
