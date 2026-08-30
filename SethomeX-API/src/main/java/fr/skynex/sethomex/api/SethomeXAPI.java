package fr.skynex.sethomex.api;

import fr.skynex.sethomex.models.Home;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Official SethomeX API interface for programmatic control over player homes,
 * home teleportation, limits, and home access permissions.
 */
public interface SethomeXAPI {

    /**
     * Retrieves a specific home of a player by name.
     *
     * @param playerUuid UUID of the home owner
     * @param name Home name
     * @return Optional containing the Home object if found
     */
    @NotNull
    Optional<Home> getHome(@NotNull UUID playerUuid, @NotNull String name);

    /**
     * Gets all homes owned by a player.
     *
     * @param playerUuid UUID of the home owner
     * @return List of Home objects
     */
    @NotNull
    List<Home> getHomes(@NotNull UUID playerUuid);

    /**
     * Creates or updates a home for a player at a specific location.
     *
     * @param playerUuid UUID of the home owner
     * @param name Home name
     * @param location Target location
     * @return True if the home was set successfully
     */
    boolean setHome(@NotNull UUID playerUuid, @NotNull String name, @NotNull Location location);

    /**
     * Deletes a home owned by a player.
     *
     * @param playerUuid UUID of the home owner
     * @param name Home name
     * @return True if the home was deleted
     */
    boolean deleteHome(@NotNull UUID playerUuid, @NotNull String name);

    /**
     * Teleports a player to one of their homes (with configured warmup/cooldowns).
     *
     * @param player Target player
     * @param name Home name
     * @return True if teleport process started
     */
    boolean teleportToHome(@NotNull Player player, @NotNull String name);

    /**
     * Teleports a player directly to a specific Home object.
     *
     * @param player Target player
     * @param home Target Home
     * @return True if teleport process started
     */
    boolean teleportToHome(@NotNull Player player, @NotNull Home home);

    /**
     * Checks if a player has a home with the specified name.
     *
     * @param playerUuid UUID of the home owner
     * @param name Home name
     * @return True if home exists
     */
    boolean hasHome(@NotNull UUID playerUuid, @NotNull String name);

    /**
     * Gets the total number of homes created by a player.
     *
     * @param playerUuid UUID of the home owner
     * @return Total home count
     */
    int getHomeCount(@NotNull UUID playerUuid);

    /**
     * Gets the maximum number of homes allowed for a player based on permissions.
     *
     * @param player Target player
     * @return Maximum home limit
     */
    int getMaxHomes(@NotNull Player player);

    /**
     * Retrieves all public homes currently available on the server.
     *
     * @return List of public Home objects
     */
    @NotNull
    List<Home> getPublicHomes();

    /**
     * Grants trust permission to a player for a specific home.
     *
     * @param home Target home
     * @param guestUuid UUID of the player to trust
     * @return True if trust was granted
     */
    boolean trustPlayer(@NotNull Home home, @NotNull UUID guestUuid);

    /**
     * Removes trust permission from a player for a specific home.
     *
     * @param home Target home
     * @param guestUuid UUID of the player to untrust
     * @return True if trust was removed
     */
    boolean untrustPlayer(@NotNull Home home, @NotNull UUID guestUuid);
}
