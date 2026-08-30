package fr.skynex.sethomex.managers;

import fr.skynex.sethomex.SethomeX;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;

import java.io.File;

public class MessageManager {

    private final SethomeX plugin;
    private File messagesFile;
    private FileConfiguration messagesConfig;
    private String prefix;

    public MessageManager(SethomeX plugin) {
        this.plugin = plugin;
        loadMessages();
    }

    public void loadMessages() {
        if (messagesFile == null) {
            messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        }
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        fr.skynex.sethomex.util.ConfigUpdater.updateConfig(plugin, "messages.yml");
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
        prefix = messagesConfig.getString("prefix", "<yellow>[SethomeX] ");
    }

    public Component getParsedMessage(String path, boolean includePrefix, String... placeholders) {
        String raw = messagesConfig.getString(path, "§cMessage not configured: " + path);

        // Remplacement des placeholders basiques (format {0}, {1} alternatifs non
        // supportés ici, on utilise {key})
        for (int i = 0; i < placeholders.length; i += 2) {
            if (i + 1 < placeholders.length) {
                raw = raw.replace(placeholders[i], placeholders[i + 1]);
            }
        }

        raw = convertLegacyToMiniMessage(raw.replace("§", "&"));
        Component msgComponent = MiniMessage.miniMessage().deserialize(raw);

        if (includePrefix) {
            String processedPrefix = convertLegacyToMiniMessage(prefix.replace("§", "&"));
            Component prefComponent = MiniMessage.miniMessage().deserialize(processedPrefix);
            return prefComponent.append(msgComponent);
        }

        return msgComponent;
    }

    public String convertLegacyToMiniMessage(String text) {
        if (text == null)
            return "";
        return text.replace("&0", "<black>").replace("&1", "<dark_blue>").replace("&2", "<dark_green>")
                .replace("&3", "<dark_aqua>").replace("&4", "<dark_red>").replace("&5", "<dark_purple>")
                .replace("&6", "<gold>").replace("&7", "<gray>").replace("&8", "<dark_gray>")
                .replace("&9", "<blue>").replace("&a", "<green>").replace("&b", "<aqua>")
                .replace("&c", "<red>").replace("&d", "<light_purple>").replace("&e", "<yellow>")
                .replace("&f", "<white>").replace("&k", "<obfuscated>").replace("&l", "<bold>")
                .replace("&m", "<strikethrough>").replace("&n", "<underlined>").replace("&o", "<italic>")
                .replace("&r", "<reset>");
    }

    public void sendMessage(CommandSender sender, String path, String... placeholders) {
        sender.sendMessage(getParsedMessage(path, true, placeholders));
    }

    public void sendMessage(Player player, String path, String... placeholders) {
        sendMessage((CommandSender) player, path, placeholders);
    }

    public void sendActionBar(Player player, String path, String... placeholders) {
        player.sendActionBar(getParsedMessage(path, false, placeholders));
    }
}
