package fr.skynex.sethomex.util.scheduler;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

public interface TaskScheduler {
    ScheduledTask runTask(Runnable runnable);
    ScheduledTask runTaskLater(Runnable runnable, long delayTicks);
    ScheduledTask runTaskTimer(Runnable runnable, long delayTicks, long periodTicks);
    
    ScheduledTask runTaskAsync(Runnable runnable);
    ScheduledTask runTaskLaterAsync(Runnable runnable, long delayTicks);
    ScheduledTask runTaskTimerAsync(Runnable runnable, long delayTicks, long periodTicks);
    
    ScheduledTask runTaskAtEntity(Entity entity, Runnable runnable);
    ScheduledTask runTaskLaterAtEntity(Entity entity, Runnable runnable, long delayTicks);
    ScheduledTask runTaskTimerAtEntity(Entity entity, Runnable runnable, long delayTicks, long periodTicks);
    
    ScheduledTask runTaskAtLocation(Location location, Runnable runnable);
    ScheduledTask runTaskLaterAtLocation(Location location, Runnable runnable, long delayTicks);
    ScheduledTask runTaskTimerAtLocation(Location location, Runnable runnable, long delayTicks, long periodTicks);
}
