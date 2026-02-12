package com.geminispoofer;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class FakePlayerJoinListener implements Listener {
    private final BotManager botManager;

    public FakePlayerJoinListener(BotManager botManager) {
        this.botManager = botManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        botManager.syncTo(event.getPlayer());
    }
}
