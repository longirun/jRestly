/**
 * Declarative HTTP annotations that describe a controller interface: verbs
 * ({@code @Get}, {@code @Post}, {@code @Put}, {@code @Patch}, {@code @Delete}),
 * parameter bindings ({@code @RequestParam}, {@code @PathVariable},
 * {@code @RequestHeader}, {@code @Cookie}, {@code @RequestBody},
 * {@code @MultipartFormFile}, {@code @TargetUrl}), and call-level policies
 * ({@code @Timeout}, {@code @Retry}, {@code @OnError}, {@code @WithInterceptor},
 * {@code @Compress}, {@code @Trace}, {@code @CircuitBreaker},
 * {@code @RateLimiter}, {@code @ExpectStatus}, {@code @FollowRedirects}).
 *
 * <p>Annotations are read once per method into {@code MethodMetadata} and are
 * validated eagerly when the proxy is created ({@code ControllerValidator}).
 */
package ru.jrestly.annotation;
