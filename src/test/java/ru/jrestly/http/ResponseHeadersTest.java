package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.ControllerValidator;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestParam;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@code ResponseHeaders} (ADR-0022 / ADR-0038): case-insensitive
 * header access and immutability, RFC 8288 {@code Link} and {@code Retry-After}
 * parsing, caching/conditional helpers and the synchronous capture pattern
 * (including ThreadLocal cleanup on failure). No network.
 */
class ResponseHeadersTest {

    @Test
    @DisplayName("Unpopulated holder has default empty values")
    void unpopulatedDefaults() {
        ResponseHeaders headers = new ResponseHeaders();

        assertFalse(headers.isPopulated());
        assertEquals(0, headers.statusCode());
        assertFalse(headers.isSuccess());
        assertEquals(Duration.ZERO, headers.duration());
        assertTrue(headers.headers().isEmpty());
        assertTrue(headers.map().isEmpty());
        assertTrue(headers.firstValue("Content-Type").isEmpty());
        assertTrue(headers.allValues("Content-Type").isEmpty());
        assertTrue(headers.firstLongValue("Content-Length").isEmpty());
        assertTrue(headers.etag().isEmpty());
        assertTrue(headers.location().isEmpty());
        assertTrue(headers.locationUri().isEmpty());
        assertTrue(headers.contentType().isEmpty());
        assertTrue(headers.link("next").isEmpty());
        assertTrue(headers.links().isEmpty());
        assertTrue(headers.retryAfter().isEmpty());
        assertFalse(headers.isNotModified());
        assertFalse(headers.isNotFound());
        assertTrue(headers.lastModified().isEmpty());
        assertTrue(headers.lastModifiedInstant().isEmpty());
        assertTrue(headers.cacheControl().isEmpty());
    }

    @Test
    @DisplayName("Pre-populated holder stores status, duration, and case-insensitive headers")
    void populatedData() {
        List<Header> headerList = List.of(
                new Header("Content-Type", "application/json; charset=utf-8"),
                new Header("ETag", "\"abc123xyz\""),
                new Header("Location", "https://api.example.com/items/42"),
                new Header("X-RateLimit-Remaining", "99"),
                new Header("Set-Cookie", "session=1"),
                new Header("set-cookie", "pref=dark")
        );
        ResponseHeaders headers = new ResponseHeaders(201, headerList, Duration.ofMillis(150));

        assertTrue(headers.isPopulated());
        assertEquals(201, headers.statusCode());
        assertTrue(headers.isSuccess());
        assertEquals(Duration.ofMillis(150), headers.duration());

        // Case-insensitive lookup
        assertEquals(Optional.of("application/json; charset=utf-8"), headers.contentType());
        assertEquals(Optional.of("application/json; charset=utf-8"), headers.firstValue("content-type"));
        assertEquals(Optional.of("application/json; charset=utf-8"), headers.firstValue("CONTENT-TYPE"));

        assertEquals(Optional.of("\"abc123xyz\""), headers.etag());
        assertEquals(Optional.of("https://api.example.com/items/42"), headers.location());
        assertEquals(Optional.of(URI.create("https://api.example.com/items/42")), headers.locationUri());

        // Numeric header
        assertEquals(OptionalLong.of(99L), headers.firstLongValue("X-RateLimit-Remaining"));
        assertEquals(OptionalLong.of(99L), headers.firstLongValue("x-ratelimit-remaining"));
        assertTrue(headers.firstLongValue("Non-Existent").isEmpty());
        assertTrue(headers.firstLongValue("Content-Type").isEmpty()); // non-numeric

        // Multi-value headers
        List<String> cookies = headers.allValues("Set-Cookie");
        assertEquals(2, cookies.size());
        assertEquals(List.of("session=1", "pref=dark"), cookies);
    }

    @Test
    @DisplayName("Header value lists are unmodifiable: a caller cannot corrupt the holder")
    void headerValueListsAreUnmodifiable() {
        ResponseHeaders headers = new ResponseHeaders(200, List.of(
                new Header("Set-Cookie", "a=1"),
                new Header("Set-Cookie", "b=2")), Duration.ZERO);

        List<String> viaAccessor = headers.allValues("Set-Cookie");
        assertThrows(UnsupportedOperationException.class, () -> viaAccessor.add("c=3"));

        List<String> viaMap = headers.map().get("Set-Cookie");
        assertThrows(UnsupportedOperationException.class, () -> viaMap.add("d=4"));

        // the rejected mutations must not have leaked into the holder's state
        assertEquals(List.of("a=1", "b=2"), headers.allValues("Set-Cookie"));
    }

