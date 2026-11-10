package io.github.hvmello.aienglab.shared;

/** Token accounting as reported by the provider. A field is null when the provider did not report it. */
public record TokenUsage(Integer inputTokens, Integer outputTokens, Integer totalTokens) {}
