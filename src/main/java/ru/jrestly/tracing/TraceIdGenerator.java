package ru.jrestly.tracing;

/**
 * Functional interface for generating unique tracing and correlation identifiers.
 */
@FunctionalInterface
public interface TraceIdGenerator {

    /**
     * Generates a new unique identifier string.
     *
     * @return generated ID, never {@code null}
     */
    String generate();
}
