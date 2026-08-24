package ru.jrestly.fixtures;

import java.time.LocalDateTime;

public record EventDto(
        Long id,
        LocalDateTime createdAt
) {}
