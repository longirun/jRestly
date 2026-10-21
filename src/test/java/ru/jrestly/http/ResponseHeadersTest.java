package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.ControllerValidator;
import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestParam;

import java.net.URI;
import java.net.http.HttpClient;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
            @Post(value = "/form",  requestType = RequestType.APPLICATION_FORM_URLENCODED)
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
            @Get("/items")
            List<String> getItems(@RequestParam int page, ResponseHeaders headers);
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

    @Test
    @DisplayName("requestId parses X-Request-ID and falls back to X-Correlation-ID")
    void requestIdParsingAndFallback() {
        ResponseHeaders r1 = new ResponseHeaders(200, List.of(new Header("X-Request-ID", "req-1")), Duration.ZERO);
        assertEquals(Optional.of("req-1"), r1.requestId());

        ResponseHeaders r2 = new ResponseHeaders(200, List.of(new Header("X-Correlation-ID", "corr-2")), Duration.ZERO);
        assertEquals(Optional.of("corr-2"), r2.requestId());

        ResponseHeaders r3 = new ResponseHeaders(200, List.of(
                new Header("X-Request-ID", "req-3"),
                new Header("X-Correlation-ID", "corr-3")
        ), Duration.ZERO);
        assertEquals(Optional.of("req-3"), r3.requestId());

        ResponseHeaders r4 = new ResponseHeaders(200, List.of(), Duration.ZERO);
        assertTrue(r4.requestId().isEmpty());
    }

    @Test
    @DisplayName("Link header edge cases: quoted commas, multiple rel tokens, unquoted rel, blank headers, malformed brackets")
    void linkHeaderEdgeCases() {
        // Quoted comma inside rel attribute and unquoted rel
        ResponseHeaders r1 = new ResponseHeaders(200, List.of(
                new Header("Link", "<https://api.example.com/items?a=1,2>; rel=\"next,prev\""),
                new Header("Link", "<https://api.example.com/items?page=1>; rel=first"),
                new Header("Link", "<https://api.example.com/items?page=10>; rel=\"last end\""),
                new Header("Link", "   "),
                new Header("Link", null),
                new Header("Link", "malformed-without-brackets"),
                new Header("Link", ">inverted<; rel=ignored"),
                new Header("Link", "<https://api.example.com/noparam>; rel")
        ), Duration.ZERO);

        Map<String, String> links = r1.links();
        assertEquals("https://api.example.com/items?a=1,2", links.get("next,prev"));
        assertEquals("https://api.example.com/items?page=1", links.get("first"));
        assertEquals("https://api.example.com/items?page=10", links.get("last"));
        assertEquals("https://api.example.com/items?page=10", links.get("end"));
        assertNull(links.get("ignored"));
    }

    @Test
    @DisplayName("locationUri returns empty when Location header is not a valid URI")
    void locationUriInvalidReturnsEmpty() {
        ResponseHeaders r = new ResponseHeaders(302, List.of(new Header("Location", "http://invalid uri with spaces")), Duration.ZERO);
        assertTrue(r.locationUri().isEmpty());
    }

    @Test
    @DisplayName("Constructors and populate handle null collections, durations, versions, and headers with null elements")
    void doPopulateAndConstructorsNullAndEdgeCases() {
        ResponseHeaders h1 = new ResponseHeaders(200, null, null, null, null);
        assertEquals(200, h1.statusCode());
        assertEquals(Duration.ZERO, h1.duration());
        assertEquals(HttpClient.Version.HTTP_1_1, h1.version());
        assertTrue(h1.headers().isEmpty());
        assertTrue(h1.trailers().isEmpty());

        ResponseHeaders h2 = new ResponseHeaders(200, null, null);
        assertEquals(Duration.ZERO, h2.duration());
        assertTrue(h2.headers().isEmpty());

        ResponseHeaders h3 = new ResponseHeaders(200, null, null, null);
        assertEquals(HttpClient.Version.HTTP_1_1, h3.version());

        ResponseHeaders h4 = new ResponseHeaders();
        h4.populate(204, null, null);
        assertEquals(204, h4.statusCode());
        assertEquals(Duration.ZERO, h4.duration());

        h4.populate(204, null, null, null);
        assertEquals(HttpClient.Version.HTTP_1_1, h4.version());

        h4.populate(204, null, null, null, null);
        assertTrue(h4.trailers().isEmpty());

        List<Header> weirdHeaders = new ArrayList<>();
        weirdHeaders.add(new Header(null, "val-with-null-name"));
        weirdHeaders.add(new Header("X-Null-Val", null));
        weirdHeaders.add(new Header("X-Normal", "ok"));

        List<Header> weirdTrailers = new ArrayList<>();
        weirdTrailers.add(new Header(null, "trailer-val-with-null-name"));
        weirdTrailers.add(new Header("X-Trailer-Null", null));
        weirdTrailers.add(new Header("X-Trailer-Normal", "t-ok"));

        ResponseHeaders h5 = new ResponseHeaders(200, weirdHeaders, Duration.ZERO, HttpClient.Version.HTTP_1_1, weirdTrailers);
        assertEquals(Optional.of(""), h5.firstValue("X-Null-Val"));
        assertEquals(Optional.of("ok"), h5.firstValue("X-Normal"));
        assertEquals(Optional.of(""), h5.firstTrailer("X-Trailer-Null"));
        assertEquals(Optional.of("t-ok"), h5.firstTrailer("X-Trailer-Normal"));
    }

    @Test
    @DisplayName("populateTrailers handles null, empty lists, and trailers with null elements and null values")
    void populateTrailersEdgeCases() {
        ResponseHeaders resp = new ResponseHeaders();
        resp.populateTrailers(null);
        assertTrue(resp.trailers().isEmpty());

        resp.populateTrailers(List.of());
        assertTrue(resp.trailers().isEmpty());

        List<Header> trailers = new ArrayList<>();
        trailers.add(new Header(null, "val-with-null-name"));
        trailers.add(new Header("X-Checksum", null));
        trailers.add(new Header("X-Checksum", "sha256-abc"));
        resp.populateTrailers(trailers);

        assertEquals(List.of("", "sha256-abc"), resp.trailerValues("X-Checksum"));
        assertEquals(Optional.of(""), resp.firstTrailer("X-Checksum"));
    }

    @Test
    @DisplayName("declaredTrailers and firstTrailer handle null values, empty comma segments, and null names")
    void declaredTrailersAndFirstTrailerEdgeCases() {
        ResponseHeaders resp = new ResponseHeaders(200, List.of(
                new Header("Trailer", null),
                new Header("Trailer", " , X-Hash-1 , , X-Hash-2 , ")
        ), Duration.ZERO);

        assertEquals(List.of("X-Hash-1", "X-Hash-2"), resp.declaredTrailers());
        assertTrue(resp.firstTrailer(null).isEmpty());
        assertTrue(resp.trailerValues(null).isEmpty());
    }

    @Test
    @DisplayName("parseCookies handles Set-Cookie2-only responses, null/blank/malformed cookies, and null checks")
    void cookiesSetCookie2OnlyAndEdgeCases() {
        ResponseHeaders resp1 = new ResponseHeaders(200, List.of(
                new Header("Set-Cookie2", "theme=dark; Path=/")
        ), Duration.ZERO);
        assertEquals(1, resp1.cookies().size());
        assertEquals(Optional.of("dark"), resp1.cookieValue("theme"));

        List<Header> cookieHeaders = new ArrayList<>();
        cookieHeaders.add(new Header("Set-Cookie", null));
        cookieHeaders.add(new Header("Set-Cookie", "   "));
        cookieHeaders.add(new Header("Set-Cookie", "valid_c=123; Path=/"));
        cookieHeaders.add(new Header("Set-Cookie", "=malformed-no-name"));
        cookieHeaders.add(new Header("Set-Cookie2", null));
        cookieHeaders.add(new Header("Set-Cookie2", "   "));
        cookieHeaders.add(new Header("Set-Cookie2", "valid_c2=456; Path=/"));
        cookieHeaders.add(new Header("Set-Cookie2", "=malformed-no-name-2"));

        ResponseHeaders resp2 = new ResponseHeaders(200, cookieHeaders, Duration.ZERO);
        assertEquals(2, resp2.cookies().size());
        assertEquals(Optional.of("123"), resp2.cookieValue("valid_c"));
        assertEquals(Optional.of("456"), resp2.cookieValue("valid_c2"));

        assertThrows(NullPointerException.class, () -> resp2.cookie(null));
        assertThrows(NullPointerException.class, () -> resp2.cookieValue(null));
    }

    @Test
    @DisplayName("cookieMap handles cookies with empty values")
    void cookieMapWithEmptyValuedCookie() {
        ResponseHeaders resp = new ResponseHeaders(200, List.of(
                new Header("Set-Cookie", "empty_val=")
        ), Duration.ZERO);
        assertEquals(Optional.of(""), resp.cookieValue("empty_val"));
        assertEquals("", resp.cookieMap().get("empty_val"));
    }

    @Test
    @DisplayName("Nested capture(Supplier) restores previous captor correctly")
    void captureSupplierNestedRestoresPreviousCaptor() {
        CapturedResponse<String> outer = ResponseHeaders.capture(() -> {
            CapturedResponse<Integer> inner = ResponseHeaders.capture(() -> {
                Consumer<ResponseHeaders> innerCaptor = ResponseHeaders.CURRENT_CAPTOR.get();
                assertNotNull(innerCaptor);
                innerCaptor.accept(new ResponseHeaders(201, List.of(new Header("X-Inner", "1")), Duration.ZERO));
                return 42;
            });
            assertEquals(42, inner.result());
            assertEquals(Optional.of("1"), inner.headers().firstValue("X-Inner"));

            Consumer<ResponseHeaders> outerCaptor = ResponseHeaders.CURRENT_CAPTOR.get();
            assertNotNull(outerCaptor);
            outerCaptor.accept(new ResponseHeaders(200, List.of(new Header("X-Outer", "2")), Duration.ZERO));
            return "done";
        });
        assertEquals("done", outer.result());
        assertEquals(Optional.of("2"), outer.headers().firstValue("X-Outer"));
        assertNull(ResponseHeaders.CURRENT_CAPTOR.get());
    }

    @Test
    @DisplayName("Link header parsing handles quotes with angle brackets, quotes in URIs, trailing commas, and edge quotes in rel")
    void linkHeaderComplexDelimitersAndQuotes() {
        ResponseHeaders resp = new ResponseHeaders(200, List.of(
                new Header("Link", "<https://example.com/tag>; rel=\"next<tag>\", <https://example.com/title>; title=\"a>b\"; rel=\"sec\""),
                new Header("Link", "<https://example.com/quote\"uri>; rel=\"prev\""),
                new Header("Link", "<https://example.com/first>; rel=\"first\","),
                new Header("Link", "<https://example.com/unclosed>; rel=\"unclosed"),
                new Header("Link", "<https://example.com/unopened>; rel=unopened\""),
                new Header("Link", "<https://example.com/singlequote>; rel=\""),
                new Header("Link", "<https://example.com/emptyrel>; rel=\"\""),
                new Header("Link", "<https://example.com/blankrel>; rel=\"   \"")
        ), Duration.ZERO);

        Map<String, String> links = resp.links();
        assertEquals("https://example.com/tag", links.get("next<tag>"));
        assertEquals("https://example.com/title", links.get("sec"));
        assertEquals("https://example.com/quote\"uri", links.get("prev"));
        assertEquals("https://example.com/first", links.get("first"));
        assertEquals("https://example.com/unclosed", links.get("\"unclosed"));
        assertEquals("https://example.com/unopened", links.get("unopened\""));
        assertNull(links.get(""));
        assertNull(links.get("   "));
    }

    @Test
    @DisplayName("firstValue, allValues, and firstLongValue enforce non-null header name")
    void firstValueAndAllValuesExceptions() {
        ResponseHeaders resp = new ResponseHeaders();
        assertThrows(NullPointerException.class, () -> resp.firstValue(null));
        assertThrows(NullPointerException.class, () -> resp.allValues(null));
        assertThrows(NullPointerException.class, () -> resp.firstLongValue(null));
    }

    @Test
    @DisplayName("Empty trailer set shares the empty-map singleton instead of a built TreeMap (Candidate #14)")
    void emptyTrailersShareTheEmptyMapSingleton() throws Exception {
        ResponseHeaders headers = new ResponseHeaders(
                200, List.of(new Header("X-A", "1")), null, HttpClient.Version.HTTP_1_1, List.of());

        // Public contract unchanged: trailer access is empty
        assertTrue(headers.firstTrailer("anything").isEmpty());
        assertEquals(List.of(), headers.trailerValues("anything"));
        assertTrue(headers.trailers().isEmpty());

        // Structural guard: when no trailers arrived, the per-response
        // TreeMap + unmodifiable-map wrapper must not be built at all —
        // the shared Collections.emptyMap() singleton suffices.
        Field trailerMap = ResponseHeaders.class.getDeclaredField("trailerMap");
        trailerMap.setAccessible(true);
        assertSame(Collections.emptyMap(), trailerMap.get(headers),
                "empty trailer map must be the shared emptyMap() singleton");
    }

    @Test
    @DisplayName("Single-value header names are served by immutable List.of() entries (Candidate #14)")
    void singleValueHeaderListsAreImmutableSingletons() {
        ResponseHeaders headers = new ResponseHeaders(200,
                List.of(new Header("X-A", "1"), new Header("X-B", "2"), new Header("X-B", "3")), null);

        // Contract: content, case-insensitivity, and immutability are unchanged
        assertEquals(List.of("1"), headers.allValues("X-A"));
        assertThrows(UnsupportedOperationException.class, () -> headers.allValues("X-A").add("another"));
        assertEquals(List.of("2", "3"), headers.allValues("x-b"));
        assertThrows(UnsupportedOperationException.class, () -> headers.allValues("x-b").add("4"));

        // Structural guard: a single-value name must not carry an intermediate
        // ArrayList plus unmodifiable wrapper — the compact immutable list does.
        assertEquals("java.util.ImmutableCollections$List12",
                headers.allValues("X-A").getClass().getName(),
                "single-value header lists must be the compact immutable List.of entry");
    }

    @Test
    @DisplayName("Edge cases: null header names, null trailer lookups, and blank Link / Set-Cookie")
    void edgeCasesNullHeadersAndTrailers() {
        // Headers with null and null name: map.isEmpty() branch returns emptyMap()
        ResponseHeaders onlyNullName = new ResponseHeaders(200, List.of(new Header(null, "val")), Duration.ZERO);
        assertTrue(onlyNullName.map().isEmpty());

        // Null trailer query
        ResponseHeaders resp = new ResponseHeaders(200, List.of(
                new Header("Link", ""),
                new Header("Link", "   "),
                new Header("Link", "invalid-link-without-brackets; rel=next"),
                new Header("Set-Cookie", "   "),
                new Header("Set-Cookie2", "   ")
        ), Duration.ZERO);

        assertTrue(resp.firstTrailer(null).isEmpty());
        assertEquals(List.of(), resp.trailerValues(null));
        assertTrue(resp.link(null).isEmpty());
        assertTrue(resp.link("   ").isEmpty());
        assertTrue(resp.cookies().isEmpty());
    }

    @Test
    @DisplayName("links and cookies lazy evaluation double-checked locking")
    void doubleCheckedLockingConcurrentSimulation() {
        ResponseHeaders resp = new ResponseHeaders(200, List.of(
                new Header("Link", "<https://api.example.com/items?page=2>; rel=\"next\""),
                new Header("Set-Cookie", "sid=secret123; Path=/")
        ), Duration.ZERO);

        // Multiple invocations to verify cached lazy references
        assertEquals("https://api.example.com/items?page=2", resp.links().get("next"));
        assertEquals("https://api.example.com/items?page=2", resp.links().get("next"));

        assertEquals("secret123", resp.cookieValue("sid").orElseThrow());
        assertEquals("secret123", resp.cookieValue("sid").orElseThrow());
    }
}

