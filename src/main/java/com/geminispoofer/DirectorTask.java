package com.geminispoofer;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

public class DirectorTask extends BukkitRunnable {
    private final GeminiSpoofer database;
    private final PersonaManager personaManager;
    private final LlmClient llmClient;
    private final BotActor botActor;
    private final BotManager botManager;
    private final ConversationSeeder conversationSeeder;
    private final int contextLines;

    private long lastTalkTime = 0L;
    private double heatLevel = 0.0;
    private long lastDebugLog = 0L;

    public DirectorTask(GeminiSpoofer database,
                        PersonaManager personaManager,
                        LlmClient llmClient,
                        BotActor botActor,
                        BotManager botManager,
                        ConversationSeeder conversationSeeder,
                        int contextLines) {
        this.database = database;
        this.personaManager = personaManager;
        this.llmClient = llmClient;
        this.botActor = botActor;
        this.botManager = botManager;
        this.conversationSeeder = conversationSeeder;
        this.contextLines = contextLines;
    }

    @Override
    public void run() {
        if (!database.getConfig().getBoolean("enabled", true)) {
            debug("Chat disabled in config.yml; skipping DirectorTask tick.");
            return;
        }

        if (conversationSeeder != null) {
            conversationSeeder.tick();
        }
        int humanCount = Bukkit.getOnlinePlayers().size();
        int botCount = botManager.getAllBots().size();
        boolean requireHuman = database.getConfig().getBoolean("require_human_for_chat", true);
        debug("Director tick: humans=" + humanCount + ", bots=" + botCount + ", requireHuman=" + requireHuman);
        if (requireHuman && humanCount == 0) {
            debug("No human players online (humans=0); skipping DirectorTask tick.");
            return;
        }
        if (humanCount == 0 && botCount == 0) {
            debug("No players or bots online (humans=0,bots=0); skipping DirectorTask tick.");
            return;
        }

        double activity = database.getActivityMultiplier();
        double baseChance = database.getConfig().getDouble("chat_base_chance", 0.05);
        double heatDecay = database.getConfig().getDouble("chat_heat_decay", 0.08);
        double maxChance = database.getConfig().getDouble("chat_max_chance", 0.75);

        long now = System.currentTimeMillis();
        if (lastTalkTime > 0 && (now - lastTalkTime) > 300_000) {
            heatLevel = 0.0;
        } else {
            heatLevel = Math.max(0.0, heatLevel - heatDecay);
        }

        double scaledBase = baseChance * activity;
        double scaledMax = Math.min(1.0, maxChance * Math.max(0.3, activity));
        if (humanCount == 0) {
            double deadMultiplier = database.getConfig().getDouble("chat_dead_server_multiplier", 0.05);
            scaledBase *= deadMultiplier;
            scaledMax *= deadMultiplier;
        }
        double chance = Math.min(scaledMax, Math.max(0.0, scaledBase + heatLevel));
        debug("Heat=" + String.format("%.3f", heatLevel) + ", base=" + String.format("%.3f", scaledBase) + ", max=" + String.format("%.3f", scaledMax) + ", chance=" + String.format("%.3f", chance));

        double roll = ThreadLocalRandom.current().nextDouble();
        if (roll > chance) {
            debug("Chat roll failed: " + String.format("%.3f", roll) + " > " + String.format("%.3f", chance));
            return;
        }
        debug("Chat roll passed: " + String.format("%.3f", roll) + " <= " + String.format("%.3f", chance) + ", fetching context...");

        CompletableFuture<String> lastSenderFuture = database.getLastSenderAsync();
        CompletableFuture<String> lastSenderTypeFuture = database.getLastSenderTypeAsync();
        CompletableFuture<Long> lastHumanFuture = database.getLastHumanTimestampAsync();

        CompletableFuture.allOf(lastSenderFuture, lastSenderTypeFuture, lastHumanFuture)
            .thenCompose(ignored -> {
                String lastSender = lastSenderFuture.join();
                FakePlayer bot = pickRandomActiveBot(lastSender);
                if (bot == null) {
                    debug("No active bots available for chat.");
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                }

                long botJoinedAt = database.getBotJoinedAt(bot.getName());
                CompletableFuture<String> contextFuture = database.getRecentContextAsync(contextLines, botJoinedAt);

                return contextFuture.thenCompose(context -> {
                    ContextData data = new ContextData(
                        context,
                        lastSender,
                        lastSenderTypeFuture.join(),
                        lastHumanFuture.join()
                    );

                    Persona persona = personaManager.getPersona(bot.getName());
                    String prompt = persona != null ? persona.systemPrompt() : "";
                    String contextStr = data.context();
                    boolean deepThinkingEnabled = database.getConfig().getBoolean("deep-thinking.enabled", true);
                    long recentWindowSeconds = database.getConfig().getLong("deep-thinking.recent-human-seconds", 30);
                    long recentWindowMs = Math.max(0L, recentWindowSeconds) * 1000L;
                    boolean recentHuman = "human".equalsIgnoreCase(data.lastSenderType());
                    if (!recentHuman && data.lastHumanTimestamp() != null && recentWindowMs > 0) {
                        recentHuman = (System.currentTimeMillis() - data.lastHumanTimestamp()) <= recentWindowMs;
                    }
                    if (recentHuman && deepThinkingEnabled) {
                        String boost = database.getConfig().getString("deep-thinking.context-boost", "").trim();
                        if (!boost.isBlank()) {
                            contextStr = boost + "\n\n" + contextStr;
                        }
                    }
                    debug("Calling LLM for bot: " + bot.getName() + ", context length: " + contextStr.length() + ", joinedAt: " + botJoinedAt);

                    return llmClient.generateReplyAsync(prompt, contextStr)
                        .thenApply(response -> {
                            if (response == null || response.isBlank()) {
                                debug("LLM returned null/empty response for bot: " + bot.getName());
                            } else {
                                debug("LLM response received for " + bot.getName() + ": length=" + response.length());
                            }
                            return new ResponseData(bot, response);
                        });
                });
            })
            .thenAccept(responseData -> {
                if (responseData == null || responseData.response == null || responseData.response.isBlank()) {
                    debug("LLM response was empty; skipping bot chat.");
                    database.getLogger().fine("LLM response was empty; skipping bot chat.");
                    return;
                }
                Bukkit.getScheduler().runTask(database, () -> {
                    botActor.performChat(responseData.bot, responseData.response);
                    lastTalkTime = System.currentTimeMillis();
                });
            });
    }

