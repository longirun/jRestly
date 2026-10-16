package ru.jrestly.http;

import java.time.Duration;

/**
 * Diagnostics of a single SSE reconnect attempt (ADR-0059), passed to
 * {@link ReconnectPolicy#getOnReconnect()} right before the wait.
 *
 * @param attempt     the 1-based reconnect attempt number within this stream
 * @param lastEventId the event id replayed as {@code Last-Event-ID} in the next
 *                    request, or {@code null} when no event carried one yet
 * @param delay       the effective wait before this reconnect (server {@code retry:}
 *                    or the policy fallback, grown and jittered per policy)
 * @param cause       the failure that triggered this reconnect, or {@code null}
 *                    after a clean EOF
 */
public record ReconnectInfo(int attempt, String lastEventId, Duration delay, Throwable cause) {
}
