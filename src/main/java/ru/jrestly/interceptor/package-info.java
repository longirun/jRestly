/**
 * Request/response interceptor SPI (ADR-0018, ADR-0029, ADR-0044): the
 * {@link ru.jrestly.interceptor.RequestInterceptor} and
 * {@link ru.jrestly.interceptor.ResponseInterceptor} contracts, their
 * {@code RequestContext}/{@code ResponseContext} views, and the built-in
 * {@link ru.jrestly.interceptor.ShortCircuitInterceptor} (ADR-0047).
 *
 * <p>Interceptors are resolved per method — global, then interface hierarchy,
 * then method — and ordered within a declaration by {@code @Order}.
 */
package ru.jrestly.interceptor;
