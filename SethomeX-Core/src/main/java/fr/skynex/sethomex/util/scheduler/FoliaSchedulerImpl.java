package fr.skynex.sethomex.util.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

public class FoliaSchedulerImpl implements TaskScheduler {

    private final Plugin plugin;

    public FoliaSchedulerImpl(Plugin plugin) {
        this.plugin = plugin;
    }

    private ScheduledTask wrap(io.papermc.paper.threadedregions.scheduler.ScheduledTask task) {
        return new ScheduledTask() {
            @Override
            public void cancel() {
                task.cancel();
            }

            @Override
            public boolean isCancelled() {
                return task.isCancelled();
            }
        };
    }

    @Override
    public ScheduledTask runTask(Runnable runnable) {
        return wrap(Bukkit.getGlobalRegionScheduler().run(plugin, task -> runnable.run()));
    }

    @Override
    public ScheduledTask runTaskLater(Runnable runnable, long delayTicks) {
        if (delayTicks <= 0) {
            return runTask(runnable);
        }
        return wrap(Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> runnable.run(), delayTicks));
    }

    @Override
    public ScheduledTask runTaskTimer(Runnable runnable, long delayTicks, long periodTicks) {
        long initialDelay = Math.max(1, delayTicks);
        long period = Math.max(1, periodTicks);
        return wrap(Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> runnable.run(), initialDelay, period));
    }

    @Override
    public ScheduledTask runTaskAsync(Runnable runnable) {
        return wrap(Bukkit.getAsyncScheduler().runNow(plugin, task -> runnable.run()));
    }

    @Override
    public ScheduledTask runTaskLaterAsync(Runnable runnable, long delayTicks) {
        if (delayTicks <= 0) {
            return runTaskAsync(runnable);
        }
        return wrap(Bukkit.getAsyncScheduler().runDelayed(plugin, task -> runnable.run(), delayTicks * 50L, TimeUnit.MILLISECONDS));
    }

    @Override
    public ScheduledTask runTaskTimerAsync(Runnable runnable, long delayTicks, long periodTicks) {
        long initialDelay = Math.max(1, delayTicks);
        long period = Math.max(1, periodTicks);
        return wrap(Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> runnable.run(), initialDelay * 50L, period * 50L, TimeUnit.MILLISECONDS));
    }

    @Override
    public ScheduledTask runTaskAtEntity(Entity entity, Runnable runnable) {
        return wrap(entity.getScheduler().run(plugin, task -> runnable.run(), null));
    }

    @Override
    public ScheduledTask runTaskLaterAtEntity(Entity entity, Runnable runnable, long delayTicks) {
        if (delayTicks <= 0) {
            return runTaskAtEntity(entity, runnable);
        }
        return wrap(entity.getScheduler().runDelayed(plugin, task -> runnable.run(), null, delayTicks));
    }

    @Override
    public ScheduledTask runTaskTimerAtEntity(Entity entity, Runnable runnable, long delayTicks, long periodTicks) {
        long initialDelay = Math.max(1, delayTicks);
        long period = Math.max(1, periodTicks);
        return wrap(entity.getScheduler().runAtFixedRate(plugin, task -> runnable.run(), null, initialDelay, period));
    }

    @Override
    public ScheduledTask runTaskAtLocation(Location location, Runnable runnable) {
        return wrap(Bukkit.getRegionScheduler().run(plugin, location, task -> runnable.run()));
    }

    @Override
    public ScheduledTask runTaskLaterAtLocation(Location location, Runnable runnable, long delayTicks) {
        if (delayTicks <= 0) {
            return runTaskAtLocation(location, runnable);
        }
        return wrap(Bukkit.getRegionScheduler().runDelayed(plugin, location, task -> runnable.run(), delayTicks));
    }

    @Override
    public ScheduledTask runTaskTimerAtLocation(Location location, Runnable runnable, long delayTicks, long periodTicks) {
        long initialDelay = Math.max(1, delayTicks);
        long period = Math.max(1, periodTicks);
        return wrap(Bukkit.getRegionScheduler().runAtFixedRate(plugin, location, task -> runnable.run(), initialDelay, period));
    }
}