    @Test
    @DisplayName("Trailer value lists are unmodifiable: a caller cannot corrupt the holder")
    void trailerValueListsAreUnmodifiable() {
        ResponseHeaders headers = new ResponseHeaders();
        headers.populateTrailers(List.of(
                new Header("X-Checksum", "abc"),
                new Header("X-Checksum", "def")));

        List<String> viaAccessor = headers.trailerValues("X-Checksum");
        assertThrows(UnsupportedOperationException.class, () -> viaAccessor.add("ghi"));

        assertEquals(List.of("abc", "def"), headers.trailerValues("X-Checksum"));
    }

    @Test
    @DisplayName("Parses RFC 8288 Link headers with quoted and unquoted rel attributes")
    void rfc8288LinkParser() {
        List<Header> headerList = List.of(
                new Header("Link", "<https://api.example.com/items?page=2>; rel=\"next\", <https://api.example.com/items?page=10>; rel=\"last\""),
                new Header("Link", "<https://api.example.com/items?page=1>; rel=prev; title=\"Previous Page\"")
        );
        ResponseHeaders headers = new ResponseHeaders(200, headerList, Duration.ofMillis(50));

        Map<String, String> links = headers.links();
        assertEquals(3, links.size());
        assertEquals("https://api.example.com/items?page=2", links.get("next"));
        assertEquals("https://api.example.com/items?page=10", links.get("last"));
        assertEquals("https://api.example.com/items?page=1", links.get("prev"));

        assertEquals(Optional.of("https://api.example.com/items?page=2"), headers.link("next"));
        assertEquals(Optional.of("https://api.example.com/items?page=2"), headers.link("NEXT")); // case-insensitive
        assertEquals(Optional.of("https://api.example.com/items?page=10"), headers.link("last"));
        assertEquals(Optional.of("https://api.example.com/items?page=1"), headers.link("prev"));
        assertTrue(headers.link("first").isEmpty());
        assertTrue(headers.link(null).isEmpty());
        assertTrue(headers.link("").isEmpty());
    }

    @Test
    @DisplayName("Parses RFC 8288 multi-relation rel attribute (space separated)")
    void rfc8288MultiRelation() {
        List<Header> headerList = List.of(
                new Header("Link", "<https://api.example.com/home>; rel=\"start index\"")
        );
        ResponseHeaders headers = new ResponseHeaders(200, headerList, Duration.ZERO);

        assertEquals(Optional.of("https://api.example.com/home"), headers.link("start"));
        assertEquals(Optional.of("https://api.example.com/home"), headers.link("index"));
    }

    @Test
    @DisplayName("Parses Retry-After in both delta-seconds and HTTP-date forms")
    void retryAfterParsing() {
        // Delta-seconds
        ResponseHeaders secondsHeader = new ResponseHeaders(429, List.of(new Header("Retry-After", "120")), Duration.ZERO);
        assertEquals(Optional.of(Duration.ofSeconds(120)), secondsHeader.retryAfter());

        // HTTP-date in future
        Instant future = Instant.now().plusSeconds(60);
        String dateStr = DateTimeFormatter.RFC_1123_DATE_TIME.format(future.atZone(java.time.ZoneOffset.UTC));
        ResponseHeaders dateHeader = new ResponseHeaders(503, List.of(new Header("Retry-After", dateStr)), Duration.ZERO);
        Optional<Duration> parsedDuration = dateHeader.retryAfter();
        assertTrue(parsedDuration.isPresent());
        assertTrue(parsedDuration.get().toSeconds() >= 50 && parsedDuration.get().toSeconds() <= 65);

        // Invalid Retry-After
        ResponseHeaders invalidHeader = new ResponseHeaders(429, List.of(new Header("Retry-After", "invalid-format")), Duration.ZERO);
        assertTrue(invalidHeader.retryAfter().isEmpty());
    }

    @Test
    @DisplayName("Capture pattern wraps synchronous call safely")
    void capturePattern() {
        CapturedResponse<String> captured = ResponseHeaders.capture(() -> {
            // Emulate client populating current captor
            Consumer<ResponseHeaders> captor = ResponseHeaders.CURRENT_CAPTOR.get();
            assertNotNull(captor);
            captor.accept(new ResponseHeaders(200, List.of(new Header("ETag", "\"v1\"")), Duration.ofMillis(10)));
            return "hello";
        });

        assertEquals("hello", captured.result());
        assertTrue(captured.headers().isPopulated());
        assertEquals(200, captured.headers().statusCode());
        assertEquals(Optional.of("\"v1\""), captured.headers().etag());
        assertNull(ResponseHeaders.CURRENT_CAPTOR.get(), "Captor should be cleaned up after capture");
    }

