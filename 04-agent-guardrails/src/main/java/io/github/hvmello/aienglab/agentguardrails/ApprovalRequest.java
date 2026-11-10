package io.github.hvmello.aienglab.agentguardrails;

import java.util.regex.Pattern;

/**
 * What the approver sees. The summary is built by the application from validated arguments, and it is sanitized
 * here anyway: ANSI and control sequences are removed and the length is capped, so text planted in a note cannot
 * redraw the prompt or fake a second one.
 *
 * @param tool the tool name
 * @param step the model call that asked for it, from 1
 * @param summary one line of plain text
 * @param detail everything the action will write, sanitized and never cut: the approver must see all of it
 */
public record ApprovalRequest(String tool, int step, String summary, String detail) {

    static final int MAX_SUMMARY = 200;
    /** Cap on the detail. Larger than any tool input (a note is at most 2,000 characters), so it never cuts. */
    static final int MAX_DETAIL = 4_000;

    private static final Pattern ANSI = Pattern.compile(
            // CSI with the 7-bit or the 8-bit introducer, then OSC (ends with BEL or ESC backslash), then other ESC pairs
            "(?:\\u001B\\[|\\u009B)[0-?]*[ -/]*[@-~]"
                    + "|\\u001B\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)?"
                    + "|\\u001B[@-Z\\\\-_]");

    public ApprovalRequest {
        tool = sanitize(tool == null ? "" : tool, 64);
        if (step < 1) {
            throw new IllegalArgumentException("step starts at 1");
        }
        summary = sanitize(summary == null ? "" : summary, MAX_SUMMARY);
        detail = sanitize(detail == null ? "" : detail, MAX_DETAIL);
    }

    public ApprovalRequest(String tool, int step, String summary) {
        this(tool, step, summary, "");
    }

    /**
     * Removes ANSI sequences and turns control characters and whitespace into spaces. Invisible format and tag
     * characters are not dropped: each one is shown as a visible escape such as backslash, u and the hex code point in braces, so the approver
     * sees everything that would be stored. Walks code points, so supplementary characters are classified correctly.
     */
    static String sanitize(String text, int max) {
        StringBuilder clean = new StringBuilder();
        String withoutAnsi = ANSI.matcher(text).replaceAll(" ");
        withoutAnsi.codePoints().forEach(cp -> {
            if (Character.isISOControl(cp) || Character.isWhitespace(cp)) {
                clean.append(' ');
            } else if (Character.getType(cp) == Character.FORMAT || cp >= 0xE0000 && cp <= 0xE007F) {
                clean.append('\\').append("u{").append(Integer.toHexString(cp).toUpperCase(java.util.Locale.ROOT)).append('}');
            } else {
                clean.appendCodePoint(cp);
            }
        });
        String line = clean.toString().replaceAll(" {2,}", " ").strip();
        if (line.length() <= max) {
            return line;
        }
        int end = max - 3;
        if (Character.isHighSurrogate(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end) + "...";
    }
}
