package fr.skynex.sethomex.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import fr.skynex.sethomex.SethomeX;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class DatabaseManager {

    private final SethomeX plugin;
    private HikariDataSource dataSource;
    private boolean isMySQL;

    public DatabaseManager(SethomeX plugin) {
        this.plugin = plugin;
    }

    public boolean isMySQL() {
        return isMySQL;
    }

    public boolean connect() {
        String type = plugin.getConfig().getString("database.type", "SQLITE").toUpperCase();
        this.isMySQL = type.equals("MYSQL");

        try {
            if (isMySQL) {
                setupMySQL();
            } else {
                setupSQLite();
            }
            createTables();
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Error during database initialization: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void setupSQLite() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        File dbFile = new File(dataFolder, "sethomex.db");

        HikariConfig config = new HikariConfig();
        config.setPoolName("SethomeX-SQLitePool");
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());

        // Paramètres optimaux pour SQLite avec HikariCP
        config.setMaximumPoolSize(1); // Recommandé pour éviter les verrous de fichier (database locks)
        config.setConnectionTimeout(10000);

        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        this.dataSource = new HikariDataSource(config);
        plugin.getLogger().info("SQLite Connection (HikariCP) established successfully!");
    }

    private void setupMySQL() {
        HikariConfig config = new HikariConfig();
        String host = plugin.getConfig().getString("database.mysql.host", "localhost");
        int port = plugin.getConfig().getInt("database.mysql.port", 3306);
        String db = plugin.getConfig().getString("database.mysql.database", "sethomex");

        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + db);
        config.setUsername(plugin.getConfig().getString("database.mysql.username", "root"));
        config.setPassword(plugin.getConfig().getString("database.mysql.password", ""));

        config.setMaximumPoolSize(plugin.getConfig().getInt("database.mysql.pool.maximum-size", 10));
        config.setMinimumIdle(plugin.getConfig().getInt("database.mysql.pool.minimum-idle", 2));
        config.setIdleTimeout(plugin.getConfig().getLong("database.mysql.pool.idle-timeout", 600000));
        config.setMaxLifetime(plugin.getConfig().getLong("database.mysql.pool.max-lifetime", 1800000));
        config.setConnectionTimeout(plugin.getConfig().getLong("database.mysql.pool.connection-timeout", 30000));

        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        this.dataSource = new HikariDataSource(config);
        plugin.getLogger().info("MySQL Connection (HikariCP) established successfully!");
    }

    private void createTables() throws SQLException {
        String createTableQuery = "CREATE TABLE IF NOT EXISTS sethomex_homes (" +
                (isMySQL ? "id INT AUTO_INCREMENT PRIMARY KEY," : "id INTEGER PRIMARY KEY AUTOINCREMENT,") +
                "player_uuid VARCHAR(36) NOT NULL," +
                "home_name VARCHAR(64) NOT NULL," +
                "world_name VARCHAR(128) NOT NULL," +
                "x DOUBLE NOT NULL," +
                "y DOUBLE NOT NULL," +
                "z DOUBLE NOT NULL," +
                "yaw FLOAT NOT NULL," +
                "pitch FLOAT NOT NULL," +
                "icon_material VARCHAR(64) NOT NULL," +
                "icon_texture TEXT NULL," +
                "is_public INT DEFAULT 0," +
                "visits BIGINT DEFAULT 0," +
                "is_respawn INT DEFAULT 0," +
                "category VARCHAR(32) DEFAULT 'none'," +
                "description TEXT NULL," +
                "welcome_message TEXT NULL," +
                "visit_fee DOUBLE DEFAULT 0.0," +
                "music_disc VARCHAR(64) DEFAULT 'none'," +
                "time_lock BIGINT DEFAULT -1," +
                "weather_lock VARCHAR(32) DEFAULT 'none'," +
                "is_sponsored INT DEFAULT 0," +
                "sponsored_until BIGINT DEFAULT 0," +
                "UNIQUE(player_uuid, home_name)" +
                ");";

        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(createTableQuery)) {
            stmt.execute();

            // MIGRATIONS : Ajout progressif des colonnes si elles manquent
            java.sql.DatabaseMetaData meta = conn.getMetaData();
            
            // Check for is_public
            try (java.sql.ResultSet rs = meta.getColumns(null, null, "sethomex_homes", "is_public")) {
                if (!rs.next()) {
                    conn.prepareStatement("ALTER TABLE sethomex_homes ADD COLUMN is_public INT DEFAULT 0").executeUpdate();
                    plugin.getLogger().info("Migration: Added column 'is_public'");
                }
            }
            // Check for visits
            try (java.sql.ResultSet rs = meta.getColumns(null, null, "sethomex_homes", "visits")) {
                if (!rs.next()) {
                    conn.prepareStatement("ALTER TABLE sethomex_homes ADD COLUMN visits BIGINT DEFAULT 0").executeUpdate();
                    plugin.getLogger().info("Migration: Added column 'visits'");
                }
            }
            // Check for is_respawn
            try (java.sql.ResultSet rs = meta.getColumns(null, null, "sethomex_homes", "is_respawn")) {
                if (!rs.next()) {
                    conn.prepareStatement("ALTER TABLE sethomex_homes ADD COLUMN is_respawn INT DEFAULT 0").executeUpdate();
                    plugin.getLogger().info("Migration: Added column 'is_respawn'");
                }
            }
            // Check for icon_texture
            try (java.sql.ResultSet rs = meta.getColumns(null, null, "sethomex_homes", "icon_texture")) {
                if (!rs.next()) {
                    conn.prepareStatement("ALTER TABLE sethomex_homes ADD COLUMN icon_texture TEXT NULL").executeUpdate();
                    plugin.getLogger().info("Migration: Added column 'icon_texture'");
                }
            }
            // Check for category
            try (java.sql.ResultSet rs = meta.getColumns(null, null, "sethomex_homes", "category")) {
                if (!rs.next()) {
                    conn.prepareStatement("ALTER TABLE sethomex_homes ADD COLUMN category VARCHAR(32) DEFAULT 'none'").executeUpdate();
                    plugin.getLogger().info("Migration: Added column 'category'");
                }
            }
            // Check for description
            try (java.sql.ResultSet rs = meta.getColumns(null, null, "sethomex_homes", "description")) {
                if (!rs.next()) {
                    conn.prepareStatement("ALTER TABLE sethomex_homes ADD COLUMN description TEXT NULL").executeUpdate();
                    plugin.getLogger().info("Migration: Added column 'description'");
                }
            }
            // Check for welcome_message
            try (java.sql.ResultSet rs = meta.getColumns(null, null, "sethomex_homes", "welcome_message")) {
                if (!rs.next()) {
                    conn.prepareStatement("ALTER TABLE sethomex_homes ADD COLUMN welcome_message TEXT NULL").executeUpdate();
                    plugin.getLogger().info("Migration: Added column 'welcome_message'");
                }
            }
            // Check for visit_fee
            try (java.sql.ResultSet rs = meta.getColumns(null, null, "sethomex_homes", "visit_fee")) {
                if (!rs.next()) {
                    conn.prepareStatement("ALTER TABLE sethomex_homes ADD COLUMN visit_fee DOUBLE DEFAULT 0.0").executeUpdate();
                    plugin.getLogger().info("Migration: Added column 'visit_fee'");
                }
            }
            // Check for music_disc
            try (java.sql.ResultSet rs = meta.getColumns(null, null, "sethomex_homes", "music_disc")) {
                if (!rs.next()) {
                    conn.prepareStatement("ALTER TABLE sethomex_homes ADD COLUMN music_disc VARCHAR(64) DEFAULT 'none'").executeUpdate();
                    plugin.getLogger().info("Migration: Added column 'music_disc'");
                }
            }
            // Check for time_lock
            try (java.sql.ResultSet rs = meta.getColumns(null, null, "sethomex_homes", "time_lock")) {
                if (!rs.next()) {
                    conn.prepareStatement("ALTER TABLE sethomex_homes ADD COLUMN time_lock BIGINT DEFAULT -1").executeUpdate();
                    plugin.getLogger().info("Migration: Added column 'time_lock'");
                }
            }
            // Check for weather_lock
            try (java.sql.ResultSet rs = meta.getColumns(null, null, "sethomex_homes", "weather_lock")) {
                if (!rs.next()) {
                    conn.prepareStatement("ALTER TABLE sethomex_homes ADD COLUMN weather_lock VARCHAR(32) DEFAULT 'none'").executeUpdate();
                    plugin.getLogger().info("Migration: Added column 'weather_lock'");
                }
            }
            // Check for is_sponsored
            try (java.sql.ResultSet rs = meta.getColumns(null, null, "sethomex_homes", "is_sponsored")) {
                if (!rs.next()) {
                    conn.prepareStatement("ALTER TABLE sethomex_homes ADD COLUMN is_sponsored INT DEFAULT 0").executeUpdate();
                    plugin.getLogger().info("Migration: Added column 'is_sponsored'");
                }
            }
            // Check for sponsored_until
            try (java.sql.ResultSet rs = meta.getColumns(null, null, "sethomex_homes", "sponsored_until")) {
                if (!rs.next()) {
                    conn.prepareStatement("ALTER TABLE sethomex_homes ADD COLUMN sponsored_until BIGINT DEFAULT 0").executeUpdate();
                    plugin.getLogger().info("Migration: Added column 'sponsored_until'");
                }
            }

            // 3. Création de la table de confiance (TRUSTS) pour le partage
            String createTrustsTable = "CREATE TABLE IF NOT EXISTS sethomex_trusts (" +
                    "owner_uuid VARCHAR(36) NOT NULL," +
                    "home_name VARCHAR(64) NOT NULL," +
                    "guest_uuid VARCHAR(36) NOT NULL," +
                    "expires_at BIGINT DEFAULT -1," +
                    "PRIMARY KEY (owner_uuid, home_name, guest_uuid)" +
                    ");";
            conn.prepareStatement(createTrustsTable).executeUpdate();

            // Create index for guest_uuid if not exist (compatible with both MySQL and SQLite)
            try {
                conn.prepareStatement("CREATE INDEX idx_sethomex_trusts_guest ON sethomex_trusts (guest_uuid)").executeUpdate();
            } catch (SQLException ignored) {}

            // Migration pour sethomex_trusts (expires_at)
            try (java.sql.ResultSet rs = meta.getColumns(null, null, "sethomex_trusts", "expires_at")) {
                if (!rs.next()) {
                    conn.prepareStatement("ALTER TABLE sethomex_trusts ADD COLUMN expires_at BIGINT DEFAULT -1").executeUpdate();
                    plugin.getLogger().info("Migration: Added column 'expires_at' to sethomex_trusts");
                }
            }

            // Migration pour sethomex_trusts (role)
            try (java.sql.ResultSet rs = meta.getColumns(null, null, "sethomex_trusts", "role")) {
                if (!rs.next()) {
                    conn.prepareStatement("ALTER TABLE sethomex_trusts ADD COLUMN role VARCHAR(32) DEFAULT 'VISITOR'").executeUpdate();
                    plugin.getLogger().info("Migration: Added column 'role' to sethomex_trusts");
                }
            }

            // 4. Création de la table de suivi des activités (Purger / Statistiques)
            String createUsersTable = "CREATE TABLE IF NOT EXISTS sethomex_users (" +
                    "player_uuid VARCHAR(36) PRIMARY KEY," +
                    "player_name VARCHAR(64) NULL," +
                    "last_seen BIGINT NOT NULL," +
                    "selected_particle VARCHAR(64) DEFAULT 'default'," +
                    "selected_style VARCHAR(32) DEFAULT 'default'," +
                    "selected_sound VARCHAR(64) DEFAULT 'default'," +
                    "selected_success_sound VARCHAR(64) DEFAULT 'default'" +
                    ");";
            conn.prepareStatement(createUsersTable).executeUpdate();

            // Migration pour sethomex_users (cosmetics)
            try (java.sql.ResultSet rs = meta.getColumns(null, null, "sethomex_users", "selected_particle")) {
                if (!rs.next()) {
                    conn.prepareStatement("ALTER TABLE sethomex_users ADD COLUMN selected_particle VARCHAR(64) DEFAULT 'default'").executeUpdate();
                    conn.prepareStatement("ALTER TABLE sethomex_users ADD COLUMN selected_style VARCHAR(32) DEFAULT 'default'").executeUpdate();
                    conn.prepareStatement("ALTER TABLE sethomex_users ADD COLUMN selected_sound VARCHAR(64) DEFAULT 'default'").executeUpdate();
                    conn.prepareStatement("ALTER TABLE sethomex_users ADD COLUMN selected_success_sound VARCHAR(64) DEFAULT 'default'").executeUpdate();
                    plugin.getLogger().info("Migration: Added cosmetics columns to sethomex_users");
                }
            }
            // Migration pour sethomex_users (player_name)
            try (java.sql.ResultSet rs = meta.getColumns(null, null, "sethomex_users", "player_name")) {
                if (!rs.next()) {
                    conn.prepareStatement("ALTER TABLE sethomex_users ADD COLUMN player_name VARCHAR(64) NULL").executeUpdate();
                    plugin.getLogger().info("Migration: Added player_name column to sethomex_users");
                }
            }

            // 5. Création de la table des favoris (FAVORITES)
            String createFavoritesTable = "CREATE TABLE IF NOT EXISTS sethomex_favorites (" +
                    "player_uuid VARCHAR(36) NOT NULL," +
                    "owner_uuid VARCHAR(36) NOT NULL," +
                    "home_name VARCHAR(64) NOT NULL," +
                    "PRIMARY KEY (player_uuid, owner_uuid, home_name)" +
                    ");";
            conn.prepareStatement(createFavoritesTable).executeUpdate();

            // 6. Création de la table de likes (LIKES)
            String createLikesTable = "CREATE TABLE IF NOT EXISTS sethomex_likes (" +
                    "owner_uuid VARCHAR(36) NOT NULL," +
                    "home_name VARCHAR(64) NOT NULL," +
                    "player_uuid VARCHAR(36) NOT NULL," +
                    "PRIMARY KEY (owner_uuid, home_name, player_uuid)" +
                    ");";
            conn.prepareStatement(createLikesTable).executeUpdate();

            // 7. Création de la table des bannissements (BANS)
            String createBansTable = "CREATE TABLE IF NOT EXISTS sethomex_bans (" +
                    "owner_uuid VARCHAR(36) NOT NULL," +
                    "home_name VARCHAR(64) NOT NULL," +
                    "banned_uuid VARCHAR(36) NOT NULL," +
                    "PRIMARY KEY (owner_uuid, home_name, banned_uuid)" +
                    ");";
            conn.prepareStatement(createBansTable).executeUpdate();

            // 8. Création de la table des visites (VISITS_LOG)
            String createVisitsLogTable = "CREATE TABLE IF NOT EXISTS sethomex_visits_log (" +
                    "id " + (isMySQL ? "INT AUTO_INCREMENT PRIMARY KEY," : "INTEGER PRIMARY KEY AUTOINCREMENT,") +
                    "owner_uuid VARCHAR(36) NOT NULL," +
                    "home_name VARCHAR(64) NOT NULL," +
                    "visitor_uuid VARCHAR(36) NOT NULL," +
                    "visitor_name VARCHAR(64) NOT NULL," +
                    "timestamp BIGINT NOT NULL" +
                    ");";
            conn.prepareStatement(createVisitsLogTable).executeUpdate();

            // Create index for owner_uuid and home_name if not exist (compatible with both MySQL and SQLite)
            try {
                conn.prepareStatement("CREATE INDEX idx_sethomex_visits_owner_home ON sethomex_visits_log (owner_uuid, home_name)").executeUpdate();
            } catch (SQLException ignored) {}

            // 9. Création de la table des slots loués (RENTED_SLOTS)
            String createRentedSlotsTable = "CREATE TABLE IF NOT EXISTS sethomex_rented_slots (" +
                    "player_uuid VARCHAR(36) NOT NULL," +
                    "amount INT NOT NULL," +
                    "expires_at BIGINT NOT NULL," +
                    "PRIMARY KEY (player_uuid)" +
                    ");";
            conn.prepareStatement(createRentedSlotsTable).executeUpdate();
        }
    }

    public Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("DataSource not initialized!");
        }
        return dataSource.getConnection();
    }

    public void disconnect() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("Database connection closed.");
        }
    }
}
