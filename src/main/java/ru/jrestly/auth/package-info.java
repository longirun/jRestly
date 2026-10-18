/**
 * Zero-dependency authorization SPI and token management (ADR-0046): the
 * {@link ru.jrestly.auth.TokenManager} contract with a single-flight default,
 * the built-in bearer/basic/API-key {@link ru.jrestly.AuthProvider}
 * implementations, and the {@link ru.jrestly.auth.Token} value type.
 *
 * <p>When a captured credential is refreshed, the request pipeline replays the
 * call once (see the {@code retryOnUnauthorized} client option).
 */
package ru.jrestly.auth;
