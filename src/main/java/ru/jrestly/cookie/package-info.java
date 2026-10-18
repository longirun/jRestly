/**
 * Cookie-store persistence SPI (ADR-0039): the
 * {@link ru.jrestly.cookie.CookiePersister} contract with a file-backed
 * {@link ru.jrestly.cookie.FileCookiePersister}, the serialization boundary
 * {@link ru.jrestly.cookie.CookieCodec}, and the
 * {@link ru.jrestly.cookie.PersistentCookieStore} decorator that preloads and
 * saves a backing {@code CookieStore}.
 */
package ru.jrestly.cookie;
