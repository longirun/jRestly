package ru.jrestly.fixtures;

import ru.jrestly.annotation.Get;
import ru.jrestly.annotation.PathVariable;

public interface PathVariableController {

    @Get(path = "/api/items/${slug}")
    String getItemBySlug(@PathVariable(name = "slug") String slug);

    @Get(path = "/api/users/${userId}/orders/${orderId}")
    String getUserOrder(@PathVariable(name = "userId") Long userId,
                        @PathVariable(name = "orderId") String orderId);

    /**
     * Name-fallback fixture: {@code @PathVariable} without an explicit {@code name} —
     * requires {@code -parameters} on compileTestJava so the parameter name is retained.
     */
    @Get(path = "/api/sku/${sku}")
    String getBySku(@PathVariable String sku);
}
