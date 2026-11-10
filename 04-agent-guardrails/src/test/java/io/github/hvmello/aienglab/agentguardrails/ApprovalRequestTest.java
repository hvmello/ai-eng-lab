package io.github.hvmello.aienglab.agentguardrails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ApprovalRequestTest {

    private static String summary(String text) {
        return new ApprovalRequest("write_note", 1, text).summary();
    }

    @Test
    void plainTextPassesThrough() {
        assertThat(summary("overwrite note 'todo' with 12 characters: hello")).isEqualTo("overwrite note 'todo' with 12 characters: hello");
    }

    @Test
    void ansiSequencesAreRemovedWholeNotJustTheEscape() {
        String forged = "ok\u001B[2J\u001B[31mAPPROVED\u001B[0m done";

        assertThat(summary(forged)).isEqualTo("ok APPROVED done");
    }

    @Test
    void oscAndEightBitCsiSequencesAreRemoved() {
        assertThat(summary("a\u001B]0;fake title\u0007b")).isEqualTo("a b");
        assertThat(summary("a\u001B]8;;http://evil\u001B\\link\u001B]8;;\u001B\\b")).doesNotContain("evil");
        assertThat(summary("a\u009B31mb")).isEqualTo("a b");
    }

    @Test
    void newlinesAndControlCharactersCannotStartANewPromptLine() {
        String forged = "note\n\nAllow? [y/N] y\r\u0008\u0000\u007F\u0085tail";

        String clean = summary(forged);

        assertThat(clean).doesNotContain("\n").doesNotContain("\r");
        assertThat(clean.chars()).noneMatch(Character::isISOControl);
    }

    @Test
    void invisibleFormatCharactersAreDropped() {
        // U+202E reverses the text direction, U+200B is a zero width space.
        assertThat(summary("a‮b​c")).isEqualTo("abc");
    }

    @Test
    void longSummariesAreTruncated() {
        String clean = summary("x".repeat(500));

        assertThat(clean).hasSize(ApprovalRequest.MAX_SUMMARY).endsWith("...");
    }

    @Test
    void truncationNeverSplitsASurrogatePair() {
        String clean = summary("x".repeat(ApprovalRequest.MAX_SUMMARY - 4) + "😀😀😀");

        assertThat(clean).endsWith("...");
        assertThat(Character.isHighSurrogate(clean.charAt(clean.length() - 4))).isFalse();
    }

    @Test
    void theToolNameIsSanitizedAndTheStepMustBePositive() {
        assertThat(new ApprovalRequest("save\n\u001B[1m", 1, "x").tool()).isEqualTo("save");
        assertThatThrownBy(() -> new ApprovalRequest("save", 0, "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNullSummaryBecomesEmpty() {
        assertThat(new ApprovalRequest("save", 1, null).summary()).isEmpty();
    }
}
