package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.json.JacksonCodec;

import static org.junit.jupiter.api.Assertions.*;

class JacksonCodecPrettyPrintTest {

    @Test
    @DisplayName("prettyPrint formats valid JSON with newlines and indentation; invalid input is returned as is")
    void prettyPrintFormatsValidJsonAndReturnsInvalidInputAsIs() {
        JacksonCodec codec = JacksonCodec.defaults();

        String pretty = codec.prettyPrint("{\"name\":\"value\",\"nested\":{\"a\":1}}");
        assertTrue(pretty.contains("\n"), "pretty-printed JSON must contain newlines");
        assertTrue(pretty.contains("  \"name\""), "pretty-printed JSON must be indented");
        assertNotEquals("{\"name\":\"value\",\"nested\":{\"a\":1}}", pretty,
                "pretty-printed JSON must differ from the compact input");

        String invalid = "not a json {{{";
        assertEquals(invalid, codec.prettyPrint(invalid), "invalid JSON must be returned unchanged");
    }
}
