package com.geminispoofer;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Method;

public final class PaperChatListener {
    private PaperChatListener() {
    }

    public static void register(GeminiSpoofer plugin) {
        try {
            Class<?> asyncChatEventClass = Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
            Listener listener = new Listener() {
            };
            PluginManager pluginManager = plugin.getServer().getPluginManager();
            pluginManager.registerEvent(
                asEventClass(asyncChatEventClass),
                listener,
                EventPriority.MONITOR,
                (unused, event) -> handleEvent(plugin, event),
                plugin,
                true
            );
            plugin.getLogger().info("Hooked Paper AsyncChatEvent for chat logging.");
        } catch (ClassNotFoundException ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Event> asEventClass(Class<?> candidate) {
        return (Class<? extends Event>) candidate;
    }

    private static void handleEvent(GeminiSpoofer plugin, Event event) {
        if (event == null) {
            return;
        }

        if (isCancelled(event) && plugin.getConfig().getBoolean("debug_chat", false)) {
            plugin.getLogger().info("[ChatDebug] AsyncChatEvent was cancelled; still logging for heat/context.");
        }

        Player player = extractPlayer(event);
        if (player == null || player.hasPermission("geminispoofer.ignore")) {
            return;
        }

        String message = extractMessage(event);
        if (message == null || message.isBlank() || message.startsWith("/")) {
            return;
        }

        if (isBlacklisted(plugin, message)) {
            plugin.logMessageAsync(player.getName(), message, "human");
            return;
        }

        plugin.logMessageAsync(player.getName(), message, "human");
        plugin.onHumanMessage();
        if (plugin.getConfig().getBoolean("debug_chat", false)) {
            if (shouldTriggerResponse(plugin, message)) {
                plugin.getLogger().info("[ChatDebug] Human message logged, heat increased: " + message);
            } else {
                plugin.getLogger().info("[ChatDebug] Human message logged (no trigger match), heat increased: " + message);
            }
        }
    }

    private static boolean isCancelled(Event event) {
        try {
            Method method = event.getClass().getMethod("isCancelled");
            Object result = method.invoke(event);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static Player extractPlayer(Event event) {
        try {
            Method method = event.getClass().getMethod("getPlayer");
            Object result = method.invoke(event);
            if (result instanceof Player player) {
                return player;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static String extractMessage(Event event) {
        Object component = invokeIfPresent(event, "message");
        if (component == null) {
            component = invokeIfPresent(event, "originalMessage");
        }
        String plain = extractPlainText(component);
        if (plain != null) {
            return plain;
        }
        Object legacy = invokeIfPresent(event, "getMessage");
        if (legacy instanceof String legacyMessage) {
            return legacyMessage;
        }
        return null;
    }

    private static String extractPlainText(Object component) {
        if (component == null) {
            return null;
        }
        try {
            Class<?> serializerClass = Class.forName("net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer");
            Method plainTextMethod = serializerClass.getMethod("plainText");
            Object serializer = plainTextMethod.invoke(null);
            if (serializer == null) {
                return component.toString();
            }
            Method serializeMethod = serializer.getClass().getMethod("serialize", component.getClass());
            Object result = serializeMethod.invoke(serializer, component);
            if (result instanceof String plain) {
                return plain;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return component.toString();
    }

    private static Object invokeIfPresent(Event event, String methodName) {
        try {
            Method method = event.getClass().getMethod(methodName);
            return method.invoke(event);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static boolean isBlacklisted(GeminiSpoofer plugin, String message) {
        java.util.List<String> blacklist = plugin.getConfig().getStringList("trigger.blacklist-words");
        if (blacklist.isEmpty()) {
            return false;
        }
        String lowerMessage = message.toLowerCase();
        for (String word : blacklist) {
            if (lowerMessage.contains(word.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldTriggerResponse(GeminiSpoofer plugin, String message) {
        String triggerMode = plugin.getConfig().getString("trigger.mode", "all");
        if ("all".equalsIgnoreCase(triggerMode)) {
            return true;
        }
        
        if ("mention".equalsIgnoreCase(triggerMode)) {
            return false;
        }
        
        java.util.List<String> keywords = plugin.getConfig().getStringList("trigger.keywords");
        if (keywords.isEmpty()) {
            return false;
        }
        String lowerMessage = message.toLowerCase();
        for (String keyword : keywords) {
            if (lowerMessage.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
