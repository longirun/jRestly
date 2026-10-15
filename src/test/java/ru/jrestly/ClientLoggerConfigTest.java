package ru.jrestly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("Client and ModuleInfo custom logger configuration (ADR-0049)")
class ClientLoggerConfigTest {

    static class RecordingLogger implements System.Logger {
        final AtomicInteger logCount = new AtomicInteger();

        @Override
        public String getName() {
            return "recording";
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
            logCount.incrementAndGet();
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            logCount.incrementAndGet();
        }
    }

    @Test
    @DisplayName("ModuleInfo and SimpleModuleInfo default getLogger returns null")
    void testDefaultLoggerIsNull() {
        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .build();

        assertNull(info.getLogger());
    }

    @Test
    @DisplayName("SimpleModuleInfo.Builder stores custom logger")
    void testSimpleModuleInfoBuilderLogger() {
        RecordingLogger customLogger = new RecordingLogger();
        SimpleModuleInfo info = SimpleModuleInfo.builder()
                .baseUrl("http://localhost:8080")
                .logger(customLogger)
                .build();

        assertSame(customLogger, info.getLogger());
    }

    @Test
    @DisplayName("JRestlyClient.Builder forwards custom logger to underlying ModuleInfo")
    void testJRestlyClientBuilderLogger() {
        RecordingLogger customLogger = new RecordingLogger();
        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://localhost:8080")
                .logger(customLogger)
                .build();

        assertSame(customLogger, client.getModuleInfo().getLogger());
        client.close();
    }
}
