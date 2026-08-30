package fr.skynex.sethomex.listeners;

import fr.skynex.sethomex.SethomeX;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.entity.Projectile;

public class TeleportListener implements Listener {

    private final SethomeX plugin;

    public TeleportListener(SethomeX plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        
        // Reset weather/time overrides if moved far away
        plugin.getTeleportManager().checkAndResetOverrides(player);
        
        // Block movement for players currently previewing a home
        if (plugin.getTeleportManager().isPreviewing(player)) {
            org.bukkit.Location from = event.getFrom();
            org.bukkit.Location to = event.getTo();
            if (to != null && (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ())) {
                org.bukkit.Location newLoc = from.clone();
                newLoc.setYaw(to.getYaw());
                newLoc.setPitch(to.getPitch());
                event.setTo(newLoc);
            }
            return;
        }

        if (!plugin.getTeleportManager().isTeleporting(player)) {
            return;
        }

        if (plugin.getConfig().getBoolean("teleport.cancel-on-move", true)) {
            // Vérification intelligente : On n'annule que s'il s'est vraiment déplacé de bloc (on ignore les mouvements de tête !)
            if (event.getFrom().getX() != event.getTo().getX() ||
                    event.getFrom().getY() != event.getTo().getY() ||
                    event.getFrom().getZ() != event.getTo().getZ()) {
                plugin.getTeleportManager().cancelTeleport(player, true);
            }
        }
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        // Cancel damage for previewing players
        if (plugin.getTeleportManager().isPreviewing(player)) {
            event.setCancelled(true);
            return;
        }

        // Protection temporaire post-téléportation
        if (plugin.getTeleportManager().isProtected(player)) {
            event.setCancelled(true);
            return;
        }

        if (!plugin.getTeleportManager().isTeleporting(player)) {
            return;
        }

        if (plugin.getConfig().getBoolean("teleport.cancel-on-damage", true)) {
            plugin.getTeleportManager().cancelTeleport(player, true);
        }
    }

    @EventHandler
    public void onCombatDetection(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) return;
        if (!plugin.getConfig().getBoolean("teleport.cancel-in-combat", true)) return;

        // 1. Marquer la victime si c'est un joueur
        if (event.getEntity() instanceof Player victim) {
            plugin.getTeleportManager().tagCombat(victim);
        }

        // 2. Identifier l'attaquant (Direct ou par projectile type Flèche)
        Player attacker = null;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof Projectile projectile) {
            if (projectile.getShooter() instanceof Player p) {
                attacker = p;
            }
        }

        // Marquer l'attaquant
        if (attacker != null) {
            plugin.getTeleportManager().tagCombat(attacker);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (plugin.getTeleportManager().isTeleporting(player)) {
            plugin.getTeleportManager().cancelTeleport(player, false);
        }
        if (plugin.getTeleportManager().isPreviewing(player)) {
            plugin.getTeleportManager().endPreview(player, true, null);
        }
        plugin.getTeleportManager().removeOverrideOnQuit(player);
    }

    @EventHandler
    public void onPreviewInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (plugin.getTeleportManager().isPreviewing(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPreviewInventoryOpen(org.bukkit.event.inventory.InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            if (plugin.getTeleportManager().isPreviewing(player)) {
                event.setCancelled(true);
            }
        }
    }
}
