package fr.skynex.sethomex.managers;

import fr.skynex.sethomex.SethomeX;
import fr.skynex.sethomex.models.Home;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

public class HomeManager {

    private final SethomeX plugin;
    private final Cache<UUID, Map<String, Home>> cache;
    private final Map<UUID, List<Home>> resolvedFavorites = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> rentedSlotsCache = new ConcurrentHashMap<>();
    private final Map<UUID, String> nameCache = new ConcurrentHashMap<>();
    
    // Pool dédié pour garantir que les écritures SQL ne sont pas interrompues
    // prématurément lors du slash stop
    private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor();

    public HomeManager(SethomeX plugin) {
        this.plugin = plugin;
        int retention = plugin.getConfig().getInt("performance.cache-retention-minutes", 30);
        this.cache = Caffeine.newBuilder()
                .expireAfterAccess(retention, TimeUnit.MINUTES)
                .recordStats()
                .build();
        
        // Tâche de nettoyage périodique des trusts expirés (toutes les 5 minutes)
        plugin.getScheduler().runTaskTimer(this::cleanExpiredTrusts, 1200L, 6000L);
    }

    public Cache<UUID, Map<String, Home>> getCache() {
        return cache;
    }

    /**
     * Charge les homes et trusts d'un joueur en cache (Appelé à la connexion)
     */
    public void loadPlayerHomes(UUID playerUuid) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null) {
            nameCache.put(playerUuid, player.getName());
        }
        databaseExecutor.execute(() -> {
            loadRentedSlots(playerUuid);
            Map<String, Home> playerHomes = new ConcurrentHashMap<>();
            String queryHomes = "SELECT h.*, (SELECT COUNT(*) FROM sethomex_likes l WHERE l.owner_uuid = h.player_uuid AND LOWER(l.home_name) = LOWER(h.home_name)) as likes_count FROM sethomex_homes h WHERE h.player_uuid = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(queryHomes)) {
                stmt.setString(1, playerUuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Home home = extractHomeFromResultSet(rs);
                        playerHomes.put(home.getName().toLowerCase(), home);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Impossible de charger les habitations du joueur : " + e.getMessage());
            }

            // Chargement des trusts pour ces homes
            String queryTrusts = "SELECT home_name, guest_uuid, expires_at, role FROM sethomex_trusts WHERE owner_uuid = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(queryTrusts)) {
                stmt.setString(1, playerUuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String homeName = rs.getString("home_name").toLowerCase();
                        UUID guest = UUID.fromString(rs.getString("guest_uuid"));
                        long expiresAt = rs.getLong("expires_at");
                        String role = rs.getString("role");
                        Home home = playerHomes.get(homeName);
                        if (home != null) {
                            home.addTrust(guest, expiresAt, role);
                        }
                    }
                }
            } catch (SQLException | IllegalArgumentException e) {
                plugin.getLogger().warning("Impossible de charger les trusts du joueur : " + e.getMessage());
            }

            // Chargement des bans pour ces homes
            String queryBans = "SELECT home_name, banned_uuid FROM sethomex_bans WHERE owner_uuid = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(queryBans)) {
                stmt.setString(1, playerUuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String homeName = rs.getString("home_name").toLowerCase();
                        UUID banned = UUID.fromString(rs.getString("banned_uuid"));
                        Home home = playerHomes.get(homeName);
                        if (home != null) {
                            home.banPlayer(banned);
                        }
                    }
                }
            } catch (SQLException | IllegalArgumentException e) {
                plugin.getLogger().warning("Impossible de charger les bans du joueur : " + e.getMessage());
            }

            // Chargement des préférences de cosmétiques
            String queryCosmetics = "SELECT selected_particle, selected_style, selected_sound, selected_success_sound FROM sethomex_users WHERE player_uuid = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(queryCosmetics)) {
                stmt.setString(1, playerUuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String particle = rs.getString("selected_particle");
                        String style = rs.getString("selected_style");
                        String sound = rs.getString("selected_sound");
                        String successSound = rs.getString("selected_success_sound");
                        plugin.getTeleportManager().setPlayerCosmetics(playerUuid, particle, style, sound, successSound);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Impossible de charger les cosmétiques du joueur : " + e.getMessage());
            }

            // Chargement des favoris
            List<Home> favorites = new ArrayList<>();
            String queryFavs = "SELECT h.*, (SELECT COUNT(*) FROM sethomex_likes l WHERE l.owner_uuid = h.player_uuid AND LOWER(l.home_name) = LOWER(h.home_name)) as likes_count FROM sethomex_homes h INNER JOIN sethomex_favorites f ON h.player_uuid = f.owner_uuid AND h.home_name = f.home_name WHERE f.player_uuid = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(queryFavs)) {
                stmt.setString(1, playerUuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        favorites.add(extractHomeFromResultSet(rs));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Impossible de charger les favoris du joueur : " + e.getMessage());
            }
            resolvedFavorites.put(playerUuid, favorites);

            if (!playerHomes.isEmpty()) {
                cache.put(playerUuid, playerHomes);
            }
        });
    }

    /**
     * Charge les joueurs actuellement en ligne (ex: reload du plugin)
     */
    public void loadOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            loadPlayerHomes(player.getUniqueId());
        }
    }

    /**
     * Exécute une purge asynchrone des données des joueurs inactifs.
     */
    @SuppressWarnings("deprecation")
    public void runAutoPurgeTask() {
        if (!plugin.getConfig().getBoolean("database.maintenance.auto-purge.enabled", false)) {
            return;
        }

        int daysInactive = plugin.getConfig().getInt("database.maintenance.auto-purge.days-inactive", 180);
        long cutoffTime = System.currentTimeMillis() - ((long) daysInactive * 24 * 60 * 60 * 1000);

        databaseExecutor.execute(() -> {
            List<UUID> inactivePlayers = new ArrayList<>();
            for (org.bukkit.OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
                if (offlinePlayer.getLastPlayed() > 0 && offlinePlayer.getLastPlayed() < cutoffTime) {
                    inactivePlayers.add(offlinePlayer.getUniqueId());
                }
            }

            if (inactivePlayers.isEmpty()) return;

            plugin.getLogger().info("Starting auto-purge for " + inactivePlayers.size() + " inactive players...");

            String deleteHomes = "DELETE FROM sethomex_homes WHERE player_uuid = ?";
            String deleteTrusts = "DELETE FROM sethomex_trusts WHERE owner_uuid = ?";

            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                boolean initialAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);

                try (PreparedStatement stmtHomes = conn.prepareStatement(deleteHomes);
                     PreparedStatement stmtTrusts = conn.prepareStatement(deleteTrusts)) {

                    for (UUID uuid : inactivePlayers) {
                        stmtHomes.setString(1, uuid.toString());
                        stmtHomes.addBatch();

                        stmtTrusts.setString(1, uuid.toString());
                        stmtTrusts.addBatch();
                    }

                    stmtHomes.executeBatch();
                    stmtTrusts.executeBatch();
                    conn.commit();
                    
                    plugin.getLogger().info("Successfully purged data for " + inactivePlayers.size() + " inactive players.");
                } catch (SQLException e) {
                    conn.rollback();
                    plugin.getLogger().severe("Error during auto-purge transaction. Rolled back. " + e.getMessage());
                } finally {
                    conn.setAutoCommit(initialAutoCommit);
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("DB Connection error during auto-purge: " + e.getMessage());
            }
        });
    }

    /**
     * Crée et sauvegarde un nouveau home en cache et en base de données.
     */
    public void createHome(Player player, String name, Location loc) {
        UUID uuid = player.getUniqueId();
        Material defaultIcon = Material.valueOf(plugin.getConfig().getString("gui.default-home-item", "RED_BED"));

        Map<String, Home> playerHomes = cache.getIfPresent(uuid);
        if (playerHomes == null) {
            playerHomes = new ConcurrentHashMap<>();
            cache.put(uuid, playerHomes);
        }
        Home existing = playerHomes.get(name.toLowerCase());

        Home home;
        if (existing != null) {
            // Préserver les métadonnées (icônes customisées, etc.) en cas de déplacement du
            // home !
            home = new Home(uuid, existing.getName(), loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(),
                    loc.getPitch(), existing.getIconMaterial(), existing.getIconTexture(), existing.isPublic(),
                    existing.getVisits(), existing.isRespawn());
            home.setCategory(existing.getCategory());
            home.setDescription(existing.getDescription());
            home.setWelcomeMessage(existing.getWelcomeMessage());
            home.setLikesCount(existing.getLikesCount());
            home.setVisitFee(existing.getVisitFee());
            home.setMusicDisc(existing.getMusicDisc());
            home.setTimeLock(existing.getTimeLock());
            home.setWeatherLock(existing.getWeatherLock());
            home.setSponsored(existing.isSponsored());
            home.setSponsoredUntil(existing.getSponsoredUntil());
        } else {
            home = new Home(uuid, name, loc, defaultIcon);
        }

        fr.skynex.sethomex.api.events.PlayerSetHomeEvent event = new fr.skynex.sethomex.api.events.PlayerSetHomeEvent(
                player, home);
        org.bukkit.Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled())
            return;

        // Ajout au cache
        playerHomes.put(name.toLowerCase(), home);

        // Synchroniser sur les cartes interactives si nécessaire
        if (plugin.getMapIntegrationManager() != null) {
            plugin.getMapIntegrationManager().syncHome(home);
        }

        // Sauvegarde asynchrone sécurisée
        databaseExecutor.execute(() -> {
            boolean isMySQL = plugin.getDatabaseManager().isMySQL();
            String query;
            if (isMySQL) {
                query = "INSERT INTO sethomex_homes (player_uuid, home_name, world_name, x, y, z, yaw, pitch, icon_material, icon_texture, is_public, visits, is_respawn, category, description, welcome_message, visit_fee, music_disc, time_lock, weather_lock, is_sponsored, sponsored_until) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE world_name = VALUES(world_name), x = VALUES(x), y = VALUES(y), z = VALUES(z), yaw = VALUES(yaw), pitch = VALUES(pitch), icon_material = VALUES(icon_material), icon_texture = VALUES(icon_texture), is_public = VALUES(is_public), visits = VALUES(visits), is_respawn = VALUES(is_respawn), category = VALUES(category), description = VALUES(description), welcome_message = VALUES(welcome_message), visit_fee = VALUES(visit_fee), music_disc = VALUES(music_disc), time_lock = VALUES(time_lock), weather_lock = VALUES(weather_lock), is_sponsored = VALUES(is_sponsored), sponsored_until = VALUES(sponsored_until)";
            } else {
                query = "INSERT OR REPLACE INTO sethomex_homes (player_uuid, home_name, world_name, x, y, z, yaw, pitch, icon_material, icon_texture, is_public, visits, is_respawn, category, description, welcome_message, visit_fee, music_disc, time_lock, weather_lock, is_sponsored, sponsored_until) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            }

            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setString(1, uuid.toString());
                stmt.setString(2, name);
                stmt.setString(3, home.getWorldName());
                stmt.setDouble(4, home.getX());
                stmt.setDouble(5, home.getY());
                stmt.setDouble(6, home.getZ());
                stmt.setFloat(7, home.getYaw());
                stmt.setFloat(8, home.getPitch());
                stmt.setString(9, home.getIconMaterial().name());
                stmt.setString(10, home.getIconTexture());
                stmt.setInt(11, home.isPublic() ? 1 : 0);
                stmt.setLong(12, home.getVisits());
                stmt.setInt(13, home.isRespawn() ? 1 : 0);
                stmt.setString(14, home.getCategory());
                stmt.setString(15, home.getDescription());
                stmt.setString(16, home.getWelcomeMessage());
                stmt.setDouble(17, home.getVisitFee());
                stmt.setString(18, home.getMusicDisc());
                stmt.setLong(19, home.getTimeLock());
                stmt.setString(20, home.getWeatherLock());
                stmt.setInt(21, home.isSponsored() ? 1 : 0);
                stmt.setLong(22, home.getSponsoredUntil());
                stmt.executeUpdate();

                // Map integration
                if (plugin.getMapIntegrationManager() != null) {
                    plugin.getMapIntegrationManager().syncHome(home);
                }

                // Cross-server sync (BungeeCord)
                plugin.getBungeeSyncManager().sendSyncMessage("INVALIDATE_CACHE", home.getPlayerUuid(), null);

            } catch (SQLException e) {
                plugin.getLogger().severe("Error saving home " + name + " for " + player.getName()
                        + " : " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    public void saveHome(Home home) {
        if (home == null) return;
        UUID uuid = home.getPlayerUuid();
        Map<String, Home> playerHomes = cache.getIfPresent(uuid);
        if (playerHomes == null) {
            playerHomes = new ConcurrentHashMap<>();
            cache.put(uuid, playerHomes);
        }
        playerHomes.put(home.getName().toLowerCase(), home);

        databaseExecutor.execute(() -> {
            boolean isMySQL = plugin.getDatabaseManager().isMySQL();
            String query;
            if (isMySQL) {
                query = "INSERT INTO sethomex_homes (player_uuid, home_name, world_name, x, y, z, yaw, pitch, icon_material, icon_texture, is_public, visits, is_respawn, category, description, welcome_message, visit_fee, music_disc, time_lock, weather_lock, is_sponsored, sponsored_until) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE world_name = VALUES(world_name), x = VALUES(x), y = VALUES(y), z = VALUES(z), yaw = VALUES(yaw), pitch = VALUES(pitch), icon_material = VALUES(icon_material), icon_texture = VALUES(icon_texture), is_public = VALUES(is_public), visits = VALUES(visits), is_respawn = VALUES(is_respawn), category = VALUES(category), description = VALUES(description), welcome_message = VALUES(welcome_message), visit_fee = VALUES(visit_fee), music_disc = VALUES(music_disc), time_lock = VALUES(time_lock), weather_lock = VALUES(weather_lock), is_sponsored = VALUES(is_sponsored), sponsored_until = VALUES(sponsored_until)";
            } else {
                query = "INSERT OR REPLACE INTO sethomex_homes (player_uuid, home_name, world_name, x, y, z, yaw, pitch, icon_material, icon_texture, is_public, visits, is_respawn, category, description, welcome_message, visit_fee, music_disc, time_lock, weather_lock, is_sponsored, sponsored_until) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            }

            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setString(1, uuid.toString());
                stmt.setString(2, home.getName());
                stmt.setString(3, home.getWorldName());
                stmt.setDouble(4, home.getX());
                stmt.setDouble(5, home.getY());
                stmt.setDouble(6, home.getZ());
                stmt.setFloat(7, home.getYaw());
                stmt.setFloat(8, home.getPitch());
                stmt.setString(9, home.getIconMaterial().name());
                stmt.setString(10, home.getIconTexture());
                stmt.setInt(11, home.isPublic() ? 1 : 0);
                stmt.setLong(12, home.getVisits());
                stmt.setInt(13, home.isRespawn() ? 1 : 0);
                stmt.setString(14, home.getCategory());
                stmt.setString(15, home.getDescription());
                stmt.setString(16, home.getWelcomeMessage());
                stmt.setDouble(17, home.getVisitFee());
                stmt.setString(18, home.getMusicDisc());
                stmt.setLong(19, home.getTimeLock());
                stmt.setString(20, home.getWeatherLock());
                stmt.setInt(21, home.isSponsored() ? 1 : 0);
                stmt.setLong(22, home.getSponsoredUntil());
                stmt.executeUpdate();

                if (plugin.getMapIntegrationManager() != null) {
                    plugin.getMapIntegrationManager().syncHome(home);
                }

                plugin.getBungeeSyncManager().sendSyncMessage("INVALIDATE_CACHE", home.getPlayerUuid(), null);

            } catch (SQLException e) {
                plugin.getLogger().severe("Error saving home " + home.getName() + " for " + uuid + " : " + e.getMessage());
            }
        });
    }

    /**
     * Supprime un home du cache et de la base de données.
     */
    public boolean deleteHome(Player player, String name) {
        return deleteHome(player.getUniqueId(), name, player.getName());
    }

    public boolean deleteHome(UUID uuid, String name, String logName) {
        Map<String, Home> playerHomes = cache.getIfPresent(uuid);
        if (playerHomes == null || !playerHomes.containsKey(name.toLowerCase())) {
            return false;
        }

        Home home = playerHomes.remove(name.toLowerCase());

        // Retirer des cartes interactives
        if (plugin.getMapIntegrationManager() != null && home != null) {
            plugin.getMapIntegrationManager().removeHome(home);
        }

        databaseExecutor.execute(() -> {
            String queryHomes = "DELETE FROM sethomex_homes WHERE player_uuid = ? AND LOWER(home_name) = ?";
            String queryTrusts = "DELETE FROM sethomex_trusts WHERE owner_uuid = ? AND LOWER(home_name) = ?";
            String queryFavorites = "DELETE FROM sethomex_favorites WHERE owner_uuid = ? AND LOWER(home_name) = ?";

            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                boolean autoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    // 1. Purge du Home
                    try (PreparedStatement stmt = conn.prepareStatement(queryHomes)) {
                        stmt.setString(1, uuid.toString());
                        stmt.setString(2, name.toLowerCase());
                        stmt.executeUpdate();
                    }
                    // 2. Purge des permissions liées (IMPORTANT)
                    try (PreparedStatement stmt = conn.prepareStatement(queryTrusts)) {
                        stmt.setString(1, uuid.toString());
                        stmt.setString(2, name.toLowerCase());
                        stmt.executeUpdate();
                    }
                    // 3. Purge des favoris (IMPORTANT)
                    try (PreparedStatement stmt = conn.prepareStatement(queryFavorites)) {
                        stmt.setString(1, uuid.toString());
                        stmt.setString(2, name.toLowerCase());
                        stmt.executeUpdate();
                    }
                    conn.commit();

                    // Remove from memory for all loaded favorites
                    for (List<Home> favs : resolvedFavorites.values()) {
                        favs.removeIf(h -> h.getPlayerUuid().equals(uuid) && h.getName().equalsIgnoreCase(name));
                    }

                    // Cross-server sync
                    plugin.getBungeeSyncManager().sendSyncMessage("INVALIDATE_CACHE", uuid, null);

                } catch (SQLException ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(autoCommit);
                }
            } catch (SQLException e) {
                plugin.getLogger().severe(
                        "Error deleting home " + name + " for " + logName + " : " + e.getMessage());
                e.printStackTrace();
            }
        });
        return true;
    }

    /**
     * Modifie l'icône matérielle d'un home.
     */
    public void updateHomeIcon(Home home, Material newMaterial, String textureString) {
        home.setIconMaterial(newMaterial);
        home.setIconTexture(textureString);
        databaseExecutor.execute(() -> {
            String query = "UPDATE sethomex_homes SET icon_material = ?, icon_texture = ? WHERE player_uuid = ? AND LOWER(home_name) = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, newMaterial.name());
                stmt.setString(2, textureString);
                stmt.setString(3, home.getPlayerUuid().toString());
                stmt.setString(4, home.getName().toLowerCase());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe(
                        "Error updating icon for home " + home.getName() + " : " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Modifie la catégorie/dossier d'un home.
     */
    public void updateHomeCategory(Home home) {
        databaseExecutor.execute(() -> {
            String query = "UPDATE sethomex_homes SET category = ? WHERE player_uuid = ? AND LOWER(home_name) = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, home.getCategory());
                stmt.setString(2, home.getPlayerUuid().toString());
                stmt.setString(3, home.getName().toLowerCase());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe(
                        "Error updating category for home " + home.getName() + " : " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Met à jour l'état de visibilité publique et le nombre de visites en base de
     * données.
     */
    public void updateHomeSocial(Home home) {
        // Synchroniser l'état public/visites sur les cartes web
        if (plugin.getMapIntegrationManager() != null) {
            plugin.getMapIntegrationManager().syncHome(home);
        }

        databaseExecutor.execute(() -> {
            String query = "UPDATE sethomex_homes SET is_public = ?, visits = ? WHERE player_uuid = ? AND LOWER(home_name) = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setInt(1, home.isPublic() ? 1 : 0);
                stmt.setLong(2, home.getVisits());
                stmt.setString(3, home.getPlayerUuid().toString());
                stmt.setString(4, home.getName().toLowerCase());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger()
                        .severe("Error during social update for home " + home.getName() + " : " + e.getMessage());
            }
        });
    }

    /**
     * Définit un home comme le point de respawn officiel, et désactive les autres
     * du même joueur.
     */
    public void setRespawnHome(UUID uuid, Home targetHome) {
        Map<String, Home> homes = cache.getIfPresent(uuid);
        if (homes == null)
            return;

        // 1. Désactiver l'ancien respawn point s'il existe en mémoire
        for (Home h : homes.values()) {
            if (h.isRespawn() && !h.equals(targetHome)) {
                h.setRespawn(false);
                updateHomeRespawn(h); // Sync SQL off
            }
        }

        // 2. Basculer le statut du nouveau
        if (targetHome != null) {
            // Si déjà actif, le désactive, sinon l'active
            targetHome.setRespawn(!targetHome.isRespawn());
            updateHomeRespawn(targetHome);
        }
    }

    /**
     * Récupère le home marqué comme point de réapparition d'un joueur.
     */
    public Home getRespawnHome(UUID uuid) {
        Map<String, Home> homes = cache.getIfPresent(uuid);
        if (homes == null)
            return null;
        for (Home h : homes.values()) {
            if (h.isRespawn())
                return h;
        }
        return null;
    }

    /**
     * Met à jour l'état du respawn en base de données de manière asynchrone.
     */
    private void updateHomeRespawn(Home home) {
        databaseExecutor.execute(() -> {
            String query = "UPDATE sethomex_homes SET is_respawn = ? WHERE player_uuid = ? AND LOWER(home_name) = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setInt(1, home.isRespawn() ? 1 : 0);
                stmt.setString(2, home.getPlayerUuid().toString());
                stmt.setString(3, home.getName().toLowerCase());
                stmt.executeUpdate();
                
                // Cross-server sync
                plugin.getBungeeSyncManager().sendSyncMessage("INVALIDATE_CACHE", home.getPlayerUuid(), null);
                
            } catch (SQLException e) {
                plugin.getLogger().severe("Error updating Respawn Home for " + home.getName() + " : " + e.getMessage());
            }
        });
    }

    /**
     * Ajoute un joueur de confiance à une habitation.
     */
    public void addTrust(Home home, UUID guestUuid) {
        addTrust(home, guestUuid, -1L, "VISITOR");
    }

    public void addTrust(Home home, UUID guestUuid, long expiresAt) {
        addTrust(home, guestUuid, expiresAt, "VISITOR");
    }

    public void addTrust(Home home, UUID guestUuid, long expiresAt, String role) {
        if (role == null) role = "VISITOR";
        home.removeTrust(guestUuid);
        home.addTrust(guestUuid, expiresAt, role);

        final String finalRole = role;

        databaseExecutor.execute(() -> {
            String delete = "DELETE FROM sethomex_trusts WHERE owner_uuid = ? AND home_name = ? AND guest_uuid = ?";
            String insert = "INSERT INTO sethomex_trusts (owner_uuid, home_name, guest_uuid, expires_at, role) VALUES (?, ?, ?, ?, ?)";

            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement delStmt = conn.prepareStatement(delete)) {
                    delStmt.setString(1, home.getPlayerUuid().toString());
                    delStmt.setString(2, home.getName());
                    delStmt.setString(3, guestUuid.toString());
                    delStmt.executeUpdate();
                }
                try (PreparedStatement insStmt = conn.prepareStatement(insert)) {
                    insStmt.setString(1, home.getPlayerUuid().toString());
                    insStmt.setString(2, home.getName());
                    insStmt.setString(3, guestUuid.toString());
                    insStmt.setLong(4, expiresAt);
                    insStmt.setString(5, finalRole);
                    insStmt.executeUpdate();
                }
                conn.commit();
                
                // Cross-server sync
                plugin.getBungeeSyncManager().sendSyncMessage("INVALIDATE_CACHE", home.getPlayerUuid(), null);
                plugin.getBungeeSyncManager().sendSyncMessage("TRUST_NOTIFY", guestUuid, getPlayerName(home.getPlayerUuid()) + ":" + home.getName());
                
            } catch (SQLException e) {
                plugin.getLogger().warning("DB Error on adding Trust: " + e.getMessage());
            }
        });
    }

    /**
     * Retire la confiance d'un joueur sur une habitation.
     */
    public void removeTrust(Home home, UUID guestUuid) {
        if (!home.isTrusted(guestUuid))
            return;
        home.removeTrust(guestUuid);

        databaseExecutor.execute(() -> {
            String query = "DELETE FROM sethomex_trusts WHERE owner_uuid = ? AND home_name = ? AND guest_uuid = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, home.getPlayerUuid().toString());
                stmt.setString(2, home.getName());
                stmt.setString(3, guestUuid.toString());
                stmt.executeUpdate();
                
                // Cross-server sync
                plugin.getBungeeSyncManager().sendSyncMessage("INVALIDATE_CACHE", home.getPlayerUuid(), null);
                
            } catch (SQLException e) {
                plugin.getLogger().warning("DB Error on removing Trust: " + e.getMessage());
            }
        });
    }

    /**
     * Récupère la liste de tous les homes partagés avec un joueur spécifique.
     */
    public CompletableFuture<List<Home>> getSharedHomesAsync(UUID guestUuid) {
        return CompletableFuture.supplyAsync(() -> {
            List<Home> shared = new ArrayList<>();
            String query = "SELECT h.*, (SELECT COUNT(*) FROM sethomex_likes l WHERE l.owner_uuid = h.player_uuid AND LOWER(l.home_name) = LOWER(h.home_name)) as likes_count, t.guest_uuid, t.expires_at, t.role FROM sethomex_homes h INNER JOIN sethomex_trusts t ON h.player_uuid = t.owner_uuid AND h.home_name = t.home_name WHERE t.guest_uuid = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, guestUuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Home home = extractHomeFromResultSet(rs);
                        long expiresAt = rs.getLong("expires_at");
                        String role = rs.getString("role");
                        if (expiresAt != -1L && System.currentTimeMillis() > expiresAt) {
                            continue;
                        }
                        home.addTrust(guestUuid, expiresAt, role);
                        shared.add(home);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("DB Error fetching shared homes: " + e.getMessage());
            }
            return shared;
        }, databaseExecutor);
    }

    public void cleanExpiredTrusts() {
        databaseExecutor.execute(() -> {
            String query = "DELETE FROM sethomex_trusts WHERE expires_at != -1 AND expires_at < ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setLong(1, System.currentTimeMillis());
                int deleted = stmt.executeUpdate();
                if (deleted > 0) {
                    plugin.getLogger().info("[Purger] Nettoyage automatique de " + deleted + " partages temporaires expirés de la base de données.");
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to purge expired trusts: " + e.getMessage());
            }
        });
    }

    public void savePlayerCosmetics(UUID playerUuid, String particle, String style, String sound, String successSound) {
        databaseExecutor.execute(() -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                String update = "UPDATE sethomex_users SET selected_particle = ?, selected_style = ?, selected_sound = ?, selected_success_sound = ? WHERE player_uuid = ?";
                int rows = 0;
                try (PreparedStatement stmt = conn.prepareStatement(update)) {
                    stmt.setString(1, particle);
                    stmt.setString(2, style);
                    stmt.setString(3, sound);
                    stmt.setString(4, successSound);
                    stmt.setString(5, playerUuid.toString());
                    rows = stmt.executeUpdate();
                }
                if (rows == 0) {
                    String insert = "INSERT INTO sethomex_users (player_uuid, last_seen, selected_particle, selected_style, selected_sound, selected_success_sound) VALUES (?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement stmt = conn.prepareStatement(insert)) {
                        stmt.setString(1, playerUuid.toString());
                        stmt.setLong(2, System.currentTimeMillis());
                        stmt.setString(3, particle);
                        stmt.setString(4, style);
                        stmt.setString(5, sound);
                        stmt.setString(6, successSound);
                        stmt.executeUpdate();
                    }
                }
                plugin.getTeleportManager().setPlayerCosmetics(playerUuid, particle, style, sound, successSound);
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to save cosmetics: " + e.getMessage());
            }
        });
    }

    public Home getHome(Player player, String name) {
        return getHome(player.getUniqueId(), name);
    }

    public Home getHome(UUID uuid, String name) {
        Map<String, Home> playerHomes = cache.getIfPresent(uuid);
        if (playerHomes == null) {
            // In a complete implementation, this would dynamically fetch from DB
            return null;
        }
        return playerHomes.get(name.toLowerCase());
    }

    public Collection<Home> getPlayerHomes(Player player) {
        return getPlayerHomes(player.getUniqueId());
    }

    public Collection<Home> getPlayerHomes(UUID uuid) {
        Map<String, Home> playerHomes = cache.getIfPresent(uuid);
        return playerHomes != null ? playerHomes.values() : Collections.emptyList();
    }

    public CompletableFuture<Integer> getPublicHomesCountAsync() {
        return getPublicHomesCountAsync(null);
    }

    public CompletableFuture<Integer> getPublicHomesCountAsync(String searchQuery) {
        return CompletableFuture.supplyAsync(() -> {
            boolean hasSearch = searchQuery != null && !searchQuery.isEmpty();
            String query = hasSearch ? 
                "SELECT COUNT(*) FROM sethomex_homes h LEFT JOIN sethomex_users u ON h.player_uuid = u.player_uuid WHERE h.is_public = 1 AND (LOWER(h.home_name) LIKE ? OR LOWER(u.player_name) LIKE ?)" :
                "SELECT COUNT(*) FROM sethomex_homes WHERE is_public = 1";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                if (searchQuery != null && !searchQuery.isEmpty()) {
                    String pattern = "%" + searchQuery.toLowerCase() + "%";
                    stmt.setString(1, pattern);
                    stmt.setString(2, pattern);
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("DB Error fetching public homes count: " + e.getMessage());
            }
            return 0;
        }, databaseExecutor);
    }

    /**
     * Récupère une page spécifique de homes configurés comme PUBLICS (optimisation pagination SQL).
     */
    public CompletableFuture<List<Home>> getPublicHomesPageAsync(int page, int itemsPerPage, String sortMode) {
        return getPublicHomesPageAsync(page, itemsPerPage, sortMode, null);
    }

    public CompletableFuture<List<Home>> getPublicHomesPageAsync(int page, int itemsPerPage, String sortMode, String searchQuery) {
        return CompletableFuture.supplyAsync(() -> {
            List<Home> publicHomes = new ArrayList<>();
            String orderClause = "ORDER BY h.is_sponsored DESC, h.visits DESC";
            
            if ("ALPHABETICAL".equalsIgnoreCase(sortMode)) {
                orderClause = "ORDER BY h.is_sponsored DESC, h.home_name ASC";
            } else if ("RANDOM".equalsIgnoreCase(sortMode)) {
                orderClause = "ORDER BY h.is_sponsored DESC, " + (plugin.getDatabaseManager().isMySQL() ? "RAND()" : "RANDOM()");
            } else if ("LIKES".equalsIgnoreCase(sortMode)) {
                orderClause = "ORDER BY h.is_sponsored DESC, likes_count DESC, h.visits DESC";
            }

            int offset = (page - 1) * itemsPerPage;
            boolean hasSearch = searchQuery != null && !searchQuery.isEmpty();
            String selectClause = "SELECT h.*, (SELECT COUNT(*) FROM sethomex_likes l WHERE l.owner_uuid = h.player_uuid AND LOWER(l.home_name) = LOWER(h.home_name)) as likes_count";
            String query = hasSearch ?
                selectClause + " FROM sethomex_homes h LEFT JOIN sethomex_users u ON h.player_uuid = u.player_uuid WHERE h.is_public = 1 AND (LOWER(h.home_name) LIKE ? OR LOWER(u.player_name) LIKE ?) " + orderClause + " LIMIT ? OFFSET ?" :
                selectClause + " FROM sethomex_homes h WHERE h.is_public = 1 " + orderClause + " LIMIT ? OFFSET ?";
            
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                // Nettoyage des sponsors expirés
                try (PreparedStatement cleanStmt = conn.prepareStatement("UPDATE sethomex_homes SET is_sponsored = 0 WHERE is_sponsored = 1 AND sponsored_until < ?")) {
                    cleanStmt.setLong(1, System.currentTimeMillis());
                    cleanStmt.executeUpdate();
                }

                try (PreparedStatement stmt = conn.prepareStatement(query)) {
                    if (searchQuery != null && !searchQuery.isEmpty()) {
                        String pattern = "%" + searchQuery.toLowerCase() + "%";
                        stmt.setString(1, pattern);
                        stmt.setString(2, pattern);
                        stmt.setInt(3, itemsPerPage);
                        stmt.setInt(4, offset);
                    } else {
                        stmt.setInt(1, itemsPerPage);
                        stmt.setInt(2, offset);
                    }
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            publicHomes.add(extractHomeFromResultSet(rs));
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("DB Error fetching public homes page: " + e.getMessage());
            }
            return publicHomes;
        }, databaseExecutor);
    }

    /**
     * Récupère la liste intégrale de tous les homes configurés comme PUBLICS (utile pour les maps Web).
     */
    public CompletableFuture<List<Home>> getAllPublicHomesAsync() {
        return CompletableFuture.supplyAsync(() -> {
            List<Home> publicHomes = new ArrayList<>();
            String query = "SELECT h.*, (SELECT COUNT(*) FROM sethomex_likes l WHERE l.owner_uuid = h.player_uuid AND LOWER(l.home_name) = LOWER(h.home_name)) as likes_count FROM sethomex_homes h WHERE h.is_public = 1";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    publicHomes.add(extractHomeFromResultSet(rs));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("DB Error fetching all public homes: " + e.getMessage());
            }
            return publicHomes;
        }, databaseExecutor);
    }

    private Home extractHomeFromResultSet(ResultSet rs) throws SQLException {
        UUID uuid = UUID.fromString(rs.getString("player_uuid"));
        String name = rs.getString("home_name");
        String worldName = rs.getString("world_name");
        double x = rs.getDouble("x");
        double y = rs.getDouble("y");
        double z = rs.getDouble("z");
        float yaw = rs.getFloat("yaw");
        float pitch = rs.getFloat("pitch");
        String iconMatName = rs.getString("icon_material");
        String iconTexture = rs.getString("icon_texture");
        boolean isPublic = rs.getInt("is_public") == 1;
        long visits = rs.getLong("visits");
        boolean isRespawn = rs.getInt("is_respawn") == 1;
        
        String category = "none";
        try {
            category = rs.getString("category");
            if (category == null) category = "none";
        } catch (SQLException ignored) {}

        String description = "";
        try {
            description = rs.getString("description");
            if (description == null) description = "";
        } catch (SQLException ignored) {}

        String welcomeMessage = "";
        try {
            welcomeMessage = rs.getString("welcome_message");
            if (welcomeMessage == null) welcomeMessage = "";
        } catch (SQLException ignored) {}

        Material iconMat;
        try {
            iconMat = Material.valueOf(iconMatName);
        } catch (IllegalArgumentException e) {
            iconMat = Material.RED_BED;
        }

        int likes = 0;
        try {
            likes = rs.getInt("likes_count");
        } catch (SQLException ignored) {}

        double visitFee = 0.0;
        try {
            visitFee = rs.getDouble("visit_fee");
        } catch (SQLException ignored) {}

        String musicDisc = "none";
        try {
            musicDisc = rs.getString("music_disc");
            if (musicDisc == null) musicDisc = "none";
        } catch (SQLException ignored) {}

        long timeLock = -1;
        try {
            timeLock = rs.getLong("time_lock");
        } catch (SQLException ignored) {}

        String weatherLock = "none";
        try {
            weatherLock = rs.getString("weather_lock");
            if (weatherLock == null) weatherLock = "none";
        } catch (SQLException ignored) {}

        boolean isSponsored = false;
        try {
            isSponsored = rs.getInt("is_sponsored") == 1;
        } catch (SQLException ignored) {}

        long sponsoredUntil = 0;
        try {
            sponsoredUntil = rs.getLong("sponsored_until");
        } catch (SQLException ignored) {}

        Home home = new Home(uuid, name, worldName, x, y, z, yaw, pitch, iconMat, iconTexture, isPublic, visits, isRespawn);
        home.setCategory(category);
        home.setDescription(description);
        home.setWelcomeMessage(welcomeMessage);
        home.setLikesCount(likes);
        home.setVisitFee(visitFee);
        home.setMusicDisc(musicDisc);
        home.setTimeLock(timeLock);
        home.setWeatherLock(weatherLock);
        home.setSponsored(isSponsored && sponsoredUntil > System.currentTimeMillis());
        home.setSponsoredUntil(sponsoredUntil);
        return home;
    }

    public int getPlayerLimit(Player player) {
        if (player.isOp())
            return 9999;

        int max = plugin.getConfig().getInt("limits.default", 3);

        // On parcourt les configurations de limites définies de manière sécurisée
        org.bukkit.configuration.ConfigurationSection limitsSection = plugin.getConfig().getConfigurationSection("limits");
        if (limitsSection != null) {
            Set<String> groups = limitsSection.getKeys(false);
            for (String group : groups) {
                if (group.equalsIgnoreCase("default")) continue; // Ignorer la valeur par défaut ici
                if (player.hasPermission("sethomex.limit." + group)) {
                    int limit = plugin.getConfig().getInt("limits." + group);
                    if (limit > max) {
                        max = limit;
                    }
                }
            }
        }

        // Parcourir également les permissions effectives pour détecter le pattern sethomex.limit.<nombre>
        for (org.bukkit.permissions.PermissionAttachmentInfo permInfo : player.getEffectivePermissions()) {
            if (permInfo.getValue()) {
                String perm = permInfo.getPermission().toLowerCase();
                if (perm.startsWith("sethomex.limit.")) {
                    String suffix = perm.substring("sethomex.limit.".length());
                    try {
                        int limit = Integer.parseInt(suffix);
                        if (limit > max) {
                            max = limit;
                        }
                    } catch (NumberFormatException ignored) {
                        // Ce n'est pas une limite numérique (ex: sethomex.limit.vip)
                    }
                }
            }
        }

        max += getRentedSlots(player.getUniqueId());
        return max;
    }

    /**
     * Arrête proprement le pool de threads SQL en attendant la fin des écritures.
     */
    public void shutdown() {
        plugin.getLogger().info("Waiting for remaining database operations to complete...");
        databaseExecutor.shutdown();
        try {
            if (!databaseExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Save timeout exceeded! Forcing immediate shutdown.");
                databaseExecutor.shutdownNow();
            } else {
                plugin.getLogger().info("All SQL data has been successfully saved!");
            }
        } catch (InterruptedException e) {
            databaseExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Enregistre l'activité du joueur pour le système de purge des inactifs.
     */
    public void updateUserActivity(Player player) {
        UUID uuid = player.getUniqueId();
        String playerName = player.getName();
        nameCache.put(uuid, playerName);
        databaseExecutor.execute(() -> {
            boolean isMySQL = plugin.getDatabaseManager().isMySQL();
            String query;
            if (isMySQL) {
                query = "INSERT INTO sethomex_users (player_uuid, player_name, last_seen) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE player_name = VALUES(player_name), last_seen = VALUES(last_seen)";
            } else {
                query = "INSERT OR REPLACE INTO sethomex_users (player_uuid, player_name, last_seen) VALUES (?, ?, ?)";
            }

            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(query)) {
                long now = System.currentTimeMillis();
                stmt.setString(1, uuid.toString());
                stmt.setString(2, playerName);
                stmt.setLong(3, now);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("[Purger] Failed to update user activity: " + e.getMessage());
            }
        });
    }

    /**
     * Nettoie les habitations des joueurs n'ayant pas été vus depuis X jours.
     * Très performant car exécuté côté base de données directement !
     */
    public void runPurgeTask(int daysInactive) {
        if (daysInactive <= 0)
            return;

        databaseExecutor.execute(() -> {
            long threshold = System.currentTimeMillis() - ((long) daysInactive * 24 * 60 * 60 * 1000L);

            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                conn.setAutoCommit(false);

                // 1. Compter avant suppression pour le log
                int beforeCount = 0;
                try (PreparedStatement countStmt = conn.prepareStatement(
                        "SELECT COUNT(*) FROM sethomex_homes WHERE player_uuid IN (SELECT player_uuid FROM sethomex_users WHERE last_seen < ?)")) {
                    countStmt.setLong(1, threshold);
                    try (ResultSet rs = countStmt.executeQuery()) {
                        if (rs.next())
                            beforeCount = rs.getInt(1);
                    }
                }

                if (beforeCount > 0) {
                    // 2. Supprimer les homes des joueurs inactifs
                    String deleteQuery = "DELETE FROM sethomex_homes WHERE player_uuid IN (SELECT player_uuid FROM sethomex_users WHERE last_seen < ?)";
                    try (PreparedStatement deleteStmt = conn.prepareStatement(deleteQuery)) {
                        deleteStmt.setLong(1, threshold);
                        deleteStmt.executeUpdate();
                    }

                    // 3. Nettoyer aussi les trusts associés
                    String deleteTrustsQuery = "DELETE FROM sethomex_trusts WHERE owner_uuid IN (SELECT player_uuid FROM sethomex_users WHERE last_seen < ?)";
                    try (PreparedStatement dtStmt = conn.prepareStatement(deleteTrustsQuery)) {
                        dtStmt.setLong(1, threshold);
                        dtStmt.executeUpdate();
                    }

                    conn.commit();

                    final int deletedCount = beforeCount;
                    plugin.getLogger().info("[Purger] Auto-cleaned " + deletedCount
                            + " dormant homes from database (inactive for " + daysInactive + "+ days).");

                    // Recharger le cache pour vider les homes supprimés de la mémoire immédiate
                    plugin.getScheduler().runTask(this::loadOnlinePlayers);
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("[Purger] Fatality error during maintenance task: " + e.getMessage());
            }
        });
    }

    public List<Home> getFavorites(UUID uuid) {
        return resolvedFavorites.getOrDefault(uuid, Collections.emptyList());
    }

    public boolean isFavorite(UUID playerUuid, UUID ownerUuid, String homeName) {
        List<Home> favs = resolvedFavorites.get(playerUuid);
        if (favs == null) return false;
        for (Home h : favs) {
            if (h.getPlayerUuid().equals(ownerUuid) && h.getName().equalsIgnoreCase(homeName)) {
                return true;
            }
        }
        return false;
    }

    public void addFavorite(UUID playerUuid, Home home) {
        List<Home> favs = resolvedFavorites.computeIfAbsent(playerUuid, k -> new ArrayList<>());
        boolean alreadyFav = false;
        for (Home h : favs) {
            if (h.getPlayerUuid().equals(home.getPlayerUuid()) && h.getName().equalsIgnoreCase(home.getName())) {
                alreadyFav = true;
                break;
            }
        }
        if (!alreadyFav) {
            favs.add(home);
        }

        databaseExecutor.execute(() -> {
            String query = "INSERT INTO sethomex_favorites (player_uuid, owner_uuid, home_name) VALUES (?, ?, ?)";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, playerUuid.toString());
                stmt.setString(2, home.getPlayerUuid().toString());
                stmt.setString(3, home.getName());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Error adding favorite: " + e.getMessage());
            }
        });
    }

    public void removeFavorite(UUID playerUuid, UUID ownerUuid, String homeName) {
        List<Home> favs = resolvedFavorites.get(playerUuid);
        if (favs != null) {
            favs.removeIf(h -> h.getPlayerUuid().equals(ownerUuid) && h.getName().equalsIgnoreCase(homeName));
        }

        databaseExecutor.execute(() -> {
            String query = "DELETE FROM sethomex_favorites WHERE player_uuid = ? AND owner_uuid = ? AND home_name = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, playerUuid.toString());
                stmt.setString(2, ownerUuid.toString());
                stmt.setString(3, homeName);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Error removing favorite: " + e.getMessage());
            }
        });
    }

    public void toggleFavorite(Player player, UUID ownerUuid, String homeName) {
        UUID playerUuid = player.getUniqueId();
        List<Home> favs = resolvedFavorites.getOrDefault(playerUuid, new ArrayList<>());
        
        boolean exists = false;
        for (Home h : favs) {
            if (h.getPlayerUuid().equals(ownerUuid) && h.getName().equalsIgnoreCase(homeName)) {
                exists = true;
                break;
            }
        }
        
        if (exists) {
            removeFavorite(playerUuid, ownerUuid, homeName);
            plugin.getMessageManager().sendMessage(player, "favorites.removed", "{name}", homeName);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
        } else {
            int maxFavs = plugin.getConfig().getInt("favorites.max-favorites", 7);
            if (favs.size() >= maxFavs) {
                plugin.getMessageManager().sendMessage(player, "favorites.limit-reached");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }
            
            Home targetHome = null;
            if (ownerUuid.equals(playerUuid)) {
                targetHome = getHome(playerUuid, homeName);
            } else {
                Map<String, Home> ownerHomes = cache.getIfPresent(ownerUuid);
                if (ownerHomes != null) {
                    targetHome = ownerHomes.get(homeName.toLowerCase());
                }
                if (targetHome == null) {
                    String query = "SELECT h.*, (SELECT COUNT(*) FROM sethomex_likes l WHERE l.owner_uuid = h.player_uuid AND LOWER(l.home_name) = LOWER(h.home_name)) as likes_count FROM sethomex_homes h WHERE h.player_uuid = ? AND LOWER(h.home_name) = ?";
                    try (Connection conn = plugin.getDatabaseManager().getConnection();
                         PreparedStatement stmt = conn.prepareStatement(query)) {
                        stmt.setString(1, ownerUuid.toString());
                        stmt.setString(2, homeName.toLowerCase());
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                targetHome = extractHomeFromResultSet(rs);
                            }
                        }
                    } catch (SQLException e) {
                        plugin.getLogger().warning("Error fetching home for favorite: " + e.getMessage());
                    }
                }
            }
            
            if (targetHome != null) {
                addFavorite(playerUuid, targetHome);
                plugin.getMessageManager().sendMessage(player, "favorites.added", "{name}", targetHome.getName());
                try {
                    player.spawnParticle(org.bukkit.Particle.CRIT, player.getLocation().add(0, 1, 0), 15, 0.5, 0.5, 0.5, 0.1);
                } catch (Exception ignored) {}
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
            } else {
                player.sendMessage("§cError: Home not found.");
            }
        }
    }

    public void removePlayerFavorites(UUID playerUuid) {
        resolvedFavorites.remove(playerUuid);
    }

    public void updateHomeDescription(Home home) {
        databaseExecutor.execute(() -> {
            String query = "UPDATE sethomex_homes SET description = ? WHERE player_uuid = ? AND LOWER(home_name) = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, home.getDescription());
                stmt.setString(2, home.getPlayerUuid().toString());
                stmt.setString(3, home.getName().toLowerCase());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Error updating description for home " + home.getName() + " : " + e.getMessage());
            }
        });
    }

    public void updateHomeWelcomeMessage(Home home) {
        databaseExecutor.execute(() -> {
            String query = "UPDATE sethomex_homes SET welcome_message = ? WHERE player_uuid = ? AND LOWER(home_name) = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, home.getWelcomeMessage());
                stmt.setString(2, home.getPlayerUuid().toString());
                stmt.setString(3, home.getName().toLowerCase());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Error updating welcome message for home " + home.getName() + " : " + e.getMessage());
            }
        });
    }

    public void updateHomeVisitFee(Home home) {
        if (plugin.getMapIntegrationManager() != null) {
            plugin.getMapIntegrationManager().syncHome(home);
        }
        databaseExecutor.execute(() -> {
            String query = "UPDATE sethomex_homes SET visit_fee = ? WHERE player_uuid = ? AND LOWER(home_name) = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setDouble(1, home.getVisitFee());
                stmt.setString(2, home.getPlayerUuid().toString());
                stmt.setString(3, home.getName().toLowerCase());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Error updating visit fee for home " + home.getName() + " : " + e.getMessage());
            }
        });
    }

    public CompletableFuture<Boolean> hasLikedAsync(UUID playerUuid, UUID ownerUuid, String homeName) {
        return CompletableFuture.supplyAsync(() -> {
            String query = "SELECT 1 FROM sethomex_likes WHERE owner_uuid = ? AND LOWER(home_name) = ? AND player_uuid = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, ownerUuid.toString());
                stmt.setString(2, homeName.toLowerCase());
                stmt.setString(3, playerUuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Error checking if player liked home: " + e.getMessage());
            }
            return false;
        }, databaseExecutor);
    }

    public CompletableFuture<Boolean> toggleLikeAsync(UUID playerUuid, UUID ownerUuid, String homeName) {
        return CompletableFuture.supplyAsync(() -> {
            boolean alreadyLiked = hasLikedAsync(playerUuid, ownerUuid, homeName).join();
            String query = alreadyLiked ? 
                "DELETE FROM sethomex_likes WHERE owner_uuid = ? AND LOWER(home_name) = ? AND player_uuid = ?" :
                "INSERT INTO sethomex_likes (owner_uuid, home_name, player_uuid) VALUES (?, ?, ?)";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, ownerUuid.toString());
                stmt.setString(2, homeName.toLowerCase());
                stmt.setString(3, playerUuid.toString());
                stmt.executeUpdate();

                // Update in memory cache and map markers
                Home cachedHome = getHome(ownerUuid, homeName);
                if (cachedHome != null) {
                    cachedHome.setLikesCount(cachedHome.getLikesCount() + (alreadyLiked ? -1 : 1));
                    if (plugin.getMapIntegrationManager() != null) {
                        plugin.getMapIntegrationManager().syncHome(cachedHome);
                    }
                }

                return !alreadyLiked; // Returns new state: true if liked, false if unliked
            } catch (SQLException e) {
                plugin.getLogger().severe("Error toggling like: " + e.getMessage());
            }
            return alreadyLiked;
        }, databaseExecutor);
    }

    public CompletableFuture<List<OfflinePlayerInfo>> getPlayersWithHomesAsync() {
        return CompletableFuture.supplyAsync(() -> {
            List<OfflinePlayerInfo> list = new ArrayList<>();
            String query = "SELECT DISTINCT h.player_uuid, u.player_name FROM sethomex_homes h LEFT JOIN sethomex_users u ON h.player_uuid = u.player_uuid";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String uuidStr = rs.getString("player_uuid");
                    String name = rs.getString("player_name");
                    UUID uuid = UUID.fromString(uuidStr);
                    list.add(new OfflinePlayerInfo(uuid, name != null ? name : "Unknown"));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("DB Error fetching players with homes: " + e.getMessage());
            }
            return list;
        }, databaseExecutor);
    }

    public static class OfflinePlayerInfo {
        private final UUID uuid;
        private final String name;

        public OfflinePlayerInfo(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        public UUID getUuid() {
            return uuid;
        }

        public String getName() {
            return name;
        }
    }

    public void updateHomeMusicDisc(Home home) {
        databaseExecutor.execute(() -> {
            String query = "UPDATE sethomex_homes SET music_disc = ? WHERE player_uuid = ? AND LOWER(home_name) = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, home.getMusicDisc());
                stmt.setString(2, home.getPlayerUuid().toString());
                stmt.setString(3, home.getName().toLowerCase());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Error updating music_disc: " + e.getMessage());
            }
        });
    }

    public void updateHomeTimeLock(Home home) {
        databaseExecutor.execute(() -> {
            String query = "UPDATE sethomex_homes SET time_lock = ? WHERE player_uuid = ? AND LOWER(home_name) = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setLong(1, home.getTimeLock());
                stmt.setString(2, home.getPlayerUuid().toString());
                stmt.setString(3, home.getName().toLowerCase());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Error updating time_lock: " + e.getMessage());
            }
        });
    }

    public void updateHomeWeatherLock(Home home) {
        databaseExecutor.execute(() -> {
            String query = "UPDATE sethomex_homes SET weather_lock = ? WHERE player_uuid = ? AND LOWER(home_name) = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, home.getWeatherLock());
                stmt.setString(2, home.getPlayerUuid().toString());
                stmt.setString(3, home.getName().toLowerCase());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Error updating weather_lock: " + e.getMessage());
            }
        });
    }

    public void updateHomeSponsored(Home home) {
        databaseExecutor.execute(() -> {
            String query = "UPDATE sethomex_homes SET is_sponsored = ?, sponsored_until = ? WHERE player_uuid = ? AND LOWER(home_name) = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setInt(1, home.isSponsored() ? 1 : 0);
                stmt.setLong(2, home.getSponsoredUntil());
                stmt.setString(3, home.getPlayerUuid().toString());
                stmt.setString(4, home.getName().toLowerCase());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Error updating is_sponsored: " + e.getMessage());
            }
        });
    }

    public void addBan(Home home, UUID bannedUuid) {
        home.banPlayer(bannedUuid);
        databaseExecutor.execute(() -> {
            String insert = "INSERT OR IGNORE INTO sethomex_bans (owner_uuid, home_name, banned_uuid) VALUES (?, ?, ?)";
            if (plugin.getDatabaseManager().isMySQL()) {
                insert = "INSERT IGNORE INTO sethomex_bans (owner_uuid, home_name, banned_uuid) VALUES (?, ?, ?)";
            }
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(insert)) {
                stmt.setString(1, home.getPlayerUuid().toString());
                stmt.setString(2, home.getName());
                stmt.setString(3, bannedUuid.toString());
                stmt.executeUpdate();
                plugin.getBungeeSyncManager().sendSyncMessage("INVALIDATE_CACHE", home.getPlayerUuid(), null);
            } catch (SQLException e) {
                plugin.getLogger().warning("DB Error on adding Ban: " + e.getMessage());
            }
        });
    }

    public void removeBan(Home home, UUID bannedUuid) {
        home.unbanPlayer(bannedUuid);
        databaseExecutor.execute(() -> {
            String query = "DELETE FROM sethomex_bans WHERE owner_uuid = ? AND home_name = ? AND banned_uuid = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, home.getPlayerUuid().toString());
                stmt.setString(2, home.getName());
                stmt.setString(3, bannedUuid.toString());
                stmt.executeUpdate();
                plugin.getBungeeSyncManager().sendSyncMessage("INVALIDATE_CACHE", home.getPlayerUuid(), null);
            } catch (SQLException e) {
                plugin.getLogger().warning("DB Error on removing Ban: " + e.getMessage());
            }
        });
    }

    public void logVisit(Home home, Player visitor) {
        databaseExecutor.execute(() -> {
            String query = "INSERT INTO sethomex_visits_log (owner_uuid, home_name, visitor_uuid, visitor_name, timestamp) VALUES (?, ?, ?, ?, ?)";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, home.getPlayerUuid().toString());
                stmt.setString(2, home.getName());
                stmt.setString(3, visitor.getUniqueId().toString());
                stmt.setString(4, visitor.getName());
                stmt.setLong(5, System.currentTimeMillis());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("DB Error logging visit: " + e.getMessage());
            }
        });
    }

    public static class VisitRecord {
        private final String visitorName;
        private final long timestamp;
        public VisitRecord(String visitorName, long timestamp) {
            this.visitorName = visitorName;
            this.timestamp = timestamp;
        }
        public String getVisitorName() { return visitorName; }
        public long getTimestamp() { return timestamp; }
    }

    public List<VisitRecord> getVisitHistory(UUID ownerUuid, String homeName) {
        List<VisitRecord> history = new ArrayList<>();
        String query = "SELECT visitor_name, timestamp FROM sethomex_visits_log WHERE owner_uuid = ? AND LOWER(home_name) = ? ORDER BY timestamp DESC LIMIT 30";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, ownerUuid.toString());
            stmt.setString(2, homeName.toLowerCase());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    history.add(new VisitRecord(rs.getString("visitor_name"), rs.getLong("timestamp")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("DB Error loading visit history: " + e.getMessage());
        }
        return history;
    }

    public void loadRentedSlots(UUID playerUuid) {
        String query = "SELECT amount FROM sethomex_rented_slots WHERE player_uuid = ? AND expires_at > ?";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, playerUuid.toString());
            stmt.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    rentedSlotsCache.put(playerUuid, rs.getInt("amount"));
                } else {
                    rentedSlotsCache.remove(playerUuid);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("DB Error loading rented slots: " + e.getMessage());
        }
    }

    public void addRentedSlots(UUID playerUuid, int amount, long durationMs) {
        databaseExecutor.execute(() -> {
            long expiresAt = System.currentTimeMillis() + durationMs;
            int currentAmount = rentedSlotsCache.getOrDefault(playerUuid, 0);
            int newAmount = currentAmount + amount;
            rentedSlotsCache.put(playerUuid, newAmount);

            String query = "INSERT INTO sethomex_rented_slots (player_uuid, amount, expires_at) VALUES (?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE amount = ?, expires_at = ?";
            if (!plugin.getDatabaseManager().isMySQL()) {
                query = "INSERT OR REPLACE INTO sethomex_rented_slots (player_uuid, amount, expires_at) VALUES (?, ?, ?)";
            }

            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, playerUuid.toString());
                stmt.setInt(2, newAmount);
                stmt.setLong(3, expiresAt);
                if (plugin.getDatabaseManager().isMySQL()) {
                    stmt.setInt(4, newAmount);
                    stmt.setLong(5, expiresAt);
                }
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("DB Error saving rented slots: " + e.getMessage());
            }
        });
    }

    public int getRentedSlots(UUID playerUuid) {
        return rentedSlotsCache.getOrDefault(playerUuid, 0);
    }

    public String getPlayerName(UUID uuid) {
        if (uuid == null) return "Unknown";
        String name = nameCache.get(uuid);
        if (name != null) {
            return name;
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            nameCache.put(uuid, online.getName());
            return online.getName();
        }
        String offlineName = Bukkit.getOfflinePlayer(uuid).getName();
        if (offlineName != null) {
            nameCache.put(uuid, offlineName);
            return offlineName;
        }
        return "Unknown";
    }
}
