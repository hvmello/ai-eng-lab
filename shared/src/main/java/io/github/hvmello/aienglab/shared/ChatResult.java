package io.github.hvmello.aienglab.shared;

/** The model answer. {@code usage} is null when the provider reported none. */
public record ChatResult(String text, FinishReason finishReason, TokenUsage usage) {}
