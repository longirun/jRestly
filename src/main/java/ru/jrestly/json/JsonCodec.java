package ru.jrestly.json;

import java.lang.reflect.Type;

public interface JsonCodec {

    <T> T deserialize(String payload, Type type);

    String serialize(Object value);

    /**
     * Best-effort: returns the payload unchanged if it cannot be re-printed.
     */
    String prettyPrint(String payload);
}
