package fr.skynex.sethomex.commands;

import fr.skynex.sethomex.SethomeX;
import fr.skynex.sethomex.gui.HomeGUI;
import fr.skynex.sethomex.models.Home;
import fr.skynex.sethomex.storage.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;



public class HomeCommands implements CommandExecutor, TabCompleter {

    private final SethomeX plugin;
    private final HomeGUI gui;

    public static class PendingInvite {
        private final UUID hostUuid;
        private final String homeName;
        private final long expiryTime;

        public PendingInvite(UUID hostUuid, String homeName, long durationMs) {
            this.hostUuid = hostUuid;
            this.homeName = homeName;
            this.expiryTime = System.currentTimeMillis() + durationMs;
        }

        public UUID getHostUuid() {
            return hostUuid;
        }

        public String getHomeName() {
            return homeName;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expiryTime;
        }
    }

    private static final Map<UUID, Map<UUID, PendingInvite>> pendingInvites = new java.util.concurrent.ConcurrentHashMap<>();

    public HomeCommands(SethomeX plugin) {
        this.plugin = plugin;
        this.gui = new HomeGUI(plugin); // Initialise et enregistre le Listener du GUI
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        String cmdName = command.getName().toLowerCase();

        // La commande Admin est autorisée en Console
        if (cmdName.equals("sethomex")) {
            handleSetHomeXAdmin(sender, args);
            return true;
        }

        // Les autres commandes nécessitent un joueur physique
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendMessage(sender, "commands.only-players");
            return true;
        }

        switch (cmdName) {
            case "sethome":
                handleSetHome(player, args);
                break;
            case "home":
                handleHome(player, args);
                break;
            case "delhome":
                handleDelHome(player, args);
                break;
        }

