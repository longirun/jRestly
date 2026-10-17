package ru.jrestly.http;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Streaming RFC 7578 multipart/form-data body publisher.
 */
public final class MultipartBodyPublisher {

    private MultipartBodyPublisher() {
    }

    /**
     * Creates an {@link HttpRequest.BodyPublisher} for the given boundary and multipart parts.
     *
     * @param boundary the multipart boundary string
     * @param parts the list of multipart parts
     * @return the composite streaming body publisher
     */
    public static HttpRequest.BodyPublisher of(String boundary, List<MultipartPart> parts) {
        byte[] crlf = "\r\n".getBytes(StandardCharsets.UTF_8);

        List<HttpRequest.BodyPublisher> sequences = new ArrayList<>();
        for (MultipartPart part : parts) {
            sequences.add(HttpRequest.BodyPublishers.ofByteArray(partHeaders(boundary, part)));
            sequences.add(contentPublisher(part));
            sequences.add(HttpRequest.BodyPublishers.ofByteArray(crlf));
        }

        byte[] closingDelimiter = ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        sequences.add(HttpRequest.BodyPublishers.ofByteArray(closingDelimiter));

        return HttpRequest.BodyPublishers.concat(sequences.toArray(HttpRequest.BodyPublisher[]::new));
    }

    // RFC 7578 part framing: dash-boundary, Content-Disposition, optional Content-Type, CRLF
    private static byte[] partHeaders(String boundary, MultipartPart part) {
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"").append(quoted(part.name())).append('"');
        if (part.filename() != null) {
            sb.append("; filename=\"").append(quoted(part.filename())).append('"');
        }
        sb.append("\r\n");

        if (part.contentType() != null) {
            sb.append("Content-Type: ").append(part.contentType()).append("\r\n");
        }

        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // RFC 2045 quoted-string: a raw backslash or quote would terminate the value and corrupt part framing
    private static String quoted(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    private static HttpRequest.BodyPublisher contentPublisher(MultipartPart part) {
        Object content = part.content();
        if (content instanceof byte[] bytes) {
            return HttpRequest.BodyPublishers.ofByteArray(bytes);
        }
        if (content instanceof Path path) {
            try {
                return HttpRequest.BodyPublishers.ofFile(path);
            } catch (FileNotFoundException e) {
                throw new RuntimeException("Cannot read multipart file: " + path, e);
            }
        }
        if (content instanceof Supplier<?> supplier) {
            // Only Supplier<? extends InputStream> is supported for stream sources
            @SuppressWarnings("unchecked")
            Supplier<? extends InputStream> source = (Supplier<? extends InputStream>) supplier;
            return part.contentLength() == null
                    ? HttpRequest.BodyPublishers.ofInputStream(source)
                    : ofStream(source, part.contentLength());
        }
        throw new IllegalStateException("Unsupported multipart content: " + content.getClass().getName());
    }

    // Streams an InputStream chunk-by-chunk with a known Content-Length
    static HttpRequest.BodyPublisher ofStream(Supplier<? extends InputStream> source, long contentLength) {
        // BodyPublishers.fromPublisher requires positive length; 0-length short-circuits to noBody
        if (contentLength == 0) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.fromPublisher(subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private volatile InputStream in;
            private final AtomicBoolean closed = new AtomicBoolean();

            // request() may arrive from different client threads; emissions must not interleave
            public synchronized void request(long n) {
                for (long i = 0; i < n && !closed.get(); i++) {
                    try {
                        if (in == null) {
                            InputStream stream = source.get();
                            if (stream == null) {
                                throw new NullPointerException("InputStream source returned null");
                            }
                            in = stream;
                        }
                        // a fresh buffer per chunk: onNext hands it over without copying
                        byte[] buffer = new byte[8192];
                        int read = in.read(buffer);
                        if (read > 0) {
                            subscriber.onNext(ByteBuffer.wrap(buffer, 0, read));
                        } else {
                            finish();
                            subscriber.onComplete();
                            return;
                        }
                    } catch (Throwable t) {
                        finish();
                        subscriber.onError(t);
                        return;
                    }
                }
            }

            public void cancel() {
                finish();
            }

            private void finish() {
                // request() and cancel() race from different threads: close must run exactly once;
                // closing from cancel() is deliberate — it interrupts a read blocked in another thread
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                InputStream stream = in;
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }), contentLength);
    }
}
