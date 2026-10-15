package ru.jrestly.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code @OnError} configuration for a controller method: tracks the mapping
 * from HTTP status codes to target error payload classes or custom exception types.
 *
 * <p>Assembled by {@code HttpTemplateBuilder} from all {@code @OnError} annotations
 * on the controller method and interface hierarchy, and consumed by
 * {@code HttpTemplate.processResponse}. Supports multi-status mappings (ADR-0028)
 * via repeatable declarations.</p>
 */
class OnErrorParser {

    private final Map<Integer, Class<?>> statusMappings = new LinkedHashMap<>();
    private List<Integer> legacyStatuses;
    private Class<?> legacyErrorClass;
    private Class<?> defaultErrorClass;

    /**
     * Creates an empty parser.
     */
    OnErrorParser() {
    }

    /**
     * Registers a status code mapping to a target error class.
     *
     * @param status the HTTP status code
     * @param errorClass the class to decode the error response body into
     */
    public void register(int status, Class<?> errorClass) {
        statusMappings.putIfAbsent(status, errorClass);
        if (legacyErrorClass == null) {
            legacyErrorClass = errorClass;
        }
    }

    /**
     * Registers a default/wildcard error class applied to any error status
     * when no specific status mapping matches.
     *
     * @param errorClass the default error class
     */
    public void registerDefault(Class<?> errorClass) {
        if (defaultErrorClass == null) {
            defaultErrorClass = errorClass;
        }
        if (legacyErrorClass == null) {
            legacyErrorClass = errorClass;
        }
    }

    /**
     * Whether the given status is declared in this parser's {@code @OnError} mappings.
     *
     * @param httpStatusCode the HTTP status code to check
     * @return {@code true} if the status is handled by this parser
     */
    public boolean canParse(int httpStatusCode) {
        if (statusMappings.containsKey(httpStatusCode)) {
            return true;
        }
        if (legacyStatuses != null && legacyStatuses.contains(httpStatusCode)) {
            return true;
        }
        return defaultErrorClass != null && httpStatusCode >= 400;
    }

    /**
     * Resolves the target error class for a specific HTTP status code.
     *
     * @param httpStatusCode the HTTP status code
     * @return the resolved error class, or {@code Void.class} if unmapped
     */
    public Class<?> getErrorClass(int httpStatusCode) {
        Class<?> clazz = statusMappings.get(httpStatusCode);
        if (clazz != null) {
            return clazz;
        }
        if (defaultErrorClass != null) {
            return defaultErrorClass;
        }
        return legacyErrorClass != null ? legacyErrorClass : Void.class;
    }

    /**
     * The primary class the error response body is decoded into.
     * Provided for backwards compatibility with earlier single-rule callers.
     *
     * @return the primary error class ({@code Void.class} by default)
     */
    public Class<?> getErrorClass() {
        if (legacyErrorClass != null) {
            return legacyErrorClass;
        }
        if (!statusMappings.isEmpty()) {
            return statusMappings.values().iterator().next();
        }
        return defaultErrorClass != null ? defaultErrorClass : Void.class;
    }

    /**
     * Sets the legacy single-rule error class.
     *
     * @param errorClass the {@code @OnError} error class
     */
    public void setErrorClass(Class<?> errorClass) {
        this.legacyErrorClass = errorClass;
        if (legacyStatuses != null) {
            for (int s : legacyStatuses) {
                statusMappings.putIfAbsent(s, errorClass);
            }
        }
    }

    /**
     * The HTTP status codes declared in {@code @OnError}.
     *
     * @return an unmodifiable list of declared status codes
     */
    public List<Integer> getStatuses() {
        if (!statusMappings.isEmpty()) {
            return Collections.unmodifiableList(new ArrayList<>(statusMappings.keySet()));
        }
        return legacyStatuses != null ? Collections.unmodifiableList(legacyStatuses) : Collections.emptyList();
    }

    /**
     * Sets the legacy HTTP status codes declared in {@code @OnError}.
     *
     * @param statuses the declared status codes
     */
    public void setStatuses(List<Integer> statuses) {
        this.legacyStatuses = statuses;
        if (statuses != null && legacyErrorClass != null) {
            for (int s : statuses) {
                statusMappings.putIfAbsent(s, legacyErrorClass);
            }
        }
    }
}
