package io.github.hvmello.aienglab.agentguardrails;

/**
 * One definition of "text the approver cannot see", shared by the write tool (reject) and the approval prompt
 * (escape). Covers control characters, format characters (Cf) and the whole Unicode Default_Ignorable_Code_Point
 * set (DerivedCoreProperties.txt), which adds what Cf misses: variation selectors, combining grapheme joiner,
 * Hangul fillers, Mongolian free variation selectors and the like.
 */
final class InvisibleText {

    /** Inclusive ranges of Default_Ignorable_Code_Point, sorted. */
    private static final int[][] DEFAULT_IGNORABLE = {
        {0x00AD, 0x00AD}, {0x034F, 0x034F}, {0x061C, 0x061C}, {0x115F, 0x1160}, {0x17B4, 0x17B5},
        {0x180B, 0x180F}, {0x200B, 0x200F}, {0x202A, 0x202E}, {0x2060, 0x206F}, {0x3164, 0x3164},
        {0xFE00, 0xFE0F}, {0xFEFF, 0xFEFF}, {0xFFA0, 0xFFA0}, {0xFFF0, 0xFFF8}, {0x1BCA0, 0x1BCA3},
        {0x1D173, 0x1D17A}, {0xE0000, 0xE0FFF}
    };

    private InvisibleText() {
    }

    static boolean isDefaultIgnorable(int cp) {
        for (int[] range : DEFAULT_IGNORABLE) {
            if (cp < range[0]) {
                return false;
            }
            if (cp <= range[1]) {
                return true;
            }
        }
        return false;
    }

    /** True for a control, a format character or a default-ignorable code point. Whitespace handling is the caller's. */
    static boolean isInvisible(int cp) {
        return Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT || isDefaultIgnorable(cp);
    }
}
