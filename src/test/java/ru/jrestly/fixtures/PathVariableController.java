package ru.jrestly.fixtures;

import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.PathVariable;

/**
 * Path-variable fixture: single- and multi-segment {@code {placeholder}}
 * templates (ADR-0025). {@code getBySku} pins the name-fallback contract —
 * a bare {@code @PathVariable} without an explicit {@code name} — and needs
 * {@code -parameters} on compileTestJava (ADR-0017, Amendment #212).
 */
public interface PathVariableController {

    @Get("/api/items/{slug}")
    String getItemBySlug(@PathVariable String slug);

    @Get("/api/users/{userId}/orders/{orderId}")
    String getUserOrder(@PathVariable Long userId,
                        @PathVariable String orderId);

    /**
     * Name-fallback fixture: {@code @PathVariable} without an explicit {@code name} —
     * requires {@code -parameters} on compileTestJava so the parameter name is retained.
     */
    @Get("/api/sku/{sku}")
    String getBySku(@PathVariable String sku);
}
