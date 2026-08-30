package fr.skynex.sethomex.api.util;

import fr.skynex.sethomex.api.SethomeXAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Optional;

/**
 * Utility helper class for safely retrieving the active SethomeXAPI instance from Bukkit.
 */
public final class SethomeXHook {

    private SethomeXHook() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Obtains the registered SethomeXAPI instance.
     *
     * @return Optional containing the SethomeXAPI instance if SethomeX is loaded and enabled.
     */
    public static Optional<SethomeXAPI> getAPI() {
        if (!Bukkit.getPluginManager().isPluginEnabled("SethomeX")) {
            return Optional.empty();
        }
        RegisteredServiceProvider<SethomeXAPI> provider = Bukkit.getServicesManager().getRegistration(SethomeXAPI.class);
        return provider != null ? Optional.ofNullable(provider.getProvider()) : Optional.empty();
    }
}
