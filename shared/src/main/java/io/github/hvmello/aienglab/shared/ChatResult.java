package io.github.hvmello.aienglab.shared;

import java.util.List;

/**
 * The model answer. {@code usage} is null when the provider reported none. {@code toolCalls} is empty unless the
 * model asked for tools.
 */
public record ChatResult(String text, FinishReason finishReason, TokenUsage usage, List<ToolCall> toolCalls) {

    public ChatResult {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public ChatResult(String text, FinishReason finishReason, TokenUsage usage) {
        this(text, finishReason, usage, List.of());
    }
}
