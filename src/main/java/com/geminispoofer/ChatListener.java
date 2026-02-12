package com.geminispoofer;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.List;

public class ChatListener implements Listener {
    private final GeminiSpoofer plugin;

    public ChatListener(GeminiSpoofer plugin) {
        this.plugin = plugin;
    }

    /**
     * Called when a player sends a chat message. If the player has the 'geminispoofer.ignore' permission,
     * the message will be ignored and not logged. If the message starts with a slash, it will be
     * ignored as it is likely a command. Otherwise, the message will be logged with the player's name.
     *
     * @param event the event
     */
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (event.getPlayer().hasPermission("geminispoofer.ignore")) {
            return;
        }

        String message = event.getMessage();
        if (message.startsWith("/")) {
            return;
        }

        if (isBlacklisted(message)) {
            plugin.logMessageAsync(event.getPlayer().getName(), message, "human");
            return;
        }

        plugin.logMessageAsync(event.getPlayer().getName(), message, "human");
        plugin.onHumanMessage();
        if (plugin.getConfig().getBoolean("debug_chat", false)) {
            if (shouldTriggerResponse(message)) {
                plugin.getLogger().info("[ChatDebug] Human message logged, heat increased: " + message);
            } else {
                plugin.getLogger().info("[ChatDebug] Human message logged (no trigger match), heat increased: " + message);
            }
        }
    }

    private boolean isBlacklisted(String message) {
        List<String> blacklist = plugin.getConfig().getStringList("trigger.blacklist-words");
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

    private boolean shouldTriggerResponse(String message) {
        String triggerMode = plugin.getConfig().getString("trigger.mode", "all");
        if ("all".equalsIgnoreCase(triggerMode)) {
            return true;
        }
        
        if ("mention".equalsIgnoreCase(triggerMode)) {
            return false;
        }
        
        List<String> keywords = plugin.getConfig().getStringList("trigger.keywords");
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
