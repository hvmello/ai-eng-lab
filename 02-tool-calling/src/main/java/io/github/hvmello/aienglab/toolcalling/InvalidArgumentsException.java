package io.github.hvmello.aienglab.toolcalling;

import java.util.List;

/**
 * The arguments the model sent do not fit the tool. Thrown from {@link Tool#parse} and from the compact
 * constructors of argument records. The problems are written for the model, so they never carry internals.
 */
public class InvalidArgumentsException extends RuntimeException {

    private final transient List<FieldProblem> problems;

    public InvalidArgumentsException(List<FieldProblem> problems) {
        super("invalid arguments");
        if (problems == null || problems.isEmpty()) {
            throw new IllegalArgumentException("at least one problem is needed");
        }
        this.problems = List.copyOf(problems);
    }

    public InvalidArgumentsException(String path, String problem) {
        this(List.of(new FieldProblem(path, problem)));
    }

    public List<FieldProblem> problems() {
        return problems;
    }
}
