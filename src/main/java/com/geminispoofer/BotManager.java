package com.geminispoofer;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedRegistry;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

public class BotManager {
    private final JavaPlugin plugin;
    private final ProtocolManager protocolManager;
    private final Map<String, FakePlayer> bots = new ConcurrentHashMap<>();
    private final AtomicInteger entityCounter = new AtomicInteger(200_000);
    private final int playerEntityTypeId;

    public BotManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
        this.playerEntityTypeId = resolvePlayerEntityTypeId();
    }

    /**
     * Spawns a bot with the given name, skin name, and location.
     * <p>
     * If a bot with the same name already exists, it will be respawned at the given location if it is not already spawned.
     * If the bot does not exist, a new one will be created.
     * </p>
     * @param name The name of the bot to spawn.
     * @param skinName The name of the skin to use for the bot.
     * @param loc The location to spawn the bot at.
     * @return The spawned bot.
     */
    public FakePlayer spawnBot(String name, String skinName, Location loc) {
        return callSync(() -> {
            String key = name.toLowerCase(Locale.ROOT);
            FakePlayer existing = bots.get(key);
            if (existing != null) {
                existing.setLocation(loc);
                sendSpawn(existing);
                return existing;
            }

            UUID uuid = UUID.nameUUIDFromBytes(("finnehspoof:" + name).getBytes(StandardCharsets.UTF_8));
            int entityId = entityCounter.incrementAndGet();
            FakePlayer bot = new FakePlayer(name, skinName, uuid, entityId, loc);
            bot.setPing(randomPing());
            bots.put(key, bot);
            sendAddToTab(bot);
            sendSpawn(bot);
            return bot;
        });
    }

    /**
     * Despawns a bot with the given name if it exists.
     * <p>
     * If the bot does not exist, this method does nothing.
     * </p>
     * @param name The name of the bot to despawn.
     */
    public void despawnBot(String name) {
        callSync(() -> {
            String key = name.toLowerCase(Locale.ROOT);
            FakePlayer bot = bots.remove(key);
            if (bot == null) {
                return null;
            }
            sendDestroy(bot);
            sendRemoveFromTab(bot);
            return null;
        });
    }

    /**
     * Gets all currently spawned bots.
     * <p>
     * This method is thread-safe and will not throw any exceptions.
     * </p>
     * @return A list of all currently spawned bots.
     */
    public List<FakePlayer> getAllBots() {
        return callSync(() -> new ArrayList<>(bots.values()));
    }

    public boolean isActive(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String key = name.toLowerCase(Locale.ROOT);
        Boolean result = callSync(() -> bots.containsKey(key));
        return Boolean.TRUE.equals(result);
    }

    /**
     * Despawns and destroys all tracked bots.
     */
    public void despawnAll() {
        callSync(() -> {
            for (FakePlayer bot : new ArrayList<>(bots.values())) {
                sendDestroy(bot);
                sendRemoveFromTab(bot);
            }
            bots.clear();
            return null;
        });
    }

    public void syncTo(Player viewer) {
        if (viewer == null) {
            return;
        }
        callSync(() -> {
            for (FakePlayer bot : bots.values()) {
                sendAddToTab(bot, viewer);
                sendSpawn(bot, viewer);
            }
            return null;
        });
    }

    public void moveBot(FakePlayer bot, Location location) {
        if (bot == null || location == null) {
            return;
        }
        callSync(() -> {
            bot.setLocation(location);
            sendDestroy(bot);
            sendSpawn(bot);
            return null;
        });
    }

    /**
     * Runs a given callable on the primary thread, blocking until it completes.
     * <p>
     * If the callable throws an exception, it will be caught and logged to the plugin logger.
     * If the thread is interrupted while waiting for the callable to complete, it will be re-interrupted and the method will return null.
     * If the callable completes exceptionally, the method will return null and log the exception to the plugin logger.
     * </p>
     * @param action The callable to run on the primary thread.
     * @return The result of the callable, or null if an exception occurred.
     */
    private <T> T callSync(Callable<T> action) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return action.call();
            } catch (Exception exception) {
                plugin.getLogger().warning("Protocol bot call failed: " + exception.getMessage());
                return null;
            }
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(action.call());
            } catch (Exception exception) {
                future.completeExceptionally(exception);
            }
        });

        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("Protocol bot call interrupted: " + exception.getMessage());
            return null;
        } catch (ExecutionException exception) {
            plugin.getLogger().warning("Protocol bot call failed: " + exception.getCause().getMessage());
            return null;
        }
    }

    private void sendAddToTab(FakePlayer bot) {
        WrappedGameProfile profile = new WrappedGameProfile(bot.getUuid(), bot.getName());
        WrappedChatComponent displayName = WrappedChatComponent.fromText(buildTabDisplayName(bot));
        PlayerInfoData infoData = new PlayerInfoData(
            bot.getUuid(),
            bot.getPing(),
            true,
            EnumWrappers.NativeGameMode.SURVIVAL,
            profile,
            displayName
        );

        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.PLAYER_INFO);
        packet.getSpecificModifier(EnumSet.class)
            .writeSafely(0, buildPlayerInfoActions());
        List<Object> entries = List.of(PlayerInfoData.getConverter().getGeneric(infoData));
        packet.getModifier().withType(List.class).writeSafely(0, entries);

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            sendPacket(viewer, packet);
        }
    }

    private void sendAddToTab(FakePlayer bot, Player viewer) {
        WrappedGameProfile profile = new WrappedGameProfile(bot.getUuid(), bot.getName());
        WrappedChatComponent displayName = WrappedChatComponent.fromText(buildTabDisplayName(bot));
        PlayerInfoData infoData = new PlayerInfoData(
            bot.getUuid(),
            bot.getPing(),
            true,
            EnumWrappers.NativeGameMode.SURVIVAL,
            profile,
            displayName
        );

        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.PLAYER_INFO);
        packet.getSpecificModifier(EnumSet.class)
            .writeSafely(0, buildPlayerInfoActions());
        List<Object> entries = List.of(PlayerInfoData.getConverter().getGeneric(infoData));
        packet.getModifier().withType(List.class).writeSafely(0, entries);

        sendPacket(viewer, packet);
    }

    private void sendRemoveFromTab(FakePlayer bot) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
        packet.getSpecificModifier(List.class).writeSafely(0, List.of(bot.getUuid()));
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            sendPacket(viewer, packet);
        }
    }

    private void sendSpawn(FakePlayer bot) {
        Location loc = bot.getLocation();
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
        packet.getIntegers().writeSafely(0, bot.getEntityId());
        packet.getUUIDs().writeSafely(0, bot.getUuid());
        packet.getIntegers().writeSafely(1, playerEntityTypeId);
        packet.getDoubles().writeSafely(0, loc.getX());
        packet.getDoubles().writeSafely(1, loc.getY());
        packet.getDoubles().writeSafely(2, loc.getZ());
        packet.getBytes().writeSafely(0, toPackedByte(loc.getYaw()));
        packet.getBytes().writeSafely(1, toPackedByte(loc.getPitch()));
        packet.getIntegers().writeSafely(2, 0);
        packet.getShorts().writeSafely(0, (short) 0);
        packet.getShorts().writeSafely(1, (short) 0);
        packet.getShorts().writeSafely(2, (short) 0);

        PacketContainer head = protocolManager.createPacket(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
        head.getIntegers().writeSafely(0, bot.getEntityId());
        head.getBytes().writeSafely(0, toPackedByte(loc.getYaw()));

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            sendPacket(viewer, packet);
            sendPacket(viewer, head);
        }
    }

    private void sendSpawn(FakePlayer bot, Player viewer) {
        Location loc = bot.getLocation();
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
        packet.getIntegers().writeSafely(0, bot.getEntityId());
        packet.getUUIDs().writeSafely(0, bot.getUuid());
        packet.getIntegers().writeSafely(1, playerEntityTypeId);
        packet.getDoubles().writeSafely(0, loc.getX());
        packet.getDoubles().writeSafely(1, loc.getY());
        packet.getDoubles().writeSafely(2, loc.getZ());
        packet.getBytes().writeSafely(0, toPackedByte(loc.getYaw()));
        packet.getBytes().writeSafely(1, toPackedByte(loc.getPitch()));
        packet.getIntegers().writeSafely(2, 0);
        packet.getShorts().writeSafely(0, (short) 0);
        packet.getShorts().writeSafely(1, (short) 0);
        packet.getShorts().writeSafely(2, (short) 0);

        PacketContainer head = protocolManager.createPacket(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
        head.getIntegers().writeSafely(0, bot.getEntityId());
        head.getBytes().writeSafely(0, toPackedByte(loc.getYaw()));

        sendPacket(viewer, packet);
        sendPacket(viewer, head);
    }

    private void sendDestroy(FakePlayer bot) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
        if (!writeEntityDestroyIds(packet, bot.getEntityId())) {
            packet.getIntegerArrays().writeSafely(0, new int[] { bot.getEntityId() });
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            sendPacket(viewer, packet);
        }
    }

    private boolean writeEntityDestroyIds(PacketContainer packet, int entityId) {
        try {
            Class<?> intListClass = Class.forName("it.unimi.dsi.fastutil.ints.IntList");
            Class<?> intArrayListClass = Class.forName("it.unimi.dsi.fastutil.ints.IntArrayList");
            Object intList = intArrayListClass.getConstructor(int[].class).newInstance(new int[] { entityId });
            packet.getModifier().withType(intListClass).writeSafely(0, intList);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private EnumSet<?> buildPlayerInfoActions() {
        Class<?> actionClass = EnumWrappers.getPlayerInfoActionClass();
        if (actionClass == null || !actionClass.isEnum()) {
            return EnumSet.of(
                EnumWrappers.PlayerInfoAction.ADD_PLAYER,
                EnumWrappers.PlayerInfoAction.UPDATE_GAME_MODE,
                EnumWrappers.PlayerInfoAction.UPDATE_LISTED,
                EnumWrappers.PlayerInfoAction.UPDATE_LATENCY,
                EnumWrappers.PlayerInfoAction.UPDATE_DISPLAY_NAME
            );
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        EnumSet actions = EnumSet.noneOf((Class<? extends Enum>) actionClass);
        actions.add((Enum) EnumWrappers.getPlayerInfoActionConverter().getGeneric(EnumWrappers.PlayerInfoAction.ADD_PLAYER));
        actions.add((Enum) EnumWrappers.getPlayerInfoActionConverter().getGeneric(EnumWrappers.PlayerInfoAction.UPDATE_GAME_MODE));
        actions.add((Enum) EnumWrappers.getPlayerInfoActionConverter().getGeneric(EnumWrappers.PlayerInfoAction.UPDATE_LISTED));
        actions.add((Enum) EnumWrappers.getPlayerInfoActionConverter().getGeneric(EnumWrappers.PlayerInfoAction.UPDATE_LATENCY));
        actions.add((Enum) EnumWrappers.getPlayerInfoActionConverter().getGeneric(EnumWrappers.PlayerInfoAction.UPDATE_DISPLAY_NAME));
        return actions;
    }

    private String buildTabDisplayName(FakePlayer bot) {
        String format = plugin.getConfig().getString("fake_tab_display_format", "%player_name% &7Ping: &a%ping%");
        String resolved = format
            .replace("%player_name%", bot.getName())
            .replace("%ping%", Integer.toString(bot.getPing()));
        return ChatColor.translateAlternateColorCodes('&', resolved);
    }

    private int randomPing() {
        int min = Math.max(0, plugin.getConfig().getInt("fake_ping_min", 85));
        int max = Math.max(min, plugin.getConfig().getInt("fake_ping_max", 260));
        return min == max ? min : min + (int) (Math.random() * (max - min + 1));
    }

    private void sendPacket(Player player, PacketContainer packet) {
        try {
            protocolManager.sendServerPacket(player, packet);
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to send protocol packet: " + exception.getMessage());
        }
    }

    private int resolvePlayerEntityTypeId() {
        try {
            Class<?> entityTypeClass = Class.forName("net.minecraft.world.entity.EntityType");
            WrappedRegistry registry = WrappedRegistry.getRegistry(entityTypeClass);
            if (registry != null) {
                return registry.getId("minecraft:player");
            }
        } catch (ClassNotFoundException ignored) {
        }
        return 1;
    }

    private byte toPackedByte(float angle) {
        return (byte) Math.floor(angle * 256.0F / 360.0F);
    }
}
