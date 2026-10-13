package ru.jrestly;

import java.io.Serial;
import java.util.List;

// Not part of the wire contract: controller is a Class<?> (not Serializable),
// and problems are diagnostic strings (ADR-0013)
@SuppressWarnings("serial")
public class ControllerValidationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Class<?> controller;
    private final List<String> problems;

    public ControllerValidationException(Class<?> controller, List<String> problems) {
        super("Controller " + controller.getName() + " is invalid:\n- " + String.join("\n- ", problems));
        this.controller = controller;
        this.problems = List.copyOf(problems);
    }

    public Class<?> getController() {
        return controller;
    }

    public List<String> getProblems() {
        return problems;
    }
}
