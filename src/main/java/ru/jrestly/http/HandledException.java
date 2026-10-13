package ru.jrestly.http;

import java.io.Serial;
import java.util.Collections;
import java.util.List;

/**
 * Thrown when the response status is declared in {@code @OnError}: the body
 * (when present) is decoded into the annotation's error class and carried as
 * a typed payload via {@link #getDetails()}.
 *
 * <p>Unlike {@link UnexpectedStatusException}, the body here has a contract
 * (the declared error class), so it is decoded rather than exposed raw.
 */
// Not part of the wire contract: details is an arbitrary user DTO, and the
// Header record is not Serializable (ADR-0013)
@SuppressWarnings("serial")
public class HandledException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 1L;

  /** The decoded {@code @OnError} error object, or {@code null} when the response had no body. */
  private final Object details;

  /** The HTTP status that triggered the exception. */
  private final int statusCode;

  /** The response headers, never {@code null} (empty when the client received none). */
  private final List<Header> responseHeaders;

  /**
   * Creates the exception carrying the decoded error payload.
   *
   * @param details the decoded error object, or {@code null} when the response had no body
   * @param statusCode the HTTP status that triggered the exception
   * @param responseHeaders the response headers, or {@code null} for none
   */
  public HandledException(Object details, int statusCode, List<Header> responseHeaders) {
    super("HTTP " + statusCode);

    this.details = details;
    this.statusCode = statusCode;
    this.responseHeaders = responseHeaders == null
        ? Collections.emptyList()
        : Collections.unmodifiableList(responseHeaders);
  }

  /**
   * The decoded {@code @OnError} error object.
   *
   * @param <T> the {@code @OnError} error type declared on the method
   * @return the decoded error object, or {@code null} when the response had no body
   */
  @SuppressWarnings("unchecked")
  public <T> T getDetails() {
      return (T) details;
  }

  /**
   * The HTTP status that triggered the exception.
   *
   * @return the HTTP status
   */
  public int getStatusCode() {
    return statusCode;
  }

  /**
   * The response headers, never {@code null} (empty when the client received none).
   *
   * @return the response headers
   */
  public List<Header> getResponseHeaders() {
    return responseHeaders;
  }
}
