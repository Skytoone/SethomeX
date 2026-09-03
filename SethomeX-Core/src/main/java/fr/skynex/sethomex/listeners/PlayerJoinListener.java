package fr.skynex.sethomex.listeners;

import fr.skynex.sethomex.SethomeX;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerJoinListener implements Listener {

    private final SethomeX plugin;

    public PlayerJoinListener(SethomeX plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Restaurer l'état de preview s'il a été interrompu
        plugin.getTeleportManager().checkAndRestorePreviewState(event.getPlayer());

        // Enregistrer l'activité asynchrone pour le Purger/Maintenance
        plugin.getHomeManager().updateUserActivity(event.getPlayer());
        
        // Charger les homes du joueur dans le cache Caffeine
        plugin.getHomeManager().loadPlayerHomes(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getHomeManager().cleanupPlayer(event.getPlayer().getUniqueId());
        fr.skynex.sethomex.gui.HomeGUI.cleanPlayerSession(event.getPlayer().getUniqueId());
    }
}
