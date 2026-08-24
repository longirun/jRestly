package ru.jrestly.fixtures;

import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestBody;

public interface DateTimeController {

    @Post(path = "/api/events")
    EventDto createEvent(@RequestBody EventDto event);
}
