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
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import ru.jrestly.codec.PayloadCodec;

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
 *     <li>{@link #closeQuietly(InputStream, System.Logger)} — tolerant close;</li>
 *     <li>{@link #contentTypeOf(List)} — the response Content-Type the codec
 *         is selected by (ADR-0060);</li>
 *     <li>{@link #bufferedOptional(int, byte[], Type, Charset, String, java.util.function.Supplier, System.Logger)}
 *         and {@link #bufferedRaw(byte[], Type, Charset, String, java.util.function.Supplier, System.Logger)}
 *         — the two buffered decode legs of {@code processResponse}: the
 *         Optional-returning contour (ADR-0023) and the raw contour. The codec
 *         is passed as a {@code Supplier} and is only resolved on the DTO leg
 *         (exactly once per leg, ADR-0077) — on the byte[]/String/Void legs a
 *         bare template must survive with no codec at all.</li>
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

    // ADR-0097: Stream<TypedServerSentEvent<T>> returns a typed event stream
    // chained over the event stream with payload deserialized via PayloadCodec.
    static <T> Stream<TypedServerSentEvent<T>> typedEventStream(
            InputStream stream,
            Charset charset,
            PayloadCodec codec,
            Type targetType,
            System.Logger logger) {
        return eventStream(stream, charset, logger)
                .map(raw -> raw.toTyped(targetType, codec));
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

    static boolean isTypedEventStream(Type type) {
        if (type instanceof ParameterizedType pt && Stream.class.equals(pt.getRawType())) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length == 1) {
                Type element = args[0];
                if (element instanceof ParameterizedType elemPt) {
                    return TypedServerSentEvent.class.equals(elemPt.getRawType());
                }
                return TypedServerSentEvent.class.equals(element);
            }
        }
        return false;
    }

    static Type extractTypedEventType(Type type) {
        if (type instanceof ParameterizedType pt && Stream.class.equals(pt.getRawType())) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length == 1) {
                Type element = args[0];
                if (element instanceof ParameterizedType elemPt && TypedServerSentEvent.class.equals(elemPt.getRawType())) {
                    Type[] subArgs = elemPt.getActualTypeArguments();
                    if (subArgs.length == 1) {
                        return subArgs[0];
                    }
                }
            }
        }
        return Object.class;
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

    // ADR-0060: the response Content-Type the codec is selected by — null when
    // the header is absent. A null-named or null-valued header is skipped.
    static String contentTypeOf(List<Header> headers) {
        if (headers == null) {
            return null;
        }
        for (Header header : headers) {
            if (header.name() != null && header.name().equalsIgnoreCase("Content-Type")) {
                return header.value();
            }
        }
        return null;
    }

    // ADR-0066 step 5c: the buffered decode leg of processResponse — the
    // Optional-returning contour (ADR-0023). A missing resource (404/304/204)
    // or a null/empty body is Optional.empty(); byte[]/String/Void return
    // without ever touching the codec; a DTO decodes through the lazily
    // supplied response codec, which is resolved exactly once per leg and
    // shared by the log preview and the deserialize (ADR-0077) — eagerly
    // resolving it here would break a bare template whose registry has no
    // codec for this media type.
    static Object bufferedOptional(int statusCode, byte[] bytes, Type returnType, Charset payloadCharset,
                                   String contentType, Supplier<PayloadCodec> codec, System.Logger logger) {
        if (isMissingResource(statusCode) || bytes == null || bytes.length == 0) {
            if (logger.isLoggable(System.Logger.Level.INFO)) {
                logger.log(System.Logger.Level.INFO, "No response or empty/status for Optional return.");
            }
            return Optional.empty();
        }
        if (byte[].class.equals(returnType)) {
            if (logger.isLoggable(System.Logger.Level.INFO)) {
                logger.log(System.Logger.Level.INFO, "Response size: " + bytes.length + " bytes");
            }
            return Optional.of(bytes);
        }
        if (String.class.equals(returnType)) {
            String payload = new String(bytes, payloadCharset);
            if (logger.isLoggable(System.Logger.Level.INFO)) {
                logger.log(System.Logger.Level.INFO, "Response size: " + payload.length() + " bytes\n" + payload);
            }
            return Optional.of(payload);
        }
        if (Void.class.equals(returnType) || void.class.equals(returnType)) {
            return Optional.empty();
        }
        // DTO leg. The codec is resolved once for the whole leg — the
        // supplier may be a registry lookup (content-type scan + media-type
        // normalization), and resolving it per use (log preview, deserialize)
        // doubled the work (ADR-0077). The size line reports the wire byte
        // size — materializing a UTF-8 preview string just for its length
        // would decode the whole payload a second time (a log line is not
        // data, ADR-0056) — while prettyPrint receives the payload charset.
        PayloadCodec resolvedCodec = codec.get();
        if (logger.isLoggable(System.Logger.Level.INFO)) {
            logger.log(System.Logger.Level.INFO, "Response size: " + bytes.length + " bytes\n"
                    + resolvedCodec.prettyPrint(bytes, contentType, payloadCharset));
        }
        return Optional.ofNullable(resolvedCodec.deserialize(bytes, returnType, contentType));
    }

    // ADR-0066 step 5c: the buffered decode leg of processResponse — the raw
    // contour. A null/empty body is null; byte[]/String/Void return without
    // ever touching the codec; a DTO decodes through the lazily supplied
    // response codec, possibly to null (raw semantics — no Optional wrapper).
    static Object bufferedRaw(byte[] bytes, Type returnType, Charset payloadCharset,
                              String contentType, Supplier<PayloadCodec> codec, System.Logger logger) {
        if (bytes == null || bytes.length == 0) {
            if (logger.isLoggable(System.Logger.Level.INFO)) {
                logger.log(System.Logger.Level.INFO, "No response.");
            }
            return null;
        }
        if (byte[].class.equals(returnType)) {
            if (logger.isLoggable(System.Logger.Level.INFO)) {
                logger.log(System.Logger.Level.INFO, "Response size: " + bytes.length + " bytes");
            }
            return bytes;
        }
        if (String.class.equals(returnType)) {
            String payload = new String(bytes, payloadCharset);
            if (logger.isLoggable(System.Logger.Level.INFO)) {
                logger.log(System.Logger.Level.INFO, "Response size: " + payload.length() + " bytes\n" + payload);
            }
            return payload;
        }
        if (Void.class.equals(returnType) || void.class.equals(returnType)) {
            return null;
        }
        // DTO leg — the same single-resolution and wire-byte-size contract
        // as the Optional contour (ADR-0077).
        PayloadCodec resolvedCodec = codec.get();
        if (logger.isLoggable(System.Logger.Level.INFO)) {
            logger.log(System.Logger.Level.INFO, "Response size: " + bytes.length + " bytes\n"
                    + resolvedCodec.prettyPrint(bytes, contentType, payloadCharset));
        }
        return resolvedCodec.deserialize(bytes, returnType, contentType);
    }
}
