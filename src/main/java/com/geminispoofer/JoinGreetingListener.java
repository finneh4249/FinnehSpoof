package com.geminispoofer;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class JoinGreetingListener implements Listener {
    private final GeminiSpoofer plugin;

    public JoinGreetingListener(GeminiSpoofer plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.scheduleJoinGreeting(event.getPlayer().getName(), true);
    }
}
