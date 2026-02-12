package com.geminispoofer;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class FinnehSpoofCommand implements CommandExecutor {
    private final GeminiSpoofer plugin;

    public FinnehSpoofCommand(GeminiSpoofer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String prefix = "§r✦ §d§lғɪɴɴᴇʜ§5§lꜱᴘᴏᴏꜰ §7• §f";
        String usage = "§r✦ §d§lᴜꜱᴀɢᴇ §7• §f/" + label + " <reload|enable|disable>";
        if (args.length == 0) {
            sender.sendMessage(usage);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadPlugin();
            sender.sendMessage(prefix + "ʀᴇʟᴏᴀᴅᴇᴅ ᴘʟᴜɢɪɴ.");
            return true;
        }

        if (args[0].equalsIgnoreCase("enable")) {
            plugin.setBotResponsesEnabled(true);
            sender.sendMessage(prefix + "§aᴇɴᴀʙʟᴇᴅ §fʙᴏᴛ ʀᴇꜱᴘᴏɴꜱᴇꜱ.");
            return true;
        }

        if (args[0].equalsIgnoreCase("disable")) {
            plugin.setBotResponsesEnabled(false);
            sender.sendMessage(prefix + "§cᴅɪꜱᴀʙʟᴇᴅ §fʙᴏᴛ ʀᴇꜱᴘᴏɴꜱᴇꜱ.");
            return true;
        }

        sender.sendMessage(usage);
        return true;
    }
}
