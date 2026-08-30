package fr.skynex.sethomex.integration.impl;

import fr.skynex.sethomex.integration.MapHook;
import fr.skynex.sethomex.models.Home;
import org.bukkit.Bukkit;
import xyz.jpenilla.squaremap.api.Key;
import xyz.jpenilla.squaremap.api.Point;
import xyz.jpenilla.squaremap.api.SimpleLayerProvider;
import xyz.jpenilla.squaremap.api.SquaremapProvider;
import xyz.jpenilla.squaremap.api.WorldIdentifier;
import xyz.jpenilla.squaremap.api.marker.Marker;
import xyz.jpenilla.squaremap.api.marker.MarkerOptions;

import java.util.HashMap;
import java.util.Map;

public class SquaremapHook implements MapHook {

    private final Map<WorldIdentifier, SimpleLayerProvider> providers = new HashMap<>();
    private final Key layerKey = Key.of("sethomex_homes");

    public SquaremapHook() {
        setup();
    }

    private void setup() {
        try {
            SquaremapProvider.get().mapWorlds().forEach(world -> {
                SimpleLayerProvider provider = SimpleLayerProvider.builder("Public Homes (SethomeX)")
                        .showControls(true)
                        .defaultHidden(false)
                        .build();
                world.layerRegistry().register(layerKey, provider);
                providers.put(world.identifier(), provider);
            });
        } catch (Exception ignored) {
            // Fallback if API call happens before Squaremap fully loaded
        }
    }

    @Override
    public void registerHome(Home home) {
        org.bukkit.World bukkitWorld = Bukkit.getWorld(home.getWorldName());
        if (bukkitWorld == null || !home.isPublic())
            return;

        try {
            WorldIdentifier identifier = WorldIdentifier.parse(bukkitWorld.getKey().toString());
            SimpleLayerProvider provider = providers.get(identifier);
            if (provider == null) {
                // Recours aux mondes enregistrés au cas où le monde a été chargé dynamiquement
                for (xyz.jpenilla.squaremap.api.MapWorld world : SquaremapProvider.get().mapWorlds()) {
                    if (world.identifier().equals(identifier)) {
                        if (!world.layerRegistry().hasEntry(layerKey)) {
                            SimpleLayerProvider newProvider = SimpleLayerProvider.builder("Public Homes (SethomeX)")
                                    .showControls(true)
                                    .defaultHidden(false)
                                    .build();
                            world.layerRegistry().register(layerKey, newProvider);
                            providers.put(identifier, newProvider);
                        }
                        break;
                    }
                }
                provider = providers.get(identifier);
            }

            if (provider == null)
                return;

            String ownerName = fr.skynex.sethomex.SethomeX.getInstance().getHomeManager().getPlayerName(home.getPlayerUuid());
            if (ownerName == null || ownerName.equals("Unknown"))
                ownerName = "Unknown";

            String descStr = home.getDescription() != null && !home.getDescription().isEmpty() ? home.getDescription() : "Aucune description";
            String feeStr = home.getVisitFee() > 0 ? fr.skynex.sethomex.SethomeX.getInstance().getEconomyManager().format(home.getVisitFee()) : "Gratuit";

            String desc = "<b>Home:</b> " + home.getName() + "<br/>"
                    + "<b>Owner:</b> " + ownerName + "<br/>"
                    + "<b>Visits:</b> " + home.getVisits() + "<br/>"
                    + "<b>Likes:</b> 👍 " + home.getLikesCount() + "<br/>"
                    + "<b>Fee:</b> " + feeStr + "<br/>"
                    + "<b>Description:</b> " + descStr;

            Key markerKey = Key
                    .of("home_" + home.getPlayerUuid() + "_" + home.getName().toLowerCase().replace(" ", "_"));

            // We can use an anchor or a default circle marker if icons are tricky, but
            // Squaremap supports Icon and standard shapes.
            // Let's create a simple Icon or Circle marker. Let's use a circle for high
            // reliability, styled nicely.
            Marker marker = Marker.circle(Point.of(home.getX(), home.getZ()), 3.0);
            marker.markerOptions(MarkerOptions.builder()
                    .hoverTooltip(desc)
                    .fillColor(java.awt.Color.YELLOW)
                    .fillOpacity(0.7)
                    .strokeColor(java.awt.Color.ORANGE)
                    .strokeWeight(2)
                    .build());

            provider.addMarker(markerKey, marker);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void removeHome(Home home) {
        org.bukkit.World bukkitWorld = Bukkit.getWorld(home.getWorldName());
        if (bukkitWorld == null)
            return;

        try {
            WorldIdentifier identifier = WorldIdentifier.parse(bukkitWorld.getKey().toString());
            SimpleLayerProvider provider = providers.get(identifier);
            if (provider == null)
                return;

            Key markerKey = Key
                    .of("home_" + home.getPlayerUuid() + "_" + home.getName().toLowerCase().replace(" ", "_"));
            provider.removeMarker(markerKey);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void clearAll() {
        try {
            providers.values().forEach(provider -> {
                if (provider != null) {
                    provider.clearMarkers();
                }
            });
        } catch (Exception ignored) {
        }
    }
}
