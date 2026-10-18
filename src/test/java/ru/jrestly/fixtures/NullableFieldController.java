package ru.jrestly.fixtures;

import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestBody;

public interface NullableFieldController {

    @Post("/api/nullable")
    NullableFieldDto createNullable(@RequestBody NullableFieldDto item);
}
