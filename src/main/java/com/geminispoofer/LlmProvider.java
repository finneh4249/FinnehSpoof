package com.geminispoofer;

import java.util.Locale;

public enum LlmProvider {
    GEMINI,
    OPENAI,
    OPENROUTER,
    ANTHROPIC;

    public static LlmProvider fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return GEMINI;
        }
        try {
            return LlmProvider.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return GEMINI;
        }
    }
}
