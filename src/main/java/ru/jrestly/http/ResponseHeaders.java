package ru.jrestly.http;

import ru.jrestly.tracing.TraceContext;

import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Container and holder for HTTP response headers and call metadata.
 *
 * <p>Preserves the transparent method contract (ADR-0004) by providing
 * call-site access to response metadata (status codes, duration, headers,
 * RFC 8288 pagination links, ETags, Location, rate limits) without requiring
 * wrapper types like {@code Response<T>}.
 *
 * <p>Usage patterns:
 * <ul>
 *   <li><b>Method parameter:</b> Pass an instance to a controller method
 *       declaring a {@code ResponseHeaders} parameter:
 *       <pre>{@code
 *       ResponseHeaders headers = new ResponseHeaders();
 *       List<Item> items = client.getItems(1, headers);
 *       Optional<String> nextLink = headers.link("next");
 *       }</pre>
 *   </li>
 *   <li><b>Capture pattern:</b> Wrap a synchronous call without changing
 *       the interface signature:
 *       <pre>{@code
 *       CapturedResponse<List<Item>> captured = ResponseHeaders.capture(() -> client.getItems(1));
 *       List<Item> items = captured.result();
 *       Optional<String> nextLink = captured.headers().link("next");
 *       }</pre>
 *   </li>
 * </ul>
 */
public class ResponseHeaders {

    static final ThreadLocal<Consumer<ResponseHeaders>> CURRENT_CAPTOR = new ThreadLocal<>();

    private volatile boolean populated;
    private volatile int statusCode;
    private volatile Duration duration = Duration.ZERO;
    private volatile List<Header> rawHeaders = List.of();
    private volatile Map<String, List<String>> headerMap = Collections.emptyMap();
    private volatile HttpClient.Version version = HttpClient.Version.HTTP_1_1;
    private volatile List<Header> trailers = List.of();
    private volatile Map<String, List<String>> trailerMap = Collections.emptyMap();
    private volatile Map<String, String> linkMap;
    private volatile List<HttpCookie> parsedCookies;
    private volatile Consumer<List<Header>> trailerForwarder;

    void attachTrailerForwarder(Consumer<List<Header>> forwarder) {
        this.trailerForwarder = forwarder;
    }

    /**
     * Creates an empty, unpopulated response headers holder.
     * Can be passed into controller methods to receive response metadata upon completion.
     */
    public ResponseHeaders() {
    }

    /**
     * Creates a pre-populated response headers container.
     *
     * @param statusCode the HTTP status code
     * @param headers the list of headers, or {@code null} for empty
     * @param duration the request duration, or {@code null} for {@link Duration#ZERO}
     */
    public ResponseHeaders(int statusCode, List<Header> headers, Duration duration) {
        this(statusCode, headers, duration, HttpClient.Version.HTTP_1_1, List.of());
    }

    /**
     * Creates a pre-populated response headers container with negotiated HTTP version.
     *
     * @param statusCode the HTTP status code
     * @param headers the list of headers, or {@code null} for empty
     * @param duration the request duration, or {@code null} for {@link Duration#ZERO}
     * @param version the negotiated HTTP protocol version, or {@code null} for {@link HttpClient.Version#HTTP_1_1}
     */
    public ResponseHeaders(int statusCode, List<Header> headers, Duration duration, HttpClient.Version version) {
        this(statusCode, headers, duration, version, List.of());
    }

    /**
     * Creates a pre-populated response headers container with negotiated HTTP version and trailers.
     *
     * @param statusCode the HTTP status code
     * @param headers the list of headers, or {@code null} for empty
     * @param duration the request duration, or {@code null} for {@link Duration#ZERO}
     * @param version the negotiated HTTP protocol version, or {@code null} for {@link HttpClient.Version#HTTP_1_1}
     * @param trailers the trailing headers, or {@code null} for empty
     */
    public ResponseHeaders(int statusCode, List<Header> headers, Duration duration, HttpClient.Version version, List<Header> trailers) {
        doPopulate(statusCode, headers, duration, version, trailers);
    }

