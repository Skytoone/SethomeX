package fr.skynex.sethomex.integration.impl;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.markers.HtmlMarker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import fr.skynex.sethomex.integration.MapHook;
import fr.skynex.sethomex.models.Home;

public class BlueMapHook implements MapHook {

    public BlueMapHook() {
        BlueMapAPI.onEnable(api -> {
            // Optional setup if we want to preload when API wakes up
        });
    }

    @Override
    public void registerHome(Home home) {
        if (!home.isPublic())
            return;

        BlueMapAPI.getInstance().ifPresent(api -> {
            api.getWorld(home.getWorldName()).ifPresent(world -> {
                world.getMaps().forEach(map -> {
                    MarkerSet set = map.getMarkerSets().computeIfAbsent("sethomex_homes",
                            id -> MarkerSet.builder().label("Public Homes (SethomeX)").build());

                    String markerId = "home_" + home.getPlayerUuid().toString() + "_"
                            + home.getName().toLowerCase().replace(" ", "_");
                    String ownerName = fr.skynex.sethomex.SethomeX.getInstance().getHomeManager()
                            .getPlayerName(home.getPlayerUuid());
                    if (ownerName == null || ownerName.equals("Unknown"))
                        ownerName = "Unknown";

                    String descStr = home.getDescription() != null && !home.getDescription().isEmpty()
                            ? home.getDescription()
                            : "Aucune description";
                    String feeStr = home.getVisitFee() > 0
                            ? fr.skynex.sethomex.SethomeX.getInstance().getEconomyManager().format(home.getVisitFee())
                            : "Gratuit";
                    String avatarUrl = "https://mc-heads.net/avatar/" + home.getPlayerUuid().toString() + "/32";

                    String desc = "<div style='padding:8px; font-family: Arial, sans-serif; background:rgba(30,30,30,0.95); border:2px solid #ffcc00; border-radius:8px; color:white; display:flex; align-items:center; gap:10px;'>"
                            + "<img src='" + avatarUrl
                            + "' style='width:32px; height:32px; border-radius:4px;' alt='Avatar'/>"
                            + "<div>"
                            + "<h3 style='margin:0 0 5px 0; color:#ffcc00; font-size:15px;'>🏡 " + home.getName()
                            + "</h3>"
                            + "<span style='font-size:12px; display:block;'><b>👤 Propriétaire :</b> " + ownerName
                            + "</span>"
                            + "<span style='font-size:12px; display:block;'><b>📊 Visites :</b> " + home.getVisits()
                            + "</span>"
                            + "<span style='font-size:12px; display:block;'><b>👍 Likes :</b> " + home.getLikesCount()
                            + "</span>"
                            + "<span style='font-size:12px; display:block;'><b>💰 Taxe :</b> " + feeStr + "</span>"
                            + "<span style='font-size:12px; display:block;'><b>📝 Description :</b> " + descStr
                            + "</span>"
                            + "</div>"
                            + "</div>";

                    // Set marker height slightly higher to avoid floating blocks intersections
                    HtmlMarker marker = HtmlMarker.builder()
                            .label(home.getName())
                            .position(home.getX(), home.getY() + 1.0, home.getZ())
                            .html(desc)
                            .build();

                    set.put(markerId, marker);
                });
            });
        });
    }

    @Override
    public void removeHome(Home home) {
        BlueMapAPI.getInstance().ifPresent(api -> {
            api.getWorld(home.getWorldName()).ifPresent(world -> {
                world.getMaps().forEach(map -> {
                    MarkerSet set = map.getMarkerSets().get("sethomex_homes");
                    if (set != null) {
                        String markerId = "home_" + home.getPlayerUuid().toString() + "_"
                                + home.getName().toLowerCase().replace(" ", "_");
                        set.remove(markerId);
                    }
                });
            });
        });
    }

    @Override
    public void clearAll() {
        BlueMapAPI.getInstance().ifPresent(api -> {
            api.getWorlds().forEach(world -> {
                world.getMaps().forEach(map -> {
                    map.getMarkerSets().remove("sethomex_homes");
                });
            });
        });
    }
}
