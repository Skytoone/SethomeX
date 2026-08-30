package fr.skynex.sethomex.util;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import java.util.concurrent.CompletableFuture;

public class TeleportUtil {
    public static CompletableFuture<Boolean> teleportAsync(Entity entity, Location location) {
        try {
            return entity.teleportAsync(location);
        } catch (LinkageError e) {
            boolean success = entity.teleport(location);
            return CompletableFuture.completedFuture(success);
        }
    }
}
