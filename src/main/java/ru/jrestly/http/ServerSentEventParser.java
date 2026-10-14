package ru.jrestly.http;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Streaming parser for WHATWG Server-Sent Events (ADR-0016).
 *
 * <p>Consumes lines on-demand from an upstream line stream and yields
 * parsed {@link ServerSentEvent} instances lazily.
 */
final class ServerSentEventParser {

    private ServerSentEventParser() {
    }

    /**
     * Parses the given stream of raw text lines into a stream of {@link ServerSentEvent}s.
     *
     * @param lines the input line stream (without newline terminators)
     * @return a lazy stream of parsed events
     */
    static Stream<ServerSentEvent> parse(Stream<String> lines) {
        Objects.requireNonNull(lines, "lines must not be null");
        Iterator<String> lineIterator = lines.iterator();

        Iterator<ServerSentEvent> eventIterator = new Iterator<>() {
            private ServerSentEvent nextEvent = null;
            private boolean finished = false;

            private String id = null;
            private String event = null;
            private StringBuilder dataBuffer = null;
            private Long retry = null;

            @Override
            public boolean hasNext() {
                if (nextEvent != null) {
                    return true;
                }
                if (finished) {
                    return false;
                }
                nextEvent = advance();
                if (nextEvent == null) {
                    finished = true;
                    return false;
                }
                return true;
            }

            @Override
            public ServerSentEvent next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                ServerSentEvent current = nextEvent;
                nextEvent = null;
                return current;
            }

            private ServerSentEvent advance() {
                while (lineIterator.hasNext()) {
                    String line = lineIterator.next();
                    if (line.isEmpty()) {
                        ServerSentEvent dispatched = buildAndReset();
                        if (dispatched != null) {
                            return dispatched;
                        }
                        continue;
                    }

                    if (line.startsWith(":")) {
                        continue;
                    }

                    int colonIndex = line.indexOf(':');
                    String field;
                    String value;
                    if (colonIndex != -1) {
                        field = line.substring(0, colonIndex);
                        value = line.substring(colonIndex + 1);
                        if (value.startsWith(" ")) {
                            value = value.substring(1);
                        }
                    } else {
                        field = line;
                        value = "";
                    }

                    processField(field, value);
                }

                return buildAndReset();
            }

            private void processField(String field, String value) {
                switch (field) {
                    case "data" -> {
                        if (dataBuffer == null) {
                            dataBuffer = new StringBuilder(value);
                        } else {
                            dataBuffer.append('\n').append(value);
                        }
                    }
                    case "event" -> event = value;
                    case "id" -> id = value;
                    case "retry" -> {
                        try {
                            long parsed = Long.parseLong(value);
                            if (parsed >= 0) {
                                retry = parsed;
                            }
                        } catch (NumberFormatException ignored) {
                            // Non-numeric retry ignored per WHATWG SSE
                        }
                    }
                    default -> {
                        // Unrecognized fields ignored
                    }
                }
            }

            private ServerSentEvent buildAndReset() {
                if (dataBuffer == null && event == null && id == null && retry == null) {
                    return null;
                }
                String data = dataBuffer != null ? dataBuffer.toString() : null;
                ServerSentEvent sse = new ServerSentEvent(id, event, data, retry);
                id = null;
                event = null;
                dataBuffer = null;
                retry = null;
                return sse;
            }
        };

        Spliterator<ServerSentEvent> spliterator = Spliterators.spliteratorUnknownSize(
                eventIterator,
                Spliterator.ORDERED | Spliterator.NONNULL
        );

        return StreamSupport.stream(spliterator, false).onClose(lines::close);
    }
}
