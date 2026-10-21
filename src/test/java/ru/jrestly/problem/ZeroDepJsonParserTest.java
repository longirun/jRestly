package ru.jrestly.problem;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ZeroDepJsonParser Internal Syntax Coverage Tests")
class ZeroDepJsonParserTest {

    @Test
    @DisplayName("parseObject handles EOF transitions, whitespace trailing and nested escapes")
    void testParseObjectInternalBranches() {
        // 1. Whitespace only inside braces -> empty map (breaks on i >= len)
        Map<String, Object> emptyMap = ProblemDetail.ZeroDepJsonParser.parseObject("{   ");
        assertTrue(emptyMap.isEmpty());

        // 2. Trailing whitespace after comma in object leading to EOF
        Map<String, Object> trailingCommaMap = ProblemDetail.ZeroDepJsonParser.parseObject("{\"a\": 1,   ");
        assertEquals(1, trailingCommaMap.get("a"));

        // 3. Incomplete key ending immediately at EOF before colon
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.ZeroDepJsonParser.parseObject("{\"key\" "));

        // 4. Incomplete value ending immediately at EOF after colon
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.ZeroDepJsonParser.parseObject("{\"key\": "));

        // 5. Minus sign ending at EOF
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.ZeroDepJsonParser.parseObject("{\"key\": -"));

        // 6. Minus sign followed by non-digit
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.ZeroDepJsonParser.parseObject("{\"key\": -x"));

        // 7. Slash ending at EOF inside string
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.ZeroDepJsonParser.parseObject("{\"key\": \"abc\\"));

        // 8. Incomplete unicode escape sequence (less than 4 chars before EOF)
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.ZeroDepJsonParser.parseObject("{\"badU\": \"\\u12"));

        // 9. Unclosed array with trailing whitespace until EOF
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.ZeroDepJsonParser.parseObject("{\"arr\": [1, 2 "));

        // 10. Nested object containing escaped backslash inside quotes
        Map<String, Object> nested = ProblemDetail.ZeroDepJsonParser.parseObject("{\"obj\": {\"path\": \"C:\\\\dir\\\\file.txt\"}}");
        assertNotNull(nested.get("obj"));

        // 11. Exponents with capital E and explicit plus/minus
        Map<String, Object> exps = ProblemDetail.ZeroDepJsonParser.parseObject("{\"a\": 1.2E-3, \"b\": 2E+2}");
        assertEquals(0.0012, exps.get("a"));
        assertEquals(200.0, exps.get("b"));

        // 12. Invalid delimiters in object and array
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.ZeroDepJsonParser.parseObject("{\"a\": 1; \"b\": 2}"));
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.ZeroDepJsonParser.parseObject("{\"arr\": [1 "));
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.ZeroDepJsonParser.parseObject("{\"arr\": [1; 2]}"));

        // 13. Backslash outside quotes in nested bracket finding
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.ZeroDepJsonParser.parseObject("{\"obj\": {\\}}"));

        // 14. Incomplete object/array ending immediately after number/comma at EOF
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.ZeroDepJsonParser.parseObject("{\"a\": 123"));
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.ZeroDepJsonParser.parseObject("{\"arr\": [123"));
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.ZeroDepJsonParser.parseObject("{\"arr\": [1, "));
    }
}
