package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.problem.ProblemDetail;
import ru.jrestly.problem.ProblemDetailException;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ProblemDetail} and {@link ProblemDetailException} RFC 7807 / RFC 9457 compliance.
 */
class ProblemDetailTest {

    @Test
    @DisplayName("Default ProblemDetail has type about:blank and zero status")
    void defaultProblemDetail() {
        ProblemDetail pd = new ProblemDetail();
        assertEquals(ProblemDetail.DEFAULT_TYPE, pd.getType());
        assertEquals("about:blank", pd.getType().toString());
        assertNull(pd.getTitle());
        assertEquals(0, pd.getStatus());
        assertNull(pd.getDetail());
        assertNull(pd.getInstance());
        assertTrue(pd.getProperties().isEmpty());
    }

    @Test
    @DisplayName("Factory forStatus sets default RFC HTTP title and status code")
    void forStatusFactory() {
        ProblemDetail pd404 = ProblemDetail.forStatus(404);
        assertEquals(404, pd404.getStatus());
        assertEquals("Not Found", pd404.getTitle());
        assertEquals(ProblemDetail.DEFAULT_TYPE, pd404.getType());

        ProblemDetail pd400 = ProblemDetail.forStatus(400);
        assertEquals(400, pd400.getStatus());
        assertEquals("Bad Request", pd400.getTitle());

        ProblemDetail pd403 = ProblemDetail.forStatus(403);
        assertEquals(403, pd403.getStatus());
        assertEquals("Forbidden", pd403.getTitle());

        ProblemDetail pd422 = ProblemDetail.forStatus(422);
        assertEquals(422, pd422.getStatus());
        assertEquals("Unprocessable Content", pd422.getTitle());

        ProblemDetail pd500 = ProblemDetail.forStatus(500);
        assertEquals(500, pd500.getStatus());
        assertEquals("Internal Server Error", pd500.getTitle());

        ProblemDetail pd503 = ProblemDetail.forStatus(503);
        assertEquals(503, pd503.getStatus());
        assertEquals("Service Unavailable", pd503.getTitle());
    }

    @Test
    @DisplayName("Factory forStatusAndDetail sets status, title, and detail")
    void forStatusAndDetailFactory() {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(404, "User account with ID 42 was not found.");
        assertEquals(404, pd.getStatus());
        assertEquals("Not Found", pd.getTitle());
        assertEquals("User account with ID 42 was not found.", pd.getDetail());
    }

    @Test
    @DisplayName("Builder correctly configures all standard and extension properties")
    void builderConfiguration() {
        ProblemDetail pd = ProblemDetail.builder()
                .type("https://example.com/probs/out-of-credit")
                .title("You do not have enough credit.")
                .status(403)
                .detail("Your current balance is 30, but that costs 50.")
                .instance("/account/12345/msgs/abc")
                .property("balance", 30)
                .property("accounts", List.of("/account/12345", "/account/67890"))
                .build();

        assertEquals(URI.create("https://example.com/probs/out-of-credit"), pd.getType());
        assertEquals("You do not have enough credit.", pd.getTitle());
        assertEquals(403, pd.getStatus());
        assertEquals("Your current balance is 30, but that costs 50.", pd.getDetail());
        assertEquals(URI.create("/account/12345/msgs/abc"), pd.getInstance());
        assertEquals(30, pd.getProperty("balance"));
        assertEquals(Integer.valueOf(30), pd.getProperty("balance", Integer.class));
        assertNotNull(pd.getProperty("accounts"));
    }

    @Test
    @DisplayName("Zero-dep JSON parser correctly parses RFC 9457 Out-of-Credit example")
    void parsesRfcExample() {
        String json = """
                {
                  "type": "https://example.com/probs/out-of-credit",
                  "title": "You do not have enough credit.",
                  "status": 403,
                  "detail": "Your current balance is 30, but that costs 50.",
                  "instance": "/account/12345/msgs/abc",
                  "balance": 30,
                  "active": true
                }
                """;

        ProblemDetail pd = ProblemDetail.fromJson(json);
        assertEquals(URI.create("https://example.com/probs/out-of-credit"), pd.getType());
        assertEquals("You do not have enough credit.", pd.getTitle());
        assertEquals(403, pd.getStatus());
        assertEquals("Your current balance is 30, but that costs 50.", pd.getDetail());
        assertEquals(URI.create("/account/12345/msgs/abc"), pd.getInstance());
        assertEquals(30, pd.getProperty("balance"));
        assertEquals(Boolean.TRUE, pd.getProperty("active"));
    }

