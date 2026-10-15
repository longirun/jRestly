package ru.jrestly.benchmark.util;

import java.util.ResourceBundle;

/**
 * High-performance no-op logger for benchmarks to eliminate console I/O noise and string allocations.
 */
public final class NoOpLogger implements System.Logger {

    public static final NoOpLogger INSTANCE = new NoOpLogger();

    private NoOpLogger() {}

    @Override
    public String getName() {
        return "noop";
    }

    @Override
    public boolean isLoggable(Level level) {
        return false;
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {}

    @Override
    public void log(Level level, ResourceBundle bundle, String format, Object... params) {}
}
