package ru.jrestly.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Package-private response-body decode primitives shared by the synchronous
 * and asynchronous exchange contours (ADR-0066, step 5).
 *
 * <p>The wire response carries its payload either as an {@link InputStream}
 * (byte binding, ADR-0015 Aspect 3) or as a buffered {@code byte[]}. This
 * helper owns the text-facing decisions that sit in front of the codec:
 *
 * <ul>
 *     <li>{@link #resolveCharset(List)} — the charset declared by the
 *         {@code Content-Type} header, UTF-8 unless stated otherwise
 *         (RFC 9110 §8.3.2, ADR-0056);</li>
 *     <li>{@link #isStreamType(Type, Class)} — whether a declared return type
 *         is {@code Stream<String>} or {@code Stream<ServerSentEvent>}
 *         (ADR-0016);</li>
 *     <li>{@link #consumePayload(InputStream, Charset)} — full consumption of a
 *         streaming error payload respecting the resolved charset (ADR-0015
 *         Aspect 3);</li>
 *     <li>{@link #extractText(WireResponse)} — a buffered body as text with the
 *         resolved charset;</li>
 *     <li>{@link #isMissingResource(int)} — the shared missing-resource rule of
 *         the Optional-returning methods, ADR-0023 × ADR-0016;</li>
 *     <li>{@link #lineStream(InputStream, Charset)} and {@link #eventStream(InputStream,
 *         Charset)} — the streaming artifacts built over the wire stream
 *         (ADR-0016);</li>
 *     <li>{@link #wrapForTrailers(InputStream, java.util.function.Supplier, ResponseHeaders,
 *         ResponseHeaders)} — the terminal hook for late-arriving trailers
 *         (ADR-0059);</li>
 *     <li>{@link #closeQuietly(InputStream, System.Logger)} — tolerant close.</li>
 * </ul>
 *
 * <p>It is stateless, like {@link RetryAfter} and {@link ProblemDetailResolver}:
 * every input arrives as an argument, so no collaborator instance is needed.
 */
final class ResponseBody {

    private ResponseBody() {
    }

    // ADR-0023 × ADR-0016: the shared missing-resource rule of the
    // Optional-returning methods — a missing resource is Optional.empty()
    // with the stream closed, never a stream to drain
    static boolean isMissingResource(int statusCode) {
        return statusCode == 404 || statusCode == 304 || statusCode == 204;
    }

    // ADR-0016 Aspect 1: Stream<String> returns a line stream. The close
    // propagates back to the wire stream — the artifact owns it.
    static Stream<String> lineStream(InputStream stream, Charset charset, System.Logger logger) {
        return new BufferedReader(new InputStreamReader(stream, charset))
                .lines()
                .onClose(() -> closeQuietly(stream, logger));
    }

    // ADR-0016 Aspect 5: Stream<ServerSentEvent> returns a parsed event
    // stream chained over the line stream — closing the artifact closes the
    // line stream, which closes the wire stream.
    static Stream<ServerSentEvent> eventStream(InputStream stream, Charset charset, System.Logger logger) {
        return ServerSentEvent.parse(lineStream(stream, charset, logger));
    }

    // ADR-0059: terminal streaming hook — late-arriving trailers are
    // populated into the per-call holder and the capture-thread-local holder
    // when the stream ends. The hook is only attached when someone actually
    // receives the headers.
    static InputStream wrapForTrailers(InputStream stream,
                                       Supplier<List<Header>> trailersSupplier,
                                       ResponseHeaders perCallHolder,
                                       ResponseHeaders capturedHolder) {
        if (trailersSupplier == null || (perCallHolder == null && capturedHolder == null)) {
            return stream;
        }
        Consumer<List<Header>> trailersConsumer = trailers -> {
            if (perCallHolder != null) {
                perCallHolder.populateTrailers(trailers);
            }
            if (capturedHolder != null) {
                capturedHolder.populateTrailers(trailers);
            }
        };
        return new TrailerCompletingInputStream(stream, trailersSupplier, trailersConsumer);
    }

    // Body hygiene, not the call's fate: a close failure is logged (when a
    // logger is around), never thrown
    static void closeQuietly(InputStream stream, System.Logger logger) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException e) {
            if (logger != null) {
                logger.log(System.Logger.Level.INFO, "Failed to close the response stream", e);
            }
        }
    }

    // ADR-0015 & ADR-0016: streaming variant of the payload log line. A
    // null-named or null-valued header is skipped — findFirst on a null
    // mapped element would throw, and a malformed response must not kill
    // the INFO log of a streaming call
    static String streamingPayloadLine(String prefix, List<Header> headers) {
        String contentLength = headers.stream()
                .filter(h -> h.name() != null && h.name().equalsIgnoreCase("Content-Length") && h.value() != null)
                .map(Header::value)
                .findFirst()
                .orElse(null);
        return contentLength == null
                ? prefix + " (length unknown)"
                : prefix + ", Content-Length: " + contentLength;
    }

    // ADR-0015 Aspect 3: the error paths' consumption — the stream's
    // readAllBytes is the byte[] path's payload in stream form: an empty
    // stream is a null payload, as an empty body is. The close follows the
    // JDK contract (read to EOF, then close)
    static String consumePayload(InputStream stream, Charset charset) throws IOException {
        try (stream) {
            byte[] bytes = stream.readAllBytes();
            return bytes.length > 0 ? new String(bytes, charset) : null;
        }
    }

    static boolean isStreamType(Type type, Class<?> elementClass) {
        if (type instanceof ParameterizedType pt && Stream.class.equals(pt.getRawType())) {
            Type[] args = pt.getActualTypeArguments();
            return args.length == 1 && elementClass.equals(args[0]);
        }
        return false;
    }

    // RFC 9110 §8.3.2: the declared Content-Type charset is authoritative for
    // text payloads — every String materialization honors it and falls back to
    // UTF-8, matching the line/SSE streaming path (ADR-0016)
    static Charset resolveCharset(List<Header> headers) {
        if (headers == null) {
            return StandardCharsets.UTF_8;
        }
        for (Header header : headers) {
            if (header.name() != null && header.name().equalsIgnoreCase("Content-Type")) {
                String value = header.value();
                if (value != null) {
                    Charset cs = parseCharsetFromContentType(value);
                    if (cs != null) {
                        return cs;
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static Charset parseCharsetFromContentType(String contentType) {
        String[] parts = contentType.split(";");
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].trim();
            int eq = part.indexOf('=');
            if (eq > 0) {
                String key = part.substring(0, eq).trim();
                if ("charset".equalsIgnoreCase(key)) {
                    String charsetName = part.substring(eq + 1).trim();
                    if (charsetName.startsWith("\"") && charsetName.endsWith("\"") && charsetName.length() >= 2) {
                        charsetName = charsetName.substring(1, charsetName.length() - 1).trim();
                    }
                    if (charsetName.isEmpty()) {
                        return StandardCharsets.UTF_8;
                    }
                    try {
                        return Charset.forName(charsetName);
                    } catch (Exception e) {
                        return StandardCharsets.UTF_8;
                    }
                }
            }
        }
        return null;
    }

    static String extractText(WireResponse response) {
        if (response == null || response.body() == null) {
            return "";
        }
        return new String(response.body(), resolveCharset(response.headers()));
    }
}
