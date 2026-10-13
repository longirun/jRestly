package ru.jrestly.http;

import java.io.Serial;
import java.util.Collections;
import java.util.List;

// Not part of the wire contract: details is an arbitrary user DTO, and the
// Header record is not Serializable (ADR-0013)
@SuppressWarnings("serial")
public class HandledException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 1L;

  private final Object details;
  private final int statusCode;
  private final List<Header> responseHeaders;

  public HandledException(Object details, int statusCode, List<Header> responseHeaders) {
    super("HTTP " + statusCode);

    this.details = details;
    this.statusCode = statusCode;
    this.responseHeaders = responseHeaders == null
        ? Collections.emptyList()
        : Collections.unmodifiableList(responseHeaders);
  }

  @SuppressWarnings("unchecked")
  public <T> T getDetails() {
      return (T) details;
  }

  public int getStatusCode() {
    return statusCode;
  }

  public List<Header> getResponseHeaders() {
    return responseHeaders;
  }
}
