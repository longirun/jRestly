package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@code OnErrorParser} (ADR-0028): registration and resolution
 * of per-status {@code @OnError} payload types, the default fallback type and
 * the legacy setter API. No network.
 */
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

    @Test
    @DisplayName("OnErrorParser empty parser defaults to Void and false")
    void onErrorParserEmptyDefaults() {
        OnErrorParser parser = new OnErrorParser();
        assertFalse(parser.canParse(404));
        assertEquals(Void.class, parser.getErrorClass(404));
        assertEquals(Void.class, parser.getErrorClass());
        assertTrue(parser.getStatuses().isEmpty());

        parser.setStatuses(null);
        parser.setErrorClass(null);
        assertFalse(parser.canParse(500));
        assertTrue(parser.getStatuses().isEmpty());
    }

    @Test
    @DisplayName("OnErrorParser registerDefault only without specific statuses")
    void onErrorParserDefaultOnly() {
        OnErrorParser parser = new OnErrorParser();
        parser.registerDefault(ErrorDefault.class);

        assertTrue(parser.canParse(400));
        assertTrue(parser.canParse(500));
        assertFalse(parser.canParse(200));

        assertEquals(ErrorDefault.class, parser.getErrorClass(404));
        assertEquals(ErrorDefault.class, parser.getErrorClass(500));
        assertEquals(ErrorDefault.class, parser.getErrorClass());
        assertTrue(parser.getStatuses().isEmpty());
    }

    @Test
    @DisplayName("OnErrorParser supports setting error class before statuses")
    void onErrorParserReversedLegacySetters() {
        OnErrorParser parser = new OnErrorParser();
        parser.setErrorClass(ErrorA.class);
        parser.setStatuses(List.of(400, 401));

        assertTrue(parser.canParse(400));
        assertTrue(parser.canParse(401));
        assertEquals(ErrorA.class, parser.getErrorClass(400));
        assertEquals(ErrorA.class, parser.getErrorClass(401));
        assertEquals(List.of(400, 401), parser.getStatuses());
    }

    @Test
    @DisplayName("OnErrorParser legacy statuses without class and error class without statuses")
    void onErrorParserLegacyPartialConfiguration() {
        // Statuses only
        OnErrorParser p1 = new OnErrorParser();
        p1.setStatuses(List.of(403, 404));
        assertTrue(p1.canParse(403));
        assertEquals(Void.class, p1.getErrorClass(403));
        assertEquals(List.of(403, 404), p1.getStatuses());

        // ErrorClass only
        OnErrorParser p2 = new OnErrorParser();
        p2.setErrorClass(ErrorB.class);
        assertFalse(p2.canParse(500));
        assertEquals(ErrorB.class, p2.getErrorClass(500));
        assertEquals(ErrorB.class, p2.getErrorClass());
        assertTrue(p2.getStatuses().isEmpty());

        // StatusMappings only without legacyErrorClass for getErrorClass()
        OnErrorParser p3 = new OnErrorParser();
        p3.register(409, ErrorA.class);
        assertEquals(ErrorA.class, p3.getErrorClass());
    }
}

