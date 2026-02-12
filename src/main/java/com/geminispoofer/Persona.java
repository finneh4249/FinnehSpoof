package com.geminispoofer;

/**
 * Represents a bot personality loaded from personalities.yml.
 *
 * @param name         the internal key (e.g. "miner_steve")
 * @param skin         the Minecraft skin name used by Citizens
 * @param systemPrompt the system prompt sent to Gemini
 */
public record Persona(String name, String skin, String systemPrompt) {
}