    @Test
    @DisplayName("Capture pattern cleans up ThreadLocal even when supplier throws")
    void capturePatternCleansUpOnException() {
        assertThrows(IllegalStateException.class, () -> ResponseHeaders.capture(() -> {
            throw new IllegalStateException("boom");
        }));
        assertNull(ResponseHeaders.CURRENT_CAPTOR.get(), "Captor must be cleaned up on exception");
    }

    @Test
    @DisplayName("ControllerValidator treats methods with only ResponseHeaders as lacking form params")
    void controllerValidatorFormCheck() {
        interface BadFormController {
            @Post(path = "/form", requestType = RequestType.APPLICATION_FORM_URLENCODED)
            String postForm(ResponseHeaders headers);
        }

        var problems = ControllerValidator.validate(BadFormController.class);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("APPLICATION_FORM_URLENCODED method must have at least one parameter"));
    }

    @Test
    @DisplayName("ControllerValidator accepts methods with ResponseHeaders alongside normal params")
    void controllerValidatorValidMethod() {
        interface GoodController {
            @Get(path = "/items")
            List<String> getItems(@RequestParam(name = "page") int page, ResponseHeaders headers);
        }

        var problems = ControllerValidator.validate(GoodController.class);
        assertTrue(problems.isEmpty(), "Expected no problems but got: " + problems);
    }

    @Test
    @DisplayName("Caching headers and condition helpers: 304, 404, Last-Modified RFC 1123, Cache-Control")
    void cachingHeadersAndStatusHelpers() {
        // 304 Not Modified
        ResponseHeaders notModified = new ResponseHeaders(304, List.of(
                new Header("ETag", "\"v2\""),
                new Header("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT"),
                new Header("Cache-Control", "public, max-age=3600")
        ), Duration.ofMillis(12));

        assertTrue(notModified.isNotModified());
        assertFalse(notModified.isSuccess());
        assertFalse(notModified.isNotFound());
        assertEquals(Optional.of("\"v2\""), notModified.etag());
        assertEquals(Optional.of("Wed, 21 Oct 2015 07:28:00 GMT"), notModified.lastModified());
        assertEquals(Optional.of(Instant.parse("2015-10-21T07:28:00Z")), notModified.lastModifiedInstant());
        assertEquals(Optional.of("public, max-age=3600"), notModified.cacheControl());

        // 404 Not Found
        ResponseHeaders notFound = new ResponseHeaders(404, List.of(), Duration.ofMillis(5));
        assertTrue(notFound.isNotFound());
        assertFalse(notFound.isNotModified());
        assertFalse(notFound.isSuccess());

        // Malformed Last-Modified
        ResponseHeaders badDate = new ResponseHeaders(200, List.of(
                new Header("Last-Modified", "not-a-valid-date")
        ), Duration.ofMillis(5));
        assertEquals(Optional.of("not-a-valid-date"), badDate.lastModified());
        assertTrue(badDate.lastModifiedInstant().isEmpty());
    }

    @Test
    @DisplayName("ageSeconds() correctly parses valid, invalid, negative, and absent Age headers")
    void ageSecondsParsing() {
        ResponseHeaders validAge = new ResponseHeaders(200, List.of(new Header("Age", "3600")), Duration.ZERO);
        assertEquals(OptionalLong.of(3600L), validAge.ageSeconds());
        assertEquals(Optional.of("3600"), validAge.age());

        ResponseHeaders zeroAge = new ResponseHeaders(200, List.of(new Header("Age", "0")), Duration.ZERO);
        assertEquals(OptionalLong.of(0L), zeroAge.ageSeconds());

        ResponseHeaders negativeAge = new ResponseHeaders(200, List.of(new Header("Age", "-15")), Duration.ZERO);
        assertTrue(negativeAge.ageSeconds().isEmpty());

        ResponseHeaders invalidAge = new ResponseHeaders(200, List.of(new Header("Age", "not-a-number")), Duration.ZERO);
        assertTrue(invalidAge.ageSeconds().isEmpty());

        ResponseHeaders noAge = new ResponseHeaders(200, List.of(), Duration.ZERO);
        assertTrue(noAge.ageSeconds().isEmpty());
    }

    @Test
    @DisplayName("captureCallable wraps call with checked exception, cleans ThreadLocal, and supports nested calls")
    void captureCallablePattern() throws Exception {
        // Success case with Callable
        CapturedResponse<String> res = ResponseHeaders.captureCallable(() -> {
            Consumer<ResponseHeaders> captor = ResponseHeaders.CURRENT_CAPTOR.get();
            assertNotNull(captor);
            captor.accept(new ResponseHeaders(200, List.of(new Header("ETag", "\"v100\"")), Duration.ofMillis(5)));
            return "callable-success";
        });
        assertEquals("callable-success", res.result());
        assertEquals(Optional.of("\"v100\""), res.headers().etag());
        assertNull(ResponseHeaders.CURRENT_CAPTOR.get());

        // Checked exception propagates and ThreadLocal is cleaned up
        assertThrows(java.io.IOException.class, () -> ResponseHeaders.captureCallable(() -> {
            throw new java.io.IOException("disk I/O error");
        }));
        assertNull(ResponseHeaders.CURRENT_CAPTOR.get());

        // Nested captureCallable restores previous captor correctly
        CapturedResponse<String> outer = ResponseHeaders.captureCallable(() -> {
            CapturedResponse<Integer> inner = ResponseHeaders.captureCallable(() -> {
                Consumer<ResponseHeaders> innerCaptor = ResponseHeaders.CURRENT_CAPTOR.get();
                assertNotNull(innerCaptor);
                innerCaptor.accept(new ResponseHeaders(201, List.of(new Header("X-Inner", "in")), Duration.ZERO));
                return 42;
            });
            assertEquals(42, inner.result());
            assertEquals(201, inner.headers().statusCode());
            assertEquals(Optional.of("in"), inner.headers().firstValue("X-Inner"));

            Consumer<ResponseHeaders> outerCaptor = ResponseHeaders.CURRENT_CAPTOR.get();
            assertNotNull(outerCaptor);
            outerCaptor.accept(new ResponseHeaders(200, List.of(new Header("X-Outer", "out")), Duration.ZERO));
            return "outer-success";
        });
        assertEquals("outer-success", outer.result());
        assertEquals(Optional.of("out"), outer.headers().firstValue("X-Outer"));
        assertNull(ResponseHeaders.CURRENT_CAPTOR.get());
    }

    @Test
    @DisplayName("Parses Set-Cookie and Set-Cookie2 headers and builds case-insensitive cookieMap")
    void cookiesAndCookieMap() {
        List<Header> headers = List.of(
                new Header("Set-Cookie", "session=s123; Path=/; Secure"),
                new Header("Set-Cookie2", "pref=dark; Path=/"),
                new Header("Set-Cookie", "flag=; Path=/")
        );
        ResponseHeaders resp = new ResponseHeaders(200, headers, Duration.ZERO);

        assertEquals(3, resp.cookies().size());
        assertTrue(resp.cookie("session").isPresent());
        assertTrue(resp.cookie("SESSION").isPresent()); // case-insensitive
        assertEquals(Optional.of("s123"), resp.cookieValue("session"));
        assertEquals(Optional.of("s123"), resp.cookieValue("SESSION"));

        assertTrue(resp.cookie("pref").isPresent());
        assertEquals(Optional.of("dark"), resp.cookieValue("pref"));

        assertTrue(resp.cookie("nonexistent").isEmpty());
        assertTrue(resp.cookieValue("nonexistent").isEmpty());

        Map<String, String> cookieMap = resp.cookieMap();
        assertEquals(3, cookieMap.size());
        assertEquals("s123", cookieMap.get("session"));
        assertEquals("s123", cookieMap.get("SESSION"));
        assertEquals("dark", cookieMap.get("pref"));
        assertEquals("", cookieMap.get("flag"));
    }

    @Test
    @DisplayName("populate overloads with version, duration, and trailers")
    void populateAndTrailers() {
        ResponseHeaders resp = new ResponseHeaders();
        resp.populate(200, List.of(new Header("Trailer", "X-Checksum")), Duration.ofMillis(25), java.net.http.HttpClient.Version.HTTP_2);

        assertTrue(resp.isPopulated());
        assertEquals(200, resp.statusCode());
        assertEquals(java.net.http.HttpClient.Version.HTTP_2, resp.version());
        assertTrue(resp.isHttp2());
        assertFalse(resp.isHttp1());
        assertEquals(Duration.ofMillis(25), resp.duration());

        // Trailers
        assertEquals(List.of("X-Checksum"), resp.declaredTrailers());
        resp.populateTrailers(List.of(new Header("X-Checksum", "sha256:abcd")));
        assertEquals(Optional.of("sha256:abcd"), resp.firstTrailer("X-Checksum"));
        assertEquals(Optional.of("sha256:abcd"), resp.firstTrailer("x-checksum"));
        assertEquals(List.of("sha256:abcd"), resp.trailerValues("X-Checksum"));
        assertTrue(resp.firstTrailer("X-Missing").isEmpty());
        assertTrue(resp.trailerValues("X-Missing").isEmpty());
    }
}

