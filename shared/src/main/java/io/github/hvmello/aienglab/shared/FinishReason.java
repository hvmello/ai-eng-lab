package io.github.hvmello.aienglab.shared;

/** Why the model stopped. {@link #LENGTH} means the output was cut off and must not be trusted as complete. */
public enum FinishReason { STOP, LENGTH, TOOL_EXECUTION, CONTENT_FILTER, OTHER }
