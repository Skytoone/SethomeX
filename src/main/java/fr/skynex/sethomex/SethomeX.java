package fr.skynex.sethomex;

import fr.skynex.sethomex.commands.HomeCommands;
import fr.skynex.sethomex.listeners.TeleportListener;
import fr.skynex.sethomex.listeners.RespawnListener;
import fr.skynex.sethomex.listeners.SignListener;
import fr.skynex.sethomex.managers.HomeManager;
import fr.skynex.sethomex.managers.TeleportManager;
import fr.skynex.sethomex.managers.MessageManager;
import fr.skynex.sethomex.managers.EconomyManager;
import fr.skynex.sethomex.managers.GUIManager;
import fr.skynex.sethomex.managers.MapIntegrationManager;
import fr.skynex.sethomex.storage.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;
import fr.skynex.sethomex.integration.PlaceholderAPIExpansion;
import fr.skynex.sethomex.util.UpdateChecker;
import fr.skynex.sethomex.util.scheduler.TaskScheduler;
import fr.skynex.sethomex.util.scheduler.BukkitSchedulerImpl;
import fr.skynex.sethomex.util.scheduler.FoliaSchedulerImpl;

public final class SethomeX extends JavaPlugin {

    private static SethomeX instance;
    private TaskScheduler scheduler;
    private DatabaseManager databaseManager;
    private HomeManager homeManager;
    private TeleportManager teleportManager;
    private MessageManager messageManager;
    private EconomyManager economyManager;
    private GUIManager guiManager;
    private MapIntegrationManager mapIntegrationManager;
    private fr.skynex.sethomex.integration.BungeeSyncManager bungeeSyncManager;
    private fr.skynex.sethomex.integration.ClaimsIntegrationManager claimsIntegrationManager;
    private fr.skynex.sethomex.managers.PortalManager portalManager;

    @Override
    public void onEnable() {
        instance = this;

        // Détection de Folia par réflexion
        boolean isFolia = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionScheduler");
            isFolia = true;
        } catch (ClassNotFoundException ignored) {}

        if (isFolia) {
            this.scheduler = new FoliaSchedulerImpl(this);
            getLogger().info("Folia detected! Region-aware Scheduler initialized.");
        } else {
            this.scheduler = new BukkitSchedulerImpl(this);
            getLogger().info("Spigot/Paper detected! Legacy Bukkit Scheduler initialized.");
        }

        // Banner de démarrage stylée dans la console
        sendStartupBanner();

        // Initialisation de la configuration par défaut
        fr.skynex.sethomex.util.ConfigUpdater.updateConfig(this, "config.yml");
        saveDefaultConfig();

        // Initialisation du gestionnaire de messages
        this.messageManager = new MessageManager(this);
        
        // Initialisation du gestionnaire de GUI
        this.guiManager = new GUIManager(this);

        // Initialisation du gestionnaire d'économie
        this.economyManager = new EconomyManager(this);

