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

public class EssentialsImporter {

    private final SethomeX plugin;

    public EssentialsImporter(SethomeX plugin) {
        this.plugin = plugin;
    }

    /**
     * Commence l'importation asynchrone des homes depuis EssentialsX.
     */
    public void startImport(CommandSender sender) {
        File essDataFolder = new File(plugin.getDataFolder().getParentFile(),
                "Essentials" + File.separator + "userdata");

        if (!essDataFolder.exists() || !essDataFolder.isDirectory()) {
            sender.sendMessage(
                    "§c[Importer] Essentials userdata folder not found! Expected: " + essDataFolder.getPath());
            return;
        }

        File[] userFiles = essDataFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".yml"));
        if (userFiles == null || userFiles.length == 0) {
            sender.sendMessage("§c[Importer] No user data files (.yml) found in Essentials folder.");
            return;
        }

        sender.sendMessage(
                "§a[Importer] Found " + userFiles.length + " player files. Starting migration asynchronously...");

        CompletableFuture.runAsync(() -> {
            int successCount = 0;
            int failedCount = 0;
            int skippedCount = 0;

            String defaultIcon = plugin.getConfig().getString("gui.default-home-item", "RED_BED");
            String dbType = plugin.getConfig().getString("database.type", "SQLITE").toUpperCase();

            String insertQuery = "INSERT INTO sethomex_homes (player_uuid, home_name, world_name, x, y, z, yaw, pitch, icon_material) "
                    +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE world_name = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ?";

            if (dbType.equals("SQLITE")) {
                insertQuery = "INSERT OR REPLACE INTO sethomex_homes (player_uuid, home_name, world_name, x, y, z, yaw, pitch, icon_material) "
                        +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            }

            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(insertQuery)) {

                // Désactivation autocommit pour optimiser si nécessaire (gros volumes)
                conn.setAutoCommit(false);

                for (File file : userFiles) {
                    String fileName = file.getName();
                    String uuidStr = fileName.substring(0, fileName.lastIndexOf('.'));

                    UUID uuid;
                    try {
                        uuid = UUID.fromString(uuidStr);
                    } catch (IllegalArgumentException e) {
                        // Peut-être un ancien nom de compte ou fichier corrompu
                        skippedCount++;
                        continue;
                    }

                    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

                    // Chargement de la section "homes" (Contient tous les homes Y COMPRIS "bed"
                    // chez Essentials !)
                    ConfigurationSection homesSection = yaml.getConfigurationSection("homes");
                    if (homesSection == null) {
                        continue;
                    }

                    for (String homeName : homesSection.getKeys(false)) {
                        ConfigurationSection h = homesSection.getConfigurationSection(homeName);
                        if (h == null)
                            continue;

                        String worldName = h.getString("world");
                        if (worldName == null)
                            continue;

                        double x = h.getDouble("x");
                        double y = h.getDouble("y");
                        double z = h.getDouble("z");
                        float yaw = (float) h.getDouble("yaw", 0.0);
                        float pitch = (float) h.getDouble("pitch", 0.0);

                        try {
                            stmt.setString(1, uuid.toString());
                            stmt.setString(2, homeName);
                            stmt.setString(3, worldName);
                            stmt.setDouble(4, x);
                            stmt.setDouble(5, y);
                            stmt.setDouble(6, z);
                            stmt.setFloat(7, yaw);
                            stmt.setFloat(8, pitch);
                            stmt.setString(9, defaultIcon);

                            if (!dbType.equals("SQLITE")) {
                                stmt.setString(10, worldName);
                                stmt.setDouble(11, x);
                                stmt.setDouble(12, y);
                                stmt.setDouble(13, z);
                                stmt.setFloat(14, yaw);
                                stmt.setFloat(15, pitch);
                            }

                            stmt.addBatch();
                            successCount++;

                            // Flush régulier du batch pour pas saturer la mémoire ram
                            if (successCount % 500 == 0) {
                                stmt.executeBatch();
                            }
                        } catch (Exception e) {
                            failedCount++;
                        }
                    }
                }

                // Exécution finale
                stmt.executeBatch();
                conn.commit();

                final int totalSuccess = successCount;
                final int totalFailed = failedCount;
                final int totalSkipped = skippedCount;

                // Rechargement du cache principal pour refléter l'import instantanément !
                plugin.getHomeManager().loadOnlinePlayers();

                plugin.getScheduler().runTask(() -> {
                    sender.sendMessage("§a§l[Importer] MIGRATION COMPLETED!");
                    sender.sendMessage("§f➡ Total homes imported: §e" + totalSuccess);
                    if (totalSkipped > 0) {
                        sender.sendMessage("§7➡ Total files skipped (legacy/archaic): §f" + totalSkipped);
                    }
                    if (totalFailed > 0) {
                        sender.sendMessage("§c➡ Total failed records: §4" + totalFailed);
                    }
                    sender.sendMessage("§7(Live cache has been automatically updated!)");
                });

            } catch (SQLException e) {
                plugin.getLogger().severe("[Importer] Database error during import: " + e.getMessage());
                plugin.getScheduler().runTask(() -> {
                    sender.sendMessage("§c[Importer] Fatal Error: Check console for trace.");
                });
            }
        });
    }
}