    /**
     * Populates this holder with response data.
     *
     * @param statusCode the HTTP status code
     * @param headers the response headers
     * @param duration the call round-trip duration
     */
    public synchronized void populate(int statusCode, List<Header> headers, Duration duration) {
        doPopulate(statusCode, headers, duration, HttpClient.Version.HTTP_1_1, List.of());
    }

    /**
     * Populates this holder with response data and negotiated HTTP version.
     *
     * @param statusCode the HTTP status code
     * @param headers the response headers
     * @param duration the call round-trip duration
     * @param version the negotiated HTTP protocol version
     */
    public synchronized void populate(int statusCode, List<Header> headers, Duration duration, HttpClient.Version version) {
        doPopulate(statusCode, headers, duration, version, List.of());
    }

    /**
     * Populates this holder with response data, negotiated HTTP version, and trailers.
     *
     * @param statusCode the HTTP status code
     * @param headers the response headers
     * @param duration the call round-trip duration
     * @param version the negotiated HTTP protocol version
     * @param trailers the response trailing headers
     */
    public synchronized void populate(int statusCode, List<Header> headers, Duration duration, HttpClient.Version version, List<Header> trailers) {
        doPopulate(statusCode, headers, duration, version, trailers);
    }

    private void doPopulate(int statusCode, List<Header> headers, Duration duration, HttpClient.Version version, List<Header> trailers) {
        this.statusCode = statusCode;
        this.duration = duration != null ? duration : Duration.ZERO;
        this.rawHeaders = headers != null ? List.copyOf(headers) : List.of();
        this.version = version != null ? version : HttpClient.Version.HTTP_1_1;
        this.trailers = trailers != null ? List.copyOf(trailers) : List.of();

        Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Header h : rawHeaders) {
            if (h != null && h.name() != null) {
                map.computeIfAbsent(h.name(), k -> new ArrayList<>())
                        .add(h.value() != null ? h.value() : "");
            }
        }
        // Wrap every value list: Collections.unmodifiableMap alone would still
        // expose the backing ArrayLists, so a caller could mutate the holder's
        // state through allValues()/map().get(...). Case-insensitive lookups are
        // kept by wrapping the TreeMap in place.
        map.replaceAll((name, values) -> Collections.unmodifiableList(values));
        this.headerMap = Collections.unmodifiableMap(map);

