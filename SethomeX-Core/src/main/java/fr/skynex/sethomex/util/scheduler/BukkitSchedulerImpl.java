package fr.skynex.sethomex.util.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public class BukkitSchedulerImpl implements TaskScheduler {

    private final Plugin plugin;

    public BukkitSchedulerImpl(Plugin plugin) {
        this.plugin = plugin;
    }

    private ScheduledTask wrap(BukkitTask task) {
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
        return wrap(Bukkit.getScheduler().runTask(plugin, runnable));
    }

    @Override
    public ScheduledTask runTaskLater(Runnable runnable, long delayTicks) {
        return wrap(Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks));
    }

    @Override
    public ScheduledTask runTaskTimer(Runnable runnable, long delayTicks, long periodTicks) {
        return wrap(Bukkit.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks));
    }

    @Override
    public ScheduledTask runTaskAsync(Runnable runnable) {
        return wrap(Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable));
    }

    @Override
    public ScheduledTask runTaskLaterAsync(Runnable runnable, long delayTicks) {
        return wrap(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, runnable, delayTicks));
    }

    @Override
    public ScheduledTask runTaskTimerAsync(Runnable runnable, long delayTicks, long periodTicks) {
        return wrap(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, runnable, delayTicks, periodTicks));
    }

    @Override
    public ScheduledTask runTaskAtEntity(Entity entity, Runnable runnable) {
        return runTask(runnable);
    }

    @Override
    public ScheduledTask runTaskLaterAtEntity(Entity entity, Runnable runnable, long delayTicks) {
        return runTaskLater(runnable, delayTicks);
    }

    @Override
    public ScheduledTask runTaskTimerAtEntity(Entity entity, Runnable runnable, long delayTicks, long periodTicks) {
        return runTaskTimer(runnable, delayTicks, periodTicks);
    }

    @Override
    public ScheduledTask runTaskAtLocation(Location location, Runnable runnable) {
        return runTask(runnable);
    }

    @Override
    public ScheduledTask runTaskLaterAtLocation(Location location, Runnable runnable, long delayTicks) {
        return runTaskLater(runnable, delayTicks);
    }

    @Override
    public ScheduledTask runTaskTimerAtLocation(Location location, Runnable runnable, long delayTicks, long periodTicks) {
        return runTaskTimer(runnable, delayTicks, periodTicks);
    }
}
