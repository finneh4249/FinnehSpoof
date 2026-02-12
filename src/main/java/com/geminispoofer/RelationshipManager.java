package com.geminispoofer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Core class managing all relationship operations: interaction tracking,
 * topic extraction, relationship summarization, decay, and prompt context building.
 */
public class RelationshipManager {
    private final GeminiSpoofer plugin;
    private final RelationshipDatabase db;
    private final LlmClient llmClient;
    private final Logger logger;

    private static final double PASSIVE_WEIGHT = 0.1;
    private static final double MENTION_WEIGHT = 1.0;

    public RelationshipManager(GeminiSpoofer plugin, RelationshipDatabase db, LlmClient llmClient) {
        this.plugin = plugin;
        this.db = db;
        this.llmClient = llmClient;
        this.logger = plugin.getLogger();
    }

    /**
     * Checks whether the relationship-memory system is enabled in config.
     */
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("relationship-memory.enabled", false);
    }

    // ── Interaction Processing ───────────────────────────────────────────

    /**
     * Called after each message is logged. Updates relationships between the
     * speaker and all other online entities.
     *
     * @param senderName the name of the message sender
     * @param senderType "bot" or "human"
     * @param message    the raw message text
     */
    public void processMessage(String senderName, String senderType, String message) {
        if (!isEnabled()) return;
        if (senderName == null || senderName.isBlank()) return;

        // Collect Bukkit-based entity data on the main thread, then process DB updates asynchronously.
        Bukkit.getScheduler().runTask(plugin, () -> {
            List<String> onlineEntities = getOnlineEntityNames();
            onlineEntities.remove(senderName.toLowerCase());

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    for (String other : onlineEntities) {
                        String otherType = plugin.isBotActive(other) ? "bot" : "human";

                        // Passive: just being online together
                        int relId = db.upsertRelationship(senderName, senderType, other, otherType, PASSIVE_WEIGHT);

                        // Active: direct name mention
                        double totalWeight = PASSIVE_WEIGHT;
                        if (message != null && message.toLowerCase().contains(other.toLowerCase())) {
                            db.upsertRelationship(senderName, senderType, other, otherType, MENTION_WEIGHT);
                            totalWeight += MENTION_WEIGHT;
                        }

                        // Check thresholds for topic extraction and summarization
                        if (relId > 0) {
                            checkThresholds(senderName, other, relId, totalWeight);
                        }
                    }
                } catch (SQLException e) {
                    logger.warning("[RelationshipManager] Failed to process message: " + e.getMessage());
                }
            });
        });
    }

    /**
     * Checks if interaction thresholds are met for topic extraction or summarization.
     * Uses the weight that was just added to determine if we crossed a threshold.
     */
    private void checkThresholds(String entityA, String entityB, int relationshipId, double addedWeight) throws SQLException {
        FileConfiguration config = plugin.getConfig();
        int topicThreshold = config.getInt("relationship-memory.topic-extract-after-interactions", 20);
        int summaryThreshold = config.getInt("relationship-memory.summarize-after-interactions", 50);

        double currentCount = db.getInteractionCount(entityA, entityB);
        double previousCount = currentCount - addedWeight;

        // Topic extraction when crossing threshold boundaries
        if (topicThreshold > 0) {
            int prevFloor = (int) Math.floor(previousCount / topicThreshold);
            int currFloor = (int) Math.floor(currentCount / topicThreshold);
            if (currFloor > prevFloor) {
                extractTopicsAsync(entityA, entityB, relationshipId);
            }
        }

        // Summarization when crossing threshold boundaries
        if (summaryThreshold > 0) {
            int prevFloor = (int) Math.floor(previousCount / summaryThreshold);
            int currFloor = (int) Math.floor(currentCount / summaryThreshold);
            if (currFloor > prevFloor) {
                summarizeRelationshipAsync(entityA, entityB, relationshipId);
            }
        }
    }

    // ── Context Building ─────────────────────────────────────────────────

    /**
     * Builds a relationship context string for a bot, considering the currently
     * present entities. Returns an empty RelationshipContext if disabled or no
     * relevant relationships exist.
     *
     * @param botName         the bot to build context for
     * @param presentEntities list of currently online entity names
     * @return formatted relationship context for prompt injection
     */
    public RelationshipContext getRelationshipContext(String botName, List<String> presentEntities) {
        if (!isEnabled()) {
            return new RelationshipContext("", List.of(), List.of());
        }

        FileConfiguration config = plugin.getConfig();
        int maxSentences = config.getInt("relationship-memory.max-context-sentences", 3);
        int maxEntities = config.getInt("relationship-memory.max-entities-in-context", 4);

        try {
            List<RelationshipDatabase.RelationshipRow> allRels = db.getRelationshipsFor(botName, 20);
            if (allRels.isEmpty()) {
                return new RelationshipContext("", List.of(), List.of());
            }

            // Filter to only present entities, sorted by interaction count (already sorted by DB)
            List<RelationshipDatabase.RelationshipRow> relevant = new ArrayList<>();
            List<String> knownEntities = new ArrayList<>();
            for (RelationshipDatabase.RelationshipRow row : allRels) {
                String other = row.otherEntity(botName);
                knownEntities.add(other);
                for (String present : presentEntities) {
                    if (present.equalsIgnoreCase(other)) {
                        relevant.add(row);
                        break;
                    }
                }
            }

            if (relevant.isEmpty()) {
                return new RelationshipContext("", knownEntities, List.of());
            }

            // Cap at max entities
            if (relevant.size() > maxEntities) {
                relevant = relevant.subList(0, maxEntities);
            }

            // Build context sentences
            StringBuilder contextBuilder = new StringBuilder();
            List<String> allTopics = new ArrayList<>();
            int sentenceCount = 0;

            for (RelationshipDatabase.RelationshipRow row : relevant) {
                if (sentenceCount >= maxSentences) break;

                String other = row.otherEntity(botName);

                // Try to use a stored summary first
                String summary = db.getSummary(row.id());
                if (summary != null && !summary.isBlank()) {
                    contextBuilder.append(summary.trim());
                    if (!summary.trim().endsWith(".")) contextBuilder.append(".");
                    contextBuilder.append(" ");
                    sentenceCount++;
                    continue;
                }

                // Fallback: build from topics and interaction count
                List<String> topics = db.getTopics(row.id());
                allTopics.addAll(topics);

                if (!topics.isEmpty()) {
                    String topicStr = topics.stream().limit(3).collect(Collectors.joining(", "));
                    contextBuilder.append("You've chatted with ").append(other)
                        .append(" about ").append(topicStr).append(". ");
                    sentenceCount++;
                } else if (row.interactionCount() > 10) {
                    contextBuilder.append("You've interacted with ").append(other)
                        .append(" ").append((int) row.interactionCount()).append(" times before. ");
                    sentenceCount++;
                }
            }

            // Add entity facts for present entities
            for (String present : presentEntities) {
                if (sentenceCount >= maxSentences) break;
                List<String> facts = db.getEntityFacts(present);
                if (!facts.isEmpty()) {
                    contextBuilder.append("Note: ").append(present).append(" ")
                        .append(facts.get(0)).append(". ");
                    sentenceCount++;
                }
            }

            String formatted = contextBuilder.toString().trim();
            return new RelationshipContext(formatted, knownEntities, allTopics);

        } catch (SQLException e) {
            logger.warning("[RelationshipManager] Failed to build relationship context: " + e.getMessage());
            return new RelationshipContext("", List.of(), List.of());
        }
    }

    // ── Topic Extraction ─────────────────────────────────────────────────

    /**
     * Uses the LLM to extract topics discussed between two entities from recent chat.
     */
    private void extractTopicsAsync(String entityA, String entityB, int relationshipId) {
        int contextLines = plugin.getConfig().getInt("relationship-memory.topic-context-lines", 30);
        int maxTopics = plugin.getConfig().getInt("relationship-memory.max-topics-per-pair", 10);

        plugin.getRecentContextAsync(contextLines, 0L).thenAccept(context -> {
            if (context == null || context.isBlank()) return;

            // Filter context to only messages involving the pair
            String filteredContext = filterContextForPair(context, entityA, entityB);
            if (filteredContext.isBlank()) return;

            String systemPrompt = "Here are recent messages between " + entityA + " and " + entityB + ". " +
                "List the main topics they discussed as a JSON array. " +
                "Max 5 topics. Short phrases only. " +
                "Example: [\"trains\", \"mining\", \"star wars\"]. " +
                "If no clear topics, return empty array [].";

            llmClient.generateReplyAsync(systemPrompt, filteredContext)
                .thenAccept(response -> {
                    if (response == null || response.isBlank()) return;
                    try {
                        Gson gson = new Gson();
                        String trimmed = response.trim();
                        // Handle markdown-wrapped JSON
                        if (trimmed.startsWith("```")) {
                            int start = trimmed.indexOf('[');
                            int end = trimmed.lastIndexOf(']');
                            if (start >= 0 && end > start) {
                                trimmed = trimmed.substring(start, end + 1);
                            }
                        }
                        JsonArray jsonArray = gson.fromJson(trimmed, JsonArray.class);
                        List<String> topics = new ArrayList<>();
                        for (JsonElement el : jsonArray) {
                            String topic = el.getAsString().trim().toLowerCase();
                            if (!topic.isEmpty() && topics.size() < maxTopics) {
                                topics.add(topic);
                            }
                        }
                        db.replaceTopics(relationshipId, topics, maxTopics);
                        logger.info("[RelationshipManager] Extracted topics for " + entityA + "↔" + entityB + ": " + topics);
                    } catch (Exception e) {
                        logger.warning("[RelationshipManager] Failed to parse topics: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> {
                    logger.warning("[RelationshipManager] Topic extraction LLM call failed: " + ex.getMessage());
                    return null;
                });
        });
    }

    // ── Relationship Summarization ───────────────────────────────────────

    /**
     * Uses the LLM to generate a natural language summary of the relationship.
     */
    private void summarizeRelationshipAsync(String entityA, String entityB, int relationshipId) {
        int contextLines = plugin.getConfig().getInt("relationship-memory.summary-context-lines", 50);

        plugin.getRecentContextAsync(contextLines, 0L).thenAccept(context -> {
            if (context == null || context.isBlank()) return;

            String filteredContext = filterContextForPair(context, entityA, entityB);
            if (filteredContext.isBlank()) return;

            String systemPrompt = "Summarize the relationship between " + entityA + " and " + entityB +
                " based on their Minecraft chat history. 2 sentences max. " +
                "Write from " + entityA + "'s perspective. " +
                "Focus on: shared interests, recurring topics, general vibe. " +
                "Be casual and natural. Do not mention message counts or data.";

            llmClient.generateReplyAsync(systemPrompt, filteredContext)
                .thenAccept(response -> {
                    if (response == null || response.isBlank()) return;
                    try {
                        String summary = response.trim();
                        // Strip markdown if present
                        if (summary.startsWith("```")) {
                            int start = summary.indexOf('\n');
                            int end = summary.lastIndexOf("```");
                            if (start >= 0 && end > start) {
                                summary = summary.substring(start + 1, end).trim();
                            }
                        }
                        db.saveSummary(relationshipId, summary);
                        logger.info("[RelationshipManager] Summarized relationship " + entityA + "↔" + entityB);
                    } catch (Exception e) {
                        logger.warning("[RelationshipManager] Failed to save summary: " + e.getMessage());
                    }
                })
                .exceptionally(ex -> {
                    logger.warning("[RelationshipManager] Summarization LLM call failed: " + ex.getMessage());
                    return null;
                });
        });
    }

    // ── Decay ────────────────────────────────────────────────────────────

    /**
     * Decays stale relationships. Should be called periodically (e.g. daily).
     */
    public void decayStaleRelationships() {
        if (!isEnabled()) return;

        FileConfiguration config = plugin.getConfig();
        int decayDays = config.getInt("relationship-memory.decay-days", 30);
        double decayRate = config.getDouble("relationship-memory.decay-rate", 0.1);
        int minimumThreshold = config.getInt("relationship-memory.minimum-threshold", 5);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                int removed = db.decayStaleRelationships(decayDays, decayRate, minimumThreshold);
                if (removed > 0) {
                    logger.info("[RelationshipManager] Decayed " + removed + " stale relationship(s).");
                }
            } catch (SQLException e) {
                logger.warning("[RelationshipManager] Failed to decay relationships: " + e.getMessage());
            }
        });
    }

    // ── Command support ──────────────────────────────────────────────────

    /**
     * Gets relationship info for a player, formatted for command output.
     *
     * @param entityName the player/bot name to look up
     * @return list of formatted strings for display
     */
    public CompletableFuture<List<String>> getRelationshipInfo(String entityName) {
        CompletableFuture<List<String>> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<String> lines = new ArrayList<>();
                List<RelationshipDatabase.RelationshipRow> rels = db.getRelationshipsFor(entityName, 10);

                if (rels.isEmpty()) {
                    lines.add("§7No relationships found for §f" + entityName + "§7.");
                    future.complete(lines);
                    return;
                }

                lines.add("§d§lRelationships for §f" + entityName + "§d§l:");
                for (RelationshipDatabase.RelationshipRow row : rels) {
                    String other = row.otherEntity(entityName);
                    String sentimentColor = row.sentiment() >= 0 ? "§a" : "§c";
                    lines.add("  §7• §f" + other + " §7[" + sentimentColor +
                        String.format("%.1f", row.sentiment()) + "§7] §7interactions: §f" +
                        String.format("%.1f", row.interactionCount()));

                    List<String> topics = db.getTopics(row.id());
                    if (!topics.isEmpty()) {
                        String topicStr = topics.stream().limit(5).collect(Collectors.joining(", "));
                        lines.add("    §7topics: §e" + topicStr);
                    }

                    String summary = db.getSummary(row.id());
                    if (summary != null && !summary.isBlank()) {
                        lines.add("    §7summary: §f" + summary);
                    }
                }

                // Entity facts
                List<String> facts = db.getEntityFacts(entityName);
                if (!facts.isEmpty()) {
                    lines.add("§d§lKnown facts:");
                    for (String fact : facts) {
                        lines.add("  §7• §f" + fact);
                    }
                }

                future.complete(lines);
            } catch (SQLException e) {
                logger.warning("[RelationshipManager] Failed to get relationship info: " + e.getMessage());
                List<String> errorLines = new ArrayList<>();
                errorLines.add("§cFailed to load relationship data.");
                future.complete(errorLines);
            }
        });
        return future;
    }

    /**
     * Returns the underlying database (for direct access if needed).
     */
    public RelationshipDatabase getDatabase() {
        return db;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Gets names of all online entities (real players + active bots).
     */
    private List<String> getOnlineEntityNames() {
        List<String> names = new ArrayList<>();
        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        for (Player p : players) {
            names.add(p.getName().toLowerCase());
        }
        if (plugin.getBotCount() > 0) {
            BotManager botManager = plugin.getBotManager();
            if (botManager != null) {
                for (FakePlayer bot : botManager.getAllBots()) {
                    names.add(bot.getName().toLowerCase());
                }
            }
        }
        return names;
    }

    /**
     * Filters a chat context string to only include lines mentioning either entity.
     */
    private String filterContextForPair(String context, String entityA, String entityB) {
        String lowerA = entityA.toLowerCase();
        String lowerB = entityB.toLowerCase();
        StringBuilder filtered = new StringBuilder();
        for (String line : context.split("\n")) {
            String lower = line.toLowerCase();
            if (lower.contains(lowerA) || lower.contains(lowerB)) {
                filtered.append(line).append("\n");
            }
        }
        return filtered.toString().trim();
    }
}
