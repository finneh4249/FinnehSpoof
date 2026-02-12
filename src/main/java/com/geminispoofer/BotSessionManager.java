package com.geminispoofer;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class BotSessionManager extends BukkitRunnable {
    private final GeminiSpoofer plugin;
    private final BotManager botManager;
    private final PersonaManager personaManager;
    private final Map<String, BotSession> sessions = new HashMap<>();
    private final Map<String, Long> cooldownUntil = new HashMap<>();
    private long lastWalkUpdate = 0L;
    private long lastFluctuationCheck = 0L;
    private int joinsThisInterval = 0;
    private boolean hasRunStartupBatch = false;
    private long lastJoinAt = 0L;

    public BotSessionManager(GeminiSpoofer plugin, BotManager botManager, PersonaManager personaManager) {
        this.plugin = plugin;
        this.botManager = botManager;
        this.personaManager = personaManager;
    }

    public void resetState() {
        joinsThisInterval = 0;
        hasRunStartupBatch = false;
        cooldownUntil.clear();
        lastFluctuationCheck = 0L;
        lastJoinAt = 0L;
        plugin.getLogger().info("[BotSessionManager] State reset: joinsThisInterval=0, hasRunStartupBatch=false, cooldowns cleared");
    }

    @Override
    public void run() {
        if (!plugin.getConfig().getBoolean("enabled", true)) {
            return;
        }

        long now = System.currentTimeMillis();
        
        if (!hasRunStartupBatch && plugin.getConfig().getBoolean("fluctuation.startup.enabled", true)) {
            runStartupBatch();
            hasRunStartupBatch = true;
        }
        
        long fluctuationCheckInterval = plugin.getConfig().getLong("fluctuation.check-interval", 600) * 1000L;
        if (now - lastFluctuationCheck >= fluctuationCheckInterval) {
            lastFluctuationCheck = now;
            joinsThisInterval = 0;
            plugin.getLogger().info("[BotSessionManager] joinsThisInterval RESET (check interval elapsed)");
        }
        
        double activity = plugin.getActivityMultiplier();
        
        int humanCount = Bukkit.getOnlinePlayers().size();
        int minPlayersOnline = plugin.getConfig().getInt("fluctuation.min-players-online", 0);
        if (humanCount < minPlayersOnline) {
            return;
        }
        
        int maxActive = plugin.getConfig().getInt("max_active_bots", 4);
        int minOnline = plugin.getConfig().getInt("fluctuation.min-online", 2);
        int maxOnline = plugin.getConfig().getInt("fluctuation.max-online", 15);
        int maxPerCheck = plugin.getConfig().getInt("fluctuation.max-players-per-check", 1);
        
        int targetTotal = plugin.getConfig().getInt("target_total_players", 50);
        int fillRandomRange = plugin.getConfig().getInt("bot_fill_random_range", 3);
        int emptyMin = plugin.getConfig().getInt("empty_server_min_bots", 1);
        int emptyMax = plugin.getConfig().getInt("empty_server_max_bots", 3);
        int minSession = plugin.getConfig().getInt("min_session_seconds", 120);
        int maxSession = plugin.getConfig().getInt("max_session_seconds", 420);
        double joinChance = plugin.getConfig().getDouble("join_chance", 0.35);
        double leaveChance = plugin.getConfig().getDouble("leave_chance", 0.10);
        double joinCatchup = plugin.getConfig().getDouble("join_catchup", 0.15);
        double leaveCatchup = plugin.getConfig().getDouble("leave_catchup", 0.20);
        int walkRadius = plugin.getConfig().getInt("walk_radius", 10);
        int walkInterval = plugin.getConfig().getInt("walk_interval_seconds", 10);
        int spawnMinDistance = plugin.getConfig().getInt("spawn_min_distance", 20);
        int spawnMaxDistance = plugin.getConfig().getInt("spawn_max_distance", 60);
        int spawnAttempts = plugin.getConfig().getInt("spawn_attempts", 12);
        int cliffMaxDrop = plugin.getConfig().getInt("cliff_max_drop", 3);
        int sessionCooldownSeconds = plugin.getConfig().getInt("session_cooldown_seconds", 120);

        int adjustedMaxActive = Math.max(1, (int) Math.round(maxActive * activity));
        int desiredBots = calculateDesiredBots(humanCount, targetTotal, fillRandomRange, emptyMin, emptyMax);
        desiredBots = Math.min(desiredBots, adjustedMaxActive);

        int deficit = Math.max(0, desiredBots - sessions.size());
        int surplus = Math.max(0, sessions.size() - desiredBots);

        plugin.getLogger().info(String.format("[BotSessionManager] humanCount=%d, desiredBots=%d, currentBots=%d, deficit=%d, surplus=%d, joinsThisInterval=%d, maxPerCheck=%d",
            humanCount, desiredBots, sessions.size(), deficit, surplus, joinsThisInterval, maxPerCheck));

        double adjustedJoinChance = Math.min(1.0, Math.max(0.0, joinChance * activity + (deficit * joinCatchup)));
        double adjustedLeaveChance = Math.min(1.0, Math.max(0.0, leaveChance * (1.0 + (1.0 - activity)) + (surplus * leaveCatchup)));

        desiredBots = Math.max(minOnline, Math.min(maxOnline, desiredBots));
        
        handleLeaves(now, adjustedLeaveChance, minOnline, minSession);
        handleFluctuationJoins(now, desiredBots, maxPerCheck, minSession, maxSession, adjustedJoinChance, spawnMinDistance, spawnMaxDistance, spawnAttempts, cliffMaxDrop, sessionCooldownSeconds);
        handleWalking(now, walkInterval, walkRadius, cliffMaxDrop);
    }

    public void stopAll() {
        for (String name : new ArrayList<>(sessions.keySet())) {
            leave(name, true);
        }
    }

    public long getLastChatAt(String botName) {
        if (botName == null || botName.isBlank()) {
            return 0L;
        }
        BotSession session = sessions.get(botName.toLowerCase(Locale.ROOT));
        if (session == null) {
            return 0L;
        }
        return session.lastChatAt;
    }

    public long getJoinedAt(String botName) {
        if (botName == null || botName.isBlank()) {
            return 0L;
        }
        BotSession session = sessions.get(botName.toLowerCase(Locale.ROOT));
        if (session == null) {
            return 0L;
        }
        return session.startAt;
    }

    private void runStartupBatch() {
        int batchSize = plugin.getConfig().getInt("fluctuation.startup.batch-size", 2);
        int batchDelay = plugin.getConfig().getInt("fluctuation.startup.batch-delay", 40);
        int variation = plugin.getConfig().getInt("fluctuation.startup.batch-delay-variation", 20);
        int joinCooldownSeconds = plugin.getConfig().getInt("fluctuation.join-cooldown-seconds", 20);
        
        long now = System.currentTimeMillis();
        int minSession = plugin.getConfig().getInt("min_session_seconds", 420);
        int maxSession = plugin.getConfig().getInt("max_session_seconds", 1500);
        int spawnMinDistance = plugin.getConfig().getInt("spawn_min_distance", 20);
        int spawnMaxDistance = plugin.getConfig().getInt("spawn_max_distance", 60);
        int spawnAttempts = plugin.getConfig().getInt("spawn_attempts", 12);
        int cliffMaxDrop = plugin.getConfig().getInt("cliff_max_drop", 3);
        int sessionCooldownSeconds = plugin.getConfig().getInt("session_cooldown_seconds", 120);
        
        int minDelay = Math.max(0, batchDelay - variation);
        int maxDelay = Math.max(minDelay, batchDelay + variation);
        int cumulativeDelay = 0;
        for (int i = 0; i < batchSize; i++) {
            int stepDelay = ThreadLocalRandom.current().nextInt(minDelay, maxDelay + 1);
            cumulativeDelay += stepDelay;
            int scheduledDelay = cumulativeDelay;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                long nowLocal = System.currentTimeMillis();
                if (!canJoinNow(nowLocal, joinCooldownSeconds)) {
                    return;
                }
                if (spawnOneBot(nowLocal, minSession, maxSession, spawnMinDistance, spawnMaxDistance, spawnAttempts, cliffMaxDrop, sessionCooldownSeconds)) {
                    lastJoinAt = nowLocal;
                }
            }, scheduledDelay);
        }
    }

    private void handleFluctuationJoins(long now,
                                         int desiredBots,
                                         int maxPerCheck,
                                         int minSession,
                                         int maxSession,
                                         double joinChance,
                                         int spawnMinDistance,
                                         int spawnMaxDistance,
                                         int spawnAttempts,
                                         int cliffMaxDrop,
                                         int sessionCooldownSeconds) {
        int joinCooldownSeconds = plugin.getConfig().getInt("fluctuation.join-cooldown-seconds", 20);
        if (!canJoinNow(now, joinCooldownSeconds)) {
            return;
        }
        int toSpawn = Math.max(0, desiredBots - sessions.size());
        toSpawn = Math.min(toSpawn, maxPerCheck - joinsThisInterval);
        
        plugin.getLogger().info(String.format("[BotSessionManager] handleFluctuationJoins: toSpawn=%d (capped by maxPerCheck=%d - joinsThisInterval=%d = %d)", 
            toSpawn, maxPerCheck, joinsThisInterval, maxPerCheck - joinsThisInterval));
        
        int joinStaggerMin = plugin.getConfig().getInt("join_stagger_min_ticks", 10);
        int joinStaggerMax = plugin.getConfig().getInt("join_stagger_max_ticks", 40);
        
        for (int i = 0; i < toSpawn; i++) {
            if (ThreadLocalRandom.current().nextDouble() > joinChance) {
                continue;
            }
            int delay = 0;
            if (joinStaggerMax > 0) {
                int min = Math.max(0, joinStaggerMin);
                int max = Math.max(min, joinStaggerMax);
                delay = ThreadLocalRandom.current().nextInt(min, max + 1) * (i + 1);
            }
            int scheduledDelay = delay;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                long nowLocal = System.currentTimeMillis();
                if (!canJoinNow(nowLocal, joinCooldownSeconds)) {
                    return;
                }
                if (spawnOneBot(nowLocal, minSession, maxSession, spawnMinDistance, spawnMaxDistance, spawnAttempts, cliffMaxDrop, sessionCooldownSeconds)) {
                    joinsThisInterval++;
                    lastJoinAt = nowLocal;
                    plugin.getLogger().info(String.format("[BotSessionManager] Bot spawned! joinsThisInterval now: %d", joinsThisInterval));
                }
            }, scheduledDelay);
        }
    }

    private boolean canJoinNow(long now, int joinCooldownSeconds) {
        if (joinCooldownSeconds <= 0) {
            return true;
        }
        if (lastJoinAt <= 0) {
            return true;
        }
        return (now - lastJoinAt) >= (joinCooldownSeconds * 1000L);
    }

    private boolean spawnOneBot(long now,
                                 int minSession,
                                 int maxSession,
                                 int spawnMinDistance,
                                 int spawnMaxDistance,
                                 int spawnAttempts,
                                 int cliffMaxDrop,
                                 int sessionCooldownSeconds) {
        Persona persona = pickRandomAvailablePersona(now, sessionCooldownSeconds);
        if (persona == null) {
            return false;
        }

        Location anchorLocation = pickAnchorLocation();
        if (anchorLocation == null) {
            return false;
        }

        Location spawnLocation = findSpawnLocation(anchorLocation, spawnMinDistance, spawnMaxDistance, spawnAttempts, cliffMaxDrop);
        if (spawnLocation == null) {
            return false;
        }
        FakePlayer bot = botManager.spawnBot(persona.name(), persona.skin(), spawnLocation);
        if (bot == null) {
            return false;
        }

        long sessionLengthMs = ThreadLocalRandom.current().nextLong(
            Math.max(10, minSession),
            Math.max(minSession + 1, maxSession) + 1
        ) * 1000L;
        sessions.put(persona.name().toLowerCase(Locale.ROOT), new BotSession(bot, now, now + sessionLengthMs));
        Bukkit.broadcastMessage(formatJoinLeaveMessage(true, persona.name()));
        plugin.scheduleJoinGreeting(persona.name(), false);
        return true;
    }

    private void handleLeaves(long now, double leaveChance, int minOnline, int minSessionSeconds) {
        int joinGraceSeconds = plugin.getConfig().getInt("leave_grace_after_join_seconds", 30);
        int chatGraceSeconds = plugin.getConfig().getInt("leave_grace_after_chat_seconds", 60);
        for (Map.Entry<String, BotSession> entry : new ArrayList<>(sessions.entrySet())) {
            String name = entry.getKey();
            BotSession session = entry.getValue();
            if (sessions.size() <= minOnline) {
                break;
            }
            if (joinGraceSeconds > 0 && now < session.startAt + (joinGraceSeconds * 1000L)) {
                continue;
            }
            if (chatGraceSeconds > 0 && session.lastChatAt > 0 && now < session.lastChatAt + (chatGraceSeconds * 1000L)) {
                continue;
            }
            boolean minSessionElapsed = minSessionSeconds <= 0 || now >= session.startAt + (minSessionSeconds * 1000L);
            if (now >= session.endAt || (minSessionElapsed && ThreadLocalRandom.current().nextDouble() < leaveChance)) {
                leave(name, false);
            }
        }
    }

    public void markChat(String botName) {
        if (botName == null || botName.isBlank()) {
            return;
        }
        BotSession session = sessions.get(botName.toLowerCase(Locale.ROOT));
        if (session != null) {
            session.lastChatAt = System.currentTimeMillis();
        }
    }

    private void leave(String nameKey, boolean silent) {
        BotSession session = sessions.remove(nameKey.toLowerCase(Locale.ROOT));
        if (session == null) {
            return;
        }
        botManager.despawnBot(session.bot.getName());
        int cooldownSeconds = plugin.getConfig().getInt("session_cooldown_seconds", 120);
        if (cooldownSeconds > 0) {
            cooldownUntil.put(session.bot.getName().toLowerCase(Locale.ROOT), System.currentTimeMillis() + (cooldownSeconds * 1000L));
        }
        if (!silent) {
            Bukkit.broadcastMessage(formatJoinLeaveMessage(false, session.bot.getName()));
        }
    }

    private void handleWalking(long now, int walkIntervalSeconds, int walkRadius, int cliffMaxDrop) {
        if (walkIntervalSeconds <= 0) {
            return;
        }
        if ((now - lastWalkUpdate) < (walkIntervalSeconds * 1000L)) {
            return;
        }
        lastWalkUpdate = now;

        for (BotSession session : sessions.values()) {
            FakePlayer bot = session.bot;
            Location base = bot.getLocation();
            Location target = randomNearbyLocation(base, walkRadius, cliffMaxDrop);
            if (target == null) {
                continue;
            }
            botManager.moveBot(bot, target);
        }
    }

    private Persona pickRandomAvailablePersona(long now, int sessionCooldownSeconds) {
        if (sessionCooldownSeconds > 0) {
            cooldownUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
        List<Persona> candidates = new ArrayList<>();
        for (Persona persona : personaManager.getPersonas()) {
            String key = persona.name().toLowerCase(Locale.ROOT);
            if (sessions.containsKey(key)) {
                continue;
            }
            if (sessionCooldownSeconds > 0) {
                Long until = cooldownUntil.get(key);
                if (until != null && now < until) {
                    continue;
                }
            }
            candidates.add(persona);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private String formatJoinLeaveMessage(boolean join, String playerName) {
        String key = join ? "bot_join_message" : "bot_leave_message";
        String fallback = join ? "&8[&a+&8] &f%player_name%" : "&8[&c-&8] &f%player_name%";
        String template = plugin.getConfig().getString(key, fallback);
        String resolved = template.replace("%player_name%", playerName);
        return ChatColor.translateAlternateColorCodes('&', resolved);
    }

    private Location pickAnchorLocation() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (!players.isEmpty()) {
            return players.get(ThreadLocalRandom.current().nextInt(players.size())).getLocation();
        }
        if (Bukkit.getWorlds().isEmpty()) {
            return null;
        }
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    private Location randomNearbyLocation(Location base, int radius, int cliffMaxDrop) {
        World world = base.getWorld();
        if (world == null) {
            return null;
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            int dx = ThreadLocalRandom.current().nextInt(-radius, radius + 1);
            int dz = ThreadLocalRandom.current().nextInt(-radius, radius + 1);
            int x = base.getBlockX() + dx;
            int z = base.getBlockZ() + dz;
            if (!isCliffSafe(world, x, z, cliffMaxDrop)) {
                continue;
            }
            int y = world.getHighestBlockYAt(x, z) + 1;
            return new Location(world, x + 0.5, y, z + 0.5);
        }
        return null;
    }

    private int calculateDesiredBots(int humanCount,
                                     int targetTotal,
                                     int fillRandomRange,
                                     int emptyMin,
                                     int emptyMax) {
        if (humanCount <= 0) {
            int min = Math.max(0, emptyMin);
            int max = Math.max(min, emptyMax);
            return ThreadLocalRandom.current().nextInt(min, max + 1);
        }
        int base = Math.max(0, targetTotal - humanCount);
        int jitter = ThreadLocalRandom.current().nextInt(-fillRandomRange, fillRandomRange + 1);
        return Math.max(0, base + jitter);
    }

    private Location findSpawnLocation(Location anchor,
                                       int minDistance,
                                       int maxDistance,
                                       int attempts,
                                       int cliffMaxDrop) {
        World world = anchor.getWorld();
        if (world == null) {
            return null;
        }
        int min = Math.max(0, minDistance);
        int max = Math.max(min + 1, maxDistance);

        for (int attempt = 0; attempt < attempts; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
            int distance = ThreadLocalRandom.current().nextInt(min, max + 1);
            int x = anchor.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = anchor.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            if (!isCliffSafe(world, x, z, cliffMaxDrop)) {
                continue;
            }
            int y = world.getHighestBlockYAt(x, z) + 1;
            Location candidate = new Location(world, x + 0.5, y, z + 0.5);
            if (!isFarFromPlayers(candidate, minDistance)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private boolean isFarFromPlayers(Location location, int minDistance) {
        double minDistanceSq = minDistance * minDistance;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(location.getWorld())) {
                continue;
            }
            if (player.getLocation().distanceSquared(location) < minDistanceSq) {
                return false;
            }
        }
        return true;
    }

    private boolean isCliffSafe(World world, int x, int z, int maxDrop) {
        int center = world.getHighestBlockYAt(x, z);
        int north = world.getHighestBlockYAt(x, z - 2);
        int south = world.getHighestBlockYAt(x, z + 2);
        int west = world.getHighestBlockYAt(x - 2, z);
        int east = world.getHighestBlockYAt(x + 2, z);
        return Math.abs(center - north) <= maxDrop
            && Math.abs(center - south) <= maxDrop
            && Math.abs(center - west) <= maxDrop
            && Math.abs(center - east) <= maxDrop;
    }

    private static class BotSession {
        private final FakePlayer bot;
        private final long startAt;
        private final long endAt;
        private long lastChatAt;

        private BotSession(FakePlayer bot, long startAt, long endAt) {
            this.bot = bot;
            this.startAt = startAt;
            this.endAt = endAt;
            this.lastChatAt = 0L;
        }
    }
}
