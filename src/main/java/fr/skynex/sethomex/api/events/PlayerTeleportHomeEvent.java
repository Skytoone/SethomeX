package fr.skynex.sethomex.api.events;

import fr.skynex.sethomex.models.Home;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class PlayerTeleportHomeEvent extends Event implements Cancellable {
    private static final HandlerList handlers = new HandlerList();
    private final Player player;
    private final Home home;
    private boolean isCancelled;

    public PlayerTeleportHomeEvent(Player player, Home home) {
        this.player = player;
        this.home = home;
        this.isCancelled = false;
    }

    public Player getPlayer() {
        return player;
    }

    public Home getHome() {
        return home;
    }

    @Override
    public boolean isCancelled() {
        return isCancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        isCancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