        Map<String, List<String>> tMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Header h : this.trailers) {
            if (h != null && h.name() != null) {
                tMap.computeIfAbsent(h.name(), k -> new ArrayList<>())
                        .add(h.value() != null ? h.value() : "");
            }
        }
        tMap.replaceAll((name, values) -> Collections.unmodifiableList(values));
        this.trailerMap = Collections.unmodifiableMap(tMap);

        this.linkMap = null; // parsed lazily
        this.parsedCookies = null; // parsed lazily
        this.populated = true;
    }

    /**
     * Populates this holder with late-arriving response trailers (ADR-0059).
     *
     * <p>Thread-safe and idempotent: invoked upon stream completion (EOF or close).
     *
     * @param trailers the trailing headers to populate, or {@code null}/empty to ignore
     */
    public synchronized void populateTrailers(List<Header> trailers) {
        if (trailers == null || trailers.isEmpty()) {
            return;
        }
        this.trailers = List.copyOf(trailers);
        Map<String, List<String>> tMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Header h : this.trailers) {
            if (h != null && h.name() != null) {
                tMap.computeIfAbsent(h.name(), k -> new ArrayList<>())
                        .add(h.value() != null ? h.value() : "");
            }
        }
        tMap.replaceAll((name, values) -> Collections.unmodifiableList(values));
        this.trailerMap = Collections.unmodifiableMap(tMap);
        Consumer<List<Header>> forwarder = this.trailerForwarder;
        if (forwarder != null) {
            forwarder.accept(this.trailers);
        }
    }

    /**
     * Returns whether this holder has been populated with response data.
     *
     * @return {@code true} if populated, {@code false} otherwise
     */
    public boolean isPopulated() {
        return populated;
    }

    /**
     * Returns the HTTP status code.
     *
     * @return the status code, or {@code 0} if not yet populated
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Returns whether the HTTP status code is a 2xx success (200..299).
     *
     * @return {@code true} if status code is between 200 and 299 inclusive
     */
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * Returns whether the HTTP status code is {@code 304 Not Modified}.
     *
     * @return {@code true} if status code is 304
     */
    public boolean isNotModified() {
        return statusCode == 304;
    }

    /**
     * Returns whether the HTTP status code is {@code 404 Not Found}.
     *
     * @return {@code true} if status code is 404
     */
    public boolean isNotFound() {
        return statusCode == 404;
    }

    /**
     * Returns the total round-trip duration of the HTTP exchange.
     *
     * @return the call duration, never {@code null}
     */
    public Duration duration() {
        return duration;
    }

    /**
     * Returns the unmodifiable list of raw headers in order of arrival.
     *
     * @return list of headers, never {@code null}
     */
    public List<Header> headers() {
        return rawHeaders;
    }

    /**
     * Returns an unmodifiable case-insensitive map view of header names to their list of values.
     *
     * @return case-insensitive map of headers, never {@code null}
     */
    public Map<String, List<String>> map() {
        return headerMap;
    }

    /**
     * Returns the negotiated HTTP protocol version of the response.
     *
     * @return the negotiated HTTP version, never {@code null} (defaults to {@link HttpClient.Version#HTTP_1_1})
     */
    public HttpClient.Version version() {
        return version != null ? version : HttpClient.Version.HTTP_1_1;
    }

    /**
     * Indicates whether the response was negotiated over HTTP/2.
     *
     * @return {@code true} if negotiated over HTTP/2, {@code false} otherwise
     */
    public boolean isHttp2() {
        return version() == HttpClient.Version.HTTP_2;
    }

    /**
     * Indicates whether the response was negotiated over HTTP/1.1.
     *
     * @return {@code true} if negotiated over HTTP/1.1, {@code false} otherwise
     */
    public boolean isHttp1() {
        return version() == HttpClient.Version.HTTP_1_1;
    }

    /**
     * Returns the response trailing headers (trailers).
     *
     * @return unmodifiable list of trailer headers, never {@code null}
     */
    public List<Header> trailers() {
        return trailers;
    }

    /**
     * Finds the first value of the specified trailer name (case-insensitive).
     *
     * @param name the trailer header name
     * @return an {@link Optional} containing the first trailer value, or empty if absent
     */
    public Optional<String> firstTrailer(String name) {
        if (name == null) {
            return Optional.empty();
        }
        List<String> values = trailerMap.get(name);
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(values.get(0));
    }

    /**
     * Returns all values of the specified trailer name (case-insensitive).
     *
     * @param name the trailer header name
     * @return unmodifiable list of trailer values, never {@code null}
     */
    public List<String> trailerValues(String name) {
        if (name == null) {
            return List.of();
        }
        List<String> values = trailerMap.get(name);
        return values != null ? values : List.of();
    }

    /**
     * Returns the list of declared trailer field names from the {@code Trailer} header (RFC 7230 / RFC 9110),
     * or an empty list if no {@code Trailer} header was provided.
     *
     * @return unmodifiable list of declared trailer field names, never {@code null}
     */
    public List<String> declaredTrailers() {
        List<String> values = allValues("Trailer");
        if (values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String val : values) {
            if (val != null) {
                for (String part : val.split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        result.add(trimmed);
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * Returns the first value of the header with the given name (case-insensitive).
     *
     * @param name the header name, not {@code null}
     * @return the first value, or empty if header is not present
     */
    public Optional<String> firstValue(String name) {
        Objects.requireNonNull(name, "header name must not be null");
        List<String> values = headerMap.get(name);
        if (values != null && !values.isEmpty()) {
            return Optional.of(values.get(0));
        }
        return Optional.empty();
    }

    /**
     * Returns all values of the header with the given name (case-insensitive).
     *
     * @param name the header name, not {@code null}
     * @return unmodifiable list of header values, empty if header is not present
     */
    public List<String> allValues(String name) {
        Objects.requireNonNull(name, "header name must not be null");
        List<String> values = headerMap.get(name);
        return values != null ? values : List.of();
    }

    /**
     * Parses the first value of the specified header as a {@code long}.
     * Useful for numeric headers like {@code Content-Length}, {@code X-RateLimit-Remaining},
     * or {@code X-RateLimit-Reset}.
     *
     * @param name the header name, not {@code null}
     * @return the parsed long value, or empty if absent or not a valid long
     */
    public OptionalLong firstLongValue(String name) {
        Optional<String> value = firstValue(name);
        if (value.isPresent()) {
            try {
                return OptionalLong.of(Long.parseLong(value.get().trim()));
            } catch (NumberFormatException ignored) {
                // Return empty on invalid numeric header
            }
        }
        return OptionalLong.empty();
    }

    /**
     * Returns the value of the {@code ETag} header, if present.
     *
     * @return the ETag value, or empty if absent
     */
    public Optional<String> etag() {
        return firstValue("ETag");
    }

    /**
     * Returns the value of the {@code Location} header, if present.
     *
     * @return the Location header string, or empty if absent
     */
    public Optional<String> location() {
        return firstValue("Location");
    }

    /**
     * Parses the {@code Location} header as a {@link URI}, if present and valid.
     *
     * @return the parsed URI, or empty if absent or malformed
     */
    public Optional<URI> locationUri() {
        return location().flatMap(loc -> {
            try {
                return Optional.of(URI.create(loc.trim()));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        });
    }

    /**
     * Returns the value of the {@code Content-Type} header, if present.
     *
     * @return the Content-Type header string, or empty if absent
     */
    public Optional<String> contentType() {
        return firstValue("Content-Type");
    }

    /**
     * Returns the value of the {@code Last-Modified} header, if present.
     *
     * @return the Last-Modified header string, or empty if absent
     */
    public Optional<String> lastModified() {
        return firstValue("Last-Modified");
    }

    /**
     * Parses the {@code Last-Modified} header as an {@link Instant} using RFC 1123 format, if present and valid.
     *
     * @return the parsed Instant, or empty if absent or malformed
     */
    public Optional<Instant> lastModifiedInstant() {
        return lastModified().flatMap(lm -> {
            try {
                return Optional.of(ZonedDateTime.parse(lm.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            } catch (DateTimeParseException ignored) {
                return Optional.empty();
            }
        });
    }

    /**
     * Returns the correlation identifier from {@code X-Request-ID} or {@code X-Correlation-ID} (ADR-0042).
     *
     * @return the correlation ID, or empty if neither header is present
     */
    public Optional<String> requestId() {
        return firstValue("X-Request-ID").or(() -> firstValue("X-Correlation-ID"));
    }

    /**
     * Returns the W3C {@code traceparent} header string, if present (ADR-0042).
     *
     * @return the traceparent header value, or empty if absent
     */
    public Optional<String> traceparent() {
        return firstValue("traceparent");
    }

    /**
     * Parses the W3C {@code traceparent} (and optional {@code tracestate}) header into a {@link TraceContext} (ADR-0042).
     *
     * @return an {@link Optional} containing the parsed trace context, or empty if absent or malformed
     */
    public Optional<TraceContext> traceContext() {
        return traceparent().flatMap(tp -> TraceContext.parse(tp, firstValue("tracestate").orElse(null)));
    }

    /**
     * Returns the value of the {@code Cache-Control} header, if present.
     *
     * @return the Cache-Control header string, or empty if absent
     */
    public Optional<String> cacheControl() {
        return firstValue("Cache-Control");
    }

    /**
     * Extracts the target URI for the specified relation from RFC 8288 / RFC 5988
     * {@code Link} headers (e.g. {@code <https://api.example.com/items?page=2>; rel="next"}).
     *
     * @param rel the relation name (e.g. {@code "next"}, {@code "prev"}, {@code "first"}, {@code "last"})
     * @return the target URI string, or empty if relation is not found
     */
    public Optional<String> link(String rel) {
        if (rel == null || rel.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(links().get(rel.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * Returns all relation-to-target-URI mappings parsed from RFC 8288 {@code Link} headers.
     *
     * @return unmodifiable map of relations (lowercase) to target URIs
     */
    public Map<String, String> links() {
        Map<String, String> map = linkMap;
        if (map == null) {
            synchronized (this) {
                map = linkMap;
                if (map == null) {
                    map = parseLinks(allValues("Link"));
                    linkMap = map;
                }
            }
        }
        return map;
    }

    /**
     * Returns all cookies parsed from {@code Set-Cookie} and {@code Set-Cookie2} response headers (ADR-0032).
     *
     * @return unmodifiable list of parsed {@link HttpCookie} objects, empty if none present
     */
    public List<HttpCookie> cookies() {
        List<HttpCookie> list = parsedCookies;
        if (list == null) {
            synchronized (this) {
                list = parsedCookies;
                if (list == null) {
                    list = parseCookies();
                    parsedCookies = list;
                }
            }
        }
        return list;
    }

    private List<HttpCookie> parseCookies() {
        List<String> setCookieHeaders = allValues("Set-Cookie");
        List<String> setCookie2Headers = allValues("Set-Cookie2");
        if (setCookieHeaders.isEmpty() && setCookie2Headers.isEmpty()) {
            return List.of();
        }
        List<HttpCookie> result = new ArrayList<>();
        for (String header : setCookieHeaders) {
            if (header != null && !header.isBlank()) {
                try {
                    result.addAll(HttpCookie.parse(header));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        for (String header : setCookie2Headers) {
            if (header != null && !header.isBlank()) {
                try {
                    result.addAll(HttpCookie.parse(header));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Looks up a response cookie by its name (case-insensitive lookup, ADR-0032).
     *
     * @param name the cookie name, not {@code null}
     * @return the {@link HttpCookie}, or empty if not found
     */
    public Optional<HttpCookie> cookie(String name) {
        Objects.requireNonNull(name, "cookie name must not be null");
        return cookies().stream()
                .filter(c -> c.getName() != null && c.getName().equalsIgnoreCase(name))
                .findFirst();
    }

    /**
     * Returns the value of the response cookie with the given name (case-insensitive lookup, ADR-0032).
     *
     * @param name the cookie name, not {@code null}
     * @return the cookie value, or empty if not found
     */
    public Optional<String> cookieValue(String name) {
        return cookie(name).map(HttpCookie::getValue);
    }

    /**
     * Returns an unmodifiable map of response cookie names to their string values (case-insensitive keys, ADR-0032).
     *
     * @return unmodifiable map of cookie names to values
     */
    public Map<String, String> cookieMap() {
        Map<String, String> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (HttpCookie c : cookies()) {
            if (c.getName() != null) {
                map.put(c.getName(), c.getValue() != null ? c.getValue() : "");
            }
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * Parses the {@code Retry-After} header as a {@link Duration}, if present.
     * Supports both delta-seconds (RFC 9110 §10.2.3) and HTTP-date formats.
     *
     * @return the retry-after duration, or empty if absent or invalid
     */
    public Optional<Duration> retryAfter() {
        Optional<String> raw = firstValue("Retry-After");
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        String val = raw.get().trim();
        try {
            long seconds = Long.parseLong(val);
            return Optional.of(Duration.ofSeconds(Math.max(0, seconds)));
        } catch (NumberFormatException e) {
            try {
                Instant instant = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(val));
                long millis = Math.max(0, Duration.between(Instant.now(), instant).toMillis());
                return Optional.of(Duration.ofMillis(millis));
            } catch (DateTimeParseException ignored) {
                return Optional.empty();
            }
        }
    }

    /**
     * Captures response headers and metadata from a synchronous controller call
     * without modifying the controller interface signature.
     *
     * @param <T> the call result type
     * @param call the supplier invoking the controller method
     * @return a {@link CapturedResponse} containing the result and captured headers
     */
    public static <T> CapturedResponse<T> capture(Supplier<T> call) {
        Objects.requireNonNull(call, "call must not be null");
        ResponseHeaders holder = new ResponseHeaders();
        Consumer<ResponseHeaders> previous = CURRENT_CAPTOR.get();
        CURRENT_CAPTOR.set(captured -> {
            holder.populate(
                    captured.statusCode(),
                    captured.headers(),
                    captured.duration(),
                    captured.version(),
                    captured.trailers()
            );
            captured.attachTrailerForwarder(holder::populateTrailers);
        });
        try {
            T result = call.get();
            return new CapturedResponse<>(result, holder);
        } finally {
            if (previous != null) {
                CURRENT_CAPTOR.set(previous);
            } else {
                CURRENT_CAPTOR.remove();
            }
        }
    }

    /**
     * Captures response headers and metadata from a synchronous controller call
     * that may throw a checked exception.
     *
     * @param <T> the call result type
     * @param call the callable invoking the controller method
     * @return a {@link CapturedResponse} containing the result and captured headers
     * @throws Exception if the call throws an exception
     */
    public static <T> CapturedResponse<T> captureCallable(Callable<T> call) throws Exception {
        Objects.requireNonNull(call, "call must not be null");
        ResponseHeaders holder = new ResponseHeaders();
        Consumer<ResponseHeaders> previous = CURRENT_CAPTOR.get();
        CURRENT_CAPTOR.set(captured -> {
            holder.populate(
                    captured.statusCode(),
                    captured.headers(),
                    captured.duration(),
                    captured.version(),
                    captured.trailers()
            );
            captured.attachTrailerForwarder(holder::populateTrailers);
        });
        try {
            T result = call.call();
            return new CapturedResponse<>(result, holder);
        } finally {
            if (previous != null) {
                CURRENT_CAPTOR.set(previous);
            } else {
                CURRENT_CAPTOR.remove();
            }
        }
    }

    /**
     * RFC 8288 / RFC 5988 parser for Link headers.
     * Format: {@code <url>; rel="relation"; other="val", <url2>; rel=relation2}
     */
    private static Map<String, String> parseLinks(List<String> linkHeaders) {
        if (linkHeaders.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> relations = new LinkedHashMap<>();
        for (String header : linkHeaders) {
            if (header == null || header.isBlank()) {
                continue;
            }
            // Split entries by comma that are outside angle brackets
            List<String> entries = splitLinkHeader(header);
            for (String entry : entries) {
                parseSingleLink(entry.trim(), relations);
            }
        }
        return Collections.unmodifiableMap(relations);
    }

    private static List<String> splitLinkHeader(String header) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean insideUri = false;
        boolean insideQuote = false;

        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == '<' && !insideQuote) {
                insideUri = true;
            } else if (c == '>' && !insideQuote) {
                insideUri = false;
            } else if (c == '"' && !insideUri) {
                insideQuote = !insideQuote;
            }

            if (c == ',' && !insideUri && !insideQuote) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static void parseSingleLink(String linkPart, Map<String, String> relations) {
        int startUri = linkPart.indexOf('<');
        int endUri = linkPart.indexOf('>');
        if (startUri < 0 || endUri <= startUri) {
            return;
        }
        String uri = linkPart.substring(startUri + 1, endUri).trim();
        String params = linkPart.substring(endUri + 1);

        String[] paramParts = params.split(";");
        for (String param : paramParts) {
            int eq = param.indexOf('=');
            if (eq > 0) {
                String paramName = param.substring(0, eq).trim();
                if ("rel".equalsIgnoreCase(paramName)) {
                    String paramVal = param.substring(eq + 1).trim();
                    if (paramVal.startsWith("\"") && paramVal.endsWith("\"") && paramVal.length() >= 2) {
                        paramVal = paramVal.substring(1, paramVal.length() - 1);
                    }
                    // rel attribute may contain whitespace-separated relations (RFC 8288)
                    String[] rels = paramVal.split("\\s+");
                    for (String r : rels) {
                        if (!r.isBlank()) {
                            relations.putIfAbsent(r.toLowerCase(Locale.ROOT), uri);
                        }
                    }
                }
            }
        }
    }
}
