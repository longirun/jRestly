package ru.jrestly.fixtures;

import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestBody;

/**
 * Posts an {@link EventDto}: fixture for the JSON datetime
 * (de)serialization tests ({@code LocalDateTime} round-trip).
 */
public interface DateTimeController {

    @Post("/api/events")
    EventDto createEvent(@RequestBody EventDto event);
}
