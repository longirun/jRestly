package ru.jrestly;

import java.io.Serial;
import java.util.List;

/**
 * Thrown when a controller interface fails structural validation before the
 * proxy is created ({@link ControllerValidator#validate(Class)}): a method
 * with no HTTP verb annotation (or more than one), or an empty
 * {@code MULTIPART_FORM_DATA} / {@code APPLICATION_FORM_URLENCODED} method.
 *
 * <p>The message lists every finding; the individual problems are also
 * available via {@link #getProblems()}.
 */
// Not part of the wire contract: controller is a Class<?> (not Serializable),
// and problems are diagnostic strings (ADR-0013)
@SuppressWarnings("serial")
public class ControllerValidationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The controller interface that failed validation. */
    private final Class<?> controller;

    /** The validator's findings, one entry per problem. */
    private final List<String> problems;

    /**
     * Creates the exception from the validator's findings; the message
     * lists every problem.
     *
     * @param controller the interface that failed validation
     * @param problems the validator's findings, one entry per problem
     */
    public ControllerValidationException(Class<?> controller, List<String> problems) {
        super("Controller " + controller.getName() + " is invalid:\n- " + String.join("\n- ", problems));
        this.controller = controller;
        this.problems = List.copyOf(problems);
    }

    /**
     * The interface that failed structural validation.
     *
     * @return the failed interface
     */
    public Class<?> getController() {
        return controller;
    }

    /**
     * The validator's findings, one entry per problem.
     *
     * @return the findings
     */
    public List<String> getProblems() {
        return problems;
    }
}
