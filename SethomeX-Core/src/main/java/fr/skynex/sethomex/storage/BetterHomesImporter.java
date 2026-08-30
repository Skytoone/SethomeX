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

public class BetterHomesImporter {

    private final SethomeX plugin;

    public BetterHomesImporter(SethomeX plugin) {
        this.plugin = plugin;
    }

    public void startImport(CommandSender sender) {
        File betterHomesFile = new File(plugin.getDataFolder().getParentFile(), "BetterHomes" + File.separator + "homes.yml");
        
        // Support alternative folder name
        if (!betterHomesFile.exists()) {
            betterHomesFile = new File(plugin.getDataFolder().getParentFile(), "Better_Homes" + File.separator + "homes.yml");
        }

        if (!betterHomesFile.exists()) {
            sender.sendMessage("§c[Importer] BetterHomes storage not found! Checked plugins/BetterHomes/homes.yml");
            return;
        }

        File finalFile = betterHomesFile;
        sender.sendMessage("§a[Importer] Found BetterHomes datafile. Starting migration...");

        CompletableFuture.runAsync(() -> {
            int successCount = 0;
            int failedCount = 0;

            String defaultIcon = plugin.getConfig().getString("gui.default-home-item", "RED_BED");
            String dbType = plugin.getConfig().getString("database.type", "SQLITE").toUpperCase();

            String insertQuery = "INSERT INTO sethomex_homes (player_uuid, home_name, world_name, x, y, z, yaw, pitch, icon_material) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE world_name = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ?";

            if (dbType.equals("SQLITE")) {
                insertQuery = "INSERT OR REPLACE INTO sethomex_homes (player_uuid, home_name, world_name, x, y, z, yaw, pitch, icon_material) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            }

            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(insertQuery)) {

                conn.setAutoCommit(false);
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(finalFile);

                // Format: homes.<UUID>.<HomeName>
                ConfigurationSection root = yaml.getConfigurationSection("homes");
                if (root == null) {
                    root = yaml; // Try root if 'homes' key doesn't exist
                }

                for (String uuidStr : root.getKeys(false)) {
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(uuidStr);
                    } catch (IllegalArgumentException e) {
                        continue; // Skip non-uuid keys
                    }

                    ConfigurationSection playerSection = root.getConfigurationSection(uuidStr);
                    if (playerSection == null) continue;

                    for (String homeName : playerSection.getKeys(false)) {
                        ConfigurationSection homeData = playerSection.getConfigurationSection(homeName);
                        if (homeData == null) continue;

                        String world = homeData.getString("world");
                        if (world == null) continue;

                        double x = homeData.getDouble("x");
                        double y = homeData.getDouble("y");
                        double z = homeData.getDouble("z");
                        float yaw = (float) homeData.getDouble("yaw", 0.0);
                        float pitch = (float) homeData.getDouble("pitch", 0.0);

                        try {
                            stmt.setString(1, uuid.toString());
                            stmt.setString(2, homeName);
                            stmt.setString(3, world);
                            stmt.setDouble(4, x);
                            stmt.setDouble(5, y);
                            stmt.setDouble(6, z);
                            stmt.setFloat(7, yaw);
                            stmt.setFloat(8, pitch);
                            stmt.setString(9, defaultIcon);

                            if (!dbType.equals("SQLITE")) {
                                stmt.setString(10, world);
                                stmt.setDouble(11, x);
                                stmt.setDouble(12, y);
                                stmt.setDouble(13, z);
                                stmt.setFloat(14, yaw);
                                stmt.setFloat(15, pitch);
                            }

                            stmt.addBatch();
                            successCount++;

                            if (successCount % 500 == 0) {
                                stmt.executeBatch();
                            }
                        } catch (Exception e) {
                            failedCount++;
                        }
                    }
                }

                stmt.executeBatch();
                conn.commit();

                final int total = successCount;
                final int failed = failedCount;

                plugin.getHomeManager().loadOnlinePlayers();

                plugin.getScheduler().runTask(() -> {
                    sender.sendMessage("§a§l[Importer] BetterHomes MIGRATION COMPLETED!");
                    sender.sendMessage("§f➡ Total homes imported: §e" + total);
                    if (failed > 0) sender.sendMessage("§c➡ Errors: §4" + failed);
                });

            } catch (SQLException e) {
                plugin.getLogger().severe("[Importer] DB Error BetterHomes: " + e.getMessage());
                plugin.getScheduler().runTask(() -> sender.sendMessage("§c[Importer] DB Fatal error."));
            }
        });
    }
}
