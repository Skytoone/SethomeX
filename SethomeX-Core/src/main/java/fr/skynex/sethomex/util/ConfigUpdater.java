package fr.skynex.sethomex.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ConfigUpdater {

    /**
     * Updates the specified configuration resource file in the plugin data folder
     * by merging missing default configuration keys and comments from the default
     * resource in the JAR file.
     *
     * @param plugin       The plugin instance.
     * @param resourceName The name of the resource (e.g. config.yml).
     */
    public static void updateConfig(JavaPlugin plugin, String resourceName) {
        File file = new File(plugin.getDataFolder(), resourceName);
        if (!file.exists()) {
            plugin.saveResource(resourceName, false);
            return;
        }

        try {
            // Load user config
            YamlConfiguration userConfig = YamlConfiguration.loadConfiguration(file);

            // Load default config from jar
            InputStream defStream = plugin.getResource(resourceName);
            if (defStream == null) return;

            YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defStream, StandardCharsets.UTF_8));

            boolean updated = mergeSections(defConfig, userConfig, "");

            if (updated) {
                userConfig.save(file);
                plugin.getLogger().info("Configuration file " + resourceName + " has been updated with new default options.");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to auto-update " + resourceName + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static boolean mergeSections(ConfigurationSection source, ConfigurationSection target, String basePath) {
        boolean changed = false;

        for (String key : source.getKeys(false)) {
            String path = basePath.isEmpty() ? key : basePath + "." + key;

            if (!target.contains(key)) {
                if (source.isConfigurationSection(key)) {
                    ConfigurationSection sourceSection = source.getConfigurationSection(key);
                    ConfigurationSection targetSection = target.createSection(key);

                    // Copy parent comments
                    List<String> comments = source.getComments(key);
                    if (comments != null && !comments.isEmpty()) {
                        target.setComments(key, comments);
                    }
                    List<String> inlineComments = source.getInlineComments(key);
                    if (inlineComments != null && !inlineComments.isEmpty()) {
                        target.setInlineComments(key, inlineComments);
                    }

                    mergeSections(sourceSection, targetSection, path);
                } else {
                    Object val = source.get(key);
                    target.set(key, val);

                    // Copy block comments
                    List<String> comments = source.getComments(key);
                    if (comments != null && !comments.isEmpty()) {
                        target.setComments(key, comments);
                    }

                    // Copy inline comments
                    List<String> inlineComments = source.getInlineComments(key);
                    if (inlineComments != null && !inlineComments.isEmpty()) {
                        target.setInlineComments(key, inlineComments);
                    }
                }
                changed = true;
            } else {
                if (source.isConfigurationSection(key)) {
                    ConfigurationSection sourceSection = source.getConfigurationSection(key);
                    ConfigurationSection targetSection = target.getConfigurationSection(key);
                    if (sourceSection != null && targetSection != null) {
                        if (mergeSections(sourceSection, targetSection, path)) {
                            changed = true;
                        }
                    }
                }
            }
        }

        return changed;
    }
}
