package fr.skynex.sethomex.storage;

import fr.skynex.sethomex.SethomeX;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class SunlightImporter {

    private final SethomeX plugin;

    public SunlightImporter(SethomeX plugin) {
        this.plugin = plugin;
    }

    public void startImport(CommandSender sender) {
        File dir = new File(plugin.getDataFolder().getParentFile(), "SunLight");
        
        // Sunlight store database in 'database.db' or inside NightCore
        File dbFile = new File(dir, "database.db");
        if (!dbFile.exists()) {
            dbFile = new File(dir, "data" + File.separator + "database.db");
        }

        if (!dbFile.exists()) {
            sender.sendMessage("§c[Importer] SunLight database file not found. Checked plugins/SunLight/database.db");
            return;
        }

        File finalDb = dbFile;
        sender.sendMessage("§a[Importer] SunLight DB found. Opening reflective stream...");

        CompletableFuture.runAsync(() -> {
            int count = 0;

            String defIcon = plugin.getConfig().getString("gui.default-home-item", "RED_BED");
            String dbType = plugin.getConfig().getString("database.type", "SQLITE").toUpperCase();

            String insert = "INSERT INTO sethomex_homes (player_uuid, home_name, world_name, x, y, z, yaw, pitch, icon_material) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE world_name = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ?";
            if (dbType.equals("SQLITE")) insert = "INSERT OR REPLACE INTO sethomex_homes (player_uuid, home_name, world_name, x, y, z, yaw, pitch, icon_material) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

            String url = "jdbc:sqlite:" + finalDb.getAbsolutePath();

            try (Connection sunConn = DriverManager.getConnection(url);
                 Statement sunStmt = sunConn.createStatement();
                 ResultSet rs = sunStmt.executeQuery("SELECT * FROM sunlight_homes");
                 Connection target = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ins = target.prepareStatement(insert)) {

                ResultSetMetaData meta = rs.getMetaData();
                boolean hasLoc = hasColumn(meta, "location");
                boolean hasPos = hasColumn(meta, "position");

                target.setAutoCommit(false);

                while (rs.next()) {
                    try {
                        String name = rs.getString("homeId");
                        String ownerStr = rs.getString("ownerId");
                        if (name == null || ownerStr == null) continue;
                        UUID uuid = UUID.fromString(ownerStr);

                        String world = "";
                        double x = 0, y = 0, z = 0;
                        float yaw = 0f, pitch = 0f;

                        // Fallback architecture pour s'adapter aux différentes versions de SunLight
                        if (hasLoc) {
                            // Format: "world;x;y;z;yaw;pitch"
                            String rawLoc = rs.getString("location");
                            if (rawLoc != null && !rawLoc.isEmpty()) {
                                String[] parts = rawLoc.split(";");
                                if (parts.length >= 4) {
                                    world = parts[0];
                                    x = Double.parseDouble(parts[1]);
                                    y = Double.parseDouble(parts[2]);
                                    z = Double.parseDouble(parts[3]);
                                    if (parts.length >= 6) {
                                        yaw = Float.parseFloat(parts[4]);
                                        pitch = Float.parseFloat(parts[5]);
                                    }
                                }
                            }
                        } else {
                            world = rs.getString("world");
                            if (hasPos) {
                                String[] p = rs.getString("position").split(",");
                                x = Double.parseDouble(p[0]);
                                y = Double.parseDouble(p[1]);
                                z = Double.parseDouble(p[2]);
                            } else {
                                // Essayer direct columns
                                x = rs.getDouble("x");
                                y = rs.getDouble("y");
                                z = rs.getDouble("z");
                            }
                            // Essayer rotation
                            try { yaw = rs.getFloat("yaw"); } catch (Exception e) {}
                            try { pitch = rs.getFloat("pitch"); } catch (Exception e) {}
                        }

                        if (world == null || world.isEmpty()) continue;

                        ins.setString(1, uuid.toString());
                        ins.setString(2, name);
                        ins.setString(3, world);
                        ins.setDouble(4, x);
                        ins.setDouble(5, y);
                        ins.setDouble(6, z);
                        ins.setFloat(7, yaw);
                        ins.setFloat(8, pitch);
                        ins.setString(9, defIcon);

                        if (!dbType.equals("SQLITE")) {
                            ins.setString(10, world);
                            ins.setDouble(11, x);
                            ins.setDouble(12, y);
                            ins.setDouble(13, z);
                            ins.setFloat(14, yaw);
                            ins.setFloat(15, pitch);
                        }

                        ins.addBatch();
                        count++;
                        
                        if (count % 500 == 0) ins.executeBatch();

                    } catch (Exception e) {
                        // Continue iterative Loop on specific record errors
                    }
                }

                ins.executeBatch();
                target.commit();
                plugin.getHomeManager().loadOnlinePlayers();

                final int finalCount = count;
                plugin.getScheduler().runTask(() -> {
                    sender.sendMessage("§a§l[Importer] SunLight MIGRATION COMPLETED!");
                    sender.sendMessage("§f➡ Extracted: §e" + finalCount + " §fhomes successfully.");
                });

            } catch (Exception e) {
                plugin.getLogger().severe("[SunlightImporter] Fatal Error: " + e.getMessage());
                plugin.getScheduler().runTask(() -> sender.sendMessage("§c[Importer] Crash analyzing SunLight database. See console."));
            }
        });
    }

    private boolean hasColumn(ResultSetMetaData metaData, String columnName) throws SQLException {
        int count = metaData.getColumnCount();
        for (int i = 1; i <= count; i++) {
            if (metaData.getColumnName(i).equalsIgnoreCase(columnName)) {
                return true;
            }
        }
        return false;
    }
}
