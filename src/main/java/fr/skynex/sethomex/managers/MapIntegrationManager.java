package fr.skynex.sethomex.managers;

import fr.skynex.sethomex.SethomeX;
import fr.skynex.sethomex.integration.MapHook;
import fr.skynex.sethomex.models.Home;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class MapIntegrationManager {

    private final SethomeX plugin;
    private final List<MapHook> activeHooks = new ArrayList<>();
    private boolean isEnabled = false;

    public MapIntegrationManager(SethomeX plugin) {
        this.plugin = plugin;
        initHooks();
    }

    /**
     * Détecte et initialise de manière ultra-sécurisée les hooks de cartographie activement installés.
     */
    private void initHooks() {
        this.isEnabled = plugin.getConfig().getBoolean("integrations.maps.enabled", true);
        if (!this.isEnabled) {
            return;
        }

        // 1. Dynmap Hook
        if (Bukkit.getPluginManager().isPluginEnabled("dynmap")) {
            try {
                Class<?> hookClass = Class.forName("fr.skynex.sethomex.integration.impl.DynmapHook");
                MapHook dynmapHook = (MapHook) hookClass.getDeclaredConstructor().newInstance();
                activeHooks.add(dynmapHook);
                plugin.getLogger().info("Successfully hooked into Dynmap for public homes visualization.");
            } catch (Throwable e) {
                plugin.getLogger().log(Level.WARNING, "Failed to initialize Dynmap hook: " + e.getMessage());
            }
        }

        // 2. BlueMap Hook
        if (Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
            try {
                Class<?> hookClass = Class.forName("fr.skynex.sethomex.integration.impl.BlueMapHook");
                MapHook blueMapHook = (MapHook) hookClass.getDeclaredConstructor().newInstance();
                activeHooks.add(blueMapHook);
                plugin.getLogger().info("Successfully hooked into BlueMap for public homes visualization.");
            } catch (Throwable e) {
                plugin.getLogger().log(Level.WARNING, "Failed to initialize BlueMap hook: " + e.getMessage());
            }
        }

        // 3. Squaremap Hook
        if (Bukkit.getPluginManager().isPluginEnabled("squaremap")) {
            try {
                Class<?> hookClass = Class.forName("fr.skynex.sethomex.integration.impl.SquaremapHook");
                MapHook squaremapHook = (MapHook) hookClass.getDeclaredConstructor().newInstance();
                activeHooks.add(squaremapHook);
                plugin.getLogger().info("Successfully hooked into Squaremap for public homes visualization.");
            } catch (Throwable e) {
                plugin.getLogger().log(Level.WARNING, "Failed to initialize Squaremap hook: " + e.getMessage());
            }
        }
    }

    /**
     * Enregistre ou met à jour un home sur toutes les cartes actives.
     */
    public void syncHome(Home home) {
        if (!isEnabled) return;
        
        // S'assurer que le home est synchronisé de manière asynchrone pour ne pas ralentir le thread principal
        plugin.getScheduler().runTaskAsync(() -> {
            for (MapHook hook : activeHooks) {
                try {
                    if (home.isPublic()) {
                        hook.registerHome(home);
                    } else {
                        hook.removeHome(home); // Supprime s'il n'est plus public !
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /**
     * Retire un home de toutes les cartes actives.
     */
    public void removeHome(Home home) {
        if (!isEnabled) return;
        
        plugin.getScheduler().runTaskAsync(() -> {
            for (MapHook hook : activeHooks) {
                try {
                    hook.removeHome(home);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /**
     * Synchronise l'ensemble des homes publics actuels.
     */
    public void syncAllPublicHomes() {
        if (!isEnabled || activeHooks.isEmpty()) return;
        
        plugin.getScheduler().runTaskAsync(() -> {
            List<Home> publicHomes = plugin.getHomeManager().getAllPublicHomesAsync().join();
            for (Home home : publicHomes) {
                for (MapHook hook : activeHooks) {
                    try {
                        hook.registerHome(home);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    /**
     * Nettoie tous les marqueurs enregistrés (lors du reload ou disable du plugin).
     */
    public void cleanup() {
        for (MapHook hook : activeHooks) {
            try {
                hook.clearAll();
            } catch (Throwable ignored) {
            }
        }
        activeHooks.clear();
    }
}
