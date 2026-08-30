package fr.skynex.sethomex.integration;

import fr.skynex.sethomex.SethomeX;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class PlaceholderAPIExpansion extends PlaceholderExpansion {

    private final SethomeX plugin;

    public PlaceholderAPIExpansion(SethomeX plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "sethomex";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Skynex";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // Reste enregistré lors du reload de PAPI
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        if (offlinePlayer == null) {
            return "";
        }

        // Support complet hors ligne grâce aux overloads d'UUID créés plus tôt
        int count = plugin.getHomeManager().getPlayerHomes(offlinePlayer.getUniqueId()).size();
        
        // Récupérer la limite (soit par joueur en ligne, soit par défaut offline si impossible)
        int limit = 0;
        if (offlinePlayer.isOnline() && offlinePlayer instanceof Player) {
             limit = plugin.getHomeManager().getPlayerLimit((Player) offlinePlayer);
        } else {
             // fallback pour offline players : on récupère la valeur par défaut de la config
             limit = plugin.getConfig().getInt("homes.default-limit", 3);
        }

        switch (params.toLowerCase()) {
            case "count":
                return String.valueOf(count);
            case "limit":
                return limit >= 9999 ? "∞" : String.valueOf(limit);
            case "available":
            case "left":
                if (limit >= 9999) return "∞";
                return String.valueOf(Math.max(0, limit - count));
            case "used_percentage":
                if (limit >= 9999) return "0";
                if (limit <= 0) return "100";
                int percentage = (int) (((double) count / limit) * 100);
                return String.valueOf(Math.min(100, percentage));
            default:
                return null; // Inconnu
        }
    }
}
