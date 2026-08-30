package fr.skynex.sethomex.util;

import fr.skynex.sethomex.SethomeX;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Scanner;
import java.util.function.Consumer;

public class UpdateChecker {

    private final SethomeX plugin;
    private final int resourceId;

    public UpdateChecker(SethomeX plugin, int resourceId) {
        this.plugin = plugin;
        this.resourceId = resourceId;
    }

    public void getVersion(final Consumer<String> consumer) {
        plugin.getScheduler().runTaskAsync(() -> {
            try (InputStream inputStream = URI
                    .create("https://api.spigotmc.org/legacy/update.php?resource=" + this.resourceId).toURL()
                    .openStream();
                    Scanner scanner = new Scanner(inputStream)) {
                if (scanner.hasNext()) {
                    consumer.accept(scanner.next());
                }
            } catch (IOException exception) {
                plugin.getLogger().warning("Unable to check for updates: " + exception.getMessage());
            }
        });
    }
}
