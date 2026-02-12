package com.geminispoofer;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

public final class DiscordSrvBridge {
    private DiscordSrvBridge() {
    }

    public static void sendBotChat(Plugin plugin, String message) {
        if (plugin == null || message == null || message.isBlank()) {
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("DiscordSRV") == null) {
            return;
        }

        try {
            Class<?> discordSrvClass = Class.forName("github.scarsz.discordsrv.DiscordSRV");
            Method getPluginMethod = discordSrvClass.getMethod("getPlugin");
            Object discordSrv = getPluginMethod.invoke(null);
            if (discordSrv == null) {
                return;
            }

            if (trySendMainTextChannel(discordSrv, message)) {
                return;
            }

            if (tryProcessChatMessage(discordSrv, message)) {
                return;
            }

            trySendViaJda(discordSrv, message);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static boolean trySendMainTextChannel(Object discordSrv, String message) {
        try {
            Method method = discordSrv.getClass().getMethod("sendMessageToMainTextChannel", String.class);
            method.invoke(discordSrv, message);
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static boolean tryProcessChatMessage(Object discordSrv, String message) {
        try {
            Method method = discordSrv.getClass().getMethod("processChatMessage", org.bukkit.entity.Player.class, String.class);
            method.invoke(discordSrv, null, message);
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static void trySendViaJda(Object discordSrv, String message) {
        try {
            Method method = discordSrv.getClass().getMethod("getMainTextChannel");
            Object channel = method.invoke(discordSrv);
            if (channel == null) {
                return;
            }
            Method sendMessageMethod = channel.getClass().getMethod("sendMessage", CharSequence.class);
            Object action = sendMessageMethod.invoke(channel, message);
            if (action == null) {
                return;
            }
            Method queueMethod = action.getClass().getMethod("queue");
            queueMethod.invoke(action);
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
