package ru.jrestly.fixtures;

/**
 * Error-body record (code, message) bound by the {@code @OnError}
 * (ADR-0028) fixtures: 400/404/429/500 payloads deserialize into it.
 */
public record TestErrorDto(
        String code,
        String message
) {}
