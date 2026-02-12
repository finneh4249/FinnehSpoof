package com.geminispoofer;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

public final class QuickChatBridge {
    private static final String[] QUICKCHAT_API_CLASSES = {
        "me.quickchat.api.QuickChatAPI",
        "com.quickchat.api.QuickChatAPI",
        "dev.quickchat.api.QuickChatAPI"
    };

    private QuickChatBridge() {
    }

    public static String formatBotMessage(Plugin plugin, GeminiSpoofer config, String playerName, String message) {
        String formatted = tryQuickChatFormat(playerName, message);
        if (formatted != null && !formatted.isBlank()) {
            return formatted;
        }
        String fallbackFormat = config.getConfig().getString("bot_chat_format", "<%player_name%> %message%");
        String resolved = fallbackFormat
            .replace("%player_name%", playerName)
            .replace("%message%", message);
        return ChatColor.translateAlternateColorCodes('&', resolved);
    }

    private static String tryQuickChatFormat(String playerName, String message) {
        if (Bukkit.getPluginManager().getPlugin("QuickChat") == null) {
            return null;
        }

        for (String className : QUICKCHAT_API_CLASSES) {
            try {
                Class<?> apiClass = Class.forName(className);
                String formatted = tryInvoke(apiClass, "format", playerName, message);
                if (formatted != null) {
                    return formatted;
                }
                formatted = tryInvoke(apiClass, "formatChat", playerName, message);
                if (formatted != null) {
                    return formatted;
                }
                formatted = tryInvoke(apiClass, "formatMessage", playerName, message);
                if (formatted != null) {
                    return formatted;
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
        return null;
    }

    private static String tryInvoke(Class<?> apiClass, String methodName, String playerName, String message) {
        try {
            Method method = apiClass.getMethod(methodName, String.class, String.class);
            Object result = method.invoke(null, playerName, message);
            if (result instanceof String formatted) {
                return formatted;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }
}
