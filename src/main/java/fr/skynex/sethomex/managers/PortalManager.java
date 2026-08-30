package fr.skynex.sethomex.managers;

import fr.skynex.sethomex.SethomeX;
import fr.skynex.sethomex.models.Home;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PortalManager implements Listener {

    private final SethomeX plugin;
    private final File file;
    private YamlConfiguration config;

    // Structure représentant un portail physique
    public static class Portal {
        public UUID ownerUuid;
        public String homeName;
        public String worldName;
        public int centerX, centerY, centerZ;
        public String orientation; // "X" ou "Z"
        public List<Location> frameBlocks; // Pour la détection de destruction
        public Material frameMaterial;
        public String customParticle;
    }

    private final List<Portal> portals = new ArrayList<>();
    private final Map<UUID, String> creationModes = new ConcurrentHashMap<>(); // Player UUID -> Home Name

    public PortalManager(SethomeX plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "portals.yml");
        loadPortals();
        startParticleTask();
    }

    public void enterCreationMode(Player player, String homeName) {
        creationModes.put(player.getUniqueId(), homeName);
    }

    public boolean isInCreationMode(Player player) {
        return creationModes.containsKey(player.getUniqueId());
    }

    public void exitCreationMode(Player player) {
        creationModes.remove(player.getUniqueId());
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isInCreationMode(player)) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getClickedBlock() == null) return;

        event.setCancelled(true);
        String homeName = creationModes.remove(player.getUniqueId());
        Block clicked = event.getClickedBlock();

        // Chercher le home
        Home home = plugin.getHomeManager().getHome(player.getUniqueId(), homeName);
        if (home == null) {
            plugin.getMessageManager().sendMessage(player, "home.not-found", "{name}", homeName);
            return;
        }

        Block centerBottom = clicked;
        if (isAllowedFrameMaterial(clicked.getType())) {
            centerBottom = clicked.getRelative(0, 1, 0);
        }

        Material frameMat = detectFrameMaterial(centerBottom);
        String orientation = null;
        if (frameMat != null) {
            orientation = checkPortalFrame(centerBottom, frameMat);
        }

        if (orientation == null) {
            plugin.getMessageManager().sendMessage(player, "portal.invalid-frame");
            return;
        }

        // Créer le portail
        Portal portal = new Portal();
        portal.ownerUuid = player.getUniqueId();
        portal.homeName = homeName;
        portal.worldName = centerBottom.getWorld().getName();
        portal.centerX = centerBottom.getX();
        portal.centerY = centerBottom.getY();
        portal.centerZ = centerBottom.getZ();
        portal.orientation = orientation;
        portal.frameBlocks = getFrameLocations(centerBottom.getWorld(), centerBottom.getWorld().getName(), centerBottom.getX(), centerBottom.getY(), centerBottom.getZ(), orientation);
        portal.frameMaterial = frameMat;
        portal.customParticle = null;

        // Supprimer d'éventuels portails existants pour ce home
        portals.removeIf(p -> p.ownerUuid.equals(player.getUniqueId()) && p.homeName.equalsIgnoreCase(homeName));

        portals.add(portal);
        savePortals();

        plugin.getMessageManager().sendMessage(player, "portal.created", "{home}", homeName);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() &&
                event.getFrom().getBlockY() == event.getTo().getBlockY() &&
                event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        if (plugin.getTeleportManager().isTeleporting(player)) return;

        Block toBlock = event.getTo().getBlock();
        Portal portal = findPortalAt(toBlock);
        if (portal == null) return;

        // Récupérer le home
        Home home = plugin.getHomeManager().getHome(portal.ownerUuid, portal.homeName);
        if (home == null) return;

        // Vérifier l'accès (propriétaire ou de confiance)
        if (!portal.ownerUuid.equals(player.getUniqueId()) && !home.isTrusted(player.getUniqueId())) {
            plugin.getMessageManager().sendMessage(player, "portal.no-access");
            return;
        }

        // Lancer la téléportation
        plugin.getMessageManager().sendMessage(player, "portal.teleporting", "{owner}", plugin.getHomeManager().getPlayerName(portal.ownerUuid), "{home}", portal.homeName);
        plugin.getTeleportManager().startTeleport(player, home, portal);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Iterator<Portal> iterator = portals.iterator();
        boolean removed = false;
        while (iterator.hasNext()) {
            Portal portal = iterator.next();
            if (!portal.worldName.equalsIgnoreCase(block.getWorld().getName())) continue;
            
            // Si le bloc détruit fait partie du cadre ou du centre
            boolean isPart = (block.getX() == portal.centerX && block.getZ() == portal.centerZ && 
                              (block.getY() == portal.centerY || block.getY() == portal.centerY + 1));
            
            if (!isPart) {
                for (Location loc : portal.frameBlocks) {
                    if (loc.getBlockX() == block.getX() && loc.getBlockY() == block.getY() && loc.getBlockZ() == block.getZ()) {
                        isPart = true;
                        break;
                    }
                }
            }

            if (isPart) {
                iterator.remove();
                removed = true;
                Player owner = Bukkit.getPlayer(portal.ownerUuid);
                if (owner != null && owner.isOnline()) {
                    plugin.getMessageManager().sendMessage(owner, "portal.destroyed", "{home}", portal.homeName);
                }
            }
        }

        if (removed) {
            savePortals();
        }
    }

    private Portal findPortalAt(Block block) {
        for (Portal portal : portals) {
            if (!portal.worldName.equalsIgnoreCase(block.getWorld().getName())) continue;
            if (portal.centerX == block.getX() && portal.centerZ == block.getZ()) {
                if (block.getY() == portal.centerY || block.getY() == portal.centerY + 1) {
                    return portal;
                }
            }
        }
        return null;
    }

    public Portal getPortal(UUID ownerUuid, String homeName) {
        for (Portal p : portals) {
            if (p.ownerUuid.equals(ownerUuid) && p.homeName.equalsIgnoreCase(homeName)) {
                return p;
            }
        }
        return null;
    }

    public boolean isAllowedFrameMaterial(Material material) {
        return getAllowedFrameMaterials().contains(material);
    }

    public Set<Material> getAllowedFrameMaterials() {
        Set<Material> materials = new HashSet<>();
        org.bukkit.configuration.ConfigurationSection sec = plugin.getConfig().getConfigurationSection("portals.allowed-frames");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                try {
                    materials.add(Material.valueOf(key.toUpperCase()));
                } catch (IllegalArgumentException ignored) {}
            }
        }
        if (materials.isEmpty()) {
            materials.add(Material.QUARTZ_BLOCK);
        }
        return materials;
    }

    public Material detectFrameMaterial(Block cb) {
        Material mat = cb.getRelative(0, -1, 0).getType();
        if (isAllowedFrameMaterial(mat)) return mat;
        return null;
    }

    public double getCooldownMultiplier(Material frameMat) {
        if (frameMat == null) return 1.0;
        String path = "portals.allowed-frames." + frameMat.name() + ".cooldown-multiplier";
        return plugin.getConfig().getDouble(path, 1.0);
    }

    public Particle getDefaultParticle(Material frameMat) {
        if (frameMat == null) return Particle.PORTAL;
        String path = "portals.allowed-frames." + frameMat.name() + ".particle";
        String partName = plugin.getConfig().getString(path, "PORTAL");
        try {
            return Particle.valueOf(partName.toUpperCase());
        } catch (Exception e) {
            return Particle.PORTAL;
        }
    }

    // Valide le cadre et retourne l'orientation ("X" ou "Z") ou null
    private String checkPortalFrame(Block centerBottom, Material frameMat) {
        // Le portail fait 1x2 au centre. Le cadre entoure ces deux blocs d'air.
        if (checkFrameOrientationX(centerBottom, frameMat)) return "X";
        if (checkFrameOrientationZ(centerBottom, frameMat)) return "Z";
        return null;
    }

    private boolean checkFrameOrientationX(Block cb, Material mat) {
        // Socle (sans les coins)
        if (cb.getRelative(0, -1, 0).getType() != mat) return false;

        // Côtés gauche/droite
        if (cb.getRelative(-1, 0, 0).getType() != mat) return false;
        if (cb.getRelative(-1, 1, 0).getType() != mat) return false;
        if (cb.getRelative(1, 0, 0).getType() != mat) return false;
        if (cb.getRelative(1, 1, 0).getType() != mat) return false;

        // Haut (sans les coins)
        if (cb.getRelative(0, 2, 0).getType() != mat) return false;

        return true;
    }

    private boolean checkFrameOrientationZ(Block cb, Material mat) {
        // Socle (sans les coins)
        if (cb.getRelative(0, -1, 0).getType() != mat) return false;

        // Côtés gauche/droite
        if (cb.getRelative(0, 0, -1).getType() != mat) return false;
        if (cb.getRelative(0, 1, -1).getType() != mat) return false;
        if (cb.getRelative(0, 0, 1).getType() != mat) return false;
        if (cb.getRelative(0, 1, 1).getType() != mat) return false;

        // Haut (sans les coins)
        if (cb.getRelative(0, 2, 0).getType() != mat) return false;

        return true;
    }

    private List<Location> getFrameLocations(org.bukkit.World world, String worldName, int cx, int cy, int cz, String orientation) {
        List<Location> list = new ArrayList<>();
        if ("X".equalsIgnoreCase(orientation)) {
            list.add(new Location(world, cx, cy - 1, cz));
            list.add(new Location(world, cx - 1, cy - 1, cz));
            list.add(new Location(world, cx + 1, cy - 1, cz));
            list.add(new Location(world, cx - 1, cy, cz));
            list.add(new Location(world, cx - 1, cy + 1, cz));
            list.add(new Location(world, cx + 1, cy, cz));
            list.add(new Location(world, cx + 1, cy + 1, cz));
            list.add(new Location(world, cx, cy + 2, cz));
            list.add(new Location(world, cx - 1, cy + 2, cz));
            list.add(new Location(world, cx + 1, cy + 2, cz));
        } else {
            list.add(new Location(world, cx, cy - 1, cz));
            list.add(new Location(world, cx, cy - 1, cz - 1));
            list.add(new Location(world, cx, cy - 1, cz + 1));
            list.add(new Location(world, cx, cy, cz - 1));
            list.add(new Location(world, cx, cy + 1, cz - 1));
            list.add(new Location(world, cx, cy, cz + 1));
            list.add(new Location(world, cx, cy + 1, cz + 1));
            list.add(new Location(world, cx, cy + 2, cz));
            list.add(new Location(world, cx, cy + 2, cz - 1));
            list.add(new Location(world, cx, cy + 2, cz + 1));
        }
        return list;
    }

    private void startParticleTask() {
        plugin.getScheduler().runTaskTimerAsync(() -> {
            for (Portal portal : portals) {
                org.bukkit.World world = Bukkit.getWorld(portal.worldName);
                if (world == null) continue;
                
                // Centre inférieur et supérieur
                Location loc1 = new Location(world, portal.centerX + 0.5, portal.centerY + 0.5, portal.centerZ + 0.5);
                Location loc2 = new Location(world, portal.centerX + 0.5, portal.centerY + 1.5, portal.centerZ + 0.5);
                
                Particle particleType = Particle.PORTAL;
                if (portal.customParticle != null) {
                    try {
                        particleType = Particle.valueOf(portal.customParticle.toUpperCase());
                    } catch (Exception ignored) {}
                } else if (portal.frameMaterial != null) {
                    particleType = getDefaultParticle(portal.frameMaterial);
                }

                // Spawn des particules de portail élégantes
                world.spawnParticle(particleType, loc1, 3, 0.2, 0.2, 0.2, 0.02);
                world.spawnParticle(particleType, loc2, 3, 0.2, 0.2, 0.2, 0.02);
            }
        }, 0L, 10L); // Toutes les 500ms
    }

    private void loadPortals() {
        if (!file.exists()) {
            config = new YamlConfiguration();
            return;
        }
        config = YamlConfiguration.loadConfiguration(file);
        portals.clear();
        if (config.contains("portals")) {
            for (String key : config.getConfigurationSection("portals").getKeys(false)) {
                try {
                    Portal portal = new Portal();
                    portal.ownerUuid = UUID.fromString(config.getString("portals." + key + ".owner"));
                    portal.homeName = config.getString("portals." + key + ".home");
                    portal.worldName = config.getString("portals." + key + ".world");
                    portal.centerX = config.getInt("portals." + key + ".x");
                    portal.centerY = config.getInt("portals." + key + ".y");
                    portal.centerZ = config.getInt("portals." + key + ".z");
                    portal.orientation = config.getString("portals." + key + ".orientation", "X");
                    
                    String matStr = config.getString("portals." + key + ".frameMaterial", "QUARTZ_BLOCK");
                    try {
                        portal.frameMaterial = Material.valueOf(matStr.toUpperCase());
                    } catch (Exception e) {
                        portal.frameMaterial = Material.QUARTZ_BLOCK;
                    }
                    portal.customParticle = config.getString("portals." + key + ".customParticle", null);

                    // Reconstruire les blocs du cadre (indépendant du fait que le monde soit chargé à l'initialisation)
                    org.bukkit.World world = Bukkit.getWorld(portal.worldName);
                    portal.frameBlocks = getFrameLocations(world, portal.worldName, portal.centerX, portal.centerY, portal.centerZ, portal.orientation);
                    portals.add(portal);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to load portal key " + key + ": " + e.getMessage());
                }
            }
        }
    }

    public void savePortals() {
        config = new YamlConfiguration();
        int index = 0;
        for (Portal portal : portals) {
            String path = "portals.p" + index;
            config.set(path + ".owner", portal.ownerUuid.toString());
            config.set(path + ".home", portal.homeName);
            config.set(path + ".world", portal.worldName);
            config.set(path + ".x", portal.centerX);
            config.set(path + ".y", portal.centerY);
            config.set(path + ".z", portal.centerZ);
            config.set(path + ".orientation", portal.orientation);
            if (portal.frameMaterial != null) {
                config.set(path + ".frameMaterial", portal.frameMaterial.name());
            }
            if (portal.customParticle != null) {
                config.set(path + ".customParticle", portal.customParticle);
            }
            index++;
        }
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save portals.yml: " + e.getMessage());
        }
    }
}
