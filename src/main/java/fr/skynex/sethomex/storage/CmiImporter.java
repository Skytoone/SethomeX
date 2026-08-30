package fr.skynex.sethomex.storage;

import fr.skynex.sethomex.SethomeX;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class CmiImporter {

    private final SethomeX plugin;

    public CmiImporter(SethomeX plugin) {
        this.plugin = plugin;
    }

    public void startImport(CommandSender sender) {
        File cmiFolder = new File(plugin.getDataFolder().getParentFile(), "CMI");
        
        // Localiser le fichier SQLite de CMI (Peut s'appeler cmi.db ou users.db)
        File dbFile = new File(cmiFolder, "cmi.db");
        if (!dbFile.exists()) {
            dbFile = new File(cmiFolder, "users.db");
        }
        if (!dbFile.exists()) {
            dbFile = new File(cmiFolder, "CMI.db");
        }

        if (!dbFile.exists()) {
            sender.sendMessage("§c[Importer] CMI SQLite file not found in plugins/CMI/. Checked cmi.db, users.db.");
            sender.sendMessage("§eNote: If you use MySQL for CMI, this direct importer cannot reach it locally.");
            return;
        }

        File finalDb = dbFile;
        sender.sendMessage("§a[Importer] Found CMI database (" + finalDb.getName() + "). Injecting secondary connection...");

        CompletableFuture.runAsync(() -> {
            int totalImported = 0;

            String defaultIcon = plugin.getConfig().getString("gui.default-home-item", "RED_BED");
            String dbType = plugin.getConfig().getString("database.type", "SQLITE").toUpperCase();

            String queryInsert = "INSERT INTO sethomex_homes (player_uuid, home_name, world_name, x, y, z, yaw, pitch, icon_material) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE world_name = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ?";
            if (dbType.equals("SQLITE")) queryInsert = "INSERT OR REPLACE INTO sethomex_homes (player_uuid, home_name, world_name, x, y, z, yaw, pitch, icon_material) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

            String cmiJdbcUrl = "jdbc:sqlite:" + finalDb.getAbsolutePath();

            try (Connection cmiConn = DriverManager.getConnection(cmiJdbcUrl);
                 Statement cmiStmt = cmiConn.createStatement();
                 ResultSet rs = cmiStmt.executeQuery("SELECT uuid, Homes FROM users WHERE Homes IS NOT NULL AND Homes != ''");
                 Connection mainConn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement insertStmt = mainConn.prepareStatement(queryInsert)) {

                mainConn.setAutoCommit(false);

                while (rs.next()) {
                    String uuidStr = rs.getString("uuid");
                    String rawHomes = rs.getString("Homes");

                    if (uuidStr == null || rawHomes == null || rawHomes.isEmpty()) continue;

                    UUID uuid;
                    try {
                        uuid = UUID.fromString(uuidStr);
                    } catch (Exception e) {
                        continue;
                    }

                    // Format CMI: "home1%%world:x:y:z:pitch:yaw;home2%%world:x:y:z:pitch:yaw"
                    String[] homeEntries = rawHomes.split(";");
                    for (String entry : homeEntries) {
                        if (!entry.contains("%%")) continue;

                        String[] parts = entry.split("%%");
                        if (parts.length < 2) continue;

                        String homeName = parts[0];
                        String[] loc = parts[1].split(":"); // world:x:y:z:pitch:yaw (CMI format)
                        
                        if (loc.length < 4) continue;

                        try {
                            String world = loc[0];
                            double x = Double.parseDouble(loc[1]);
                            double y = Double.parseDouble(loc[2]);
                            double z = Double.parseDouble(loc[3]);
                            
                            float pitch = loc.length > 4 ? Float.parseFloat(loc[4]) : 0.0f;
                            float yaw = loc.length > 5 ? Float.parseFloat(loc[5]) : 0.0f;

                            insertStmt.setString(1, uuid.toString());
                            insertStmt.setString(2, homeName);
                            insertStmt.setString(3, world);
                            insertStmt.setDouble(4, x);
                            insertStmt.setDouble(5, y);
                            insertStmt.setDouble(6, z);
                            insertStmt.setFloat(7, yaw); // Inverse pitch/yaw to Bukkit standard ? Note: CMI saves it pitch then yaw usually.
                            insertStmt.setFloat(8, pitch);
                            insertStmt.setString(9, defaultIcon);

                            if (!dbType.equals("SQLITE")) {
                                insertStmt.setString(10, world);
                                insertStmt.setDouble(11, x);
                                insertStmt.setDouble(12, y);
                                insertStmt.setDouble(13, z);
                                insertStmt.setFloat(14, yaw);
                                insertStmt.setFloat(15, pitch);
                            }
                            
                            insertStmt.addBatch();
                            totalImported++;

                            if (totalImported % 500 == 0) {
                                insertStmt.executeBatch();
                            }
                        } catch (Exception e) {
                            // Parsing coord errors skipped
                        }
                    }
                }

                insertStmt.executeBatch();
                mainConn.commit();
                
                plugin.getHomeManager().loadOnlinePlayers();

                final int total = totalImported;
                plugin.getScheduler().runTask(() -> {
                    sender.sendMessage("§a§l[Importer] CMI MIGRATION COMPLETED!");
                    sender.sendMessage("§f➡ " + total + " homes transferred from direct SQLite connection.");
                });

            } catch (Exception e) {
                plugin.getLogger().severe("[Importer] CMI Error: " + e.getMessage());
                plugin.getScheduler().runTask(() -> sender.sendMessage("§c[Importer] Technical error querying CMI. Exception was logged."));
            }
        });
    }
}
