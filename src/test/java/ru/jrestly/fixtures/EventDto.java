package ru.jrestly.fixtures;

import java.time.LocalDateTime;

/**
 * Event record for the datetime-serialization tests: the
 * {@code LocalDateTime} field is the payload the JSON codec must
 * round-trip via {@link DateTimeController}.
 */
public record EventDto(
        Long id,
        LocalDateTime createdAt
) {}
