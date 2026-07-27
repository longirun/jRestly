package ru.jrestly.http;

import org.apache.commons.lang3.tuple.Pair;

import java.util.Collections;
import java.util.List;

public class HandledException extends RuntimeException {

  private final Object details;
  private final int statusCode;
  private final List<Pair<String, String>> responseHeaders;

  public HandledException(Object details, int statusCode, List<Pair<String, String>> responseHeaders) {
    super("HTTP " + statusCode);

    this.details = details;
    this.statusCode = statusCode;
    this.responseHeaders = responseHeaders == null
        ? Collections.emptyList()
        : Collections.unmodifiableList(responseHeaders);
  }

  public <T> T getDetails() {
      //noinspection unchecked
      return (T) details;
  }

  public int getStatusCode() {
    return statusCode;
  }

  public List<Pair<String, String>> getResponseHeaders() {
    return responseHeaders;
  }
}
