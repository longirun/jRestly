package ru.jrestly.http;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 *         resolved charset.</li>
 * </ul>
 *
 * <p>It is stateless, like {@link RetryAfter} and {@link ProblemDetailResolver}:
 * every input arrives as an argument, so no collaborator instance is needed.
 */
final class ResponseBody {

    private ResponseBody() {
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
