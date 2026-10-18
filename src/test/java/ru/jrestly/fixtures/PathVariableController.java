package ru.jrestly.fixtures;

import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.PathVariable;

public interface PathVariableController {

    @Get("/api/items/${slug}")
    String getItemBySlug(@PathVariable String slug);

    @Get("/api/users/${userId}/orders/${orderId}")
    String getUserOrder(@PathVariable Long userId,
                        @PathVariable String orderId);

    /**
     * Name-fallback fixture: {@code @PathVariable} without an explicit {@code name} —
     * requires {@code -parameters} on compileTestJava so the parameter name is retained.
     */
    @Get("/api/sku/${sku}")
    String getBySku(@PathVariable String sku);
}