        // Initialisation du gestionnaire de base de données
        this.databaseManager = new DatabaseManager(this);
        if (!this.databaseManager.connect()) {
            getLogger().severe("Failed to connect to the database! Disabling plugin...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialisation des Managers
        this.homeManager = new HomeManager(this);
        this.teleportManager = new TeleportManager(this);
        this.bungeeSyncManager = new fr.skynex.sethomex.integration.BungeeSyncManager(this);
        this.claimsIntegrationManager = new fr.skynex.sethomex.integration.ClaimsIntegrationManager(this);

        // Chargement initial des données
        this.homeManager.loadOnlinePlayers();
        this.homeManager.runAutoPurgeTask();

        // Initialisation des Cartes Web (Dynmap, BlueMap, Squaremap)
        this.mapIntegrationManager = new MapIntegrationManager(this);
        this.scheduler.runTaskLater(() -> this.mapIntegrationManager.syncAllPublicHomes(), 100L);

        // Enregistrement des événements et écouteurs
        this.portalManager = new fr.skynex.sethomex.managers.PortalManager(this);
        getServer().getPluginManager().registerEvents(this.portalManager, this);
        getServer().getPluginManager().registerEvents(new TeleportListener(this), this);
        getServer().getPluginManager().registerEvents(new RespawnListener(this), this);
        getServer().getPluginManager().registerEvents(new fr.skynex.sethomex.listeners.PlayerJoinListener(this), this);
        getServer().getPluginManager().registerEvents(new SignListener(this), this);

        // Tâche de maintenance automatique (Purger)
        if (getConfig().getBoolean("database.maintenance.auto-purge.enabled", false)) {
            int days = getConfig().getInt("database.maintenance.auto-purge.days-inactive", 30);
            // Exécuter 10 secondes après le démarrage complet pour ne pas alourdir le boot principal !
            this.scheduler.runTaskLater(() -> homeManager.runPurgeTask(days), 200L);
        }

        // Enregistrement des commandes
        registerCommands();

        // Enregistrement de l'expansion PlaceholderAPI (Optionnel)
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new PlaceholderAPIExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion integration successful!");
        }

        // Enregistrement de bStats officiel pour le suivi des statistiques (ID: 31204)
        try {
            int pluginId = 31204;
            new Metrics(this, pluginId);
            getLogger().info("Usage statistics metrics (bStats ID: 31204) enabled!");
        } catch (Exception e) {
            getLogger().warning("Unable to enable bStats.");
        }

        // Vérification des mises à jour SpigotMC (ID de ressource à mettre à jour lors
        // de la publication)
        if (getConfig().getBoolean("updates.check-updates", true)) {
            int resourceId = 111111; // ID de ressource SpigotMC à configurer
            new UpdateChecker(this, resourceId).getVersion(version -> {
                if (isNewerVersion(getPluginMeta().getVersion(), version)) {
                    getLogger().warning("A new version of SethomeX is available (" + version
                            + ")! Download it here: https://www.spigotmc.org/resources/" + resourceId);
                } else if (getPluginMeta().getVersion().equals(version)) {
                    getLogger().info("SethomeX is up to date (Version " + version + ").");
                }
            });
        }

        getLogger().info("SethomeX initialized successfully!");
    }

    @Override
    public void onDisable() {
        if (this.portalManager != null) {
            this.portalManager.savePortals();
        }
        if (this.homeManager != null) {
            this.homeManager.shutdown();
        }
        if (this.databaseManager != null) {
            this.databaseManager.disconnect();
        }
        if (this.mapIntegrationManager != null) {
            this.mapIntegrationManager.cleanup();
        }
        getLogger().info("SethomeX stopped cleanly.");
    }

    private void registerCommands() {
        HomeCommands homeCommands = new HomeCommands(this);
        getCommand("sethome").setExecutor(homeCommands);
        getCommand("home").setExecutor(homeCommands);
        getCommand("delhome").setExecutor(homeCommands);
        getCommand("sethomex").setExecutor(homeCommands);
    }

    private void sendStartupBanner() {
        Bukkit.getConsoleSender().sendMessage("§7Version " + getPluginMeta().getVersion() + " - By SkyNex");
    }

    public static SethomeX getInstance() {
        return instance;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public HomeManager getHomeManager() {
        return homeManager;
    }

    public TeleportManager getTeleportManager() {
        return teleportManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public GUIManager getGUIManager() {
        return guiManager;
    }

    public EconomyManager getEconomyManager() {
        return economyManager;
    }

    public MapIntegrationManager getMapIntegrationManager() {
        return mapIntegrationManager;
    }

    public fr.skynex.sethomex.integration.BungeeSyncManager getBungeeSyncManager() {
        return bungeeSyncManager;
    }

    public fr.skynex.sethomex.integration.ClaimsIntegrationManager getClaimsIntegrationManager() {
        return claimsIntegrationManager;
    }

    public fr.skynex.sethomex.managers.PortalManager getPortalManager() {
        return portalManager;
    }

    public TaskScheduler getScheduler() {
        return scheduler;
    }

    public void reloadAll() {
        reloadConfig();
        if (messageManager != null) messageManager.loadMessages();
        if (guiManager != null) guiManager.load();
        if (mapIntegrationManager != null) {
            mapIntegrationManager.cleanup();
            mapIntegrationManager = new MapIntegrationManager(this);
            this.scheduler.runTaskLater(() -> mapIntegrationManager.syncAllPublicHomes(), 40L);
        }
    }

    private boolean isNewerVersion(String current, String online) {
        try {
            String[] currentParts = current.split("\\.");
            String[] onlineParts = online.split("\\.");
            int length = Math.max(currentParts.length, onlineParts.length);
            for (int i = 0; i < length; i++) {
                int c = (i < currentParts.length) ? Integer.parseInt(currentParts[i].replaceAll("[^0-9]", "")) : 0;
                int o = (i < onlineParts.length) ? Integer.parseInt(onlineParts[i].replaceAll("[^0-9]", "")) : 0;
                if (o > c)
                    return true;
                if (c > o)
                    return false;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }
}
