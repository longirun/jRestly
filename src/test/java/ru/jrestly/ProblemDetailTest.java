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
    @DisplayName("fromJson tolerates an optional leading UTF-8 byte-order mark (RFC 8259 §8.1)")
    void fromJsonToleratesLeadingByteOrderMark() {
        String problem = "{\"type\":\"https://example.com/probs/not-found\",\"title\":\"Not Found\",\"status\":404,\"detail\":\"Resource missing\"}";

        // A single leading BOM is stripped, not treated as content
        ProblemDetail pd = ProblemDetail.fromJson("\uFEFF" + problem);
        assertEquals(404, pd.getStatus());
        assertEquals("Not Found", pd.getTitle());
        assertEquals("Resource missing", pd.getDetail());
        assertEquals(URI.create("https://example.com/probs/not-found"), pd.getType());

        // Whitespace on either side of the BOM is also normalized
        ProblemDetail padded = ProblemDetail.fromJson("  \uFEFF  " + problem + " \n");
        assertEquals(pd, padded);

        // A BOM-only body is not blank, but is not an object either
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("\uFEFF"));
        // RFC 8259 allows at most one BOM prefix; a doubled BOM is content, not a BOM
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("\uFEFF\uFEFF" + problem));
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

    @Test
    @DisplayName("Zero-dep toJson() serializes nested Maps and null values in extension properties")
    void serializesNestedMapsInProperties() {
        ProblemDetail pd = ProblemDetail.builder()
                .status(400)
                .property("errors", Map.of("field", "username", "code", "required"))
                .property("nullProp", null)
                .build();

        String json = pd.toJson();
        assertTrue(json.contains("\"errors\":{"), "Expected nested JSON object, got: " + json);
        assertTrue(json.contains("\"field\":\"username\""), "Expected field key in json: " + json);
        assertTrue(json.contains("\"nullProp\":null"), "Expected null value in json: " + json);

        ProblemDetail parsed = ProblemDetail.fromJson(json);
        assertTrue(parsed.getProperty("errors") instanceof Map);
        Map<?, ?> errors = (Map<?, ?>) parsed.getProperty("errors");
        assertEquals("username", errors.get("field"));
        assertEquals("required", errors.get("code"));
        assertNull(parsed.getProperty("nullProp"));
    }

    @Test
    @DisplayName("ZeroDepJsonParser handles large numbers, escape sequences, string status and nested quotes")
    void zeroDepJsonParserEscapesAndNumbers() {
        String json = """
                {
                  "type": "https://example.org/probs/special",
                  "status": "422",
                  "title": "Unprocessable",
                  "detail": "Escaped: \\/ slash, \\b backspace, \\f formfeed, \\r return, \\t tab",
                  "bigNumber": 9999999999,
                  "nestedQuoted": {
                    "message": "value with \\"quotes\\""
                  }
                }
                """;

        ProblemDetail pd = ProblemDetail.fromJson(json);
        assertEquals(422, pd.getStatus());
        assertEquals("Unprocessable", pd.getTitle());
        assertEquals("Escaped: / slash, \b backspace, \f formfeed, \r return, \t tab", pd.getDetail());
        assertEquals(9999999999L, pd.getProperty("bigNumber"));
        assertTrue(pd.getProperty("nestedQuoted") instanceof Map);

        // Invalid string status falls back gracefully without error
        ProblemDetail pdBadStatus = ProblemDetail.fromJson("{\"status\": \"not-a-number\"}");
        assertEquals(0, pdBadStatus.getStatus());
    }

    @Test
    @DisplayName("ProblemDetail.Builder properties(Map) copies all properties")
    void builderPropertiesMap() {
        ProblemDetail pd = ProblemDetail.builder()
                .status(400)
                .properties(Map.of("k1", "v1", "k2", 123))
                .build();
        assertEquals("v1", pd.getProperty("k1"));
        assertEquals(123, pd.getProperty("k2"));

        ProblemDetail pdNull = ProblemDetail.builder().properties(null).build();
        assertNotNull(pdNull.getProperties());
    }

    @Test
    @DisplayName("Default titles for all RFC status codes and custom statuses")
    void allStatusTitles() {
        assertEquals("Unauthorized", ProblemDetail.forStatus(401).getTitle());
        assertEquals("Payment Required", ProblemDetail.forStatus(402).getTitle());
        assertEquals("Method Not Allowed", ProblemDetail.forStatus(405).getTitle());
        assertEquals("Not Acceptable", ProblemDetail.forStatus(406).getTitle());
        assertEquals("Request Timeout", ProblemDetail.forStatus(408).getTitle());
        assertEquals("Conflict", ProblemDetail.forStatus(409).getTitle());
        assertEquals("Gone", ProblemDetail.forStatus(410).getTitle());
        assertEquals("Precondition Failed", ProblemDetail.forStatus(412).getTitle());
        assertEquals("Content Too Large", ProblemDetail.forStatus(413).getTitle());
        assertEquals("Unsupported Media Type", ProblemDetail.forStatus(415).getTitle());
        assertEquals("Too Many Requests", ProblemDetail.forStatus(429).getTitle());
        assertEquals("Not Implemented", ProblemDetail.forStatus(501).getTitle());
        assertEquals("Bad Gateway", ProblemDetail.forStatus(502).getTitle());
        assertEquals("Gateway Timeout", ProblemDetail.forStatus(504).getTitle());
        assertEquals("HTTP 418", ProblemDetail.forStatus(418).getTitle());
        assertEquals("HTTP 999", ProblemDetail.forStatus(999).getTitle());
    }

    @Test
    @DisplayName("ProblemDetail.Builder and Model edge cases for type, instance and null handling")
    void builderAndModelEdgeCases() {
        URI typeUri = URI.create("https://example.com/err");
        ProblemDetail pd = ProblemDetail.builder()
                .type(typeUri)
                .type("https://example.com/err-str")
                .instance("https://example.com/inst-str")
                .build();
        assertEquals(URI.create("https://example.com/err-str"), pd.getType());
        assertEquals(URI.create("https://example.com/inst-str"), pd.getInstance());

        ProblemDetail pdNulls = ProblemDetail.builder()
                .type((URI) null)
                .type((String) null)
                .instance((String) null)
                .build();
        assertEquals(ProblemDetail.DEFAULT_TYPE, pdNulls.getType());
        assertNull(pdNulls.getInstance());

        pd.setType((URI) null);
        assertEquals(ProblemDetail.DEFAULT_TYPE, pd.getType());

        pd.setType("  ");
        assertEquals(ProblemDetail.DEFAULT_TYPE, pd.getType());

        pd.setInstance("  ");
        assertNull(pd.getInstance());
    }

    @Test
    @DisplayName("Serialization of URI and custom objects in properties, plus control characters escaping")
    void serializationUriAndCustomObjects() {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(400, "Contains \u0001 bell \u001f units");
        pd.setProperty("uriProp", URI.create("https://api.example.com/docs"));
        pd.setProperty("customObj", new Object() {
            @Override
            public String toString() {
                return "custom-val";
            }
        });

        String json = pd.toJson();
        assertTrue(json.contains("\"uriProp\":\"https://api.example.com/docs\""));
        assertTrue(json.contains("\"customObj\":\"custom-val\""));
        assertTrue(json.contains("\\u0001"));
        assertTrue(json.contains("\\u001f"));

        ProblemDetail roundtrip = ProblemDetail.fromJson(json);
        assertEquals(400, roundtrip.getStatus());
        assertEquals("https://api.example.com/docs", roundtrip.getProperty("uriProp"));
        assertEquals("custom-val", roundtrip.getProperty("customObj"));
    }

    @Test
    @DisplayName("ProblemDetailException constructor with headers and message without detail")
    void problemDetailExceptionHeadersAndMessage() {
        ProblemDetail pd = ProblemDetail.forStatus(404);
        ProblemDetailException ex = new ProblemDetailException(pd, List.of(new ru.jrestly.http.Header("X-Err", "1")));
        assertEquals(404, ex.getStatusCode());
        assertEquals("HTTP 404 [Not Found]", ex.getMessage());
        assertEquals(1, ex.getResponseHeaders().size());
        assertEquals("1", ex.getResponseHeaders().getFirst().value());
    }

    @Test
    @DisplayName("ProblemDetail.Builder instance(URI), property blank filtering and typed property mismatch")
    void builderInstanceUriAndPropertyFiltering() {
        URI instUri = URI.create("https://api.example.com/item/1");
        ProblemDetail pd = ProblemDetail.builder()
                .instance(instUri)
                .property("validKey", "validValue")
                .property(null, "ignored")
                .property("   ", "ignored")
                .build();

        assertEquals(instUri, pd.getInstance());
        assertEquals("validValue", pd.getProperty("validKey"));
        assertNull(pd.getProperty("nonExistent"));

        // Type mismatch returns null safely
        assertNull(pd.getProperty("validKey", Integer.class));
        assertEquals("validValue", pd.getProperty("validKey", String.class));
        assertNull(pd.getProperty("nonExistent", String.class));
    }

    @Test
    @DisplayName("ZeroDepJsonParser invalid numbers, unclosed brackets, and non-string keys")
    void parserInvalidNumbersAndUnclosedBrackets() {
        // Invalid numbers
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("{\"num\": 1.2.3}"));
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("{\"num\": 1e+}"));

        // Unclosed brackets in nested objects and arrays
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("{\"nested\": {\"key\": \"val\""));
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("{\"arr\": [1, 2, 3"));

        // Non-string key inside nested object
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("{\"nested\": {123: \"val\"}}"));

        // Incomplete unicode escape
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("{\"key\": \"\\u12\"}"));

        // Missing comma or bracket in array
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("{\"arr\": [1 2]}"));

        // Unclosed string with trailing backslash
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.fromJson("{\"key\": \"abc\\"));
    }

    @Test
    @DisplayName("ProblemDetail.toJson serializes Iterable (List/Set) and special escape characters in properties")
    void serializesIterableAndSpecialEscapesInProperties() {
        ProblemDetail pd = ProblemDetail.forStatus(422)
                .withProperty("list", List.of("first", "second"))
                .withProperty("specialEscapes", "backspace:\b formfeed:\f return:\r");
        String json = pd.toJson();
        assertTrue(json.contains("\"list\":[\"first\",\"second\"]"));
        assertTrue(json.contains("\\b"));
        assertTrue(json.contains("\\f"));
        assertTrue(json.contains("\\r"));

        ProblemDetail parsed = ProblemDetail.fromJson(json);
        assertEquals(422, parsed.getStatus());
        assertTrue(parsed.getProperty("list") instanceof List);
    }

    @Test
    @DisplayName("UnexpectedStatusException message formatting with various problemDetail states and rawBody null-check")
    void unexpectedStatusExceptionMessageVariants() {
        ru.jrestly.http.UnexpectedStatusException exNull = new ru.jrestly.http.UnexpectedStatusException(400, null, null, null);
        assertEquals("Unexpected HTTP status 400", exNull.getMessage());
        assertEquals("", exNull.getRawBody());

        ProblemDetail pdNoTitleNoDetail = new ProblemDetail();
        pdNoTitleNoDetail.setStatus(400);
        ru.jrestly.http.UnexpectedStatusException exNoTitleNoDetail = new ru.jrestly.http.UnexpectedStatusException(400, "err", null, pdNoTitleNoDetail);
        assertEquals("Unexpected HTTP status 400", exNoTitleNoDetail.getMessage());

        ProblemDetail pdEmptyTitleEmptyDetail = new ProblemDetail();
        pdEmptyTitleEmptyDetail.setStatus(400);
        pdEmptyTitleEmptyDetail.setTitle("");
        pdEmptyTitleEmptyDetail.setDetail("");
        ru.jrestly.http.UnexpectedStatusException exEmptyTitleEmptyDetail = new ru.jrestly.http.UnexpectedStatusException(400, "err", null, pdEmptyTitleEmptyDetail);
        assertEquals("Unexpected HTTP status 400", exEmptyTitleEmptyDetail.getMessage());

        ProblemDetail pdOnlyTitle = new ProblemDetail();
        pdOnlyTitle.setStatus(400);
        pdOnlyTitle.setTitle("Bad Request");
        ru.jrestly.http.UnexpectedStatusException exOnlyTitle = new ru.jrestly.http.UnexpectedStatusException(400, "err", null, pdOnlyTitle);
        assertEquals("Unexpected HTTP status 400 (Bad Request)", exOnlyTitle.getMessage());

        ProblemDetail pdOnlyDetail = new ProblemDetail();
        pdOnlyDetail.setStatus(400);
        pdOnlyDetail.setDetail("ID is required");
        ru.jrestly.http.UnexpectedStatusException exOnlyDetail = new ru.jrestly.http.UnexpectedStatusException(400, "err", null, pdOnlyDetail);
        assertEquals("Unexpected HTTP status 400: ID is required", exOnlyDetail.getMessage());
    }

    @Test
    @DisplayName("ProblemDetailException 3-arg constructor handles null, ProblemDetail, and arbitrary object details")
    void problemDetailExceptionConstructorAndMessageVariants() {
        ProblemDetailException exNullDetails = new ProblemDetailException(null, 400, null);
        assertEquals(400, exNullDetails.getStatusCode());
        assertNotNull(exNullDetails.problemDetail());
        assertEquals(400, exNullDetails.problemDetail().getStatus());

        ProblemDetail customPd = ProblemDetail.forStatusAndDetail(403, "Forbidden area");
        ProblemDetailException exPd = new ProblemDetailException(customPd, 403, null);
        assertEquals(customPd, exPd.problemDetail());

        ProblemDetailException exStringDetails = new ProblemDetailException("Invalid auth token", 401, null);
        assertEquals("Invalid auth token", exStringDetails.problemDetail().getDetail());

        ProblemDetail pdNoTitleNoDetail = new ProblemDetail();
        pdNoTitleNoDetail.setStatus(500);
        ProblemDetailException exNoTitleNoDetail = new ProblemDetailException(pdNoTitleNoDetail, null);
        assertEquals("HTTP 500", exNoTitleNoDetail.getMessage());

        ProblemDetail pdEmptyTitleEmptyDetail = new ProblemDetail();
        pdEmptyTitleEmptyDetail.setStatus(500);
        pdEmptyTitleEmptyDetail.setTitle("");
        pdEmptyTitleEmptyDetail.setDetail("");
        ProblemDetailException exEmptyTitleEmptyDetail = new ProblemDetailException(pdEmptyTitleEmptyDetail, null);
        assertEquals("HTTP 500", exEmptyTitleEmptyDetail.getMessage());

        ProblemDetail pdOnlyTitle = new ProblemDetail();
        pdOnlyTitle.setStatus(500);
        pdOnlyTitle.setTitle("Server Error");
        ProblemDetailException exOnlyTitle = new ProblemDetailException(pdOnlyTitle, null);
        assertEquals("HTTP 500 [Server Error]", exOnlyTitle.getMessage());

        ProblemDetail pdOnlyDetail = new ProblemDetail();
        pdOnlyDetail.setStatus(500);
        pdOnlyDetail.setDetail("Database down");
        ProblemDetailException exOnlyDetail = new ProblemDetailException(pdOnlyDetail, null);
        assertEquals("HTTP 500: Database down", exOnlyDetail.getMessage());
    }

    @Test
    @DisplayName("ProblemDetail setProperties null handling, blank property names, and toJson field permutations")
    void problemDetailSetPropertiesAndToJsonVariants() {
        ProblemDetail pd = ProblemDetail.forStatus(400);
        pd.setProperty("prop1", "val1");
        assertEquals(1, pd.getProperties().size());

        pd.setProperties(null);
        assertTrue(pd.getProperties().isEmpty());

        pd.setProperty(null, "val");
        pd.setProperty("   ", "val");
        assertTrue(pd.getProperties().isEmpty());

        // toJson field permutations with default type about:blank
        ProblemDetail pdTitleOnly = new ProblemDetail();
        pdTitleOnly.setTitle("Title Only");
        assertEquals("{\"type\":\"about:blank\",\"title\":\"Title Only\"}", pdTitleOnly.toJson());

        ProblemDetail pdStatusOnly = new ProblemDetail();
        pdStatusOnly.setStatus(404);
        assertEquals("{\"type\":\"about:blank\",\"status\":404}", pdStatusOnly.toJson());

        ProblemDetail pdDetailOnly = new ProblemDetail();
        pdDetailOnly.setDetail("Detail Only");
        assertEquals("{\"type\":\"about:blank\",\"detail\":\"Detail Only\"}", pdDetailOnly.toJson());

        ProblemDetail pdInstanceOnly = new ProblemDetail();
        pdInstanceOnly.setInstance(URI.create("/instance/1"));
        assertEquals("{\"type\":\"about:blank\",\"instance\":\"/instance/1\"}", pdInstanceOnly.toJson());

        ProblemDetail pdPropsOnly = new ProblemDetail();
        pdPropsOnly.setProperty("code", "E123");
        assertEquals("{\"type\":\"about:blank\",\"code\":\"E123\"}", pdPropsOnly.toJson());
    }

    @Test
    @DisplayName("ProblemDetail.fromJson parses null fields and ignores non-numeric status strings")
    void problemDetailFromJsonEdgeCases() {
        ProblemDetail pd = ProblemDetail.fromJson("{\"type\": null, \"title\": null, \"detail\": null, \"instance\": null, \"status\": \"not-a-number\"}");
        assertEquals(ProblemDetail.DEFAULT_TYPE, pd.getType());
        assertNull(pd.getTitle());
        assertNull(pd.getDetail());
        assertNull(pd.getInstance());
        assertEquals(0, pd.getStatus());
    }

    @Test
    @DisplayName("ProblemDetail equals and hashCode contract")
    void problemDetailEqualsAndHashCode() {
        ProblemDetail pd1 = ProblemDetail.forStatusAndDetail(400, "Bad Request");
        pd1.setProperty("key", "val");

        // Identity and null checks
        assertTrue(pd1.equals(pd1));
        assertFalse(pd1.equals(null));
        assertFalse(pd1.equals("different type"));

        // Status mismatch
        ProblemDetail pdDiffStatus = ProblemDetail.forStatusAndDetail(500, "Bad Request");
        pdDiffStatus.setProperty("key", "val");
        assertFalse(pd1.equals(pdDiffStatus));

        // Type mismatch
        ProblemDetail pdDiffType = ProblemDetail.forStatusAndDetail(400, "Bad Request");
        pdDiffType.setType("https://example.com/custom");
        pdDiffType.setProperty("key", "val");
        assertFalse(pd1.equals(pdDiffType));

        // Title mismatch
        ProblemDetail pdDiffTitle = ProblemDetail.forStatusAndDetail(400, "Bad Request");
        pdDiffTitle.setTitle("Different Title");
        pdDiffTitle.setProperty("key", "val");
        assertFalse(pd1.equals(pdDiffTitle));

        // Detail mismatch
        ProblemDetail pdDiffDetail = ProblemDetail.forStatusAndDetail(400, "Different Detail");
        pdDiffDetail.setProperty("key", "val");
        assertFalse(pd1.equals(pdDiffDetail));

        // Instance mismatch
        ProblemDetail pdDiffInstance = ProblemDetail.forStatusAndDetail(400, "Bad Request");
        pdDiffInstance.setInstance("/inst/1");
        pdDiffInstance.setProperty("key", "val");
        assertFalse(pd1.equals(pdDiffInstance));

        // Properties mismatch
        ProblemDetail pdDiffProps = ProblemDetail.forStatusAndDetail(400, "Bad Request");
        pdDiffProps.setProperty("key", "otherVal");
        assertFalse(pd1.equals(pdDiffProps));

        // Identical object
        ProblemDetail pdIdentical = ProblemDetail.forStatusAndDetail(400, "Bad Request");
        pdIdentical.setProperty("key", "val");
        assertTrue(pd1.equals(pdIdentical));
        assertEquals(pd1.hashCode(), pdIdentical.hashCode());
    }
}





