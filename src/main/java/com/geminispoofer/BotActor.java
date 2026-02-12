package com.geminispoofer;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.concurrent.ThreadLocalRandom;

public class BotActor {
    private final JavaPlugin plugin;
    private final GeminiSpoofer database;
    private final Map<String, Deque<String>> recentMessages = new ConcurrentHashMap<>();

    public BotActor(JavaPlugin plugin, GeminiSpoofer database) {
        this.plugin = plugin;
        this.database = database;
    }

    public void performChat(FakePlayer bot, String rawResponse) {
        performChat(bot, rawResponse, true);
    }

    public void performChat(FakePlayer bot, String rawResponse, boolean affectHeat) {
        if (bot == null || rawResponse == null || rawResponse.isBlank()) {
            return;
        }
        if (!database.isBotActive(bot.getName())) {
            return;
        }

        String cleanedResponse = sanitizeResponse(rawResponse);
        if (cleanedResponse.isBlank()) {
            return;
        }
        String[] parts = cleanedResponse.split("\\|", 2);
        long delay = 0L;

        for (String part : parts) {
            String message = part.trim();
            if (message.isEmpty()) {
                continue;
            }

            long scheduledDelay = delay;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!database.isBotActive(bot.getName())) {
                    return;
                }
                if (shouldSuppressForRepetition(bot.getName(), message)) {
                    return;
                }
                String adjusted = applyTypos(message);
                String formatted = QuickChatBridge.formatBotMessage(plugin, database, bot.getName(), adjusted);
                Bukkit.broadcastMessage(formatted);
                DiscordSrvBridge.sendBotChat(plugin, formatted);
                database.logMessageAsync(bot.getName(), adjusted, "bot");
                database.onBotChat(bot.getName());
                if (affectHeat) {
                    database.onBotMessage();
                }
                recordRecentMessage(bot.getName(), adjusted);
            }, scheduledDelay);

            // Realistic typing delay: base delay + (chars * ticks_per_char) + random variation
            int baseDelay = database.getConfig().getInt("chat_typing_base_delay", 40);
            int ticksPerChar = database.getConfig().getInt("chat_typing_ticks_per_char", 4);
            int variation = database.getConfig().getInt("chat_typing_variation", 20);
            
            long typingDelay = baseDelay + (message.length() * ticksPerChar);
            long randomVariation = ThreadLocalRandom.current().nextInt(-variation, variation + 1);
            delay += Math.max(20, typingDelay + randomVariation);
        }
    }

    private String applyTypos(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        if (!database.getConfig().getBoolean("typo-simulation.enabled", false)) {
            return message;
        }
        double tPrefixChance = database.getConfig().getDouble("typo-simulation.t-prefix-chance", 0.01);
        if (tPrefixChance > 0.0 && ThreadLocalRandom.current().nextDouble() < tPrefixChance) {
            return "t" + message;
        }
        double chance = database.getConfig().getDouble("typo-simulation.chance", 0.08);
        if (ThreadLocalRandom.current().nextDouble() > chance) {
            return message;
        }
        ConfigurationSection section = database.getConfig().getConfigurationSection("typo-simulation.replacements");
        if (section == null) {
            return message;
        }
        List<String> keys = new ArrayList<>(section.getKeys(false));
        if (keys.isEmpty()) {
            return message;
        }

        List<Candidate> candidates = new ArrayList<>();
        for (String key : keys) {
            Pattern pattern = Pattern.compile("\\b" + Pattern.quote(key) + "\\b", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(message);
            if (matcher.find()) {
                candidates.add(new Candidate(key, matcher.start(), matcher.end()));
            }
        }
        if (candidates.isEmpty()) {
            return message;
        }

        Candidate pick = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        List<String> replacements = section.getStringList(pick.key());
        if (replacements.isEmpty()) {
            return message;
        }
        String replacement = replacements.get(ThreadLocalRandom.current().nextInt(replacements.size()));
        return message.substring(0, pick.start()) + replacement + message.substring(pick.end());
    }

    private record Candidate(String key, int start, int end) {
    }

    private boolean shouldSuppressForRepetition(String botName, String message) {
        if (botName == null || message == null) {
            return false;
        }
        int maxHistory = database.getConfig().getInt("repetition.max-history", 5);
        double penaltyChance = database.getConfig().getDouble("repetition.penalty-chance", 0.5);
        int minWordLength = database.getConfig().getInt("repetition.min-word-length", 4);
        if (maxHistory <= 0 || penaltyChance <= 0.0) {
            return false;
        }
        Deque<String> history = recentMessages.get(botName.toLowerCase());
        if (history == null || history.isEmpty()) {
            return false;
        }
        String lower = message.toLowerCase();
        for (String prev : history) {
            for (String word : prev.split("\\s+")) {
                if (word.length() < minWordLength) {
                    continue;
                }
                if (lower.contains(word)) {
                    return ThreadLocalRandom.current().nextDouble() < penaltyChance;
                }
            }
        }
        return false;
    }

    private void recordRecentMessage(String botName, String message) {
        if (botName == null || message == null) {
            return;
        }
        int maxHistory = database.getConfig().getInt("repetition.max-history", 5);
        if (maxHistory <= 0) {
            return;
        }
        String key = botName.toLowerCase();
        Deque<String> history = recentMessages.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        history.addLast(message.toLowerCase());
        while (history.size() > maxHistory) {
            history.pollFirst();
        }
    }

    private String sanitizeResponse(String response) {
        if (response == null || response.isBlank()) {
            return "";
        }
        String normalized = response.replace("[human]", "").replace("[bot]", "").trim();
        String[] lines = normalized.split("\\r?\\n");
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (trimmed.startsWith("[human]") || trimmed.startsWith("[bot]")) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(trimmed);
        }
        return builder.toString().trim();
    }
}