    private void debug(String message) {
        if (!database.getConfig().getBoolean("debug_chat", false)) {
            return;
        }
        database.getLogger().info("[ChatDebug] " + message);
    }

    public void onHumanMessage() {
        double fallbackIncrease = database.getConfig().getDouble("chat_heat_increase", 0.25);
        double increase = database.getConfig().getDouble("chat_heat_human_increase", fallbackIncrease);
        heatLevel = Math.min(1.0, heatLevel + increase);
    }

    public void onBotMessage() {
        double fallbackDecrease = database.getConfig().getDouble("chat_heat_decay", 0.08);
        double decrease = database.getConfig().getDouble("chat_heat_bot_decrease", fallbackDecrease);
        heatLevel = Math.max(0.0, heatLevel - decrease);
    }

    public void addHeat(double amount) {
        if (amount <= 0) {
            return;
        }
        heatLevel = Math.min(1.0, heatLevel + amount);
    }

    private FakePlayer pickRandomActiveBot(String excludeName) {
        List<FakePlayer> bots = new ArrayList<>(botManager.getAllBots());
        if (bots.isEmpty()) {
            return null;
        }
        if (excludeName != null) {
            String excluded = excludeName.toLowerCase();
            bots.removeIf(bot -> bot.getName().equalsIgnoreCase(excluded));
        }
        int cooldownSeconds = database.getConfig().getInt("bot_response_cooldown_seconds", 30);
        if (cooldownSeconds > 0) {
            long now = System.currentTimeMillis();
            long cooldownMs = cooldownSeconds * 1000L;
            List<FakePlayer> cooled = new ArrayList<>();
            for (FakePlayer bot : bots) {
                long lastChat = database.getBotLastChatAt(bot.getName());
                if (lastChat <= 0 || (now - lastChat) >= cooldownMs) {
                    cooled.add(bot);
                }
            }
            if (!cooled.isEmpty()) {
                bots = cooled;
            }
        }
        if (bots.isEmpty()) {
            return null;
        }
        return bots.get(ThreadLocalRandom.current().nextInt(bots.size()));
    }

    private record ContextData(String context, String lastSender, String lastSenderType, Long lastHumanTimestamp) {
    }

    private static class ResponseData {
        private final FakePlayer bot;
        private final String response;

        private ResponseData(FakePlayer bot, String response) {
            this.bot = bot;
            this.response = response;
        }
    }
}