    @Test
    @DisplayName("Zero-dep toJson() serializes all fields and flattens extension properties")
    void serializesToJson() {
        ProblemDetail pd = ProblemDetail.builder()
                .type(URI.create("https://example.org/errors/validation"))
                .title("Validation Error")
                .status(422)
                .detail("Invalid input parameters")
                .instance(URI.create("/items/10"))
                .property("errorCode", "VAL_001")
                .property("retryable", false)
                .build();

        String json = pd.toJson();
        assertTrue(json.contains("\"type\":\"https://example.org/errors/validation\""));
        assertTrue(json.contains("\"title\":\"Validation Error\""));
        assertTrue(json.contains("\"status\":422"));
        assertTrue(json.contains("\"detail\":\"Invalid input parameters\""));
        assertTrue(json.contains("\"instance\":\"/items/10\""));
        assertTrue(json.contains("\"errorCode\":\"VAL_001\""));
        assertTrue(json.contains("\"retryable\":false"));

        // Round-trip verification
        ProblemDetail roundTrip = ProblemDetail.fromJson(json);
        assertEquals(pd, roundTrip);
    }

    @Test
    @DisplayName("Zero-dep toJson() serializes arrays (objects and primitives) in extension properties")
    void serializesArraysInProperties() {
        ProblemDetail pd = ProblemDetail.builder()
                .status(400)
                .property("stringArray", new String[]{"first", "second"})
                .property("intArray", new int[]{10, 20, 30})
                .property("booleanArray", new boolean[]{true, false})
                .build();

        String json = pd.toJson();
        assertTrue(json.contains("\"stringArray\":[\"first\",\"second\"]"), "Expected JSON array for stringArray, got: " + json);
        assertTrue(json.contains("\"intArray\":[10,20,30]"), "Expected JSON array for intArray, got: " + json);
        assertTrue(json.contains("\"booleanArray\":[true,false]"), "Expected JSON array for booleanArray, got: " + json);

        ProblemDetail parsed = ProblemDetail.fromJson(json);
        assertEquals(List.of("first", "second"), parsed.getProperty("stringArray"));
        assertEquals(List.of(10, 20, 30), parsed.getProperty("intArray"));
        assertEquals(List.of(true, false), parsed.getProperty("booleanArray"));
    }

    @Test
    @DisplayName("Special character escaping in JSON string values")
    void jsonEscaping() {
        ProblemDetail pd = ProblemDetail.builder()
                .status(400)
                .detail("Line 1\nLine 2 with \"quotes\" and \\backslash\\ and \t tab")
                .build();

        String json = pd.toJson();
        ProblemDetail parsed = ProblemDetail.fromJson(json);
        assertEquals("Line 1\nLine 2 with \"quotes\" and \\backslash\\ and \t tab", parsed.getDetail());
    }

