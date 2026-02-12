package com.geminispoofer;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

import java.lang.reflect.Method;

public class ServerListPingListener implements Listener {
    private final GeminiSpoofer plugin;

    public ServerListPingListener(GeminiSpoofer plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPing(ServerListPingEvent event) {
        if (!plugin.getConfig().getBoolean("server-list.enabled", true)) {
            return;
        }

        int bots = plugin.getBotCount();
        if (bots <= 0) {
            return;
        }

        if (plugin.getConfig().getBoolean("server-list.add-to-online-count", true)) {
            trySetNumPlayers(event, event.getNumPlayers() + bots);
        }
    }

    private void trySetNumPlayers(ServerListPingEvent event, int count) {
        try {
            Method method = event.getClass().getMethod("setNumPlayers", int.class);
            method.invoke(event, count);
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
