package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OnErrorParserTest {

    record ErrorA(String a) {}
    record ErrorB(String b) {}
    record ErrorDefault(String msg) {}

    @Test
    @DisplayName("OnErrorParser multi-status registration and resolution")
    void onErrorParserMultiStatus() {
        OnErrorParser parser = new OnErrorParser();
        parser.register(400, ErrorA.class);
        parser.register(404, ErrorB.class);
        parser.registerDefault(ErrorDefault.class);

        assertTrue(parser.canParse(400));
        assertTrue(parser.canParse(404));
        assertTrue(parser.canParse(500)); // via default
        assertFalse(parser.canParse(200));

        assertEquals(ErrorA.class, parser.getErrorClass(400));
        assertEquals(ErrorB.class, parser.getErrorClass(404));
        assertEquals(ErrorDefault.class, parser.getErrorClass(500));
        assertEquals(ErrorA.class, parser.getErrorClass()); // primary legacy getter
        assertEquals(List.of(400, 404), parser.getStatuses());
    }

    @Test
    @DisplayName("OnErrorParser legacy setters compatibility")
    void onErrorParserLegacyCompatibility() {
        OnErrorParser parser = new OnErrorParser();
        parser.setStatuses(List.of(422, 429));
        parser.setErrorClass(ErrorA.class);

        assertTrue(parser.canParse(422));
        assertTrue(parser.canParse(429));
        assertFalse(parser.canParse(500));
        assertEquals(ErrorA.class, parser.getErrorClass(422));
        assertEquals(ErrorA.class, parser.getErrorClass());
    }
}
