package fr.skynex.sethomex.models;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Home {

    private final UUID playerUuid;
    private final String name;
    private final String worldName;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private Material iconMaterial;
    private String iconTexture;
    private boolean isPublic;
    private long visits;
    private boolean isRespawn;
    private final java.util.Map<UUID, Long> trustedPlayers;
    private final java.util.Map<UUID, String> trustedRoles;
    private final java.util.Set<UUID> bannedPlayers;
    private String category = "none";
    private String description = "";
    private String welcomeMessage = "";
    private int likesCount = 0;
    private double visitFee = 0.0;
    private String musicDisc = "none";
    private long timeLock = -1;
    private String weatherLock = "none";
    private boolean isSponsored = false;
    private long sponsoredUntil = 0;

    public Home(UUID playerUuid, String name, Location location, Material iconMaterial) {
        this(playerUuid, name, location.getWorld().getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(), iconMaterial, null, false, 0, false);
    }

    public Home(UUID playerUuid, String name, String worldName, double x, double y, double z, float yaw, float pitch, Material iconMaterial, String iconTexture, boolean isPublic, long visits, boolean isRespawn) {
        this.playerUuid = playerUuid;
        this.name = name;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.iconMaterial = iconMaterial != null ? iconMaterial : Material.RED_BED;
        this.iconTexture = iconTexture;
        this.isPublic = isPublic;
        this.visits = visits;
        this.isRespawn = isRespawn;
        this.trustedPlayers = new ConcurrentHashMap<>();
        this.trustedRoles = new ConcurrentHashMap<>();
        this.bannedPlayers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getName() {
        return name;
    }

    public String getWorldName() {
        return worldName;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public Material getIconMaterial() {
        return iconMaterial;
    }

    public void setIconMaterial(Material iconMaterial) {
        this.iconMaterial = iconMaterial;
    }

    public String getIconTexture() {
        return iconTexture;
    }

    public void setIconTexture(String iconTexture) {
        this.iconTexture = iconTexture;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public void setPublic(boolean isPublic) {
        this.isPublic = isPublic;
    }

    public long getVisits() {
        return visits;
    }

    public void incrementVisits() {
        this.visits++;
    }

    public boolean isRespawn() {
        return isRespawn;
    }

    public void setRespawn(boolean isRespawn) {
        this.isRespawn = isRespawn;
    }

    public void addTrust(UUID guest) {
        addTrust(guest, -1L, "VISITOR");
    }

    public void addTrust(UUID guest, long expiresAt) {
        addTrust(guest, expiresAt, "VISITOR");
    }

    public void addTrust(UUID guest, long expiresAt, String role) {
        trustedPlayers.put(guest, expiresAt);
        trustedRoles.put(guest, role != null ? role.toUpperCase() : "VISITOR");
    }

    public void removeTrust(UUID guest) {
        trustedPlayers.remove(guest);
        trustedRoles.remove(guest);
    }

    public boolean isTrusted(UUID guest) {
        Long expiry = trustedPlayers.get(guest);
        if (expiry == null) return false;
        if (expiry != -1L && System.currentTimeMillis() > expiry) {
            trustedPlayers.remove(guest);
            trustedRoles.remove(guest);
            return false;
        }
        return true;
    }

    public long getExpiration(UUID guest) {
        Long expiry = trustedPlayers.get(guest);
        return expiry != null ? expiry : -1L;
    }

    public String getTrustRole(UUID guest) {
        return trustedRoles.getOrDefault(guest, "VISITOR");
    }

    public void setTrustRole(UUID guest, String role) {
        trustedRoles.put(guest, role != null ? role.toUpperCase() : "VISITOR");
    }

    public Set<UUID> getTrustedPlayers() {
        // Nettoyer les expirés lors de l'accès
        trustedPlayers.keySet().removeIf(guest -> {
            Long expiry = trustedPlayers.get(guest);
            boolean expired = expiry != null && expiry != -1L && System.currentTimeMillis() > expiry;
            if (expired) {
                trustedRoles.remove(guest);
            }
            return expired;
        });
        return trustedPlayers.keySet();
    }

    public Location getLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, x, y, z, yaw, pitch);
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description != null ? description : "";
    }

    public String getWelcomeMessage() {
        return welcomeMessage;
    }

    public void setWelcomeMessage(String welcomeMessage) {
        this.welcomeMessage = welcomeMessage != null ? welcomeMessage : "";
    }

    public int getLikesCount() {
        return likesCount;
    }

    public void setLikesCount(int likesCount) {
        this.likesCount = likesCount;
    }

    public double getVisitFee() {
        return visitFee;
    }

    public void setVisitFee(double visitFee) {
        this.visitFee = Math.max(0.0, visitFee);
    }

    public java.util.Set<UUID> getBannedPlayers() {
        return bannedPlayers;
    }

    public void banPlayer(UUID uuid) {
        bannedPlayers.add(uuid);
    }

    public void unbanPlayer(UUID uuid) {
        bannedPlayers.remove(uuid);
    }

    public boolean isBanned(UUID uuid) {
        return bannedPlayers.contains(uuid);
    }

    public String getMusicDisc() {
        return musicDisc;
    }

    public void setMusicDisc(String musicDisc) {
        this.musicDisc = musicDisc != null ? musicDisc : "none";
    }

    public long getTimeLock() {
        return timeLock;
    }

    public void setTimeLock(long timeLock) {
        this.timeLock = timeLock;
    }

    public String getWeatherLock() {
        return weatherLock;
    }

    public void setWeatherLock(String weatherLock) {
        this.weatherLock = weatherLock != null ? weatherLock : "none";
    }

    public boolean isSponsored() {
        return isSponsored;
    }

    public void setSponsored(boolean sponsored) {
        isSponsored = sponsored;
    }

    public long getSponsoredUntil() {
        return sponsoredUntil;
    }

    public void setSponsoredUntil(long sponsoredUntil) {
        this.sponsoredUntil = sponsoredUntil;
    }
}
