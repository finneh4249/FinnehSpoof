package com.geminispoofer;

import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class ConversationSeeder {
    private final GeminiSpoofer plugin;
    private final LlmClient llmClient;
    private final TopicSummarizer topicSummarizer;
    private final BotManager botManager;
    private final BotActor botActor;
    private long lastMessageAt;
    private boolean seeding = false;

    public ConversationSeeder(GeminiSpoofer plugin, LlmClient llmClient, TopicSummarizer topicSummarizer, 
                              BotManager botManager, BotActor botActor) {
        this.plugin = plugin;
        this.llmClient = llmClient;
        this.topicSummarizer = topicSummarizer;
        this.botManager = botManager;
        this.botActor = botActor;
        this.lastMessageAt = System.currentTimeMillis();
    }

    public void onMessageLogged() {
        lastMessageAt = System.currentTimeMillis();
    }

    public void tick() {
        if (!plugin.getConfig().getBoolean("conversation-seeder.enabled", true)) {
            return;
        }
        if (seeding) {
            return;
        }
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        long idleThresholdSeconds = plugin.getConfig().getLong("conversation-seeder.idle-threshold-seconds", 300);
        long idleMs = now - lastMessageAt;
        
        if (idleMs < (idleThresholdSeconds * 1000L)) {
            return;
        }

        double seedChance = plugin.getConfig().getDouble("conversation-seeder.seed-chance", 0.3);
        if (ThreadLocalRandom.current().nextDouble() > seedChance) {
            return;
        }

        seedConversationAsync();
    }

    private void seedConversationAsync() {
        seeding = true;
        
        List<FakePlayer> bots = botManager.getAllBots();
        if (bots.isEmpty()) {
            plugin.getLogger().fine("[ConversationSeeder] No bots available for seeding");
            seeding = false;
            return;
        }

        List<FakePlayer> shuffled = new ArrayList<>(bots);
        Collections.shuffle(shuffled);
        FakePlayer selectedBot = null;
        int cooldownSeconds = plugin.getConfig().getInt("bot_response_cooldown_seconds", 30);
        long now = System.currentTimeMillis();
        for (FakePlayer bot : shuffled) {
            if (bot == null || !botManager.isActive(bot.getName())) {
                continue;
            }
            if (cooldownSeconds > 0) {
                long lastChat = plugin.getBotLastChatAt(bot.getName());
                if (lastChat > 0 && (now - lastChat) < (cooldownSeconds * 1000L)) {
                    continue;
                }
            }
            selectedBot = bot;
            break;
        }
        if (selectedBot == null) {
            plugin.getLogger().fine("[ConversationSeeder] No eligible bot available for seeding (cooldown or inactive)");
            seeding = false;
            return;
        }

        List<String> exhaustedTopics = topicSummarizer.getExhaustedTopics();
        String topicsStr = exhaustedTopics.isEmpty() ? "none" : String.join(", ", exhaustedTopics);

        String systemPrompt = "You are a Minecraft player who wants to start a new conversation. ";
        if (!exhaustedTopics.isEmpty()) {
            systemPrompt += "These topics have been discussed recently and should be AVOIDED: " + topicsStr + ". ";
        }
        systemPrompt += "Generate ONE short conversation starter (2-8 words) about something different. "
            + "Sound natural, like a real player. Lowercase, casual, no punctuation at end.";

        String userPrompt = "Generate a fresh conversation starter:";

        final FakePlayer botToUse = selectedBot;

        plugin.getLogger().info("[ConversationSeeder] Seeding conversation via " + botToUse.getName() 
            + " (avoiding topics: " + topicsStr + ")");

        llmClient.generateReplyAsync(systemPrompt, userPrompt)
            .thenAccept(response -> {
                if (response == null || response.isBlank()) {
                    plugin.getLogger().warning("[ConversationSeeder] LLM returned empty seed");
                    seeding = false;
                    return;
                }

                String seed = response.trim();
                if (seed.length() > 100) {
                    seed = seed.substring(0, 100);
                }

                final String seedValue = seed;
                final double heatBoost = plugin.getConfig().getDouble("conversation-seeder.seed-heat-boost", 0.4);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!botManager.isActive(botToUse.getName())) {
                        plugin.getLogger().fine("[ConversationSeeder] Bot " + botToUse.getName() + " no longer active, skipping seed");
                        seeding = false;
                        return;
                    }

                    botActor.performChat(botToUse, seedValue, false);

                    if (heatBoost > 0) {
                        plugin.addHeat(heatBoost);
                    }

                    lastMessageAt = System.currentTimeMillis();
                    seeding = false;
                    plugin.getLogger().info("[ConversationSeeder] Seeded: \"" + seedValue + "\" (heat boost: " + heatBoost + ")");
                });
            })
            .exceptionally(throwable -> {
                plugin.getLogger().warning("[ConversationSeeder] Seed generation failed: " + throwable.getMessage());
                seeding = false;
                return null;
            });
    }
}
