package fr.skynex.sethomex.listeners;

import fr.skynex.sethomex.SethomeX;
import fr.skynex.sethomex.models.Home;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;

public class SignListener implements Listener {

    private final SethomeX plugin;

    public SignListener(SethomeX plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    @SuppressWarnings("deprecation")
    public void onSignChange(SignChangeEvent event) {
        String line0 = event.getLine(0);
        if (line0 != null && line0.equalsIgnoreCase("[sethomex]")) {
            Player player = event.getPlayer();
            if (!player.hasPermission("sethomex.sign.create") && !player.isOp()) {
                plugin.getMessageManager().sendMessage(player, "commands.no-permission");
                event.setCancelled(true);
                return;
            }
            event.setLine(0, "§6§l[SethomeX]");
            player.sendMessage("§a[SethomeX] Panneau de téléportation créé avec succès !");
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
        }
    }

    @EventHandler
    @SuppressWarnings("deprecation")
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        if (event.getClickedBlock() == null) {
            return;
        }
        if (event.getClickedBlock().getState() instanceof Sign sign) {
            String line0 = sign.getLine(0);
            if (line0.equals("§6§l[SethomeX]")) {
                Player player = event.getPlayer();
                String line1 = sign.getLine(1).trim();
                String line2 = sign.getLine(2).trim();

                if (line1.isEmpty()) {
                    player.sendMessage("§cErreur: Le panneau n'a pas de destination configurée.");
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    return;
                }

                OfflinePlayer targetOwner = player;
                String homeName = line1;

                if (!line2.isEmpty()) {
                    targetOwner = Bukkit.getOfflinePlayer(line1);
                    homeName = line2;
                } else {
                    if (line1.contains(":")) {
                        String[] parts = line1.split(":", 2);
                        targetOwner = Bukkit.getOfflinePlayer(parts[0]);
                        homeName = parts[1];
                    } else if (line1.contains(" ")) {
                        String[] parts = line1.split(" ", 2);
                        targetOwner = Bukkit.getOfflinePlayer(parts[0]);
                        homeName = parts[1];
                    }
                }

                Home home = plugin.getHomeManager().getHome(targetOwner.getUniqueId(), homeName);
                if (home == null) {
                    player.sendMessage("§cErreur: Le home ciblé n'existe pas.");
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    return;
                }

                // Check access: Owner, Admin, Public or Trusted
                boolean hasAccess = false;
                if (home.getPlayerUuid().equals(player.getUniqueId())) {
                    hasAccess = true;
                } else if (player.hasPermission("sethomex.command.admin")) {
                    hasAccess = true;
                } else if (home.isPublic()) {
                    hasAccess = true;
                } else if (home.isTrusted(player.getUniqueId())) {
                    hasAccess = true;
                }

                if (!hasAccess) {
                    player.sendMessage("§cErreur: Vous n'avez pas l'accès à ce home privé.");
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    return;
                }

                // Start Teleport!
                plugin.getTeleportManager().startTeleport(player, home);
            }
        }
    }
}
