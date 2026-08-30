package fr.skynex.sethomex.util.scheduler;

public interface ScheduledTask {
    void cancel();
    boolean isCancelled();
}
