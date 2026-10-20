/**
 * jRestly — a declarative HTTP client for Java: define an interface, get a
 * working HTTP client through a dynamic proxy.
 *
 * <p>This package holds the public facade and configuration surface:
 * {@link ru.jrestly.JRestlyClient}, {@link ru.jrestly.ModuleInfo} /
 * {@link ru.jrestly.SimpleModuleInfo}, {@link ru.jrestly.AuthProvider} and
 * {@link ru.jrestly.Environment}. The internal client base
 * ({@code AbstractHttpClient}) and the eager {@code ControllerValidator} /
 * {@code AnnotationChecks} validation pair live here too — they are
 * implementation details, not part of the advertised API (ADR-0048, ADR-0086).
 */
package ru.jrestly;
