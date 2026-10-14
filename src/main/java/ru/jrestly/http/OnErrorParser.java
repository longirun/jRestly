package ru.jrestly.http;

import java.util.List;

/**
 * Per-method {@code @OnError} configuration: the HTTP status codes that
 * trigger error handling and the class the error response body is decoded
 * into.
 * <p>
 * Assembled by {@code HttpTemplateBuilder} from the {@code @OnError}
 * annotation on the controller method (when the method declares it;
 * otherwise {@code null} is installed on the template) and consumed by
 * {@code HttpTemplate.processResponse}, which uses {@link #canParse(int)}
 * to classify a response as a declared {@code @OnError} status and
 * {@link #getErrorClass()} to decode its body into a
 * {@link HandledException}.
 */
public class OnErrorParser {

    private List<Integer> statuses;
    private Class<?> errorClass;

    /**
     * Creates an empty parser. Not intended for direct use: instances are
     * assembled by {@code HttpTemplateBuilder} from the {@code @OnError}
     * annotation and populated through the {@code set*} setters.
     */
    public OnErrorParser() {
    }

    /**
     * Whether the given status is declared in this parser's
     * {@code @OnError} statuses.
     *
     * @param httpStatusCode the HTTP status code to check
     * @return {@code true} if the status is declared in the {@code @OnError} statuses
     */
    public boolean canParse(int httpStatusCode) {
        return statuses.contains(httpStatusCode);
    }

    /**
     * The HTTP status codes declared in {@code @OnError}.
     *
     * @return the declared status codes
     */
    public List<Integer> getStatuses() {
        return statuses;
    }

    /**
     * Sets the HTTP status codes declared in {@code @OnError}.
     *
     * @param statuses the declared status codes
     */
    public void setStatuses(List<Integer> statuses) {
        this.statuses = statuses;
    }

    /**
     * The class the error response body is decoded into.
     *
     * @return the {@code @OnError} error class ({@code Void.class} by annotation default)
     */
    public Class<?> getErrorClass() {
        return errorClass;
    }

    /**
     * Sets the class the error response body is decoded into.
     *
     * @param errorClass the {@code @OnError} error class
     */
    public void setErrorClass(Class<?> errorClass) {
        this.errorClass = errorClass;
    }
}
