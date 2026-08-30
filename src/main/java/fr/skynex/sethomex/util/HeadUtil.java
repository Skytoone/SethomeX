package fr.skynex.sethomex.util;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URI;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HeadUtil {

    private static final Pattern TEXTURE_URL_PATTERN = Pattern.compile("http://textures\\.minecraft\\.net/texture/[a-zA-Z0-9]+");

    /**
     * Construit un ItemStack de PLAYER_HEAD portant la texture fournie.
     * Supporte :
     * - Les chaînes JSON encodées en Base64
     * - Les URLs directes (ex: http://textures.minecraft.net/texture/...)
     * - Les Hashs directs SHA-256 (ex: b1633b97b2...)
     *
     * @param texture La source de la texture
     * @return Un ItemStack configuré
     */
    public static ItemStack getCustomHead(String texture) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta == null || texture == null || texture.isEmpty()) {
            return head;
        }

        try {
            String urlString = extractUrl(texture);
            if (urlString != null) {
                // Utilisation de l'API standard cross-version 1.21 (Paper/Spigot)
                // On génère un UUID statique calculé sur le hash de l'URL pour favoriser le stacking
                UUID uuid = UUID.nameUUIDFromBytes(urlString.getBytes());
                PlayerProfile profile = Bukkit.createProfile(uuid);
                PlayerTextures textures = profile.getTextures();
                textures.setSkin(URI.create(urlString).toURL());
                profile.setTextures(textures);
                meta.setPlayerProfile(profile);
            }
        } catch (Exception e) {
            // Silently ignore and return basic PLAYER_HEAD
        }

        head.setItemMeta(meta);
        return head;
    }

    private static String extractUrl(String texture) {
        // 1. Déjà une URL valide ?
        if (texture.startsWith("http://") || texture.startsWith("https://")) {
            return texture;
        }

        // 2. Hash direct SHA-256 ? (Généralement 64 caractères)
        if (texture.length() < 100 && !texture.contains("{") && !texture.contains("=")) {
            return "http://textures.minecraft.net/texture/" + texture;
        }

        // 3. Base64 JSON encodé
        try {
            byte[] decoded = Base64.getDecoder().decode(texture);
            String json = new String(decoded);
            Matcher matcher = TEXTURE_URL_PATTERN.matcher(json);
            if (matcher.find()) {
                return matcher.group();
            }
        } catch (Exception ignored) {
        }

        return null;
    }
}
