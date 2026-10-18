package ru.jrestly.fixtures;

import ru.jrestly.annotation.Post;
import ru.jrestly.annotation.RequestBody;

/**
 * Posts a {@link NullableFieldDto}: fixture for the JSON null-field
 * serialization tests.
 */
public interface NullableFieldController {

    @Post("/api/nullable")
    NullableFieldDto createNullable(@RequestBody NullableFieldDto item);
}
