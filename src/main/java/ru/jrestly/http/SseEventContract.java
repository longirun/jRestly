package ru.jrestly.http;

/**
 * Package-private contract defining the common metadata components shared by raw
 * and typed Server-Sent Events for reconnection tracking and watchdog supervision.
 */
interface SseEventContract {

    /**
     * The last event identifier (from the {@code id:} field), or {@code null}.
     *
     * @return the event id
     */
    String id();

    /**
     * The effective reconnection time in milliseconds, or {@code null}.
     *
     * @return reconnection delay in ms
     */
    Long retry();

    /**
     * Whether this event represents a terminal stream marker (e.g. {@code [DONE]}).
     *
     * @return {@code true} if terminal
     */
    boolean isDone();
}
