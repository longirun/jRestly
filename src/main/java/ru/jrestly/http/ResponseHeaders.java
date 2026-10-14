package ru.jrestly.http;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
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
    private volatile Map<String, String> linkMap;

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
        doPopulate(statusCode, headers, duration);
    }

    /**
     * Populates this holder with response data.
     *
     * @param statusCode the HTTP status code
     * @param headers the response headers
     * @param duration the call round-trip duration
     */
    public synchronized void populate(int statusCode, List<Header> headers, Duration duration) {
        doPopulate(statusCode, headers, duration);
    }

    private void doPopulate(int statusCode, List<Header> headers, Duration duration) {
        this.statusCode = statusCode;
        this.duration = duration != null ? duration : Duration.ZERO;
        this.rawHeaders = headers != null ? List.copyOf(headers) : List.of();

        Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Header h : rawHeaders) {
            if (h != null && h.name() != null) {
                map.computeIfAbsent(h.name(), k -> new ArrayList<>())
                        .add(h.value() != null ? h.value() : "");
            }
        }
        Map<String, List<String>> unmodifiableMap = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : map.entrySet()) {
            unmodifiableMap.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        this.headerMap = Collections.unmodifiableMap(map);
        this.linkMap = null; // parsed lazily
        this.populated = true;
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
        CURRENT_CAPTOR.set(captured -> holder.populate(captured.statusCode(), captured.headers(), captured.duration()));
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
        CURRENT_CAPTOR.set(captured -> holder.populate(captured.statusCode(), captured.headers(), captured.duration()));
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
