package fr.skynex.sethomex.managers;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import fr.skynex.sethomex.SethomeX;
import fr.skynex.sethomex.models.Home;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import fr.skynex.sethomex.util.scheduler.ScheduledTask;
import fr.skynex.sethomex.util.TeleportUtil;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class TeleportManager {

    private final SethomeX plugin;
    private final NamespacedKey previewActiveKey;
    private final NamespacedKey previewWorldKey;
    private final NamespacedKey previewXKey;
    private final NamespacedKey previewYKey;
    private final NamespacedKey previewZKey;
    private final NamespacedKey previewYawKey;
    private final NamespacedKey previewPitchKey;
    private final NamespacedKey previewGMKey;
    private final NamespacedKey previewFlyKey;
    private final NamespacedKey previewAllowFlyKey;
    private final Map<UUID, ScheduledTask> activeTeleports = new ConcurrentHashMap<>();
    private final Map<UUID, Long> combatTags = new ConcurrentHashMap<>();
    private final Map<UUID, Long> globalCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> protectionTags = new ConcurrentHashMap<>();
    private final Map<UUID, org.bukkit.entity.TextDisplay> activeHolograms = new ConcurrentHashMap<>();
    private final Map<UUID, org.bukkit.boss.BossBar> activeBossBars = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectedParticles = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectedStyles = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectedSounds = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectedSuccessSounds = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> activeCosmeticPreviews = new ConcurrentHashMap<>();
    
    // Preview Caches
    public static class PreviewSession {
        public final Location originalLocation;
        public final org.bukkit.GameMode originalGameMode;
        public final boolean originalFlying;
        public final Location targetLocation;
        public final ScheduledTask task;
        
        public PreviewSession(Location originalLocation, org.bukkit.GameMode originalGameMode, boolean originalFlying, Location targetLocation, ScheduledTask task) {
            this.originalLocation = originalLocation;
            this.originalGameMode = originalGameMode;
            this.originalFlying = originalFlying;
            this.targetLocation = targetLocation;
            this.task = task;
        }
    }
    
    private final Map<UUID, PreviewSession> activePreviews = new ConcurrentHashMap<>();
    
    // Cache Anti-Spam pour la popularité des Homes (1 heure)
    private final Cache<String, Long> visitCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .recordStats()
            .build();

    public TeleportManager(SethomeX plugin) {
        this.plugin = plugin;
        this.previewActiveKey = new NamespacedKey(plugin, "preview_active");
        this.previewWorldKey = new NamespacedKey(plugin, "preview_world");
        this.previewXKey = new NamespacedKey(plugin, "preview_x");
        this.previewYKey = new NamespacedKey(plugin, "preview_y");
        this.previewZKey = new NamespacedKey(plugin, "preview_z");
        this.previewYawKey = new NamespacedKey(plugin, "preview_yaw");
        this.previewPitchKey = new NamespacedKey(plugin, "preview_pitch");
        this.previewGMKey = new NamespacedKey(plugin, "preview_gamemode");
        this.previewFlyKey = new NamespacedKey(plugin, "preview_fly");
        this.previewAllowFlyKey = new NamespacedKey(plugin, "preview_allow_fly");
    }

    public void setPlayerCosmetics(UUID uuid, String particle, String style, String sound, String successSound) {
        if (particle != null) selectedParticles.put(uuid, particle);
        if (style != null) selectedStyles.put(uuid, style);
        if (sound != null) selectedSounds.put(uuid, sound);
        if (successSound != null) selectedSuccessSounds.put(uuid, successSound);
    }

    public String getPlayerParticle(UUID uuid) {
        return selectedParticles.getOrDefault(uuid, "default");
    }

    public String getPlayerStyle(UUID uuid) {
        return selectedStyles.getOrDefault(uuid, "default");
    }

    public String getPlayerSound(UUID uuid) {
        return selectedSounds.getOrDefault(uuid, "default");
    }

    public String getPlayerSuccessSound(UUID uuid) {
        return selectedSuccessSounds.getOrDefault(uuid, "default");
    }

    public Cache<String, Long> getVisitCache() {
        return visitCache;
    }

    /**
     * Lance une demande de téléportation avec un décompte visuel et sonore.
     */
    public void startTeleport(Player player, Home home) {
        startTeleport(player, home, null);
    }

    public void startTeleport(Player player, Home home, fr.skynex.sethomex.managers.PortalManager.Portal portal) {
        fr.skynex.sethomex.api.events.PlayerTeleportHomeEvent event = new fr.skynex.sethomex.api.events.PlayerTeleportHomeEvent(player, home);
        org.bukkit.Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;

        // Vérification de bannissement
        if (home.isBanned(player.getUniqueId()) && !player.hasPermission("sethomex.bypass.ban")) {
            String ownerName = plugin.getHomeManager().getPlayerName(home.getPlayerUuid());
            if (ownerName == null || ownerName.equals("Unknown")) ownerName = "un joueur";
            player.sendMessage("§cVous êtes banni de ce home par son propriétaire (" + ownerName + ").");
            return;
        }

        UUID uuid = player.getUniqueId();

        // Annuler toute téléportation déjà en cours
        cancelTeleport(player, false);

        // 1. Vérification du cooldown global entre téléportations
        int cooldownBetween = plugin.getConfig().getInt("teleport.delay-between-tp", 30);
        if (cooldownBetween > 0 && !player.hasPermission("sethomex.bypass.cooldown") && !player.isOp()) {
            long now = System.currentTimeMillis();
            if (globalCooldowns.containsKey(uuid)) {
                long expiration = globalCooldowns.get(uuid);
                if (now < expiration) {
                    long secondsLeft = (expiration - now) / 1000 + 1;
                    plugin.getMessageManager().sendMessage(player, "teleport.error-global-cooldown", "{seconds}", String.valueOf(secondsLeft));
                    return;
                }
            }
        }

        // Vérification combat si actif dans config
        if (plugin.getConfig().getBoolean("teleport.cancel-in-combat", true) && !player.hasPermission("sethomex.bypass.combat")) {
            if (isInCombat(player)) {
                plugin.getMessageManager().sendMessage(player, "teleport.error-combat");
                return;
            }
        }

        // Vérification préliminaire de l'économie (liquidité suffisante)
        double cost = plugin.getConfig().getDouble("economy.teleport-cost", 0.0);
        double totalCost = cost;
        double visitFee = 0.0;
        boolean hasVisitFee = false;
        
        if (!home.getPlayerUuid().equals(player.getUniqueId())) {
            visitFee = home.getVisitFee();
            if (visitFee > 0 && !player.hasPermission("sethomex.bypass.fee")) {
                totalCost += visitFee;
                hasVisitFee = true;
            }
        }

        if (plugin.getEconomyManager().isEnabled() && totalCost > 0) {
             double chargeAmount = 0.0;
             if (!player.hasPermission("sethomex.bypass.cost")) {
                 chargeAmount += cost;
             }
             if (hasVisitFee) {
                 chargeAmount += visitFee;
             }

             if (chargeAmount > 0 && !plugin.getEconomyManager().hasEnough(player, chargeAmount)) {
                 plugin.getMessageManager().sendMessage(player, "economy.insufficient-funds", "{cost}", plugin.getEconomyManager().format(chargeAmount));
                 return;
             }
        }

        int cooldown = plugin.getConfig().getInt("teleport.cooldown", 3);
        if (portal != null && portal.frameMaterial != null) {
            double multiplier = plugin.getPortalManager().getCooldownMultiplier(portal.frameMaterial);
            cooldown = (int) Math.round(cooldown * multiplier);
        }

        // Si cooldown = 0 ou si le joueur a le bypass, TP instantané !
        if (cooldown <= 0 || player.hasPermission("sethomex.bypass.cooldown") || player.isOp()) {
            executeTeleport(player, home);
            return;
        }

        plugin.getMessageManager().sendMessage(player, "teleport.starting", "{name}", home.getName(), "{seconds}",
                String.valueOf(cooldown));

        // Pré-cache de la configuration & préférences cosmétiques du joueur
        String plingSoundName = getPlayerSound(uuid);
        if ("default".equalsIgnoreCase(plingSoundName)) {
            plingSoundName = plugin.getConfig().getString("effects.countdown.sound", "BLOCK_NOTE_BLOCK_PLING");
        }
        float soundVolume = (float) plugin.getConfig().getDouble("effects.countdown.volume", 0.8);
        float soundPitch = (float) plugin.getConfig().getDouble("effects.countdown.pitch", 1.0);
        Sound countdownSound = getSoundFromName(plingSoundName);

        String particleName;
        if (portal != null) {
            if (portal.customParticle != null) {
                particleName = portal.customParticle;
            } else if (portal.frameMaterial != null) {
                particleName = plugin.getPortalManager().getDefaultParticle(portal.frameMaterial).name();
            } else {
                particleName = "PORTAL";
            }
        } else {
            particleName = getPlayerParticle(uuid);
            if ("default".equalsIgnoreCase(particleName)) {
                particleName = plugin.getConfig().getString("effects.countdown.particle", "PORTAL");
            }
        }
        String effectStyle = getPlayerStyle(uuid);
        if ("default".equalsIgnoreCase(effectStyle)) {
            effectStyle = plugin.getConfig().getString("effects.countdown.style", "SPIRAL");
        }
        final String finalStyle = effectStyle.toUpperCase();
        
        Particle tempParticle;
        try {
            tempParticle = Particle.valueOf(particleName.toUpperCase());
        } catch (IllegalArgumentException e) {
            tempParticle = Particle.PORTAL;
        }
        final Particle countdownParticle = tempParticle;

        // Spawning hologram TextDisplay
        org.bukkit.entity.TextDisplay tempHolo = null;
        if (plugin.getConfig().getBoolean("teleport.hologram.enabled", true)) {
            try {
                Location holoLoc = player.getLocation().add(0, 2.2, 0);
                tempHolo = player.getWorld().spawn(holoLoc, org.bukkit.entity.TextDisplay.class);
                tempHolo.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                tempHolo.setBackgroundColor(org.bukkit.Color.fromARGB(100, 0, 0, 0));
                activeHolograms.put(uuid, tempHolo);
            } catch (LinkageError | Exception e) {
                plugin.getLogger().warning("Failed to spawn TextDisplay hologram: " + e.getMessage());
            }
        }
        final org.bukkit.entity.TextDisplay hologram = tempHolo;

        // Spawning BossBar
        org.bukkit.boss.BossBar tempBar = null;
        if (plugin.getConfig().getBoolean("teleport.bossbar.enabled", true)) {
            try {
                String titleTemplate = plugin.getConfig().getString("teleport.bossbar.title", "&e&lTeleporting to &f{name}&e... &7({seconds}s)");
                String colorStr = plugin.getConfig().getString("teleport.bossbar.color", "YELLOW").toUpperCase();
                String styleStr = plugin.getConfig().getString("teleport.bossbar.style", "PROGRESS").toUpperCase();

                org.bukkit.boss.BarColor barColor;
                try {
                    barColor = org.bukkit.boss.BarColor.valueOf(colorStr);
                } catch (IllegalArgumentException e) {
                    barColor = org.bukkit.boss.BarColor.YELLOW;
                }

                org.bukkit.boss.BarStyle barStyle;
                try {
                    barStyle = org.bukkit.boss.BarStyle.valueOf(styleStr);
                } catch (IllegalArgumentException e) {
                    barStyle = org.bukkit.boss.BarStyle.SOLID;
                }

                String formattedTitle = titleTemplate.replace("{name}", home.getName())
                        .replace("{seconds}", String.valueOf(cooldown))
                        .replace("&", "§");

                tempBar = Bukkit.createBossBar(formattedTitle, barColor, barStyle);
                tempBar.setProgress(1.0);
                tempBar.addPlayer(player);
                activeBossBars.put(uuid, tempBar);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to initialize BossBar: " + e.getMessage());
            }
        }
        final org.bukkit.boss.BossBar bossBar = tempBar;

        final int totalTicks = cooldown * 20;
        final ScheduledTask[] taskHolder = new ScheduledTask[1];
        taskHolder[0] = plugin.getScheduler().runTaskTimerAtEntity(player, new Runnable() {
            int ticksLeft = totalTicks;
            double angle = 0; // Pour l'animation de spirale

            @Override
            public void run() {
                if (!player.isOnline()) {
                    if (taskHolder[0] != null) {
                        taskHolder[0].cancel();
                    }
                    activeTeleports.remove(uuid);
                    org.bukkit.entity.TextDisplay holo = activeHolograms.remove(uuid);
                    if (holo != null && holo.isValid()) {
                        holo.remove();
                    }
                    org.bukkit.boss.BossBar bar = activeBossBars.remove(uuid);
                    if (bar != null) {
                        bar.removePlayer(player);
                    }
                    return;
                }

                Location loc = player.getLocation();

                // Suivi de la tête du joueur par l'hologramme
                if (hologram != null && hologram.isValid()) {
                    TeleportUtil.teleportAsync(hologram, player.getLocation().add(0, 2.2, 0));
                }

                // 1. Jouer des effets de particules périodiques (Chaque tick de cette boucle)
                spawnTeleportParticles(player, loc, angle, finalStyle, countdownParticle, ticksLeft, totalTicks);
                angle += 0.2;

                // Actualiser le texte de l'hologramme à chaque tick pour une fluidité optimale
                if (hologram != null && hologram.isValid()) {
                    int barLength = plugin.getConfig().getInt("teleport.hologram.bar-length", 10);
                    int ticksPassed = totalTicks - ticksLeft;
                    double progress = (double) ticksPassed / totalTicks;
                    int filledLength = (int) (progress * barLength);
                    int emptyLength = Math.max(0, barLength - filledLength);
                    String filledChar = plugin.getConfig().getString("teleport.hologram.char-filled", "█");
                    String emptyChar = plugin.getConfig().getString("teleport.hologram.char-empty", "░");
                    String bar = filledChar.repeat(filledLength) + emptyChar.repeat(emptyLength);

                    double secondsRemaining = (double) ticksLeft / 20.0;
                    net.kyori.adventure.text.Component text = plugin.getMessageManager().getParsedMessage(
                            "teleport.hologram-text",
                            false,
                            "{seconds}", String.format(java.util.Locale.US, "%.1f", secondsRemaining),
                            "{bar}", bar
                    );
                    hologram.text(text);
                }

                // Update BossBar progress and text
                if (bossBar != null) {
                    int ticksPassed = totalTicks - ticksLeft;
                    double progress = (double) ticksPassed / totalTicks;
                    bossBar.setProgress(Math.min(1.0, Math.max(0.0, 1.0 - progress)));

                    double secondsRemaining = (double) ticksLeft / 20.0;
                    String titleTemplate = plugin.getConfig().getString("teleport.bossbar.title", "&e&lTeleporting to &f{name}&e... &7({seconds}s)");
                    String formattedTitle = titleTemplate.replace("{name}", home.getName())
                            .replace("{seconds}", String.format(java.util.Locale.US, "%.1f", secondsRemaining))
                            .replace("&", "§");
                    bossBar.setTitle(formattedTitle);
                }

                // 2. Traitement à chaque seconde (tous les 20 ticks)
                if (ticksLeft > 0 && ticksLeft % 20 == 0) {
                    int secondsLeft = ticksLeft / 20;
                    plugin.getMessageManager().sendActionBar(player, "teleport.actionbar-countdown", "{seconds}",
                            String.valueOf(secondsLeft));

                    if (countdownSound != null) {
                        player.playSound(loc, countdownSound, soundVolume, soundPitch);
                    }
                }

                // 3. Fin du décompte
                if (ticksLeft <= 0) {
                    if (taskHolder[0] != null) {
                        taskHolder[0].cancel();
                    }
                    activeTeleports.remove(uuid);
                    org.bukkit.entity.TextDisplay holo = activeHolograms.remove(uuid);
                    if (holo != null && holo.isValid()) {
                        holo.remove();
                    }
                    org.bukkit.boss.BossBar bar = activeBossBars.remove(uuid);
                    if (bar != null) {
                        bar.removePlayer(player);
                    }
                    executeTeleport(player, home);
                    return;
                }

                ticksLeft--;
            }
        }, 0L, 1L);

        activeTeleports.put(uuid, taskHolder[0]);
    }

    /**
     * Annule une téléportation en cours.
     */
    public void cancelTeleport(Player player, boolean notify) {
        UUID uuid = player.getUniqueId();
        org.bukkit.entity.TextDisplay holo = activeHolograms.remove(uuid);
        if (holo != null && holo.isValid()) {
            holo.remove();
        }
        org.bukkit.boss.BossBar bar = activeBossBars.remove(uuid);
        if (bar != null) {
            bar.removePlayer(player);
        }
        if (activeTeleports.containsKey(uuid)) {
            activeTeleports.remove(uuid).cancel();
            if (notify) {
                plugin.getMessageManager().sendMessage(player, "teleport.cancelled-movement");
                plugin.getMessageManager().sendActionBar(player, "teleport.actionbar-cancelled");
                try {
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public boolean isTeleporting(Player player) {
        return activeTeleports.containsKey(player.getUniqueId());
    }

    /**
     * Marque un joueur comme étant en combat (Durée 10 secondes par défaut)
     */
    public void tagCombat(Player player) {
        // 10000 ms = 10 secondes
        combatTags.put(player.getUniqueId(), System.currentTimeMillis() + 10000);
    }

    /**
     * Vérifie si le joueur est actuellement sous l'effet du tag de combat.
     */
    public boolean isInCombat(Player player) {
        if (!combatTags.containsKey(player.getUniqueId()))
            return false;
        long expiration = combatTags.get(player.getUniqueId());
        if (System.currentTimeMillis() >= expiration) {
            combatTags.remove(player.getUniqueId()); // Expire le tag s'il est dépassé
            return false;
        }
        return true;
    }

    private boolean isSafeLocation(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        if (loc.getY() < loc.getWorld().getMinHeight() || loc.getY() > loc.getWorld().getMaxHeight() - 2) {
            return false;
        }
        org.bukkit.block.Block feet = loc.getBlock();
        org.bukkit.block.Block head = loc.clone().add(0, 1, 0).getBlock();
        org.bukkit.block.Block under = loc.clone().add(0, -1, 0).getBlock();

        // Check feet and head are passable (not solid, not suffocating)
        if (feet.getType().isSolid() && !feet.isPassable()) return false;
        if (head.getType().isSolid() && !head.isPassable()) return false;

        // Check if there is lava or fire in feet/head
        String feetType = feet.getType().name();
        String headType = head.getType().name();
        if (feetType.contains("LAVA") || feetType.contains("FIRE") || feetType.contains("CAMPFIRE") ||
            headType.contains("LAVA") || headType.contains("FIRE") || headType.contains("CAMPFIRE")) {
            return false;
        }

        // Check block below is solid AND not dangerous (not lava, not void, not fire)
        String underType = under.getType().name();
        if (underType.contains("LAVA") || underType.contains("FIRE") || underType.contains("CAMPFIRE") || underType.contains("AIR")) {
            return false;
        }
        if (!under.getType().isSolid()) {
            return false;
        }

        return true;
    }

    private Location findSafeLocation(Location origin) {
        if (isSafeLocation(origin)) {
            return origin;
        }

        List<Location> candidates = new ArrayList<>();
        org.bukkit.World world = origin.getWorld();
        int cx = origin.getBlockX();
        int cy = origin.getBlockY();
        int cz = origin.getBlockZ();

        for (int x = -3; x <= 3; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -3; z <= 3; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    Location loc = new Location(world, cx + x + 0.5, cy + y, cz + z + 0.5, origin.getYaw(), origin.getPitch());
                    if (isSafeLocation(loc)) {
                        candidates.add(loc);
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        // Sort candidates by distance squared to the origin
        candidates.sort((l1, l2) -> Double.compare(l1.distanceSquared(origin), l2.distanceSquared(origin)));
        return candidates.get(0);
    }

    private void executeTeleport(Player player, Home home) {
        Location dest = home.getLocation();
        if (dest == null) {
            plugin.getMessageManager().sendMessage(player, "teleport.error-world-not-loaded", "{world}",
                    home.getWorldName());
            return;
        }

        boolean adjustedToSafe = false;

        // Vérification de sécurité (Lave / Suffocation / Vide)
        if (plugin.getConfig().getBoolean("safety.check-safe-destination", true)) {
            if (!isSafeLocation(dest)) {
                Location safeLoc = findSafeLocation(dest);
                if (safeLoc != null) {
                    dest = safeLoc;
                    adjustedToSafe = true;
                } else {
                    // Si aucune position sûre n'est trouvée, on refuse la téléportation avec la raison spécifique
                    org.bukkit.block.Block feetBlock = dest.getBlock();
                    org.bukkit.block.Block underBlock = dest.clone().add(0, -1, 0).getBlock();

                    if (feetBlock.getType().name().contains("LAVA") || underBlock.getType().name().contains("LAVA")) {
                        plugin.getMessageManager().sendMessage(player, "safety.lava");
                        return;
                    }
                    if (dest.getY() < dest.getWorld().getMinHeight()) {
                        plugin.getMessageManager().sendMessage(player, "safety.void");
                        return;
                    }
                    plugin.getMessageManager().sendMessage(player, "safety.suffocation");
                    return;
                }
            }
        }

        final Location finalDest = dest;
        final boolean finalAdjusted = adjustedToSafe;

        plugin.getClaimsIntegrationManager().canAccessLocationAsync(player, finalDest).thenAccept(canAccess -> {
            plugin.getScheduler().runTaskAtEntity(player, () -> {
                if (!canAccess) {
                    plugin.getMessageManager().sendMessage(player, "safety.region-denied"); // Fallback key
                    return;
                }

                // --- LOGIQUE ÉCONOMIE ET SOCIAL ---
                
                // A. Mettre à jour le cooldown global (dès que le TP commence à s'exécuter !)
                int delaySecs = plugin.getConfig().getInt("teleport.delay-between-tp", 30);
                if (delaySecs > 0) {
                     globalCooldowns.put(player.getUniqueId(), System.currentTimeMillis() + (delaySecs * 1000L));
                }
                
                // B. Appliquer la protection temporaire
                int protectionSecs = plugin.getConfig().getInt("teleport.post-tp-protection", 5);
                if (protectionSecs > 0) {
                     protectionTags.put(player.getUniqueId(), System.currentTimeMillis() + (protectionSecs * 1000L));
                }

                // 1. Prélever l'argent après validation totale des sécurités (TP imminent)
                double cost = plugin.getConfig().getDouble("economy.teleport-cost", 0.0);
                boolean charged = false;
                if (plugin.getEconomyManager().isEnabled() && cost > 0 && !player.hasPermission("sethomex.bypass.cost")) {
                    if (!plugin.getEconomyManager().withdraw(player, cost)) {
                        plugin.getMessageManager().sendMessage(player, "economy.insufficient-funds", "{cost}", plugin.getEconomyManager().format(cost));
                        return;
                    }
                    charged = true;
                }

                // Prélever la taxe de visite si applicable
                double visitFee = 0.0;
                if (!home.getPlayerUuid().equals(player.getUniqueId())) {
                    visitFee = home.getVisitFee();
                    if (plugin.getEconomyManager().isEnabled() && visitFee > 0 && !player.hasPermission("sethomex.bypass.fee")) {
                        if (!plugin.getEconomyManager().withdraw(player, visitFee)) {
                            plugin.getMessageManager().sendMessage(player, "economy.insufficient-funds", "{cost}", plugin.getEconomyManager().format(visitFee));
                            return;
                        }

                        // Calcul de la commission du serveur
                        double taxPercent = plugin.getConfig().getDouble("economy.visit-fee-tax-percent", 10.0) / 100.0;
                        double taxAmount = visitFee * taxPercent;
                        double netAmount = visitFee - taxAmount;

                        // Créditer le propriétaire
                        org.bukkit.OfflinePlayer owner = Bukkit.getOfflinePlayer(home.getPlayerUuid());
                        plugin.getEconomyManager().deposit(owner, netAmount);

                        // Message de confirmation au visiteur
                        player.sendMessage("§6[Économie] §aVous avez payé une taxe d'entrée de §e" + plugin.getEconomyManager().format(visitFee) + " §apour visiter ce home.");

                        // Notification au propriétaire s'il est en ligne
                        Player onlineOwner = owner.getPlayer();
                        if (onlineOwner != null && onlineOwner.isOnline()) {
                            onlineOwner.sendMessage("§6[Économie] §e" + player.getName() + " §aa visité votre home §e" + home.getName() + "§a. Vous avez reçu §e" + plugin.getEconomyManager().format(netAmount) + " §7(Taxe serveur: " + (int)(taxPercent * 100) + "%).");
                        }
                    }
                }

                // 2. Comptage des visites si c'est le home d'un autre joueur (avec Anti-Spam !)
                if (!home.getPlayerUuid().equals(player.getUniqueId())) {
                     String visitKey = player.getUniqueId() + "_" + home.getPlayerUuid() + "_" + home.getName().toLowerCase();
                     if (visitCache.getIfPresent(visitKey) == null) {
                         visitCache.put(visitKey, System.currentTimeMillis());
                         home.incrementVisits();
                         plugin.getHomeManager().updateHomeSocial(home); // Sync async DB
                     }
                }

                // Particules au départ
                player.getWorld().spawnParticle(Particle.PORTAL, player.getLocation(), 50, 0.5, 1, 0.5, 0.1);

                final boolean finalCharged = charged;
                final double finalCost = cost;
                // Téléportation effective
                player.teleportAsync(finalDest).thenAccept(success -> {
                    if (success) {
                        // Log visit
                        plugin.getHomeManager().logVisit(home, player);

                        // Time & Weather override
                        applyWeatherTimeOverride(player, home);

                        // Blindness transition
                        player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS, 20, 1));

                        // Play music record
                        if (home.getMusicDisc() != null && !home.getMusicDisc().equalsIgnoreCase("none")) {
                            try {
                                org.bukkit.Material discMat = org.bukkit.Material.valueOf(home.getMusicDisc().toUpperCase());
                                player.playEffect(finalDest, org.bukkit.Effect.RECORD_PLAY, discMat);
                            } catch (Exception ignored) {}
                        }

                        // Particules et son à l'arrivée
                        String successSoundName = getPlayerSuccessSound(player.getUniqueId());
                        if ("default".equalsIgnoreCase(successSoundName)) {
                            successSoundName = plugin.getConfig().getString("effects.success.sound", "ENTITY_ENDERMAN_TELEPORT");
                        }
                        float volume = (float) plugin.getConfig().getDouble("effects.success.volume", 1.0);
                        float pitch = (float) plugin.getConfig().getDouble("effects.success.pitch", 1.2);
                        Sound sound = getSoundFromName(successSoundName);
                        if (sound != null) {
                            player.playSound(finalDest, sound, volume, pitch);
                        }

                        String successParticleName = getPlayerParticle(player.getUniqueId());
                        if ("default".equalsIgnoreCase(successParticleName)) {
                            successParticleName = plugin.getConfig().getString("effects.success.particle", "EXPLOSION");
                        }
                        try {
                            finalDest.getWorld().spawnParticle(Particle.valueOf(successParticleName), finalDest.clone().add(0, 1, 0), 30, 0.3,
                                    0.5, 0.3, 0.05);
                        } catch (Exception ignored) {
                            try {
                                finalDest.getWorld().spawnParticle(Particle.EXPLOSION, finalDest.clone().add(0, 1, 0), 30, 0.3, 0.5, 0.3, 0.05);
                            } catch (Exception ignored2) {}
                        }

                        if (finalAdjusted) {
                            plugin.getMessageManager().sendMessage(player, "safety.obstructed-teleport");
                        } else {
                            plugin.getMessageManager().sendMessage(player, "teleport.success", "{name}", home.getName());
                        }
                        plugin.getMessageManager().sendActionBar(player, "teleport.actionbar-success");

                        if (home.getWelcomeMessage() != null && !home.getWelcomeMessage().isEmpty()) {
                            try {
                                String minimsgText = plugin.getMessageManager().convertLegacyToMiniMessage(home.getWelcomeMessage().replace("&", "§"));
                                net.kyori.adventure.text.Component welcomeComp = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(minimsgText);
                                player.showTitle(net.kyori.adventure.title.Title.title(
                                    net.kyori.adventure.text.Component.empty(),
                                    welcomeComp,
                                    net.kyori.adventure.title.Title.Times.times(
                                        java.time.Duration.ofMillis(plugin.getConfig().getLong("social.welcome-message.fade-in-ms", 500L)),
                                        java.time.Duration.ofMillis(plugin.getConfig().getLong("social.welcome-message.stay-ms", 3000L)),
                                        java.time.Duration.ofMillis(plugin.getConfig().getLong("social.welcome-message.fade-out-ms", 500L))
                                    )
                                ));
                                player.sendMessage(welcomeComp);
                            } catch (Exception e) {
                                plugin.getLogger().warning("Failed to show welcome message title: " + e.getMessage());
                            }
                        }

                        if (finalCharged) {
                             plugin.getMessageManager().sendMessage(player, "economy.charged-teleport", "{cost}", plugin.getEconomyManager().format(finalCost));
                        }
                    }
                });
            });
        });
    }

    private void spawnTeleportParticles(Player player, Location loc, double angle, String style, Particle particle, int ticksLeft, int totalTicks) {
        double radius = 0.8;

        if (style.equals("SPIRAL")) {
            // Spirale montante
            double yOffset = (angle % (2 * Math.PI)) / (2 * Math.PI) * 2.0; // Monte de 0 à 2 blocs de hauteur
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            player.getWorld().spawnParticle(particle, loc.clone().add(x, yOffset, z), 1, 0, 0, 0, 0);
        } else if (style.equals("RING")) {
            // Double anneau rotatif
            double x1 = Math.cos(angle) * radius;
            double z1 = Math.sin(angle) * radius;
            double x2 = Math.cos(angle + Math.PI) * radius;
            double z2 = Math.sin(angle + Math.PI) * radius;
            player.getWorld().spawnParticle(particle, loc.clone().add(x1, 0.1, z1), 1, 0, 0, 0, 0);
            player.getWorld().spawnParticle(particle, loc.clone().add(x2, 1.8, z2), 1, 0, 0, 0, 0);
        } else if (style.equals("TORNADO")) {
            // Tornado style
            double yOffset = (angle % (2 * Math.PI)) / (2 * Math.PI) * 2.0; // Monte de 0 à 2 blocs
            double radiusTornado = 0.3 + (yOffset / 2.0) * 0.6; // Rayon de 0.3 à 0.9
            double x = Math.cos(angle * 2) * radiusTornado;
            double z = Math.sin(angle * 2) * radiusTornado;
            player.getWorld().spawnParticle(particle, loc.clone().add(x, yOffset, z), 1, 0, 0, 0, 0);
        } else if (style.equalsIgnoreCase("PROGRESSIVE_RING")) {
            double progress = (double) (totalTicks - ticksLeft) / totalTicks;
            double yOffset = progress * 2.0;
            for (int i = 0; i < 8; i++) {
                double pAngle = (i * Math.PI / 4) + angle;
                double px = Math.cos(pAngle) * radius;
                double pz = Math.sin(pAngle) * radius;
                player.getWorld().spawnParticle(particle, loc.clone().add(px, yOffset, pz), 1, 0, 0, 0, 0);
            }
        } else if (style.equalsIgnoreCase("BEACON")) {
            double progress = (double) (totalTicks - ticksLeft) / totalTicks;
            for (int b = 0; b < 3; b++) {
                double bAngle = (b * 2 * Math.PI / 3) + angle;
                double bx = Math.cos(bAngle) * 0.5;
                double bz = Math.sin(bAngle) * 0.5;
                for (double y = 0; y <= progress * 2.5; y += 0.25) {
                    player.getWorld().spawnParticle(particle, loc.clone().add(bx, y, bz), 1, 0, 0, 0, 0);
                }
            }
        } else if (style.equalsIgnoreCase("IMPLOSION")) {
            double ratio = (double) ticksLeft / totalTicks;
            double currentRadius = 0.3 + ratio * 2.7;
            for (double yOffset = 0.0; yOffset <= 2.0; yOffset += 0.4) {
                double spiralAngle = angle + yOffset * 2.0;
                double px = Math.cos(spiralAngle) * currentRadius;
                double pz = Math.sin(spiralAngle) * currentRadius;
                player.getWorld().spawnParticle(particle, loc.clone().add(px, yOffset, pz), 1, 0, 0, 0, 0);
            }
        } else {
            // Effet bulle de protection classique (SHIELD)
            player.getWorld().spawnParticle(particle, loc.clone().add(0, 1, 0), 3, 0.4, 0.8, 0.4, 0.02);
        }
    }

    public Sound getSoundFromName(String name) {
        if (name == null || name.isEmpty())
            return null;
        try {
            // Tente de récupérer depuis le registre moderne (supporte les formats anciens
            // "BLOCK_NOTE_BLOCK_PLING" et modernes "block.note_block.pling")
            String formattedKey = name.toLowerCase().replace("_", ".");
            NamespacedKey key = NamespacedKey.fromString(formattedKey);
            if (key != null) {
                Sound sound = Registry.SOUNDS.get(key);
                if (sound != null)
                    return sound;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public Particle getParticleFromName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            return Particle.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void playCosmeticPreview(Player player, String particleName, String styleName) {
        if ("default".equalsIgnoreCase(particleName)) {
            particleName = plugin.getConfig().getString("effects.countdown.particle", "PORTAL");
        }
        if ("default".equalsIgnoreCase(styleName)) {
            styleName = plugin.getConfig().getString("effects.countdown.style", "SPIRAL");
        }

        Particle particle = getParticleFromName(particleName);
        if (particle == null) {
            particle = Particle.PORTAL; // Fallback
        }
        
        final Particle finalParticle = particle;
        final String finalStyle = styleName != null ? styleName.toUpperCase() : "SPIRAL";
        
        final ScheduledTask[] taskHolder = new ScheduledTask[1];
        taskHolder[0] = plugin.getScheduler().runTaskTimerAtEntity(player, new Runnable() {
            int ticksLeft = 20;
            double angle = 0;
            
            @Override
            public void run() {
                if (!player.isOnline()) {
                    activeCosmeticPreviews.remove(player.getUniqueId());
                    if (taskHolder[0] != null) {
                        taskHolder[0].cancel();
                    }
                    return;
                }
                if (ticksLeft <= 0) {
                    activeCosmeticPreviews.remove(player.getUniqueId());
                    if (taskHolder[0] != null) {
                        taskHolder[0].cancel();
                    }
                    return;
                }
                
                spawnTeleportParticles(player, player.getLocation(), angle, finalStyle, finalParticle, ticksLeft, 20);
                angle += 0.2;
                ticksLeft--;
            }
        }, 0L, 1L);

        ScheduledTask oldTask = activeCosmeticPreviews.put(player.getUniqueId(), taskHolder[0]);
        if (oldTask != null) {
            oldTask.cancel();
        }
    }

    /**
     * Vérifie si le joueur bénéficie de la protection anti-dégâts temporaire post-téléportation.
     */
    public boolean isProtected(Player player) {
        if (!protectionTags.containsKey(player.getUniqueId())) return false;
        long exp = protectionTags.get(player.getUniqueId());
        if (System.currentTimeMillis() >= exp) {
             protectionTags.remove(player.getUniqueId());
             return false;
        }
        return true;
    }

    // =========================================================================
    // SECTION PREVIEW (SPECTATEUR SÉCURISÉ)
    // =========================================================================

    public boolean isPreviewing(Player player) {
        return activePreviews.containsKey(player.getUniqueId());
    }

    public Map<UUID, PreviewSession> getActivePreviews() {
        return activePreviews;
    }

    public void startPreview(Player player, Home home) {
        UUID uuid = player.getUniqueId();
        if (activePreviews.containsKey(uuid)) {
            player.sendMessage("§cVous êtes déjà en train de prévisualiser un home !");
            return;
        }

        if (isInCombat(player)) {
            plugin.getMessageManager().sendMessage(player, "teleport.error-combat");
            return;
        }

        Location originalLoc = player.getLocation();
        org.bukkit.GameMode originalGM = player.getGameMode();
        boolean originalFly = player.getAllowFlight();
        Location targetLoc = home.getLocation();
        if (targetLoc == null) {
            player.sendMessage("§cLe monde de destination n'est pas chargé.");
            return;
        }

        // Save initial state to PDC
        try {
            org.bukkit.persistence.PersistentDataContainer pdc = player.getPersistentDataContainer();
            pdc.set(previewActiveKey, org.bukkit.persistence.PersistentDataType.INTEGER, 1);
            pdc.set(previewWorldKey, org.bukkit.persistence.PersistentDataType.STRING, originalLoc.getWorld().getName());
            pdc.set(previewXKey, org.bukkit.persistence.PersistentDataType.DOUBLE, originalLoc.getX());
            pdc.set(previewYKey, org.bukkit.persistence.PersistentDataType.DOUBLE, originalLoc.getY());
            pdc.set(previewZKey, org.bukkit.persistence.PersistentDataType.DOUBLE, originalLoc.getZ());
            pdc.set(previewYawKey, org.bukkit.persistence.PersistentDataType.FLOAT, originalLoc.getYaw());
            pdc.set(previewPitchKey, org.bukkit.persistence.PersistentDataType.FLOAT, originalLoc.getPitch());
            pdc.set(previewGMKey, org.bukkit.persistence.PersistentDataType.STRING, originalGM.name());
            pdc.set(previewFlyKey, org.bukkit.persistence.PersistentDataType.INTEGER, player.isFlying() ? 1 : 0);
            pdc.set(previewAllowFlyKey, org.bukkit.persistence.PersistentDataType.INTEGER, originalFly ? 1 : 0);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to write preview PDC info: " + e.getMessage());
        }

        player.closeInventory();
        player.setGameMode(org.bukkit.GameMode.SPECTATOR);
        TeleportUtil.teleportAsync(player, targetLoc);
        player.sendMessage("§e§lSethomeX §8» §aDébut de la prévisualisation (5 secondes).");

        boolean bedrock = isBedrockPlayer(player);
        final ScheduledTask[] taskHolder = new ScheduledTask[1];
        taskHolder[0] = plugin.getScheduler().runTaskTimerAtEntity(player, new Runnable() {
            int durationSeconds = plugin.getConfig().getInt("teleport.preview.duration", 5);
            int totalTicks = durationSeconds * 20;
            int ticks = 0;
            @Override
            public void run() {
                if (!player.isOnline()) {
                    if (taskHolder[0] != null) {
                        taskHolder[0].cancel();
                    }
                    endPreview(player, false, home);
                    return;
                }

                if (ticks >= totalTicks) {
                    if (taskHolder[0] != null) {
                        taskHolder[0].cancel();
                    }
                    endPreview(player, true, home);
                    return;
                }

                // Smooth orbit cam for Java players
                if (!bedrock && targetLoc != null) {
                    double angle = (2 * Math.PI / 160.0) * ticks; // 1 full rotation in 8 seconds
                    double radius = 4.0;
                    double x = targetLoc.getX() + radius * Math.cos(angle);
                    double z = targetLoc.getZ() + radius * Math.sin(angle);
                    double y = targetLoc.getY() + 3.0;
                    Location newCamLoc = new Location(targetLoc.getWorld(), x, y, z);
                    org.bukkit.util.Vector direction = targetLoc.toVector().subtract(newCamLoc.toVector());
                    newCamLoc.setDirection(direction);
                    TeleportUtil.teleportAsync(player, newCamLoc);
                }

                if (ticks % 20 == 0) {
                    int secondsLeft = durationSeconds - (ticks / 20);
                    player.sendActionBar(net.kyori.adventure.text.Component.text("§eAperçu en cours... §c" + secondsLeft + "s §erestantes"));
                }
                ticks++;
            }
        }, 0L, 1L);

        activePreviews.put(uuid, new PreviewSession(originalLoc, originalGM, originalFly, targetLoc, taskHolder[0]));
    }

    public void endPreview(Player player, boolean teleportBack, Home home) {
        UUID uuid = player.getUniqueId();
        PreviewSession session = activePreviews.remove(uuid);
        if (session == null) {
            clearPreviewPDC(player);
            return;
        }

        if (session.task != null) {
            session.task.cancel();
        }

        if (player.isOnline()) {
            player.sendActionBar(net.kyori.adventure.text.Component.text(""));
            if (teleportBack) {
                TeleportUtil.teleportAsync(player, session.originalLocation);
                player.setGameMode(session.originalGameMode);
                player.setAllowFlight(session.originalFlying);
                player.setFlying(session.originalFlying);
                
                player.sendMessage("§e§lSethomeX §8» §aAperçu terminé. Vous avez été ramené à votre position.");
                if (home != null) {
                    String ownerName = plugin.getHomeManager().getPlayerName(home.getPlayerUuid());
                    if (ownerName == null) ownerName = "Unknown";
                    
                    net.kyori.adventure.text.TextComponent msg = net.kyori.adventure.text.Component.text()
                            .append(net.kyori.adventure.text.Component.text("§7[SethomeX] Voulez-vous vous y téléporter définitivement ? "))
                            .append(net.kyori.adventure.text.Component.text("§a§l[CLIQUEZ ICI]")
                                    .hoverEvent(net.kyori.adventure.text.Component.text("§aCliquez pour vous téléporter définitivement vers ce home !"))
                                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/home " + ownerName + " " + home.getName())))
                            .build();
                    player.sendMessage(msg);
                }
            } else {
                player.setGameMode(session.originalGameMode);
                player.setAllowFlight(session.originalFlying);
            }
        }
        clearPreviewPDC(player);
    }

    public void clearPreviewPDC(Player player) {
        try {
            org.bukkit.persistence.PersistentDataContainer pdc = player.getPersistentDataContainer();
            pdc.remove(previewActiveKey);
            pdc.remove(previewWorldKey);
            pdc.remove(previewXKey);
            pdc.remove(previewYKey);
            pdc.remove(previewZKey);
            pdc.remove(previewYawKey);
            pdc.remove(previewPitchKey);
            pdc.remove(previewGMKey);
            pdc.remove(previewFlyKey);
            pdc.remove(previewAllowFlyKey);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to clear preview PDC info: " + e.getMessage());
        }
    }

    public void checkAndRestorePreviewState(Player player) {
        try {
            org.bukkit.persistence.PersistentDataContainer pdc = player.getPersistentDataContainer();
            if (!pdc.has(previewActiveKey, org.bukkit.persistence.PersistentDataType.INTEGER)) {
                return;
            }

            String wName = pdc.get(previewWorldKey, org.bukkit.persistence.PersistentDataType.STRING);
            Double x = pdc.get(previewXKey, org.bukkit.persistence.PersistentDataType.DOUBLE);
            Double y = pdc.get(previewYKey, org.bukkit.persistence.PersistentDataType.DOUBLE);
            Double z = pdc.get(previewZKey, org.bukkit.persistence.PersistentDataType.DOUBLE);
            Float yaw = pdc.get(previewYawKey, org.bukkit.persistence.PersistentDataType.FLOAT);
            Float pitch = pdc.get(previewPitchKey, org.bukkit.persistence.PersistentDataType.FLOAT);
            String gmName = pdc.get(previewGMKey, org.bukkit.persistence.PersistentDataType.STRING);
            Integer fly = pdc.get(previewFlyKey, org.bukkit.persistence.PersistentDataType.INTEGER);
            Integer allowFly = pdc.get(previewAllowFlyKey, org.bukkit.persistence.PersistentDataType.INTEGER);

            if (wName != null && x != null && y != null && z != null) {
                org.bukkit.World world = Bukkit.getWorld(wName);
                if (world != null) {
                    Location loc = new Location(world, x, y, z, yaw != null ? yaw : 0f, pitch != null ? pitch : 0f);
                    TeleportUtil.teleportAsync(player, loc);
                }
            }

            if (gmName != null) {
                try {
                    player.setGameMode(org.bukkit.GameMode.valueOf(gmName));
                } catch (Exception ignored) {}
            }

            if (allowFly != null) {
                player.setAllowFlight(allowFly == 1);
            }
            if (fly != null) {
                player.setFlying(fly == 1);
            }

            player.sendMessage("§e§lSethomeX §8» §aVotre session d'aperçu a été interrompue. Votre position et mode de jeu ont été restaurés.");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to restore preview state for " + player.getName() + ": " + e.getMessage());
        } finally {
            clearPreviewPDC(player);
        }
    }

    private final Map<UUID, Location> weatherTimeOverrides = new ConcurrentHashMap<>();

    public void applyWeatherTimeOverride(Player player, Home home) {
        if (home.getTimeLock() != -1) {
            player.setPlayerTime(home.getTimeLock(), false);
        }
        if (home.getWeatherLock() != null && !home.getWeatherLock().equalsIgnoreCase("none")) {
            try {
                player.setPlayerWeather(org.bukkit.WeatherType.valueOf(home.getWeatherLock().toUpperCase()));
            } catch (Exception ignored) {}
        }
        if (home.getTimeLock() != -1 || (home.getWeatherLock() != null && !home.getWeatherLock().equalsIgnoreCase("none"))) {
            weatherTimeOverrides.put(player.getUniqueId(), home.getLocation());
        }
    }

    public void checkAndResetOverrides(Player player) {
        UUID uuid = player.getUniqueId();
        Location loc = weatherTimeOverrides.get(uuid);
        if (loc != null) {
            if (!player.getWorld().equals(loc.getWorld()) || player.getLocation().distanceSquared(loc) > 2500.0) { // 50 blocks
                player.resetPlayerTime();
                player.resetPlayerWeather();
                weatherTimeOverrides.remove(uuid);
            }
        }
    }

    public void removeOverrideOnQuit(Player player) {
        weatherTimeOverrides.remove(player.getUniqueId());
    }

    public boolean isBedrockPlayer(Player player) {
        try {
            Class<?> apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
            java.lang.reflect.Method apiMethod = apiClass.getMethod("api");
            Object apiInstance = apiMethod.invoke(null);
            java.lang.reflect.Method isBedrockMethod = apiInstance.getClass().getMethod("isBedrockPlayer", UUID.class);
            return (boolean) isBedrockMethod.invoke(apiInstance, player.getUniqueId());
        } catch (Throwable ignored) {}
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            java.lang.reflect.Method getInstanceMethod = apiClass.getMethod("getInstance");
            Object apiInstance = getInstanceMethod.invoke(null);
            java.lang.reflect.Method isFloodgateMethod = apiInstance.getClass().getMethod("isFloodgatePlayer", UUID.class);
            return (boolean) isFloodgateMethod.invoke(apiInstance, player.getUniqueId());
        } catch (Throwable ignored) {}
        return player.getName().startsWith("*") || player.getName().startsWith("_");
    }
}
