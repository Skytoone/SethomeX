package fr.skynex.sethomex.managers;

import fr.skynex.sethomex.SethomeX;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class GUIManager {

    private final SethomeX plugin;
    private File guiFile;
    private FileConfiguration guiConfig;

    public GUIManager(SethomeX plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        if (guiFile == null) {
            guiFile = new File(plugin.getDataFolder(), "gui.yml");
        }
        if (!guiFile.exists()) {
            plugin.saveResource("gui.yml", false);
        }
        fr.skynex.sethomex.util.ConfigUpdater.updateConfig(plugin, "gui.yml");
        guiConfig = YamlConfiguration.loadConfiguration(guiFile);
    }

    public FileConfiguration getConfig() {
        return guiConfig;
    }

    /**
     * Récupère un texte et le formate en Adventure Component (MiniMessage ou
     * Legacy)
     */
    public Component getComponent(String path, String... placeholders) {
        String raw = guiConfig.getString(path, path);
        return parseString(raw, placeholders);
    }

    /**
     * Récupère une liste de lores et formate le tout
     */
    public List<Component> getLore(String path, String... placeholders) {
        List<String> rawList = guiConfig.getStringList(path);
        if (rawList.isEmpty()) {
            return new ArrayList<>();
        }

        List<Component> components = new ArrayList<>();
        for (String line : rawList) {
            components.add(parseString(line, placeholders));
        }
        return components;
    }

    public String getRawString(String path, String def) {
        return guiConfig.getString(path, def);
    }

    private Component parseString(String raw, String... placeholders) {
        // Remplacement des placeholders
        for (int i = 0; i < placeholders.length; i += 2) {
            if (i + 1 < placeholders.length) {
                raw = raw.replace(placeholders[i], placeholders[i + 1]);
            }
        }

        raw = convertLegacyToMiniMessage(raw.replace("§", "&"));
        return MiniMessage.miniMessage().deserialize(raw);
    }

    private String convertLegacyToMiniMessage(String text) {
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
}
