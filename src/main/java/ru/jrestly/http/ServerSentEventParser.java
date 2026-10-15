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
 * parsed {@link ServerSentEvent} instances lazily. A leading UTF-8 BOM
 * on the first line is stripped per the WHATWG decode step.
 *
 * <p>The dispatch algorithm follows WHATWG HTML "dispatch the event":
 * a block with no {@code data} field is not dispatched (the event type
 * buffer is reset), while the last event ID and the reconnection time
 * are stream-level state that persists across events until overwritten.
 */
final class ServerSentEventParser {

    private ServerSentEventParser() {
    }

    private static boolean isAsciiDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
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

            // Stream-level state per WHATWG: persists across events until overwritten.
            private String lastEventId = null;
            private Long retry = null;

            // Per-event state: reset on each dispatch attempt.
            private String event = null;
            private StringBuilder dataBuffer = null;
            private boolean firstLine = true;

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
                    if (firstLine) {
                        firstLine = false;
                        if (!line.isEmpty() && line.charAt(0) == '﻿') {
                            line = line.substring(1);
                        }
                    }
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
                    case "id" -> {
                        // WHATWG: an id field containing U+0000 NULL is ignored.
                        if (value.indexOf('\u0000') < 0) {
                            lastEventId = value;
                        }
                    }
                    case "retry" -> {
                        // WHATWG: only ASCII digits are accepted (no sign, no whitespace).
                        if (isAsciiDigits(value)) {
                            try {
                                retry = Long.parseLong(value);
                            } catch (NumberFormatException ignored) {
                                // Value exceeds the long range: ignore the field.
                            }
                        }
                    }
                    default -> {
                        // Unrecognized fields ignored
                    }
                }
            }

            private ServerSentEvent buildAndReset() {
                String eventType = event;
                event = null;
                if (dataBuffer == null) {
                    // WHATWG dispatch step: an empty data buffer aborts the dispatch
                    // (the event type buffer is reset, stream-level state persists).
                    return null;
                }
                StringBuilder data = dataBuffer;
                dataBuffer = null;
                return new ServerSentEvent(lastEventId, eventType, data.toString(), retry);
            }
        };

        Spliterator<ServerSentEvent> spliterator = Spliterators.spliteratorUnknownSize(
                eventIterator,
                Spliterator.ORDERED | Spliterator.NONNULL
        );

        return StreamSupport.stream(spliterator, false).onClose(lines::close);
    }
}
