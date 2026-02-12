package com.geminispoofer;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.CompletableFuture;

public class GeminiSpoofer extends JavaPlugin {
    private Connection connection;
    private final Object dbLock = new Object();
    private BotManager botManager;
    private PersonaManager personaManager;
    private LlmClient llmClient;
    private BotActor botActor;
    private DirectorTask directorTask;
    private BotSessionManager botSessionManager;
    private TopicSummarizer topicSummarizer;
    private ConversationSeeder conversationSeeder;
    private static final String[] EXTRA_CONFIG_FILES = {
        "llm.yml",
        "chat.yml",
        "fluctuation.yml",
        "greetings.yml",
        "typo.yml"
    };

    /**
     * Called when the plugin is enabled. Initializes the database and registers the ChatListener event.
     * If the database initialization fails, the plugin is disabled.
     */
    @Override
    public void onEnable() {
        ensureConfig();
        try {
            initDatabase();
        } catch (SQLException exception) {
            getLogger().severe("Failed to initialize database: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        botManager = new BotManager(this);
        personaManager = new PersonaManager(this);
        llmClient = createLlmClient();
        botActor = new BotActor(this, this);
        topicSummarizer = new TopicSummarizer(this, llmClient);
        conversationSeeder = new ConversationSeeder(this, llmClient, topicSummarizer, botManager, botActor);
        personaManager.loadPersonalities();
        startSessionManager();
        startDirectorTask();

        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        PaperChatListener.register(this);
        TabCompleteListener.register(this, botManager);
        getServer().getPluginManager().registerEvents(new ServerListPingListener(this), this);
        getServer().getPluginManager().registerEvents(new FakePlayerJoinListener(botManager), this);
        getServer().getPluginManager().registerEvents(new JoinGreetingListener(this), this);
        getCommand("finnehspoof").setExecutor(new FinnehSpoofCommand(this));

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new FinnehSpoofPlaceholderExpansion(this).register();
        }
    }

    /**
     * Called when the plugin is disabled. Closes the database connection.
     */
    @Override
    public void onDisable() {
        stopDirectorTask();
        stopSessionManager();
        if (botManager != null) {
            botManager.despawnAll();
        }
        if (connection == null) {
            return;
        }
        synchronized (dbLock) {
            try {
                connection.close();
            } catch (SQLException exception) {
                getLogger().warning("Failed to close database: " + exception.getMessage());
            }
        }
    }

    public void reloadPlugin() {
        ensureConfig();
        llmClient = createLlmClient();
        if (personaManager != null) {
            personaManager.loadPersonalities();
        }
        topicSummarizer = new TopicSummarizer(this, llmClient);
        conversationSeeder = new ConversationSeeder(this, llmClient, topicSummarizer, botManager, botActor);
        if (botSessionManager != null) {
            botSessionManager.resetState();
        }
        restartDirectorTask();
    }

    private void ensureConfig() {
        saveDefaultConfig();
        migrateSplitConfigs();
        reloadConfig();
        mergeExtraConfigs();
    }

    private void migrateSplitConfigs() {
        java.io.File dataFolder = getDataFolder();
        java.io.File baseFile = new java.io.File(dataFolder, "config.yml");
        org.bukkit.configuration.file.YamlConfiguration base = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(baseFile);

        applyDefaults(base, "config.yml");

        List<String> llmKeys = List.of(
            "api_key",
            "gemini_api_key",
            "llm_provider",
            "gemini_model",
            "openai_api_key",
            "openai_model",
            "openrouter_api_key",
            "openrouter_model",
            "openrouter_referer",
            "openrouter_title",
            "anthropic_api_key",
            "anthropic_model",
            "anthropic_max_tokens"
        );
        List<String> chatKeys = List.of(
            "conversation-seeder",
            "chat_base_chance",
            "chat_max_chance",
            "chat_heat_decay",
            "chat_heat_human_increase",
            "chat_heat_bot_decrease",
            "chat_dead_server_multiplier",
            "bot_response_cooldown_seconds",
            "chat_typing_base_delay",
            "chat_typing_ticks_per_char",
            "chat_typing_variation",
            "trigger",
            "deep-thinking",
            "repetition",
            "bot_join_message",
            "bot_leave_message",
            "bot_chat_format"
        );
        List<String> fluctuationKeys = List.of(
            "fluctuation",
            "max_active_bots",
            "target_total_players",
            "bot_fill_random_range",
            "empty_server_min_bots",
            "empty_server_max_bots",
            "join_chance",
            "leave_chance",
            "join_catchup",
            "leave_catchup",
            "join_stagger_min_ticks",
            "join_stagger_max_ticks",
            "leave_grace_after_join_seconds",
            "leave_grace_after_chat_seconds",
            "spawn_min_distance",
            "spawn_max_distance",
            "spawn_attempts",
            "cliff_max_drop",
            "min_session_seconds",
            "max_session_seconds",
            "session_cooldown_seconds",
            "walk_radius",
            "walk_interval_seconds"
        );
        List<String> greetingsKeys = List.of("greeting");
        List<String> typoKeys = List.of("typo-simulation");

        migrateConfigFile("llm.yml", base, llmKeys);
        migrateConfigFile("chat.yml", base, chatKeys);
        migrateConfigFile("fluctuation.yml", base, fluctuationKeys);
        migrateConfigFile("greetings.yml", base, greetingsKeys);
        migrateConfigFile("typo.yml", base, typoKeys);

        try {
            base.options().copyDefaults(true);
            base.options().copyHeader(true);
            base.save(baseFile);
        } catch (Exception exception) {
            getLogger().warning("Failed to migrate config.yml: " + exception.getMessage());
        }
    }

    private void migrateConfigFile(String fileName, org.bukkit.configuration.file.YamlConfiguration base, List<String> keys) {
        java.io.File file = new java.io.File(getDataFolder(), fileName);
        if (!file.exists()) {
            saveResource(fileName, false);
        }
        org.bukkit.configuration.file.YamlConfiguration target = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        applyDefaults(target, fileName);

        boolean changed = false;
        for (String key : keys) {
            if (base.contains(key)) {
                target.set(key, base.get(key));
                base.set(key, null);
                changed = true;
            }
        }

        try {
            target.options().copyDefaults(true);
            target.options().copyHeader(true);
            target.save(file);
        } catch (Exception exception) {
            getLogger().warning("Failed to save " + fileName + ": " + exception.getMessage());
        }
    }

    private void applyDefaults(org.bukkit.configuration.file.YamlConfiguration config, String resourceName) {
        if (getResource(resourceName) == null) {
            return;
        }
        try (java.io.InputStreamReader reader = new java.io.InputStreamReader(getResource(resourceName), java.nio.charset.StandardCharsets.UTF_8)) {
            org.bukkit.configuration.file.YamlConfiguration defaults = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(reader);
            config.setDefaults(defaults);
            config.options().copyDefaults(true);
            config.options().copyHeader(true);
        } catch (Exception exception) {
            getLogger().warning("Failed to apply defaults for " + resourceName + ": " + exception.getMessage());
        }
    }

    private void mergeExtraConfigs() {
        for (String fileName : EXTRA_CONFIG_FILES) {
            java.io.File file = new java.io.File(getDataFolder(), fileName);
            if (!file.exists()) {
                continue;
            }
            org.bukkit.configuration.file.YamlConfiguration extra = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            for (String key : extra.getKeys(true)) {
                getConfig().set(key, extra.get(key));
            }
        }
    }

    public double getActivityMultiplier() {
        String timezone = getConfig().getString("timezone", "Australia/Sydney");
        int peakStart = getConfig().getInt("peak_start_hour", 18);
        int peakEnd = getConfig().getInt("peak_end_hour", 23);
        int offpeakStart = getConfig().getInt("offpeak_start_hour", 2);
        int offpeakEnd = getConfig().getInt("offpeak_end_hour", 7);
        double peakMultiplier = getConfig().getDouble("peak_multiplier", 1.0);
        double offpeakMultiplier = getConfig().getDouble("offpeak_multiplier", 0.2);
        double normalMultiplier = getConfig().getDouble("normal_multiplier", 0.6);

        java.time.ZoneId zone;
        try {
            zone = java.time.ZoneId.of(timezone);
        } catch (Exception exception) {
            zone = java.time.ZoneId.systemDefault();
        }
        int hour = java.time.ZonedDateTime.now(zone).getHour();

        if (isHourInRange(hour, peakStart, peakEnd)) {
            return peakMultiplier;
        }
        if (isHourInRange(hour, offpeakStart, offpeakEnd)) {
            return offpeakMultiplier;
        }
        return normalMultiplier;
    }

    private boolean isHourInRange(int hour, int start, int end) {
        if (start == end) {
            return true;
        }
        if (start < end) {
            return hour >= start && hour < end;
        }
        return hour >= start || hour < end;
    }

    public void onHumanMessage() {
        if (directorTask != null) {
            directorTask.onHumanMessage();
        }
    }

    public void onBotMessage() {
        if (directorTask != null) {
            directorTask.onBotMessage();
        }
    }

    public void onBotChat(String botName) {
        if (botSessionManager != null) {
            botSessionManager.markChat(botName);
        }
    }

    public long getBotLastChatAt(String botName) {
        if (botSessionManager == null) {
            return 0L;
        }
        return botSessionManager.getLastChatAt(botName);
    }

    public long getBotJoinedAt(String botName) {
        if (botSessionManager == null) {
            return 0L;
        }
        return botSessionManager.getJoinedAt(botName);
    }

    public void addHeat(double amount) {
        if (directorTask != null) {
            directorTask.addHeat(amount);
        }
    }

    public void scheduleJoinGreeting(String joinedName, boolean isHuman) {
        if (!getConfig().getBoolean("greeting.enabled", true)) {
            return;
        }
        if (joinedName == null || joinedName.isBlank()) {
            return;
        }
        double chance = getConfig().getDouble(isHuman ? "greeting.human-join-chance" : "greeting.bot-join-chance", isHuman ? 0.05 : 0.01);
        int minDelay = Math.max(0, getConfig().getInt("greeting.delay-min-ticks", 200));
        int maxDelay = Math.max(minDelay, getConfig().getInt("greeting.delay-max-ticks", 600));
        int delay = ThreadLocalRandom.current().nextInt(minDelay, maxDelay + 1);
        Bukkit.getScheduler().runTaskLater(this, () -> attemptGreeting(joinedName, isHuman, chance), delay);
    }

    private void attemptGreeting(String joinedName, boolean isHuman, double chancePerBot) {
        if (isHuman && Bukkit.getPlayerExact(joinedName) == null) {
            return;
        }
        List<FakePlayer> bots = botManager.getAllBots();
        if (bots.isEmpty()) {
            return;
        }
        Collections.shuffle(bots);
        for (FakePlayer bot : bots) {
            if (bot == null || !botManager.isActive(bot.getName())) {
                continue;
            }
            if (ThreadLocalRandom.current().nextDouble() < chancePerBot) {
                generateAndSendGreeting(bot, joinedName, isHuman);
                break;
            }
        }
    }

    private void generateAndSendGreeting(FakePlayer bot, String joinedName, boolean isHuman) {
        if (bot == null || joinedName == null || joinedName.isBlank()) {
            return;
        }
        if (!botManager.isActive(bot.getName())) {
            return;
        }
        if (isHuman && Bukkit.getPlayerExact(joinedName) == null) {
            return;
        }
        Persona persona = personaManager != null ? personaManager.getPersona(bot.getName()) : null;
        String systemPrompt = persona != null ? persona.systemPrompt() : "";
        String greetContext = PromptBuilder.buildJoinGreetingPrompt(joinedName, isHuman);
        llmClient.generateReplyAsync(systemPrompt, greetContext)
            .thenAccept(response -> {
                if (response == null || response.isBlank()) {
                    return;
                }
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!botManager.isActive(bot.getName())) {
                        return;
                    }
                    if (isHuman && Bukkit.getPlayerExact(joinedName) == null) {
                        return;
                    }
                    boolean affectHeat = getConfig().getBoolean("greeting.affects-heat", false);
                    botActor.performChat(bot, response, affectHeat);
                });
            });
    }

    public int getBotCount() {
        if (botManager == null) {
            return 0;
        }
        return botManager.getAllBots().size();
    }

    public boolean isBotActive(String name) {
        if (botManager == null) {
            return false;
        }
        return botManager.isActive(name);
    }

    private void startDirectorTask() {
        if (!getConfig().getBoolean("enabled", true)) {
            return;
        }
        int contextLines = getConfig().getInt("context_lines", 15);
        directorTask = new DirectorTask(this, personaManager, llmClient, botActor, botManager, conversationSeeder, contextLines);
        directorTask.runTaskTimer(this, 100L, 100L);
    }

    private LlmClient createLlmClient() {
        LlmProvider provider = LlmProvider.fromConfig(getConfig().getString("llm_provider", "gemini"));
        return switch (provider) {
            case OPENAI -> new OpenAiClient(this);
            case OPENROUTER -> new OpenRouterClient(this);
            case ANTHROPIC -> new AnthropicClient(this);
            case GEMINI -> new GeminiClient(this);
        };
    }

    private void stopDirectorTask() {
        if (directorTask != null) {
            directorTask.cancel();
            directorTask = null;
        }
    }

    private void restartDirectorTask() {
        stopDirectorTask();
        startDirectorTask();
    }

    private void startSessionManager() {
        if (!getConfig().getBoolean("enabled", true)) {
            return;
        }
        botSessionManager = new BotSessionManager(this, botManager, personaManager);
        botSessionManager.runTaskTimer(this, 100L, 100L);
    }

    private void stopSessionManager() {
        if (botSessionManager != null) {
            botSessionManager.cancel();
            botSessionManager.stopAll();
            botSessionManager = null;
        }
    }

    private void restartSessionManager() {
        stopSessionManager();
        startSessionManager();
    }

    /**
     * Asynchronously logs a message to the database.
     * 
     * @param sender the player who sent the message
     * @param message the message that was sent
     * @return a CompletableFuture that completes with null on success, or completes exceptionally with a SQLException on failure
     */
    public CompletableFuture<Void> logMessageAsync(String sender, String message) {
        return logMessageAsync(sender, message, "unknown");
    }

    public CompletableFuture<Void> logMessageAsync(String sender, String message, String senderType) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                insertMessage(sender, message, senderType);
                Bukkit.getScheduler().runTask(this, () -> {
                    if (topicSummarizer != null) {
                        topicSummarizer.onMessageLogged();
                    }
                    if (conversationSeeder != null) {
                        conversationSeeder.onMessageLogged();
                    }
                });
                future.complete(null);
            } catch (SQLException exception) {
                getLogger().warning("Failed to log message: " + exception.getMessage());
                future.completeExceptionally(exception);
            }
        });
        return future;
    }

    /**
     * Asynchronously reads the recent chat context from the database.
     * 
     * @param limit the number of messages to read
     * @return a CompletableFuture that completes with the recent chat context on success, or completes exceptionally with a SQLException on failure
     */
    public CompletableFuture<String> getRecentContextAsync(int limit) {
        return getRecentContextAsync(limit, 0L);
    }

    /**
     * Asynchronously reads the recent chat context from the database, filtered by timestamp.
     * 
     * @param limit the number of messages to read
     * @param since only include messages with timestamp >= this value (0 = no filter)
     * @return a CompletableFuture that completes with the recent chat context on success, or completes exceptionally with a SQLException on failure
     */
    public CompletableFuture<String> getRecentContextAsync(int limit, long since) {
        CompletableFuture<String> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String context = readRecentContext(limit, since);
                future.complete(context);
            } catch (SQLException exception) {
                getLogger().warning("Failed to read chat context: " + exception.getMessage());
                future.completeExceptionally(exception);
            }
        });
        return future;
    }

    /**
     * Asynchronously reads the last sender from the database.
     *
     * @return a CompletableFuture that completes with the last sender name, or null if unavailable
     */
    public CompletableFuture<String> getLastSenderAsync() {
        CompletableFuture<String> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String sender = readLastSender();
                future.complete(sender);
            } catch (SQLException exception) {
                getLogger().warning("Failed to read last sender: " + exception.getMessage());
                future.completeExceptionally(exception);
            }
        });
        return future;
    }

    /**
     * Asynchronously reads the last sender type from the database.
     *
     * @return a CompletableFuture that completes with the last sender type, or null if unavailable
     */
    public CompletableFuture<String> getLastSenderTypeAsync() {
        CompletableFuture<String> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String senderType = readLastSenderType();
                future.complete(senderType);
            } catch (SQLException exception) {
                getLogger().warning("Failed to read last sender type: " + exception.getMessage());
                future.completeExceptionally(exception);
            }
        });
        return future;
    }

    /**
     * Asynchronously reads the last human message timestamp from the database.
     *
     * @return a CompletableFuture that completes with the last human timestamp (ms), or null if unavailable
     */
    public CompletableFuture<Long> getLastHumanTimestampAsync() {
        CompletableFuture<Long> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Long timestamp = readLastHumanTimestamp();
                future.complete(timestamp);
            } catch (SQLException exception) {
                getLogger().warning("Failed to read last human timestamp: " + exception.getMessage());
                future.completeExceptionally(exception);
            }
        });
        return future;
    }

    /**
     * Initializes the database by creating the database directory and creating the chat_log table if it doesn't exist.
     * 
     * @throws SQLException if the database directory cannot be created, or if the chat_log table cannot be created
     */
    private void initDatabase() throws SQLException {
        Path dbPath = Path.of("plugins", "GeminiSpoofer", "chat.db");
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (Exception exception) {
            throw new SQLException("Failed to create database directory", exception);
        }

        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS chat_log (" +
                    "id INTEGER PRIMARY KEY," +
                    "sender VARCHAR(32)," +
                    "message TEXT," +
                    "timestamp LONG," +
                    "sender_type VARCHAR(16) DEFAULT 'unknown'" +
                ")"
            );
            try {
                statement.executeUpdate("ALTER TABLE chat_log ADD COLUMN sender_type VARCHAR(16) DEFAULT 'unknown'");
            } catch (SQLException ignored) {
                // Column already exists
            }
        }
    }

    /**
     * Inserts a chat message into the database.
     * 
     * @param sender the sender of the message
     * @param message the message that was sent
     * @throws SQLException if the database operation fails
     */
    private void insertMessage(String sender, String message, String senderType) throws SQLException {
        synchronized (dbLock) {
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO chat_log (sender, message, timestamp, sender_type) VALUES (?, ?, ?, ?)"
            )) {
                statement.setString(1, sender);
                statement.setString(2, message);
                statement.setLong(3, System.currentTimeMillis());
                statement.setString(4, senderType == null ? "unknown" : senderType);
                statement.executeUpdate();
            }
        }
    }

    /**
     * Reads the recent chat context from the database and returns it as a string.
     *
     * The returned string contains the most recent chat messages, with each message
     * formatted as "sender: message\n". The messages are ordered by timestamp, with
     * the most recent message first.
     *
     * @param limit the number of messages to read
     * @return the recent chat context as a string
     * @throws SQLException if the database operation fails
     */
    private String readRecentContext(int limit) throws SQLException {
        return readRecentContext(limit, 0L);
    }

    private String readRecentContext(int limit, long since) throws SQLException {
        List<String> lines = new ArrayList<>();
        synchronized (dbLock) {
            String sql = since > 0
                ? "SELECT sender, message, sender_type FROM chat_log WHERE timestamp >= ? ORDER BY id DESC LIMIT ?"
                : "SELECT sender, message, sender_type FROM chat_log ORDER BY id DESC LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                if (since > 0) {
                    statement.setLong(1, since);
                    statement.setInt(2, limit);
                } else {
                    statement.setInt(1, limit);
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String sender = resultSet.getString("sender");
                        String message = resultSet.getString("message");
                        String senderType = resultSet.getString("sender_type");
                        if (senderType == null || senderType.isBlank()) {
                            senderType = "unknown";
                        }
                        lines.add("[" + senderType + "] " + sender + ": " + message + "\n");
                    }
                }
            }
        }

        Collections.reverse(lines);
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            builder.append(line);
        }
        return builder.toString();
    }

    private String readLastSender() throws SQLException {
        synchronized (dbLock) {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sender FROM chat_log ORDER BY id DESC LIMIT 1"
            )) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getString("sender");
                    }
                }
            }
        }
        return null;
    }

    private String readLastSenderType() throws SQLException {
        synchronized (dbLock) {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sender_type FROM chat_log ORDER BY id DESC LIMIT 1"
            )) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getString("sender_type");
                    }
                }
            }
        }
        return null;
    }

    private Long readLastHumanTimestamp() throws SQLException {
        synchronized (dbLock) {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT timestamp FROM chat_log WHERE sender_type='human' ORDER BY id DESC LIMIT 1"
            )) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getLong("timestamp");
                    }
                }
            }
        }
        return null;
    }

    public void setBotResponsesEnabled(boolean enabled) {
        getConfig().set("enabled", enabled);
        try {
            getConfig().save(new java.io.File(getDataFolder(), "config.yml"));
        } catch (java.io.IOException exception) {
            getLogger().warning("Failed to save config after setBotResponsesEnabled: " + exception.getMessage());
        }
        if (enabled) {
            startDirectorTask();
            startSessionManager();
        } else {
            stopDirectorTask();
            stopSessionManager();
        }
    }
}