    @Test
    @DisplayName("Malformed JSON string throws IllegalArgumentException")
    void malformedJson() {
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson(null));
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson(""));
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("   "));
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("[1, 2, 3]"));
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("not a json"));
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("{\"key\": [?]}"));
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("{\"key\": [abc]}"));
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("{\"key\": }"));
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("{\"key\": ?}"));
    }

    @Test
    @DisplayName("ZeroDepJsonParser handles escaped keys, nested objects and various number formats")
    void zeroDepJsonParserAdvanced() {
        String json = """
                {
                  "type": "https://example.org/error",
                  "title": "Complex Error",
                  "status": 500,
                  "detail": "Failed",
                  "escaped\\"key": "val1",
                  "escaped\\\\key": "val2",
                  "negativeInt": -42,
                  "floatingPoint": -3.1415,
                  "exponent": 1.2e3,
                  "nestedObj": {
                    "inner": "value"
                  },
                  "nestedArr": [1, "two", false, null]
                }
                """;

        ProblemDetail pd = ProblemDetail.fromJson(json);
        assertEquals(500, pd.getStatus());
        assertEquals("val1", pd.getProperty("escaped\"key"));
        assertEquals("val2", pd.getProperty("escaped\\key"));
        assertEquals(-42, pd.getProperty("negativeInt"));
        assertEquals(-3.1415, pd.getProperty("floatingPoint"));
        assertEquals(1200.0, pd.getProperty("exponent"));
        assertTrue(pd.getProperty("nestedObj") instanceof Map);
        assertTrue(pd.getProperty("nestedArr") instanceof List);
    }

    @Test
    @DisplayName("ZeroDepJsonParser syntax error handling")
    void zeroDepJsonParserSyntaxErrors() {
        // Missing colon
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("{\"key\" \"val\"}"));
        // Missing comma between fields
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("{\"a\": 1 \"b\": 2}"));
        // Unclosed string
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("{\"key\": \"val}"));
        // Unclosed object
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("{\"key\": \"val\""));
        // Unclosed array
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("{\"arr\": [1, 2}"));
        // Missing value
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("{\"key\":}"));
        // Leading minus without digits
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("{\"key\": -}"));
    }

    @Test
    @DisplayName("ProblemDetail equals and hashCode contract")
    void equalsAndHashCode() {
        ProblemDetail pd1 = ProblemDetail.forStatusAndDetail(404, "Not found");
        ProblemDetail pd2 = ProblemDetail.forStatusAndDetail(404, "Not found");
        ProblemDetail pd3 = ProblemDetail.forStatusAndDetail(400, "Bad request");

        assertEquals(pd1, pd2);
        assertEquals(pd1.hashCode(), pd2.hashCode());
        assertFalse(pd1.equals(pd3));
        assertFalse(pd1.equals("other"));
    }

    @Test
    @DisplayName("ProblemDetailException carries ProblemDetail and produces descriptive message")
    void problemDetailException() {
        ProblemDetail pd = ProblemDetail.builder()
                .status(404)
                .title("Not Found")
                .detail("User 123 does not exist")
                .build();

        ProblemDetailException ex = new ProblemDetailException(pd, List.of());
        assertEquals(pd, ex.problemDetail());
        assertEquals(pd, ex.getProblemDetailPayload());
        assertEquals(404, ex.getStatusCode());
        assertEquals("HTTP 404 [Not Found]: User 123 does not exist", ex.getMessage());
        assertTrue(ex.getProblemDetail().isPresent());
        assertEquals(pd, ex.getProblemDetail().get());
    }

    @Test
    @DisplayName("ProblemDetail fluent setters, string URI setters, typed property access and toString")
    void fluentSettersAndPropertyAccess() {
        ProblemDetail pd = new ProblemDetail()
                .withType(URI.create("https://example.org/probs/custom"))
                .withTitle("Custom Problem")
                .withStatus(418)
                .withDetail("I'm a teapot")
                .withInstance(URI.create("/teapot/1"))
                .withProperty("temp", 95)
                .withProperty("ready", true);

        assertEquals(URI.create("https://example.org/probs/custom"), pd.getType());
        assertEquals("Custom Problem", pd.getTitle());
        assertEquals(418, pd.getStatus());
        assertEquals("I'm a teapot", pd.getDetail());
        assertEquals(URI.create("/teapot/1"), pd.getInstance());
        assertEquals(Integer.valueOf(95), pd.getProperty("temp", Integer.class));
        assertNull(pd.getProperty("temp", String.class));
        assertNull(pd.getProperty("nonExistent", Object.class));
        assertEquals(Boolean.TRUE, pd.getProperty("ready", Boolean.class));
        assertTrue(pd.toString().contains("418"));
        assertTrue(pd.toString().contains("I'm a teapot"));

        // String URI setters
        pd.setType("https://example.org/probs/updated");
        assertEquals(URI.create("https://example.org/probs/updated"), pd.getType());
        pd.setType((String) null);
        assertEquals(ProblemDetail.DEFAULT_TYPE, pd.getType());

        pd.setInstance("/new-instance");
        assertEquals(URI.create("/new-instance"), pd.getInstance());
        pd.setInstance((String) null);
        assertNull(pd.getInstance());
    }

    @Test
    @DisplayName("ZeroDepJsonParser parses unicode escapes and fails fast on invalid sequences")
    void unicodeEscapesInZeroDepParser() {
        String json = "{\"detail\": \"Letter \\u0041 and \\u0042\"}";
        ProblemDetail pd = ProblemDetail.fromJson(json);
        assertEquals("Letter A and B", pd.getDetail());

        // Incomplete unicode escape
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("{\"detail\": \"\\u12\"}"));
        // Invalid hex in unicode escape
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("{\"detail\": \"\\uXYZW\"}"));
    }

    @Test
    @DisplayName("ProblemDetailException constructors and message formatting edge cases")
    void problemDetailExceptionConstructors() {
        // Constructor with null details
        ProblemDetailException exNull = new ProblemDetailException(null, 500, null);
        assertEquals(500, exNull.getStatusCode());
        assertNotNull(exNull.problemDetail());
        assertEquals(500, exNull.problemDetail().getStatus());

        // Constructor with String details
        ProblemDetailException exStr = new ProblemDetailException("Something failed", 400, List.of());
        assertEquals(400, exStr.getStatusCode());
        assertEquals("Something failed", exStr.problemDetail().getDetail());

        // Constructor with existing ProblemDetail
        ProblemDetail pd = ProblemDetail.forStatus(403);
        ProblemDetailException exPd = new ProblemDetailException(pd, 403, List.of());
        assertEquals(pd, exPd.problemDetail());

        // getMessage formatting variations
        ProblemDetail pdOnlyStatus = new ProblemDetail();
        pdOnlyStatus.setStatus(502);
        ProblemDetailException exNoTitleNoDetail = new ProblemDetailException(pdOnlyStatus, List.of());
        assertEquals("HTTP 502", exNoTitleNoDetail.getMessage());

        ProblemDetail pdTitleOnly = new ProblemDetail();
        pdTitleOnly.setStatus(503);
        pdTitleOnly.setTitle("Service Unavailable");
        ProblemDetailException exTitleOnly = new ProblemDetailException(pdTitleOnly, List.of());
        assertEquals("HTTP 503 [Service Unavailable]", exTitleOnly.getMessage());

        ProblemDetail pdDetailOnly = new ProblemDetail();
        pdDetailOnly.setStatus(504);
        pdDetailOnly.setDetail("Gateway Timeout Occurred");
        ProblemDetailException exDetailOnly = new ProblemDetailException(pdDetailOnly, List.of());
        assertEquals("HTTP 504: Gateway Timeout Occurred", exDetailOnly.getMessage());
    }
}
