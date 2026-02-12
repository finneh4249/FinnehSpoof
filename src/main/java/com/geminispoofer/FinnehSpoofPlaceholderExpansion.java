package com.geminispoofer;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class FinnehSpoofPlaceholderExpansion extends PlaceholderExpansion {
    private final GeminiSpoofer plugin;

    public FinnehSpoofPlaceholderExpansion(GeminiSpoofer plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "finnehspoof";
    }

    @Override
    public String getAuthor() {
        return "FinnehSpoof";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (params == null) {
            return "";
        }

        int bots = plugin.getBotCount();
        int humans = Bukkit.getOnlinePlayers().size();

        switch (params.toLowerCase()) {
            case "bots":
                return Integer.toString(bots);
            case "humans":
                return Integer.toString(humans);
            case "online":
            case "online_total":
                return Integer.toString(bots + humans);
            default:
                return "";
        }
    }
}