        return true;
    }

    private void handleSetHome(Player player, String[] args) {
        if (args.length >= 4 && args[0].equalsIgnoreCase("portal") && args[1].equalsIgnoreCase("particle")) {
            String homeName = args[2];
            String particleName = args[3].toUpperCase();

            // Check if player owns the home
            Home home = plugin.getHomeManager().getHome(player, homeName);
            if (home == null) {
                plugin.getMessageManager().sendMessage(player, "home.error-not-found", "{name}", homeName);
                return;
            }

            // Validate particle
            try {
                org.bukkit.Particle.valueOf(particleName);
            } catch (IllegalArgumentException e) {
                plugin.getMessageManager().sendMessage(player, "portal.invalid-particle");
                return;
            }

            // Check if portal exists for this home
            fr.skynex.sethomex.managers.PortalManager.Portal portal = plugin.getPortalManager()
                    .getPortal(player.getUniqueId(), homeName);
            if (portal == null) {
                plugin.getMessageManager().sendMessage(player, "portal.not-found", "{name}", homeName);
                return;
            }

            portal.customParticle = particleName;
            plugin.getPortalManager().savePortals();

            plugin.getMessageManager().sendMessage(player, "portal.particle-updated", "{home}", homeName, "{particle}",
                    particleName);
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
            return;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("portal")) {
            String homeName = args[1];
            plugin.getPortalManager().enterCreationMode(player, homeName);
            plugin.getMessageManager().sendMessage(player, "portal.creation-instruction", "{name}", homeName);
            return;
        }

        String name = args.length > 0 ? args[0] : "home";

        // Regex de sécurité pour éviter les caractères louches dans le nom
        if (!name.matches("^[a-zA-Z0-9_-]+$")) {
            plugin.getMessageManager().sendMessage(player, "sethome.error-invalid-name");
            return;
        }

        int maxLength = plugin.getConfig().getInt("homes.max-name-length", 24);
        if (name.length() > maxLength) {
            plugin.getMessageManager().sendMessage(player, "sethome.error-too-long");
            return;
        }

        Collection<Home> currentHomes = plugin.getHomeManager().getPlayerHomes(player);
        int limit = plugin.getHomeManager().getPlayerLimit(player);

        // Vérification de la limite de homes (Si c'est un nouveau home)
        boolean exists = plugin.getHomeManager().getHome(player, name) != null;
        if (!exists && currentHomes.size() >= limit) {
            plugin.getMessageManager().sendMessage(player, "sethome.error-limit-reached", "{limit}",
                    String.valueOf(limit));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        // Sécurité optionnelle d'altitude (empêcher de sethome dans les airs)
        if (plugin.getConfig().getBoolean("safety.prevent-unsafe-sethome", true)) {
            if (player.getLocation().add(0, -1, 0).getBlock().getType() == Material.AIR && !player.isFlying()) {
                plugin.getMessageManager().sendMessage(player, "sethome.error-unsafe-location");
                return;
            }
        }

        // Vérification de la blacklist de mondes
        List<String> blacklistedWorlds = plugin.getConfig().getStringList("safety.blacklisted-worlds");
        if (blacklistedWorlds.contains(player.getWorld().getName())) {
            plugin.getMessageManager().sendMessage(player, "sethome.error-blacklisted-world");
            return;
        }

        // Vérification de la limite de homes par monde
        String worldName = player.getWorld().getName();
        if (plugin.getConfig().contains("world-limits." + worldName)) {
            int worldLimit = plugin.getConfig().getInt("world-limits." + worldName, -1);
            if (!exists && worldLimit != -1 && !player.isOp() && !player.hasPermission("sethomex.bypass.world-limit")) {
                long countInWorld = currentHomes.stream()
                        .filter(h -> h.getWorldName().equalsIgnoreCase(worldName))
                        .count();
                if (countInWorld >= worldLimit) {
                    plugin.getMessageManager().sendMessage(player, "sethome.error-world-limit-reached",
                            "{limit}", String.valueOf(worldLimit), "{world}", worldName);
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    return;
                }
            }
        }

        if (plugin.getConfig().getBoolean("protections.prevent-sethome-in-claims", true)) {
            plugin.getClaimsIntegrationManager().canAccessLocationAsync(player, player.getLocation())
                    .thenAccept(canAccess -> {
                        plugin.getScheduler().runTaskAtEntity(player, () -> {
                            if (!canAccess) {
                                plugin.getMessageManager().sendMessage(player, "safety.region-denied"); // Fallback key
                                return;
                            }
                            finalizeSetHome(player, name, exists);
                        });
                    });
        } else {
            finalizeSetHome(player, name, exists);
        }
    }

    private void finalizeSetHome(Player player, String name, boolean exists) {
        // --- LOGIQUE ÉCONOMIE CRÉATION ---
        double cost = plugin.getConfig().getDouble("economy.create-cost", 0.0);
        boolean isCharged = false;

        if (!exists && plugin.getEconomyManager().isEnabled() && cost > 0
                && !player.hasPermission("sethomex.bypass.cost")) {
            if (!plugin.getEconomyManager().hasEnough(player, cost)) {
                String formattedCost = plugin.getEconomyManager().format(cost);
                plugin.getMessageManager().sendMessage(player, "economy.insufficient-funds", "{cost}", formattedCost);
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }

            // On prélève !
            if (!plugin.getEconomyManager().withdraw(player, cost)) {
                plugin.getMessageManager().sendMessage(player, "economy.insufficient-funds", "{cost}",
                        plugin.getEconomyManager().format(cost));
                return;
            }
            isCharged = true;
        }

        plugin.getHomeManager().createHome(player, name, player.getLocation());
        plugin.getMessageManager().sendMessage(player, "sethome.success", "{name}", name);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.5f);

        if (isCharged) {
            plugin.getMessageManager().sendMessage(player, "economy.charged-create", "{cost}",
                    plugin.getEconomyManager().format(cost));
        }
    }

    private void handleHome(Player player, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("accept")) {
            String targetSenderName = (args.length >= 2) ? args[1] : null;
            handleHomeAccept(player, targetSenderName);
            return;
        }

        if (args.length == 0) {
            // Ouvre l'interface GUI avec un effet stylé
            gui.openMainGUI(player);
            return;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            handleHomeList(player);
            return;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("effects")) {
            gui.openEffectsGUI(player);
            return;
        }

        if (args.length >= 1 && (args[0].equalsIgnoreCase("compass") || args[0].equalsIgnoreCase("target"))) {
            if (args.length < 2) {
                plugin.getMessageManager().sendMessage(player, "home.compass-usage");
                return;
            }
            handleHomeCompass(player, args[1]);
            return;
        }

        // Mode Social ou Admin : /home <sous-commande> ou /home <joueur>
        if (args.length >= 2) {
            String arg0 = args[0].toLowerCase();

            if (arg0.equals("desc") || arg0.equals("description")) {
                if (args.length < 3) {
                    plugin.getMessageManager().sendMessage(player, "commands.description-usage");
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 2; i < args.length; i++) {
                    sb.append(args[i]).append(" ");
                }
                handleHomeDescription(player, args[1], sb.toString().trim());
                return;
            }

            if (arg0.equals("welcome")) {
                if (args.length < 3) {
                    plugin.getMessageManager().sendMessage(player, "commands.welcome-usage");
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 2; i < args.length; i++) {
                    sb.append(args[i]).append(" ");
                }
                handleHomeWelcome(player, args[1], sb.toString().trim());
                return;
            }

            if (arg0.equals("fee") || arg0.equals("taxe") || arg0.equals("price")) {
                if (args.length < 3) {
                    plugin.getMessageManager().sendMessage(player, "commands.fee-usage");
                    return;
                }
                double amount;
                try {
                    amount = Double.parseDouble(args[2]);
                    if (amount < 0) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    plugin.getMessageManager().sendMessage(player, "commands.fee-positive");
                    return;
                }
                
                double maxFee = plugin.getConfig().getDouble("economy.max-visit-fee", 5000.0);
                if (amount > maxFee) {
                    plugin.getMessageManager().sendMessage(player, "commands.fee-max-limit", "{max}", String.valueOf(maxFee));
                    return;
                }

                handleHomeFee(player, args[1], amount);
                return;
            }

            // Sous-commande : /home public <nom>
            if (arg0.equals("public")) {
                handleHomePublic(player, args[1]);
                return;
            }

            // Sous-commande : /home category <home> <nom_categorie>
            if (arg0.equals("category") || arg0.equals("folder")) {
                if (args.length < 3) {
                    plugin.getMessageManager().sendMessage(player, "home.category-usage", "{cmd}", arg0);
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 2; i < args.length; i++) {
                    sb.append(args[i]).append(" ");
                }
                handleHomeCategory(player, args[1], sb.toString().trim());
                return;
            }

            // Sous-commandes de partage (TRUST) : /home trust <home> <joueur> [durée/rôle]
            if (arg0.equals("trust") || arg0.equals("untrust")) {
                if (args.length < 3) {
                    plugin.getMessageManager().sendMessage(player, "social.trust-usage", "{cmd}", arg0);
                    return;
                }
                boolean isAdd = arg0.equals("trust");
                String durationStr = null;
                String roleStr = "VISITOR";
                if (isAdd) {
                    if (args.length >= 4) {
                        String arg3 = args[3].toLowerCase();
                        if (arg3.equals("visitor") || arg3.equals("co_owner") || arg3.equals("co-owner")) {
                            roleStr = arg3.replace("-", "_").toUpperCase();
                            if (args.length >= 5) {
                                durationStr = args[4];
                            }
                        } else {
                            durationStr = args[3];
                            if (args.length >= 5) {
                                String arg4 = args[4].toLowerCase();
                                if (arg4.equals("visitor") || arg4.equals("co_owner") || arg4.equals("co-owner")) {
                                    roleStr = arg4.replace("-", "_").toUpperCase();
                                }
                            }
                        }
                    }
                }
                handleHomeTrust(player, args[1], args[2], isAdd, durationStr, roleStr);
                return;
            }

            if (arg0.equals("invite")) {
                if (args.length < 3) {
                    plugin.getMessageManager().sendMessage(player, "social.trust-usage", "{cmd}", arg0);
                    return;
                }
                handleHomeInvite(player, args[1], args[2]);
                return;
            }

            if (arg0.equals("ban") || arg0.equals("unban")) {
                if (args.length < 3) {
                    plugin.getMessageManager().sendMessage(player, "commands.ban-usage", "{cmd}", arg0);
                    return;
                }
                boolean isBan = arg0.equals("ban");
                handleHomeBan(player, args[1], args[2], isBan);
                return;
            }

            if (arg0.equals("history")) {
                handleHomeHistory(player, args[1]);
                return;
            }

            if (arg0.equals("sponsor")) {
                if (args.length < 3) {
                    plugin.getMessageManager().sendMessage(player, "commands.sponsor-usage");
                    return;
                }
                int days;
                try {
                    days = Integer.parseInt(args[2]);
                    if (days <= 0) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    plugin.getMessageManager().sendMessage(player, "commands.sponsor-positive-days");
                    return;
                }
                handleHomeSponsor(player, args[1], days);
                return;
            }

            if (arg0.equals("rentslots")) {
                if (args.length < 3) {
                    plugin.getMessageManager().sendMessage(player, "commands.rentslots-usage");
                    return;
                }
                int amount, days;
                try {
                    amount = Integer.parseInt(args[1]);
                    days = Integer.parseInt(args[2]);
                    if (amount <= 0 || days <= 0) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    plugin.getMessageManager().sendMessage(player, "commands.rentslots-positive");
                    return;
                }
                handleHomeRentSlots(player, amount, days);
                return;
            }

            if (arg0.equals("sharefolder") || arg0.equals("sharecategory")) {
                if (args.length < 3) {
                    plugin.getMessageManager().sendMessage(player, "commands.sharefolder-usage");
                    return;
                }
                handleHomeShareFolder(player, args[1], args[2]);
                return;
            }

            // Sinon on considère que c'est : /home <joueur> <home>
            String targetName = args[0];
            String homeName = args[1];
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
            Home home = plugin.getHomeManager().getHome(target.getUniqueId(), homeName);

            if (home == null) {
                // Si le home n'existe pas ET que c'est notre propre home mais qu'on a juste
                // tapé un argument en trop ?
                // Non, on maintient la logique.

                // NOUVEAU : On vérifie aussi si on est un INVITÉ de ce home avant de refuser !
                Home maybeHome = plugin.getHomeManager().getHome(target.getUniqueId(), homeName);
                if (maybeHome != null && maybeHome.isTrusted(player.getUniqueId())) {
                    // C'est un invité légitime !
                    plugin.getMessageManager().sendMessage(player, "social.visiting", "{player}", plugin.getHomeManager().getPlayerName(target.getUniqueId()),
                            "{name}", homeName);
                    plugin.getTeleportManager().startTeleport(player, maybeHome);
                    return;
                }

                plugin.getMessageManager().sendMessage(player, "home.admin-not-found", "{player}", targetName, "{name}",
                        homeName);
                return;
            }

            // Vérifier si on a la permission Admin OU si le home est Public
            boolean isAdmin = player.hasPermission("sethomex.command.admin");

            if (isAdmin) {
                plugin.getMessageManager().sendMessage(player, "home.admin-teleporting", "{player}", target.getName());
            } else if (home.isPublic()) {
                plugin.getMessageManager().sendMessage(player, "social.visiting", "{player}", target.getName(),
                        "{name}", homeName);
            } else if (home.isTrusted(player.getUniqueId())) {
                // NOUVEAU : Validation des droits de partage pour la TP direct !
                plugin.getMessageManager().sendMessage(player, "social.visiting", "{player}", target.getName(),
                        "{name}", homeName);
            } else {
                // Ni admin, ni public, ni invité
                plugin.getMessageManager().sendMessage(player, "home.error-not-found", "{name}", homeName);
                return;
            }

            plugin.getTeleportManager().startTeleport(player, home);
            return;
        }

        String name = args[0];
        Home home = plugin.getHomeManager().getHome(player, name);

        if (home == null) {
            plugin.getMessageManager().sendMessage(player, "home.error-not-found", "{name}", name);
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
            return;
        }

        plugin.getTeleportManager().startTeleport(player, home);
    }

    private void handleHomeCompass(Player player, String homeName) {
        Home home = plugin.getHomeManager().getHome(player, homeName);
        if (home == null) {
            plugin.getMessageManager().sendMessage(player, "home.error-not-found", "{name}", homeName);
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
            return;
        }

        Location loc = home.getLocation();
        player.setCompassTarget(loc);

        plugin.getMessageManager().sendMessage(player, "home.compass-set", "{name}", homeName);
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, 1.0f, 1.0f);
    }

    private void handleDelHome(Player player, String[] args) {
        if (args.length == 0) {
            plugin.getMessageManager().sendMessage(player, "delhome.usage");
            return;
        }

        // Mode Admin : /delhome <joueur> <home>
        if (args.length >= 2 && player.hasPermission("sethomex.command.admin")) {
            String targetName = args[0];
            String homeName = args[1];
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

            boolean deleted = plugin.getHomeManager().deleteHome(target.getUniqueId(), homeName, targetName);
            if (deleted) {
                plugin.getMessageManager().sendMessage(player, "home.admin-deleted", "{name}", homeName, "{player}",
                        plugin.getHomeManager().getPlayerName(target.getUniqueId()));
            } else {
                plugin.getMessageManager().sendMessage(player, "home.admin-not-found", "{player}", targetName, "{name}",
                        homeName);
            }
            return;
        }

        String name = args[0];
        boolean deleted = plugin.getHomeManager().deleteHome(player, name);

        if (deleted) {
            plugin.getMessageManager().sendMessage(player, "delhome.success", "{name}", name);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
        } else {
            plugin.getMessageManager().sendMessage(player, "delhome.error-not-found", "{name}", name);
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
        }
    }

    /**
     * Bascule le statut public/privé d'un home.
     */
    private void handleHomePublic(Player player, String homeName) {
        if (!plugin.getConfig().getBoolean("homes.allow-public-homes", true)) {
            plugin.getMessageManager().sendMessage(player, "social.public-disabled");
            return;
        }

        Home home = plugin.getHomeManager().getHome(player, homeName);
        if (home == null) {
            plugin.getMessageManager().sendMessage(player, "home.error-not-found", "{name}", homeName);
            return;
        }

        boolean newState = !home.isPublic();
        home.setPublic(newState);
        plugin.getHomeManager().updateHomeSocial(home);

        if (newState) {
            plugin.getMessageManager().sendMessage(player, "social.toggled-public", "{name}", home.getName());
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
        } else {
            plugin.getMessageManager().sendMessage(player, "social.toggled-private", "{name}", home.getName());
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 0.8f);
        }
    }

    /**
     * Gère le système de partage (Trust / Untrust)
     */
    private void handleHomeTrust(Player player, String homeName, String targetGuestName, boolean add,
            String durationStr, String role) {
        Home home = getEditableHome(player, homeName);
        if (home == null) {
            plugin.getMessageManager().sendMessage(player, "home.error-not-found", "{name}", homeName);
            return;
        }

        OfflinePlayer guest = Bukkit.getOfflinePlayer(targetGuestName);
        if (guest.getUniqueId().equals(player.getUniqueId())) {
            plugin.getMessageManager().sendMessage(player, "social.cannot-invite-self");
            return;
        }

        if (add) {
            long expiresAt = -1L;
            if (durationStr != null) {
                long durationMs = parseDuration(durationStr);
                if (durationMs == -2L) {
                    plugin.getMessageManager().sendMessage(player, "commands.share-duration-invalid");
                    return;
                }
                expiresAt = System.currentTimeMillis() + durationMs;
            }

            plugin.getHomeManager().addTrust(home, guest.getUniqueId(), expiresAt, role);

            if (durationStr != null) {
                plugin.getMessageManager().sendMessage(player, "commands.share-trust-temp",
                        "{role}", role.toLowerCase(),
                        "{player}", guest.getName(),
                        "{name}", home.getName(),
                        "{duration}", durationStr);
            } else {
                plugin.getMessageManager().sendMessage(player, "commands.share-trust-perm",
                        "{role}", role.toLowerCase(),
                        "{player}", guest.getName(),
                        "{name}", home.getName());
            }
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1.0f, 1.2f);

            // Alerte l'ami s'il est en ligne !
            Player onlineGuest = Bukkit.getPlayer(guest.getUniqueId());
            if (onlineGuest != null) {
                plugin.getMessageManager().sendMessage(onlineGuest, "social.trust-received", "{owner}",
                        player.getName(), "{name}", home.getName());
                onlineGuest.playSound(onlineGuest.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.0f);
            }
        } else {
            plugin.getHomeManager().removeTrust(home, guest.getUniqueId());
            plugin.getMessageManager().sendMessage(player, "social.trust-removed", "{guest}", guest.getName(), "{name}",
                    home.getName());
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 0.8f);
        }
    }

    private long parseDuration(String input) {
        if (input == null || input.isEmpty())
            return -1L;
        try {
            char unit = input.charAt(input.length() - 1);
            if (!Character.isLetter(unit)) {
                return Long.parseLong(input) * 1000L; // par défaut secondes si c'est juste un chiffre
            }
            long value = Long.parseLong(input.substring(0, input.length() - 1));
            switch (Character.toLowerCase(unit)) {
                case 's':
                    return value * 1000L;
                case 'm':
                    return value * 60L * 1000L;
                case 'h':
                    return value * 60L * 60L * 1000L;
                case 'd':
                    return value * 24L * 60L * 60L * 1000L;
                default:
                    return -2L;
            }
        } catch (NumberFormatException e) {
            return -2L;
        }
    }

    private void handleHomeInvite(Player player, String homeName, String targetGuestName) {
        Home home = getEditableHome(player, homeName);
        if (home == null) {
            plugin.getMessageManager().sendMessage(player, "home.error-not-found", "{name}", homeName);
            return;
        }

        Player guest = Bukkit.getPlayer(targetGuestName);
        if (guest == null || !guest.isOnline()) {
            plugin.getMessageManager().sendMessage(player, "commands.invite-offline", "{player}", targetGuestName);
            return;
        }

        if (guest.getUniqueId().equals(player.getUniqueId())) {
            plugin.getMessageManager().sendMessage(player, "social.cannot-invite-self");
            return;
        }

        long durationMs = plugin.getConfig().getLong("social.invite-duration", 60) * 1000L;
        pendingInvites.computeIfAbsent(guest.getUniqueId(), k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(player.getUniqueId(), new PendingInvite(player.getUniqueId(), home.getName(), durationMs));

        plugin.getMessageManager().sendMessage(player, "invite.sent", "{player}", guest.getName(), "{home}",
                home.getName());
        plugin.getMessageManager().sendMessage(guest, "invite.received", "{player}", player.getName(), "{home}",
                home.getName());

        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
        guest.playSound(guest.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.5f);
    }

    private void handleHomeAccept(Player player, String senderName) {
        Map<UUID, PendingInvite> playerInvites = pendingInvites.get(player.getUniqueId());
        if (playerInvites == null || playerInvites.isEmpty()) {
            plugin.getMessageManager().sendMessage(player, "invite.expired");
            return;
        }

        playerInvites.values().removeIf(invite -> invite.isExpired());

        PendingInvite inviteToAccept = null;

        if (senderName == null) {
            if (playerInvites.isEmpty()) {
                plugin.getMessageManager().sendMessage(player, "invite.expired");
                return;
            } else if (playerInvites.size() == 1) {
                inviteToAccept = playerInvites.values().iterator().next();
            } else {
                plugin.getMessageManager().sendMessage(player, "commands.invite-multiple-pending");
                return;
            }
        } else {
            OfflinePlayer host = Bukkit.getOfflinePlayer(senderName);
            inviteToAccept = playerInvites.get(host.getUniqueId());
        }

        if (inviteToAccept == null || inviteToAccept.isExpired()) {
            plugin.getMessageManager().sendMessage(player, "invite.expired");
            return;
        }

        playerInvites.remove(inviteToAccept.getHostUuid());

        Home home = plugin.getHomeManager().getHome(inviteToAccept.getHostUuid(), inviteToAccept.getHomeName());
        if (home == null) {
            plugin.getMessageManager().sendMessage(player, "commands.invite-home-deleted");
            return;
        }

        plugin.getMessageManager().sendMessage(player, "invite.accepted");
        plugin.getTeleportManager().startTeleport(player, home);
    }

    /**
     * Gère la classification d'un home dans une catégorie/dossier.
     */
    private void handleHomeCategory(Player player, String homeName, String categoryName) {
        Home home = getEditableHome(player, homeName);
        if (home == null) {
            plugin.getMessageManager().sendMessage(player, "home.error-not-found", "{name}", homeName);
            return;
        }

        // Limit category name to 16 characters for GUI simplicity and safety
        if (categoryName.length() > 16) {
            categoryName = categoryName.substring(0, 16);
        }

        home.setCategory(categoryName);
        // Force the save of the category
        plugin.getHomeManager().updateHomeCategory(home);

        plugin.getMessageManager().sendMessage(player, "home.category-set", "{name}", home.getName(), "{category}",
                categoryName);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.2f);
    }

    private void handleSetHomeXAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sethomex.command.admin")) {
            plugin.getMessageManager().sendMessage(sender, "commands.no-permission");
            return;
        }

        if (args.length == 0) {
            sender.sendMessage("§7§m--------------------------------------");
            sender.sendMessage("§e§lSethomeX Admin Menu");
            sender.sendMessage(" ");
            sender.sendMessage("§e➔ /sethomex reload         §8» §fReload configuration");
            sender.sendMessage("§e➔ /sethomex info           §8» §fSystem Information");
            sender.sendMessage("§e➔ /sethomex profiler       §8» §fCaffeine Cache Statistics");
            sender.sendMessage("§e➔ /sethomex import <type>  §8» §fImport external homes");
            sender.sendMessage("§e➔ /sethomex admin          §8» §fOpen Administration GUI");
            sender.sendMessage("§7§m--------------------------------------");
            return;
        }

        String sub = args[0].toLowerCase();
        if (sub.equals("admin")) {
            if (!(sender instanceof Player player)) {
                plugin.getMessageManager().sendMessage(sender, "commands.admin-only-players");
                return;
            }
            gui.openAdminPlayersGUI(player, 1);
            return;
        }
        if (sub.equals("reload")) {
            plugin.reloadAll();
            if (sender instanceof Player p) {
                plugin.getMessageManager().sendMessage(p, "commands.reloaded");
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            } else {
                plugin.getMessageManager().sendMessage(sender, "commands.reloaded");
            }
        } else if (sub.equals("info")) {
            String dbType = plugin.getConfig().getString("database.type", "SQLITE");
            plugin.getMessageManager().sendMessage(sender, "commands.system-info-header");
            plugin.getMessageManager().sendMessage(sender, "commands.system-info-db", "{db}", dbType);
            plugin.getMessageManager().sendMessage(sender, "commands.system-info-spigot", "{version}", plugin.getServer().getVersion());
            plugin.getMessageManager().sendMessage(sender, "commands.system-info-java", "{version}", System.getProperty("java.version"));
            plugin.getMessageManager().sendMessage(sender, "home.list-footer");
        } else if (sub.equals("profiler")) {
            com.github.benmanes.caffeine.cache.stats.CacheStats cacheStats = plugin.getHomeManager().getCache().stats();
            com.github.benmanes.caffeine.cache.stats.CacheStats visitStats = plugin.getTeleportManager().getVisitCache().stats();
            long memoryUsed = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024;

            plugin.getMessageManager().sendMessage(sender, "commands.profiler-header");
            plugin.getMessageManager().sendMessage(sender, "commands.profiler-cache-title");
            plugin.getMessageManager().sendMessage(sender, "commands.profiler-cache-hit", "{hit}", String.format("%.2f", cacheStats.hitRate() * 100));
            plugin.getMessageManager().sendMessage(sender, "commands.profiler-cache-evictions", "{evictions}", String.valueOf(cacheStats.evictionCount()));
            plugin.getMessageManager().sendMessage(sender, "commands.profiler-cache-load-time", "{time}", String.valueOf(cacheStats.totalLoadTime() / 1000000));
            plugin.getMessageManager().sendMessage(sender, "commands.profiler-anti-spam-title");
            plugin.getMessageManager().sendMessage(sender, "commands.profiler-anti-spam-hit", "{hit}", String.format("%.2f", visitStats.hitRate() * 100));
            plugin.getMessageManager().sendMessage(sender, "commands.profiler-anti-spam-evictions", "{evictions}", String.valueOf(visitStats.evictionCount()));
            plugin.getMessageManager().sendMessage(sender, "commands.system-info-ram", "{ram}", String.valueOf(memoryUsed));
            plugin.getMessageManager().sendMessage(sender, "home.list-footer");
        } else if (sub.equals("import")) {
            if (args.length < 2) {
                plugin.getMessageManager().sendMessage(sender, "commands.import-usage");
                return;
            }

            String type = args[1].toLowerCase();
            switch (type) {
                case "essentials":
                case "essentialsx":
                    new EssentialsImporter(plugin).startImport(sender);
                    break;
                case "cmi":
                    new CmiImporter(plugin).startImport(sender);
                    break;
                case "sunlight":
                    new SunlightImporter(plugin).startImport(sender);
                    break;
                case "betterhomes":
                    new BetterHomesImporter(plugin).startImport(sender);
                    break;
                case "ultimatehomes":
                    new UltimateHomesImporter(plugin).startImport(sender);
                    break;
                default:
                    plugin.getMessageManager().sendMessage(sender, "commands.import-unknown");
                    break;
            }
        } else {
            plugin.getMessageManager().sendMessage(sender, "commands.unknown-subcommand");
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player))
            return Collections.emptyList();

        String cmdName = command.getName().toLowerCase();

        if (cmdName.equals("sethome")) {
            if (args.length == 1) {
                List<String> suggestions = new ArrayList<>();
                suggestions.add("portal");
                for (Home h : plugin.getHomeManager().getPlayerHomes(player)) {
                    suggestions.add(h.getName());
                }
                return suggestions.stream()
                        .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 2 && args[0].equalsIgnoreCase("portal")) {
                List<String> suggestions = new ArrayList<>();
                suggestions.add("particle");
                suggestions.addAll(plugin.getHomeManager().getPlayerHomes(player).stream()
                        .map(h -> h.getName())
                        .collect(Collectors.toList()));
                return suggestions.stream()
                        .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 3 && args[0].equalsIgnoreCase("portal") && args[1].equalsIgnoreCase("particle")) {
                return plugin.getHomeManager().getPlayerHomes(player).stream()
                        .map(h -> h.getName())
                        .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 4 && args[0].equalsIgnoreCase("portal") && args[1].equalsIgnoreCase("particle")) {
                List<String> suggestions = new ArrayList<>();
                for (org.bukkit.Particle p : org.bukkit.Particle.values()) {
                    suggestions.add(p.name());
                }
                return suggestions.stream()
                        .filter(s -> s.toLowerCase().startsWith(args[3].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }

        if (cmdName.equals("delhome")) {
            if (args.length == 1) {
                return plugin.getHomeManager().getPlayerHomes(player).stream()
                        .map(h -> h.getName())
                        .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }

        if (cmdName.equals("home")) {
            if (args.length == 1) {
                List<String> suggestions = new ArrayList<>();

                // Sous-commandes
                suggestions.add("public");
                suggestions.add("trust");
                suggestions.add("untrust");
                suggestions.add("invite");
                suggestions.add("accept");
                suggestions.add("list");
                suggestions.add("effects");
                suggestions.add("desc");
                suggestions.add("description");
                suggestions.add("welcome");
                suggestions.add("fee");
                suggestions.add("compass");
                suggestions.add("target");
                suggestions.add("category");
                suggestions.add("folder");

                Collection<Home> ownHomes = plugin.getHomeManager().getPlayerHomes(player);
                for (Home h : ownHomes) {
                    suggestions.add(h.getName());
                }

                // Suggérer les joueurs connectés si on a la perm admin
                if (player.hasPermission("sethomex.command.admin")) {
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        suggestions.add(online.getName());
                    }
                }

                return suggestions.stream()
                        .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 2) {
                String sub = args[0].toLowerCase();

                // /home trust|untrust|invite|desc|description|welcome|fee|compass|target <ICI : NOS HOMES>
                if (sub.equals("trust") || sub.equals("untrust") || sub.equals("invite") || sub.equals("public") ||
                    sub.equals("desc") || sub.equals("description") || sub.equals("welcome") || sub.equals("fee") ||
                    sub.equals("compass") || sub.equals("target") || sub.equals("category") || sub.equals("folder")) {
                    Collection<Home> ownHomes = plugin.getHomeManager().getPlayerHomes(player);
                    return ownHomes.stream().map(h -> h.getName())
                            .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                }

                if (sub.equals("accept")) {
                    Map<UUID, PendingInvite> playerInvites = pendingInvites.get(player.getUniqueId());
                    if (playerInvites != null) {
                        List<String> senders = new ArrayList<>();
                        for (PendingInvite invite : playerInvites.values()) {
                            if (!invite.isExpired()) {
                                String name = plugin.getHomeManager().getPlayerName(invite.getHostUuid());
                                if (name != null && !name.equals("Unknown")) {
                                    senders.add(name);
                                }
                            }
                        }
                        return senders.stream()
                                .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                                .collect(Collectors.toList());
                    }
                }

                // Sinon, si on a la permission admin, on a tapé un pseudo de joueur au rang 0
                if (player.hasPermission("sethomex.command.admin")) {
                    OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
                    Collection<Home> targetHomes = plugin.getHomeManager().getPlayerHomes(target.getUniqueId());

                    return targetHomes.stream()
                            .map(h -> h.getName())
                            .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                }
            } else if (args.length == 3) {
                String sub = args[0].toLowerCase();
                // /home trust|untrust <nom> <ICI : LES JOUEURS CONNECTES>
                if (sub.equals("trust") || sub.equals("untrust") || sub.equals("invite")) {
                    return Bukkit.getOnlinePlayers().stream()
                            .map(p -> p.getName())
                            .filter(n -> n.toLowerCase().startsWith(args[2].toLowerCase()))
                            .collect(Collectors.toList());
                }

                if (sub.equals("category") || sub.equals("folder")) {
                    java.util.Set<String> categories = new java.util.HashSet<>();
                    categories.add("none");
                    for (Home h : plugin.getHomeManager().getPlayerHomes(player)) {
                        if (h.getCategory() != null && !h.getCategory().equalsIgnoreCase("none")) {
                            categories.add(h.getCategory());
                        }
                    }
                    return categories.stream()
                            .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                            .collect(Collectors.toList());
                }
            }
        } else if (cmdName.equals("sethomex")) {
            if (sender.hasPermission("sethomex.command.admin")) {
                if (args.length == 1) {
                    List<String> suggestions = new ArrayList<>();
                    suggestions.add("reload");
                    suggestions.add("info");
                    suggestions.add("import");
                    suggestions.add("profiler");
                    suggestions.add("admin");
                    return suggestions.stream()
                            .filter(s -> s.startsWith(args[0].toLowerCase()))
                            .collect(Collectors.toList());
                } else if (args.length == 2 && args[0].equalsIgnoreCase("import")) {
                    List<String> suggestions = new ArrayList<>();
                    suggestions.add("essentials");
                    suggestions.add("cmi");
                    suggestions.add("sunlight");
                    suggestions.add("betterhomes");
                    suggestions.add("ultimatehomes");
                    return suggestions.stream()
                            .filter(s -> s.startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                }
            }
        }

        return Collections.emptyList();
    }

    private void handleHomeList(Player player) {
        Collection<Home> homes = plugin.getHomeManager().getPlayerHomes(player);
        if (homes.isEmpty()) {
            plugin.getMessageManager().sendMessage(player, "home.error-no-homes");
            return;
        }

        plugin.getMessageManager().sendMessage(player, "home.list-header");

        for (Home home : homes) {
            String hoverText = "§6Home: §f" + home.getName() + "\n" +
                    "§6Location: §e" + home.getWorldName() + " (" + (int) home.getX() + ", " + (int) home.getY() + ", "
                    + (int) home.getZ() + ")\n" +
                    "§6Visits: §a" + home.getVisits() + "\n" +
                    "§6Status: " + (home.isPublic() ? "§aPublic" : "§cPrivate") + "\n\n" +
                    "§e⚡ Click to teleport!";
            String delHover = "§cClick to delete this home! (Safety prompt)";

            net.kyori.adventure.text.Component homeLine = net.kyori.adventure.text.Component.text()
                    .append(net.kyori.adventure.text.Component.text("§7• "))
                    .append(net.kyori.adventure.text.Component.text("§b§l" + home.getName())
                            .hoverEvent(net.kyori.adventure.text.Component.text(hoverText))
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/home " + home.getName())))
                    .append(net.kyori.adventure.text.Component.text(" §c[❌]")
                            .hoverEvent(net.kyori.adventure.text.Component.text(delHover))
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand("/delhome " + home.getName())))
                    .build();

            player.sendMessage(homeLine);
        }

        plugin.getMessageManager().sendMessage(player, "home.list-footer");
    }

    private void handleHomeDescription(Player player, String homeName, String description) {
        Home home = getEditableHome(player, homeName);
        if (home == null) {
            plugin.getMessageManager().sendMessage(player, "home.error-not-found", "{name}", homeName);
            return;
        }

        home.setDescription(description);
        plugin.getHomeManager().updateHomeDescription(home);

        plugin.getMessageManager().sendMessage(player, "commands.description-success", "{name}", home.getName(), "{description}", description);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.2f);
    }

    private void handleHomeWelcome(Player player, String homeName, String welcomeMessage) {
        Home home = getEditableHome(player, homeName);
        if (home == null) {
            plugin.getMessageManager().sendMessage(player, "home.error-not-found", "{name}", homeName);
            return;
        }

        home.setWelcomeMessage(welcomeMessage);
        plugin.getHomeManager().updateHomeWelcomeMessage(home);

        plugin.getMessageManager().sendMessage(player, "commands.welcome-success", "{name}", home.getName(), "{welcome}", welcomeMessage);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.2f);
    }

    private void handleHomeFee(Player player, String homeName, double amount) {
        Home home = getEditableHome(player, homeName);
        if (home == null) {
            plugin.getMessageManager().sendMessage(player, "home.error-not-found", "{name}", homeName);
            return;
        }

        if (!home.isPublic()) {
            plugin.getMessageManager().sendMessage(player, "commands.fee-public-only");
            return;
        }

        home.setVisitFee(amount);
        plugin.getHomeManager().updateHomeVisitFee(home);

        plugin.getMessageManager().sendMessage(player, "commands.fee-success", "{name}", home.getName(), "{amount}", plugin.getEconomyManager().format(amount));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.2f);
    }

    public boolean canEdit(Home home, Player player) {
        if (player.hasPermission("sethomex.command.admin")) {
            return true;
        }
        if (home.getPlayerUuid().equals(player.getUniqueId())) {
            return true;
        }
        if (home.isTrusted(player.getUniqueId()) && "CO_OWNER".equalsIgnoreCase(home.getTrustRole(player.getUniqueId()))) {
            return true;
        }
        return false;
    }

    public @Nullable Home getEditableHome(Player player, String homeName) {
        if (homeName.contains(":")) {
            String[] parts = homeName.split(":", 2);
            String ownerName = parts[0];
            String realHomeName = parts[1];
            OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerName);
            Home home = plugin.getHomeManager().getHome(owner.getUniqueId(), realHomeName);
            if (home != null && canEdit(home, player)) {
                return home;
            }
            return null;
        }

        Home ownHome = plugin.getHomeManager().getHome(player, homeName);
        if (ownHome != null) {
            return ownHome;
        }

        for (UUID uuid : plugin.getHomeManager().getCache().asMap().keySet()) {
            Home home = plugin.getHomeManager().getHome(uuid, homeName);
            if (home != null && canEdit(home, player)) {
                return home;
            }
        }

        return null;
    }

    private void handleHomeBan(Player player, String homeName, String targetBannedName, boolean ban) {
        Home home = getEditableHome(player, homeName);
        if (home == null) {
            plugin.getMessageManager().sendMessage(player, "commands.ban-not-permitted");
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(targetBannedName);
        if (target == null || target.getUniqueId() == null) {
            plugin.getMessageManager().sendMessage(player, "commands.ban-unknown-player");
            return;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            plugin.getMessageManager().sendMessage(player, "commands.ban-cannot-self");
            return;
        }

        if (ban) {
            plugin.getHomeManager().addBan(home, target.getUniqueId());
            plugin.getMessageManager().sendMessage(player, "commands.ban-success", "{player}", targetBannedName, "{name}", home.getName());
        } else {
            plugin.getHomeManager().removeBan(home, target.getUniqueId());
            plugin.getMessageManager().sendMessage(player, "commands.unban-success", "{player}", targetBannedName, "{name}", home.getName());
        }
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.2f);
    }

    private void handleHomeHistory(Player player, String homeName) {
        Home home = getEditableHome(player, homeName);
        if (home == null) {
            plugin.getMessageManager().sendMessage(player, "commands.history-not-permitted");
            return;
        }

        plugin.getMessageManager().sendMessage(player, "commands.history-header", "{name}", home.getName());

        List<fr.skynex.sethomex.managers.HomeManager.VisitRecord> history = plugin.getHomeManager().getVisitHistory(home.getPlayerUuid(), home.getName());
        if (history.isEmpty()) {
            plugin.getMessageManager().sendMessage(player, "commands.history-empty");
        } else {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm");
            for (fr.skynex.sethomex.managers.HomeManager.VisitRecord record : history) {
                plugin.getMessageManager().sendMessage(player, "commands.history-entry",
                        "{player}", record.getVisitorName(),
                        "{date}", sdf.format(new java.util.Date(record.getTimestamp())));
            }
        }
        plugin.getMessageManager().sendMessage(player, "home.list-footer");
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
    }

    private void handleHomeSponsor(Player player, String homeName, int days) {
        Home home = getEditableHome(player, homeName);
        if (home == null) {
            plugin.getMessageManager().sendMessage(player, "commands.sponsor-not-permitted");
            return;
        }

        if (!home.isPublic()) {
            plugin.getMessageManager().sendMessage(player, "commands.sponsor-public-only");
            return;
        }

        double pricePerDay = plugin.getConfig().getDouble("economy.sponsor-price-per-day", 1000.0);
        double totalPrice = pricePerDay * days;

        if (plugin.getEconomyManager().isEnabled()) {
            if (!plugin.getEconomyManager().withdraw(player, totalPrice)) {
                plugin.getMessageManager().sendMessage(player, "commands.sponsor-insufficient-funds", "{cost}", plugin.getEconomyManager().format(totalPrice));
                return;
            }
        }

        long now = System.currentTimeMillis();
        long currentUntil = home.getSponsoredUntil();
        long newUntil = Math.max(now, currentUntil) + ((long) days * 24 * 3600 * 1000);

        home.setSponsored(true);
        home.setSponsoredUntil(newUntil);
        plugin.getHomeManager().updateHomeSponsored(home);

        plugin.getMessageManager().sendMessage(player, "commands.sponsor-success",
                "{name}", home.getName(),
                "{days}", String.valueOf(days),
                "{cost}", plugin.getEconomyManager().format(totalPrice));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
    }

    private void handleHomeRentSlots(Player player, int amount, int days) {
        double pricePerSlotPerDay = plugin.getConfig().getDouble("economy.rent-slot-price-per-day", 100.0);
        double totalPrice = pricePerSlotPerDay * amount * days;

        if (plugin.getEconomyManager().isEnabled()) {
            if (!plugin.getEconomyManager().withdraw(player, totalPrice)) {
                plugin.getMessageManager().sendMessage(player, "commands.rentslots-insufficient-funds", "{cost}", plugin.getEconomyManager().format(totalPrice));
                return;
            }
        }

        long durationMs = (long) days * 24 * 3600 * 1000;
        plugin.getHomeManager().addRentedSlots(player.getUniqueId(), amount, durationMs);

        plugin.getMessageManager().sendMessage(player, "commands.rentslots-success",
                "{amount}", String.valueOf(amount),
                "{days}", String.valueOf(days),
                "{cost}", plugin.getEconomyManager().format(totalPrice));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
    }

    private void handleHomeShareFolder(Player player, String folderName, String targetGuestName) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetGuestName);
        if (target == null || target.getUniqueId() == null) {
            plugin.getMessageManager().sendMessage(player, "commands.sharefolder-unknown-player");
            return;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            plugin.getMessageManager().sendMessage(player, "commands.sharefolder-cannot-self");
            return;
        }

        java.util.Collection<Home> playerHomes = plugin.getHomeManager().getPlayerHomes(player);
        List<Home> matching = new ArrayList<>();
        for (Home home : playerHomes) {
            if (folderName.equalsIgnoreCase(home.getCategory())) {
                matching.add(home);
            }
        }

        if (matching.isEmpty()) {
            plugin.getMessageManager().sendMessage(player, "commands.sharefolder-empty", "{folder}", folderName);
            return;
        }

        for (Home home : matching) {
            plugin.getHomeManager().addTrust(home, target.getUniqueId(), -1L, "VISITOR");
        }

        plugin.getMessageManager().sendMessage(player, "commands.sharefolder-success",
                "{folder}", folderName,
                "{count}", String.valueOf(matching.size()),
                "{player}", targetGuestName);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.0f);
    }
}
