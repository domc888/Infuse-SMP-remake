package com.infuse.util;

import com.infuse.InfusePlugin;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;

public final class Messages {

    private static final LegacyComponentSerializer SERIALIZER =
            LegacyComponentSerializer.legacyAmpersand();

    private Messages() {}

    public static Component comp(String text) {
        return SERIALIZER.deserialize(text);
    }

    public static void raw(CommandSender sender, String text) {
        sender.sendMessage(comp(text));
    }

    public static void send(CommandSender sender, InfusePlugin plugin, String key, String... replacements) {
        String prefix = plugin.getConfig().getString("prefix", "&dInfuse &7» ");
        String msg = plugin.getConfig().getString("messages." + key, "&cMissing message: " + key);
        msg = msg.replace("%prefix%", prefix);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            msg = msg.replace(replacements[i], replacements[i + 1]);
        }
        sender.sendMessage(comp(prefix + msg));
    }

    public static void sendList(CommandSender sender, InfusePlugin plugin, String key) {
        List<String> lines = plugin.getConfig().getStringList("messages." + key);
        for (String line : lines) {
            sender.sendMessage(comp(line));
        }
    }
}
