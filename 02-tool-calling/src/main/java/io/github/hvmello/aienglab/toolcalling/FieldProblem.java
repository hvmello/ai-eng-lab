package io.github.hvmello.aienglab.toolcalling;

/** One thing wrong with one argument: where it is and what to change. Safe to show to the model. */
public record FieldProblem(String path, String problem) {}
