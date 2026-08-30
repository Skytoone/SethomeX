package fr.skynex.sethomex.managers;

import fr.skynex.sethomex.SethomeX;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

public class EconomyManager {

    private final SethomeX plugin;
    private Economy econ = null;
    private boolean isEnabled = false;

    public EconomyManager(SethomeX plugin) {
        this.plugin = plugin;
        setupEconomy();
    }

    private void setupEconomy() {
        if (!plugin.getConfig().getBoolean("economy.enabled", false)) {
            return;
        }

        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().warning("Economy enabled but Vault is missing! Disabling economy features.");
            return;
        }

        RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            plugin.getLogger().warning("Could not find a Vault economy provider (e.g. Essentials)! Disabling economy features.");
            return;
        }

        econ = rsp.getProvider();
        isEnabled = (econ != null);
        if (isEnabled) {
             plugin.getLogger().info("Successfully linked with Vault economy system!");
        }
    }

    public boolean isEnabled() {
        return isEnabled && econ != null;
    }

    public boolean hasEnough(Player player, double amount) {
        if (!isEnabled()) return true;
        if (amount <= 0) return true;
        return econ.has(player, amount);
    }

    public boolean withdraw(Player player, double amount) {
        if (!isEnabled()) return true;
        if (amount <= 0) return true;
        
        EconomyResponse response = econ.withdrawPlayer(player, amount);
        return response.transactionSuccess();
    }

    public boolean deposit(org.bukkit.OfflinePlayer player, double amount) {
        if (!isEnabled()) return true;
        if (amount <= 0) return true;
        
        EconomyResponse response = econ.depositPlayer(player, amount);
        return response.transactionSuccess();
    }

    public String format(double amount) {
        if (!isEnabled()) return String.valueOf(amount);
        return econ.format(amount);
    }
}
