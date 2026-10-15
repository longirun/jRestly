package ru.jrestly.annotation;

/**
 * Formats for serializing {@link java.util.Collection} and array parameters
 * into query parameters or form fields.
 * <p>
 * Aligns with OpenAPI 3.0 parameter styles and Swagger 2.0 collection formats.
 */
public enum CollectionFormat {
    /**
     * Inherits the collection format from the enclosing method, interface hierarchy,
     * or defaults to {@link #EXPLODE}.
     */
    DEFAULT,

    /**
     * Repeated key-value pairs (OpenAPI 3.0 {@code style: form, explode: true}, Swagger {@code multi}).
     * <p>
     * Example: {@code tag=alpha&tag=beta}.
     */
    EXPLODE,

    /**
     * Comma-separated values (OpenAPI 3.0 {@code style: form, explode: false}, Swagger {@code csv}).
     * <p>
     * Example: {@code tag=alpha,beta}.
     */
    CSV,

    /**
     * Space-separated values (Swagger {@code ssv}).
     * <p>
     * Example: {@code tag=alpha%20beta}.
     */
    SSV,

    /**
     * Tab-separated values (Swagger {@code tsv}).
     * <p>
     * Example: {@code tag=alpha%09beta}.
     */
    TSV,

    /**
     * Pipe-separated values (Swagger {@code pipes}).
     * <p>
     * Example: {@code tag=alpha|beta}.
     */
    PIPES,

    /**
     * Repeated key-value pairs with bracket-suffixed parameter names
     * (PHP, Rails, Express, and Spring array style).
     * <p>
     * Example: {@code tag[]=alpha&tag[]=beta}.
     */
    BRACKETS
}
