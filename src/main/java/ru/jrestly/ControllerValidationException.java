package ru.jrestly;

import java.util.List;

public class ControllerValidationException extends RuntimeException {

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
