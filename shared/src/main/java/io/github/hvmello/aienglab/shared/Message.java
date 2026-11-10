package io.github.hvmello.aienglab.shared;

import java.util.List;
import java.util.Objects;

/**
 * One chat message. The role decides how the provider treats it, system prompts included.
 *
 * <p>An assistant message may carry {@code toolCalls}, and a tool message answers one call through
 * {@code toolCallId} and {@code toolName}.
 */
public record Message(Role role, String content, List<ToolCall> toolCalls, String toolCallId, String toolName) {

    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public Message {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (!toolCalls.isEmpty() && role != Role.ASSISTANT) {
            throw new IllegalArgumentException("only an assistant message can carry tool calls");
        }
        if (role == Role.TOOL) {
            if (toolCallId == null || toolCallId.isBlank() || toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("a tool message needs toolCallId and toolName");
            }
        } else if (toolCallId != null || toolName != null) {
            throw new IllegalArgumentException("toolCallId and toolName belong to tool messages only");
        }
    }

    public Message(Role role, String content) {
        this(role, content, List.of(), null, null);
    }

    public static Message system(String content) { return new Message(Role.SYSTEM, content); }

    public static Message user(String content) { return new Message(Role.USER, content); }

    public static Message assistant(String content) { return new Message(Role.ASSISTANT, content); }

    /** An assistant turn that asks for tools. The text is whatever the model said next to the calls, often empty. */
    public static Message assistantToolCalls(String text, List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            throw new IllegalArgumentException("calls must not be empty");
        }
        return new Message(Role.ASSISTANT, text == null ? "" : text, calls, null, null);
    }

    /** The result of one tool call, as JSON text, tied to the call it answers. */
    public static Message toolResult(ToolCall call, String json) {
        Objects.requireNonNull(call, "call");
        return new Message(Role.TOOL, json, List.of(), call.id(), call.name());
    }
}
