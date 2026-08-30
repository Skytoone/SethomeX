package fr.skynex.sethomex.listeners;

import fr.skynex.sethomex.SethomeX;
import fr.skynex.sethomex.models.Home;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

public class RespawnListener implements Listener {

    private final SethomeX plugin;

    public RespawnListener(SethomeX plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (!plugin.getConfig().getBoolean("homes.allow-respawn-at-home", true)) {
            return;
        }

        // Récupérer la priorité de respawn
        String priority = plugin.getConfig().getString("homes.respawn-priority", "BED_THEN_HOME").toUpperCase();
        
        // Si la priorité est BED_THEN_HOME, et que le joueur respawn à son lit ou ancre, on respecte son choix natif
        if (priority.equals("BED_THEN_HOME")) {
            if (event.isBedSpawn() || event.isAnchorSpawn()) {
                return;
            }
        }

        // Récupérer le home de respawn du joueur s'il en a un configuré
        Home respawnHome = plugin.getHomeManager().getRespawnHome(event.getPlayer().getUniqueId());
        
        if (respawnHome != null) {
            Location loc = respawnHome.getLocation();
            if (loc != null) {
                // Overrider la destination du respawn natively dans l'event !
                event.setRespawnLocation(loc);
                
                // Optionnel : notifier le joueur ou jouer un son au spawn effectif via task
                plugin.getScheduler().runTaskLaterAtEntity(event.getPlayer(), () -> {
                    if (event.getPlayer().isOnline()) {
                        plugin.getMessageManager().sendMessage(event.getPlayer(), "respawn.at-home", "{name}", respawnHome.getName());
                        event.getPlayer().playSound(loc, org.bukkit.Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.0f);
                    }
                }, 5L); // Petit délai pour être sûr qu'il a respawn
            }
        }
    }

    @EventHandler
    public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        org.bukkit.entity.Player player = event.getEntity();
        if (!plugin.getConfig().getBoolean("death.enable-auto-home-prompt", true)) {
            return;
        }

        java.util.Collection<Home> homes = plugin.getHomeManager().getPlayerHomes(player);
        if (homes == null || homes.isEmpty()) {
            return;
        }

        Location deathLoc = player.getLocation();
        Home nearestHome = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (Home home : homes) {
            Location homeLoc = home.getLocation();
            if (homeLoc != null && homeLoc.getWorld().equals(deathLoc.getWorld())) {
                double distSq = homeLoc.distanceSquared(deathLoc);
                if (distSq < nearestDistSq) {
                    nearestDistSq = distSq;
                    nearestHome = home;
                }
            }
        }

        if (nearestHome == null) {
            nearestHome = homes.iterator().next();
        }

        final Home targetHome = nearestHome;
        plugin.getScheduler().runTaskLaterAtEntity(player, () -> {
            if (player.isOnline()) {
                String rawMsg = plugin.getConfig().getString("messages.death-re-tp", "&6[SethomeX] &eVous êtes mort ! Cliquez &a&l[ICI] &epour retourner à votre home &f{home} &ele plus proche.");
                rawMsg = rawMsg.replace("{home}", targetHome.getName());
                
                net.kyori.adventure.text.Component message = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(rawMsg)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/home " + targetHome.getName()))
                        .hoverEvent(net.kyori.adventure.text.Component.text("Téléporter à " + targetHome.getName(), net.kyori.adventure.text.format.NamedTextColor.GREEN));
                
                player.sendMessage(message);
            }
        }, 40L);
    }
}
