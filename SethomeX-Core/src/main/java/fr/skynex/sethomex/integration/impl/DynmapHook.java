package fr.skynex.sethomex.integration.impl;

import fr.skynex.sethomex.integration.MapHook;
import fr.skynex.sethomex.models.Home;
import org.bukkit.Bukkit;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.Marker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;

public class DynmapHook implements MapHook {

    private final DynmapAPI api;
    private MarkerSet set;

    public DynmapHook() {
        this.api = (DynmapAPI) Bukkit.getPluginManager().getPlugin("dynmap");
        if (api != null) {
            setup();
        }
    }

    private void setup() {
        MarkerAPI markerApi = api.getMarkerAPI();
        if (markerApi == null) return;
        
        set = markerApi.getMarkerSet("sethomex.homes");
        if (set == null) {
            set = markerApi.createMarkerSet("sethomex.homes", "Public Homes (SethomeX)", null, false);
        } else {
            set.setMarkerSetLabel("Public Homes (SethomeX)");
        }
    }

    @Override
    public void registerHome(Home home) {
        if (set == null || !home.isPublic()) return;
        
        String markerId = "home_" + home.getPlayerUuid().toString() + "_" + home.getName().toLowerCase().replace(" ", "_");
        String label = home.getName();
        String ownerName = fr.skynex.sethomex.SethomeX.getInstance().getHomeManager().getPlayerName(home.getPlayerUuid());
        if (ownerName == null || ownerName.equals("Unknown")) ownerName = "Unknown";
        
        String descStr = home.getDescription() != null && !home.getDescription().isEmpty() ? home.getDescription() : "Aucune description";
        String feeStr = home.getVisitFee() > 0 ? fr.skynex.sethomex.SethomeX.getInstance().getEconomyManager().format(home.getVisitFee()) : "Gratuit";
        String desc = "<b>Home:</b> " + home.getName() + "<br/>"
                + "<b>Owner:</b> " + ownerName + "<br/>"
                + "<b>Visits:</b> " + home.getVisits() + "<br/>"
                + "<b>Likes:</b> 👍 " + home.getLikesCount() + "<br/>"
                + "<b>Fee:</b> " + feeStr + "<br/>"
                + "<b>Description:</b> " + descStr;

        Marker marker = set.findMarker(markerId);
        if (marker == null) {
            set.createMarker(markerId, label, true, home.getWorldName(), home.getX(), home.getY(), home.getZ(),
                    api.getMarkerAPI().getMarkerIcon("house"), false);
        } else {
            marker.setLocation(home.getWorldName(), home.getX(), home.getY(), home.getZ());
            marker.setLabel(label, true);
        }
        
        // Refresh description/lore
        Marker updatedMarker = set.findMarker(markerId);
        if (updatedMarker != null) {
            updatedMarker.setDescription(desc);
        }
    }

    @Override
    public void removeHome(Home home) {
        if (set == null) return;
        String markerId = "home_" + home.getPlayerUuid().toString() + "_" + home.getName().toLowerCase().replace(" ", "_");
        Marker marker = set.findMarker(markerId);
        if (marker != null) {
            marker.deleteMarker();
        }
    }

    @Override
    public void clearAll() {
        if (set == null) return;
        for (Marker marker : set.getMarkers()) {
            marker.deleteMarker();
        }
    }
}
