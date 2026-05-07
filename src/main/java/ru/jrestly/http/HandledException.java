package ru.jrestly.http;

public class HandledException extends RuntimeException {

  private final Object details;

  public HandledException(Object details) {
    this.details = details;
  }

  public <T> T getDetails() {
    return (T) details;
  }
}
