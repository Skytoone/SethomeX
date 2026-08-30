package fr.skynex.sethomex.integration;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import fr.skynex.sethomex.SethomeX;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class BungeeSyncManager implements PluginMessageListener {

    private final SethomeX plugin;
    private final String CHANNEL = "BungeeCord";
    private final String SUB_CHANNEL = "SethomeXSync";

    public BungeeSyncManager(SethomeX plugin) {
        this.plugin = plugin;
        if (plugin.getConfig().getBoolean("network.bungee-sync.enabled", false)) {
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        }
    }

    /**
     * Notifie tous les autres serveurs du réseau BungeeCord/Velocity pour une action spécifique.
     */
    public void sendSyncMessage(String action, UUID targetUuid, String extraData) {
        if (!plugin.getConfig().getBoolean("network.bungee-sync.enabled", false)) {
            return;
        }
        if (!plugin.getConfig().getString("database.type", "SQLITE").equalsIgnoreCase("MYSQL")) {
            return; // Sync is only relevant if servers share a MySQL database
        }

        Player messenger = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (messenger == null) return; // Cannot send plugin messages if no one is online

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Forward");
        out.writeUTF("ALL");
        out.writeUTF(SUB_CHANNEL);

        ByteArrayDataOutput msg = ByteStreams.newDataOutput();
        msg.writeUTF(action);
        msg.writeUTF(targetUuid.toString());
        msg.writeUTF(extraData != null ? extraData : "");

        byte[] payload = msg.toByteArray();
        out.writeShort(payload.length);
        out.write(payload);

        messenger.sendPluginMessage(plugin, CHANNEL, out.toByteArray());
    }

    @Override
    @SuppressWarnings("null")
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, @NotNull byte[] message) {
        if (!channel.equals(CHANNEL)) return;

        ByteArrayDataInput in = ByteStreams.newDataInput(message);
        String subChannel = in.readUTF();

        if (subChannel.equals(SUB_CHANNEL)) {
            short len = in.readShort();
            byte[] msgbytes = new byte[len];
            in.readFully(msgbytes);
            ByteArrayDataInput msgIn = ByteStreams.newDataInput(msgbytes);

            String action = msgIn.readUTF();
            String uuidStr = msgIn.readUTF();
            String extraData = msgIn.readUTF();

            UUID targetUuid;
            try {
                targetUuid = UUID.fromString(uuidStr);
            } catch (Exception e) {
                return;
            }

            plugin.getScheduler().runTask(() -> handleSyncAction(action, targetUuid, extraData));
        }
    }

    private void handleSyncAction(String action, UUID targetUuid, String extraData) {
        switch (action) {
            case "INVALIDATE_CACHE":
                // An admin on another server modified this player's homes, or the player did.
                // If they are online here, we must reload their homes from DB to stay in sync.
                Player p = Bukkit.getPlayer(targetUuid);
                if (p != null) {
                    plugin.getHomeManager().loadPlayerHomes(targetUuid);
                }
                break;
                
            case "TRUST_NOTIFY":
                // extraData = "ownerName:homeName"
                Player guest = Bukkit.getPlayer(targetUuid);
                if (guest != null) {
                    String[] parts = extraData.split(":", 2);
                    if (parts.length == 2) {
                        plugin.getMessageManager().sendMessage(guest, "social.trust-received", "{owner}", parts[0], "{name}", parts[1]);
                        guest.playSound(guest.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.0f);
                    }
                }
                break;
        }
    }
}
