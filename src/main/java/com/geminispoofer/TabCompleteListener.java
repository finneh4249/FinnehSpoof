package com.geminispoofer;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class TabCompleteListener {
    private TabCompleteListener() {
    }

    public static void register(GeminiSpoofer plugin, BotManager botManager) {
        try {
            Class<?> eventClass = Class.forName("com.destroystokyo.paper.event.server.AsyncTabCompleteEvent");
            Listener listener = new Listener() {
            };
            PluginManager pluginManager = plugin.getServer().getPluginManager();
            pluginManager.registerEvent(
                asEventClass(eventClass),
                listener,
                EventPriority.HIGHEST,
                (unused, event) -> handleEvent(plugin, botManager, event),
                plugin,
                true
            );
            plugin.getLogger().info("Hooked Paper AsyncTabCompleteEvent for tab completion.");
        } catch (ClassNotFoundException ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Event> asEventClass(Class<?> candidate) {
        return (Class<? extends Event>) candidate;
    }

    private static void handleEvent(GeminiSpoofer plugin, BotManager botManager, Event event) {
        if (!plugin.getConfig().getBoolean("tab-completion.inject-fakes", true)) {
            return;
        }

        String buffer = getBuffer(event);
        if (buffer == null || buffer.isEmpty()) {
            return;
        }

        String[] parts = buffer.split(" ");
        if (parts.length == 0) {
            return;
        }

        String command = parts[0].toLowerCase();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }

        List<String> excludeCommands = plugin.getConfig().getStringList("tab-completion.exclude-commands");
        for (String excluded : excludeCommands) {
            if (command.equalsIgnoreCase(excluded)) {
                return;
            }
        }

        List<String> fakeNames = new ArrayList<>();
        for (FakePlayer bot : botManager.getAllBots()) {
            if (bot != null && bot.getName() != null) {
                fakeNames.add(bot.getName());
            }
        }

        if (fakeNames.isEmpty()) {
            return;
        }

        List<String> completions = getCompletions(event);
        if (completions != null) {
            completions.addAll(fakeNames);
        }
    }

    private static String getBuffer(Event event) {
        try {
            Method method = event.getClass().getMethod("getBuffer");
            Object result = method.invoke(event);
            if (result instanceof String) {
                return (String) result;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> getCompletions(Event event) {
        try {
            Method method = event.getClass().getMethod("getCompletions");
            Object result = method.invoke(event);
            if (result instanceof List) {
                return (List<String>) result;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }
}
