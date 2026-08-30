package fr.skynex.sethomex.api.events;

import fr.skynex.sethomex.models.Home;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class PlayerDeleteHomeEvent extends Event implements Cancellable {
    private static final HandlerList handlers = new HandlerList();
    private final Player player;
    private final Home home;
    private boolean isCancelled;

    public PlayerDeleteHomeEvent(@NotNull Player player, @NotNull Home home) {
        this.player = player;
        this.home = home;
        this.isCancelled = false;
    }

    @NotNull
    public Player getPlayer() {
        return player;
    }

    @NotNull
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

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return handlers;
    }
}
