package fr.skynex.sethomex.storage;

import fr.skynex.sethomex.SethomeX;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class UltimateHomesImporter {

    private final SethomeX plugin;

    public UltimateHomesImporter(SethomeX plugin) {
        this.plugin = plugin;
    }

    public void startImport(CommandSender sender) {
        File dataDir = new File(plugin.getDataFolder().getParentFile(), "UltimateHomes");
        File yamlFile = new File(dataDir, "homes.yml");
        File playersDir = new File(dataDir, "data");

        if (!yamlFile.exists() && (!playersDir.exists() || !playersDir.isDirectory())) {
            sender.sendMessage(
                    "§c[Importer] UltimateHomes storage not found! Checked plugins/UltimateHomes/homes.yml and plugins/UltimateHomes/data/");
            return;
        }

        sender.sendMessage("§a[Importer] UltimateHomes data detected. Decoupling records...");

        CompletableFuture.runAsync(() -> {
            int successCount = 0;

            String defaultIcon = plugin.getConfig().getString("gui.default-home-item", "RED_BED");
            String dbType = plugin.getConfig().getString("database.type", "SQLITE").toUpperCase();

            String query = "INSERT INTO sethomex_homes (player_uuid, home_name, world_name, x, y, z, yaw, pitch, icon_material) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE world_name = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ?";
            if (dbType.equals("SQLITE"))
                query = "INSERT OR REPLACE INTO sethomex_homes (player_uuid, home_name, world_name, x, y, z, yaw, pitch, icon_material) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(query)) {

                conn.setAutoCommit(false);

                // 1. ESSAYER LE FICHIER CENTRAL homes.yml
                if (yamlFile.exists()) {
                    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(yamlFile);
                    ConfigurationSection root = yaml.getConfigurationSection("homes");
                    if (root == null)
                        root = yaml;

                    for (String uuidKey : root.getKeys(false)) {
                        try {
                            UUID uuid = UUID.fromString(uuidKey);
                            ConfigurationSection playerSection = root.getConfigurationSection(uuidKey);
                            if (playerSection != null) {
                                successCount += processSection(playerSection, uuid, stmt, defaultIcon, dbType);
                            }
                        } catch (Exception e) {
                            // skip
                        }
                    }
                }

                // 2. ESSAYER LES FICHIERS INDIVIDUELS
                if (playersDir.exists() && playersDir.isDirectory()) {
                    File[] files = playersDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".yml"));
                    if (files != null) {
                        for (File f : files) {
                            try {
                                String name = f.getName().replace(".yml", "");
                                UUID uuid = UUID.fromString(name);
                                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);
                                ConfigurationSection homes = yaml.getConfigurationSection("homes");
                                if (homes == null)
                                    homes = yaml;
                                successCount += processSection(homes, uuid, stmt, defaultIcon, dbType);
                            } catch (Exception e) {
                                // skip
                            }
                        }
                    }
                }

                stmt.executeBatch();
                conn.commit();

                plugin.getHomeManager().loadOnlinePlayers();

                final int total = successCount;
                plugin.getScheduler().runTask(() -> {
                    sender.sendMessage("§a§l[Importer] UltimateHomes MIGRATION COMPLETED!");
                    sender.sendMessage("§f➡ Loaded: §e" + total + " §fhomes.");
                });

            } catch (Exception e) {
                plugin.getLogger().severe("[Importer] UltimateHomes failed: " + e.getMessage());
            }
        });
    }

    private int processSection(ConfigurationSection section, UUID uuid, PreparedStatement stmt, String icon,
            String type) throws SQLException {
        int added = 0;
        for (String key : section.getKeys(false)) {
            ConfigurationSection data = section.getConfigurationSection(key);
            if (data == null)
                continue;

            String world = data.getString("world");
            if (world == null)
                continue;

            stmt.setString(1, uuid.toString());
            stmt.setString(2, key);
            stmt.setString(3, world);
            stmt.setDouble(4, data.getDouble("x"));
            stmt.setDouble(5, data.getDouble("y"));
            stmt.setDouble(6, data.getDouble("z"));
            stmt.setFloat(7, (float) data.getDouble("yaw", 0));
            stmt.setFloat(8, (float) data.getDouble("pitch", 0));
            stmt.setString(9, icon);

            if (!type.equals("SQLITE")) {
                stmt.setString(10, world);
                stmt.setDouble(11, data.getDouble("x"));
                stmt.setDouble(12, data.getDouble("y"));
                stmt.setDouble(13, data.getDouble("z"));
                stmt.setFloat(14, (float) data.getDouble("yaw", 0));
                stmt.setFloat(15, (float) data.getDouble("pitch", 0));
            }
            stmt.addBatch();
            added++;
        }
        return added;
    }
}
