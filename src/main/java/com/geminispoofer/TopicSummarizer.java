package com.geminispoofer;

import org.bukkit.Bukkit;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class TopicSummarizer {
    private final GeminiSpoofer plugin;
    private final LlmClient llmClient;
    private List<String> exhaustedTopics = new ArrayList<>();
    private long lastSummarizedAt = 0;
    private int messagesSinceLastSummary = 0;
    private boolean summarizing = false;

    public TopicSummarizer(GeminiSpoofer plugin, LlmClient llmClient) {
        this.plugin = plugin;
        this.llmClient = llmClient;
    }

    public void onMessageLogged() {
        messagesSinceLastSummary++;
        if (shouldSummarize() && !summarizing) {
            summarizeAsync();
        }
    }

    private boolean shouldSummarize() {
        if (!plugin.getConfig().getBoolean("conversation-seeder.enabled", true)) {
            return false;
        }
        long now = System.currentTimeMillis();
        int messageThreshold = plugin.getConfig().getInt("conversation-seeder.summarize-every-messages", 50);
        int minutesThreshold = plugin.getConfig().getInt("conversation-seeder.summarize-every-minutes", 30);
        long timeThresholdMs = minutesThreshold * 60L * 1000L;
        
        boolean timeExceeded = (now - lastSummarizedAt) > timeThresholdMs;
        boolean messageExceeded = messagesSinceLastSummary >= messageThreshold;
        
        return timeExceeded || messageExceeded;
    }

    private void summarizeAsync() {
        summarizing = true;
        int contextLines = plugin.getConfig().getInt("conversation-seeder.summary-context-lines", 50);
        
        plugin.getRecentContextAsync(contextLines, 0L).thenAccept(context -> {
            if (context == null || context.isBlank()) {
                plugin.getLogger().fine("[TopicSummarizer] No context to summarize, skipping");
                summarizing = false;
                return;
            }

            String systemPrompt = "Analyze this Minecraft chat log. Return ONLY a JSON array of strings representing the main topics discussed. "
                + "Maximum 10 topics. Single words or short phrases only. "
                + "Example: [\"sea lanterns\", \"diamonds\", \"elytra\", \"nether portal\"]. "
                + "If no clear topics, return empty array [].";
            
            String userPrompt = "Recent chat:\n" + context + "\n\nReturn JSON array of topics:";

            llmClient.generateReplyAsync(systemPrompt, userPrompt)
                .thenAccept(response -> {
                    if (response == null || response.isBlank()) {
                        plugin.getLogger().warning("[TopicSummarizer] LLM returned empty response");
                        summarizing = false;
                        return;
                    }

                    try {
                        Gson gson = new Gson();
                        JsonArray jsonArray = gson.fromJson(response.trim(), JsonArray.class);
                        List<String> topics = new ArrayList<>();
                        int maxTopics = plugin.getConfig().getInt("conversation-seeder.max-exhausted-topics", 10);
                        
                        for (JsonElement element : jsonArray) {
                            if (topics.size() >= maxTopics) break;
                            String topic = element.getAsString().trim().toLowerCase();
                            if (!topic.isEmpty()) {
                                topics.add(topic);
                            }
                        }

                        Bukkit.getScheduler().runTask(plugin, () -> {
                            exhaustedTopics = topics;
                            lastSummarizedAt = System.currentTimeMillis();
                            messagesSinceLastSummary = 0;
                            summarizing = false;
                            plugin.getLogger().info("[TopicSummarizer] Updated exhausted topics: " + topics);
                        });
                    } catch (Exception e) {
                        plugin.getLogger().warning("[TopicSummarizer] Failed to parse topic JSON: " + e.getMessage());
                        plugin.getLogger().warning("[TopicSummarizer] Raw response: " + response);
                        summarizing = false;
                    }
                })
                .exceptionally(throwable -> {
                    plugin.getLogger().warning("[TopicSummarizer] LLM call failed: " + throwable.getMessage());
                    summarizing = false;
                    return null;
                });
        });
    }

    public List<String> getExhaustedTopics() {
        return Collections.unmodifiableList(exhaustedTopics);
    }
}
