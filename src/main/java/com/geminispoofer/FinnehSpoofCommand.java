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
        String usage = "§r✦ §d§lᴜꜱᴀɢᴇ §7• §f/" + label + " <help|reload|enable|disable>";
        if (args.length == 0) {
            sendHelp(sender, label, prefix, usage);
            return true;
        }

        if (args[0].equalsIgnoreCase("help") || args[0].equalsIgnoreCase("?")) {
            sendHelp(sender, label, prefix, usage);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadPlugin();
            sender.sendMessage(prefix + "ʀᴇʟᴏᴀᴅᴇᴅ ᴄᴏɴꜰɪɢꜱ + ᴘᴇʀꜱᴏɴᴀꜱ.");
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

        if (args[0].equalsIgnoreCase("relationships") || args[0].equalsIgnoreCase("rels")) {
            if (args.length < 2) {
                sender.sendMessage(prefix + "§7Usage: §f/" + label + " relationships <player>");
                return true;
            }
            RelationshipManager relManager = plugin.getRelationshipManager();
            if (relManager == null) {
                sender.sendMessage(prefix + "§cRelationship memory is disabled.");
                return true;
            }
            String target = args[1];
            relManager.getRelationshipInfo(target).thenAccept(lines -> {
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                    for (String line : lines) {
                        sender.sendMessage(prefix + line);
                    }
                });
            });
            return true;
        }

        sender.sendMessage(usage);
        return true;
    }

    private void sendHelp(CommandSender sender, String label, String prefix, String usage) {
        sender.sendMessage(usage);
        sender.sendMessage(prefix + "§7/" + label + " reload §f- reload configs + personalities");
        sender.sendMessage(prefix + "§7/" + label + " enable §f- enable bot responses");
        sender.sendMessage(prefix + "§7/" + label + " disable §f- disable bot responses");
        sender.sendMessage(prefix + "§7/" + label + " relationships <player> §f- view relationship data");
        sender.sendMessage(prefix + "§7Config files: §fconfig.yml, llm.yml, chat.yml, fluctuation.yml, greetings.yml, typo.yml, relationship-memory.yml");
    }
}
