/**
 * RFC 9457 Problem Details for HTTP APIs (ADR-0064): the
 * {@link ru.jrestly.problem.ProblemDetail} model with its zero-dependency
 * JSON reader/writer, and the {@link ru.jrestly.problem.ProblemDetailException}
 * carrier.
 *
 * <p>The pipeline detects {@code application/problem+json} (or a problem-shaped
 * body) on 4xx/5xx responses and surfaces it through
 * {@code HandledException.getProblemDetail()}.
 */
package ru.jrestly.problem;
