package fr.skynex.sethomex.gui;

import fr.skynex.sethomex.SethomeX;
import fr.skynex.sethomex.managers.GUIManager;
import fr.skynex.sethomex.managers.HomeManager;
import fr.skynex.sethomex.models.Home;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HomeGUI implements Listener {

    private final SethomeX plugin;
    private final GUIManager gm;

    // CLES DE DONNEES PERSISTANTES (Evite de parser le lore ou le nom !)
    private final NamespacedKey actionKey;
    private final NamespacedKey homeOwnerKey;
    private final NamespacedKey homeNameKey;
    private final NamespacedKey textureKey;
    private final NamespacedKey folderKey;

    // Cache de Session Utilisateur
    private static final Map<UUID, InventoryType> openInventories = new ConcurrentHashMap<>();
    private static final Map<UUID, Home> selectedHomeForIcon = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> currentGUIPage = new ConcurrentHashMap<>();
    private static final Map<UUID, SortMode> currentSortModes = new ConcurrentHashMap<>();
    private static final Map<UUID, Home> pendingDeleteHome = new ConcurrentHashMap<>();
    private static final Map<UUID, Home> selectedHomeForTrust = new ConcurrentHashMap<>();
    private static final Map<UUID, String> currentGUICategory = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> selectedGuestForTrust = new ConcurrentHashMap<>();
    
    // New parameters caches
    private static final Map<UUID, ChatInputSession> activeChatInputs = new ConcurrentHashMap<>();
    private static final Map<UUID, String> currentSearchQueries = new ConcurrentHashMap<>();
    private static final Map<UUID, org.bukkit.OfflinePlayer> selectedAdminTarget = new ConcurrentHashMap<>();

    private enum InventoryType {
        MAIN_HOMES,
        ICON_SELECTOR,
        PUBLIC_CATALOG,
        CONFIRM_DELETE,
        SHARED_HOMES,
        MANAGE_TRUSTS,
        EFFECTS_SELECTOR,
        ADD_TRUST,
        GUEST_TRUST_MANAGER,
        HOME_SETTINGS,
        CATEGORY_SELECTOR,
        ADMIN_PLAYERS,
        ADMIN_HOMES,
        JUKEBOX_SELECTOR,
        TIME_WEATHER_SELECTOR,
        BAN_LIST,
        VISIT_HISTORY
    }

    public enum ChatInputType {
        SEARCH_PUBLIC,
        SET_DESCRIPTION,
        SET_WELCOME,
        CREATE_CATEGORY,
        SET_FEE,
        SPONSOR_DAYS,
        ADD_BAN_PLAYER
    }

    public static class ChatInputSession {
        public final ChatInputType type;
        public final String homeName;

        public ChatInputSession(ChatInputType type, String homeName) {
            this.type = type;
            this.homeName = homeName;
        }
    }

    public enum SortMode {
        POPULARITY("menus.public-catalog.items.sort-modes.popularity"),
        ALPHABETICAL("menus.public-catalog.items.sort-modes.alphabetical"),
        RANDOM("menus.public-catalog.items.sort-modes.random"),
        LIKES("menus.public-catalog.items.sort-modes.likes");

        private final String configPath;

        SortMode(String configPath) {
            this.configPath = configPath;
        }

        public String getConfigPath() {
            return configPath;
        }
    }

    public HomeGUI(SethomeX plugin) {
        this.plugin = plugin;
        this.gm = plugin.getGUIManager();
        this.actionKey = new NamespacedKey(plugin, "gui_action");
        this.homeOwnerKey = new NamespacedKey(plugin, "home_owner");
        this.homeNameKey = new NamespacedKey(plugin, "home_name");
        this.textureKey = new NamespacedKey(plugin, "icon_texture");
        this.folderKey = new NamespacedKey(plugin, "folder_name");

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // --- UTILITAIRES DE CONSTRUCTION D'ITEMS ---

    private ItemStack createGuiItem(Material mat, Component name, List<Component> lore, String action) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (lore != null && !lore.isEmpty())
                meta.lore(lore);
            if (action != null) {
                meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createHomeItem(Home home, Component name, List<Component> lore) {
        ItemStack item;
        if (home.getIconTexture() != null && !home.getIconTexture().isEmpty()) {
            item = fr.skynex.sethomex.util.HeadUtil.getCustomHead(home.getIconTexture());
        } else {
            item = new ItemStack(home.getIconMaterial());
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (lore != null)
                meta.lore(lore);

            // Support Tête de joueur personnalisée (Skulls) uniquement si pas de texture 3D premium active !
            if ((home.getIconTexture() == null || home.getIconTexture().isEmpty()) && 
                home.getIconMaterial() == Material.PLAYER_HEAD && meta instanceof org.bukkit.inventory.meta.SkullMeta skullMeta) {
                skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(home.getPlayerUuid()));
            }

            // Injection des données invisibles cruciales !
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "interact_home");
            meta.getPersistentDataContainer().set(homeOwnerKey, PersistentDataType.STRING,
                    home.getPlayerUuid().toString());
            meta.getPersistentDataContainer().set(homeNameKey, PersistentDataType.STRING, home.getName());

            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack[] backgroundCache = null;

    private void fillBackground(Inventory inv, int size) {
        if (!plugin.getConfig().getBoolean("gui.fill-empty-slots", true)) return;
        
        if (backgroundCache == null || backgroundCache.length != size) {
            backgroundCache = new ItemStack[size];
            Material mat = Material.valueOf(plugin.getConfig().getString("gui.fill-item", "GRAY_STAINED_GLASS_PANE"));
            ItemStack pane = createGuiItem(mat, Component.empty(), null, "background");
            Arrays.fill(backgroundCache, pane);
        }
        
        for (int i = 0; i < size; i++) {
            if (inv.getItem(i) == null || inv.getItem(i).getType() == Material.AIR) {
                inv.setItem(i, backgroundCache[i]);
            }
        }
    }

    // -------------------------------------------------------------------------
    // 1. MENU PRINCIPAL DES HOMES
    // -------------------------------------------------------------------------
    public void openMainGUI(Player player) {
        openMainGUI(player, 1, null);
    }

    public void openMainGUI(Player player, int page) {
        openMainGUI(player, page, null);
    }

    private record FolderItem(String name, int count) {}

    public void openMainGUI(Player player, int page, String categoryFilter) {
        List<Home> allHomes = new ArrayList<>(plugin.getHomeManager().getPlayerHomes(player));
        allHomes.sort(Comparator.comparing(h -> h.getName().toLowerCase()));

        List<Object> guiItems = new ArrayList<>();

        if (categoryFilter == null) {
            currentGUICategory.remove(player.getUniqueId());
            // Group by folders and add uncategorized
            Map<String, Integer> folderCounts = new java.util.HashMap<>();
            for (Home h : allHomes) {
                String cat = h.getCategory();
                if (!cat.equals("none")) {
                    folderCounts.put(cat, folderCounts.getOrDefault(cat, 0) + 1);
                }
            }

            List<String> sortedFolders = new ArrayList<>(folderCounts.keySet());
            sortedFolders.sort(String.CASE_INSENSITIVE_ORDER);
            
            for (String folder : sortedFolders) {
                guiItems.add(new FolderItem(folder, folderCounts.get(folder)));
            }

            for (Home h : allHomes) {
                if (h.getCategory().equals("none")) {
                    guiItems.add(h);
                }
            }
        } else {
            currentGUICategory.put(player.getUniqueId(), categoryFilter);
            for (Home h : allHomes) {
                if (h.getCategory().equals(categoryFilter)) {
                    guiItems.add(h);
                }
            }
        }

        int limit = plugin.getHomeManager().getPlayerLimit(player);
        int itemsPerPage = 21;
        int totalPages = Math.max(1, (int) Math.ceil((double) guiItems.size() / itemsPerPage));

        if (page < 1)
            page = 1;
        if (page > totalPages)
            page = totalPages;

        // Récupération dynamique du Titre
        Component title = gm.getComponent("menus.main.title");
        if (categoryFilter != null) {
            title = gm.getComponent("menus.main.title-folder", "{folder}", categoryFilter);
        }
        if (totalPages > 1) {
            title = title.append(gm.getComponent("common.visuals.background-pane"))
                    .append(gm.getComponent("menus.icons.page-suffix", "{page}", String.valueOf(page), "{max}", String.valueOf(totalPages)));
        }

        Inventory inv = Bukkit.createInventory(null, 45, title);
        fillBackground(inv, 45);

        int startIndex = (page - 1) * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, guiItems.size());
        int[] slots = { 10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34 };

        int sIdx = 0;
        String statusPub = gm.getRawString("menus.main.status-public", "&aPUBLIC");
        String statusPriv = gm.getRawString("menus.main.status-private", "&cPRIVATE");
        String respawnYes = gm.getRawString("menus.main.respawn-yes", "&a✔ YES");
        String respawnNo = gm.getRawString("menus.main.respawn-no", "&7✘ NO");

        // Favorites shortcuts on Row 1 (slots 1 to 7)
        List<Home> favorites = plugin.getHomeManager().getFavorites(player.getUniqueId());
        int favSlot = 1;
        int maxFavs = plugin.getConfig().getInt("favorites.max-favorites", 7);
        for (Home favHome : favorites) {
            if (favSlot > 7 || (favSlot - 1) >= maxFavs) break;
            Component fName = Component.text("★ ").color(net.kyori.adventure.text.format.NamedTextColor.GOLD)
                    .append(gm.getComponent("menus.main.items.home.name", "{name}", favHome.getName()));
            String statLabel = favHome.isPublic() ? statusPub : statusPriv;
            String respLabel = favHome.isRespawn() ? respawnYes : respawnNo;
            List<Component> lore = gm.getLore("menus.main.items.home.lore",
                    "{world}", favHome.getWorldName(),
                    "{x}", String.valueOf((int) favHome.getX()),
                    "{y}", String.valueOf((int) favHome.getY()),
                    "{z}", String.valueOf((int) favHome.getZ()),
                    "{status}", statLabel,
                    "{respawn}", respLabel);
            inv.setItem(favSlot++, createHomeItem(favHome, fName, lore));
        }

        for (int i = startIndex; i < endIndex; i++) {
            Object itemObj = guiItems.get(i);

            if (itemObj instanceof FolderItem f) {
                Component fName = gm.getComponent("menus.main.items.folder.name", "{category}", f.name);
                List<Component> fLore = gm.getLore("menus.main.items.folder.lore", "{count}", String.valueOf(f.count));
                ItemStack folderStack = createGuiItem(Material.CHEST, fName, fLore, "open_folder");
                org.bukkit.inventory.meta.ItemMeta meta = folderStack.getItemMeta();
                if (meta != null) {
                    meta.getPersistentDataContainer().set(folderKey, org.bukkit.persistence.PersistentDataType.STRING, f.name);
                    folderStack.setItemMeta(meta);
                }
                inv.setItem(slots[sIdx++], folderStack);
            } else if (itemObj instanceof Home home) {
                // Préparation des variables
                String statLabel = home.isPublic() ? statusPub : statusPriv;
                String respLabel = home.isRespawn() ? respawnYes : respawnNo;

                // Construction du Lore dynamique via le manager
                List<Component> lore = gm.getLore("menus.main.items.home.lore",
                        "{world}", home.getWorldName(),
                        "{x}", String.valueOf((int) home.getX()),
                        "{y}", String.valueOf((int) home.getY()),
                        "{z}", String.valueOf((int) home.getZ()),
                        "{status}", statLabel,
                        "{respawn}", respLabel);

                Component hName = gm.getComponent("menus.main.items.home.name", "{name}", home.getName());
                inv.setItem(slots[sIdx++], createHomeItem(home, hName, lore));
            }
        }

        // Livre Info
        inv.setItem(40, createGuiItem(Material.BOOK,
                gm.getComponent("menus.main.items.info-book.name"),
                gm.getLore("menus.main.items.info-book.lore", "{count}", String.valueOf(allHomes.size()), "{limit}",
                        (limit >= 9999 ? "∞" : String.valueOf(limit))),
                "info"));

        // Flèches
        if (page > 1) {
            inv.setItem(39, createGuiItem(Material.ARROW,
                    gm.getComponent("common.buttons.previous-page.name"), 
                    gm.getLore("common.buttons.previous-page.lore"), "prev_page"));
        }
        if (page < totalPages) {
            inv.setItem(41, createGuiItem(Material.ARROW,
                    gm.getComponent("common.buttons.next-page.name"), 
                    gm.getLore("common.buttons.next-page.lore"), "next_page"));
        }

        // Boutons spécialisés
        if (categoryFilter != null) {
            inv.setItem(44, createGuiItem(Material.ARROW, gm.getComponent("common.buttons.back-to-main.name"), null, "back_to_main"));
        } else {
            inv.setItem(44, createGuiItem(Material.FILLED_MAP,
                    gm.getComponent("menus.main.items.public-catalog.name"),
                    gm.getLore("menus.main.items.public-catalog.lore"), "open_catalog"));
        }

        inv.setItem(43, createGuiItem(Material.CAKE,
                gm.getComponent("menus.main.items.shared-homes.name"),
                gm.getLore("menus.main.items.shared-homes.lore"), "open_shared"));

        inv.setItem(42, createGuiItem(Material.AMETHYST_SHARD,
                gm.getComponent("menus.main.items.effects.name"),
                gm.getLore("menus.main.items.effects.lore"), "open_effects"));

        player.openInventory(inv);
        openInventories.put(player.getUniqueId(), InventoryType.MAIN_HOMES);
        currentGUIPage.put(player.getUniqueId(), page);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.0f);
    }

    // -------------------------------------------------------------------------
    // 2. SÉLECTEUR D'ICÔNES
    // -------------------------------------------------------------------------
    private ItemStack[] standardIconsCache = null;

    public void openIconSelectorGUI(Player player, Home home) {
        Component title = gm.getComponent("menus.icon-selector.title");
        Inventory inv = Bukkit.createInventory(null, 45, title);
        fillBackground(inv, 45);

        int[] standardSlots = { 10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25 };

        if (standardIconsCache == null) {
            standardIconsCache = new ItemStack[standardSlots.length];
            List<String> icons = gm.getConfig().getStringList("menus.icon-selector.icons");
            int standardIdx = 0;
            
            for (String name : icons) {
                if (standardIdx >= standardSlots.length) break;
                try {
                    Material mat = Material.valueOf(name);
                    Component dName = gm.getComponent("menus.icon-selector.items.icon-name", "{material}", formatMaterialName(mat));
                    // The lore will be generated per-player, but we can cache the base item
                    ItemStack iconItem = createGuiItem(mat, dName, null, "select_icon");
                    standardIconsCache[standardIdx++] = iconItem;
                } catch (Exception ignored) {}
            }
        }

        // Apply cached items and add dynamic lore
        for (int i = 0; i < standardIconsCache.length; i++) {
            ItemStack cachedItem = standardIconsCache[i];
            if (cachedItem == null) continue;
            
            ItemStack iconItem = cachedItem.clone();
            ItemMeta meta = iconItem.getItemMeta();
            if (meta != null) {
                List<Component> lore = gm.getLore("menus.icon-selector.items.icon-lore", "{name}", home.getName());
                meta.lore(lore);
                if (iconItem.getType() == Material.PLAYER_HEAD && meta instanceof org.bukkit.inventory.meta.SkullMeta sm) {
                    sm.setOwningPlayer(player);
                }
                iconItem.setItemMeta(meta);
            }
            inv.setItem(standardSlots[i], iconItem);
        }

        // 2. Miniatures 3D Premium / Custom Heads (ligne 4)
        org.bukkit.configuration.ConfigurationSection headsSec = gm.getConfig().getConfigurationSection("menus.icon-selector.custom-heads");
        if (headsSec != null) {
            int[] customSlots = { 28, 29, 30, 31, 32, 33, 34 };
            int customIdx = 0;
            for (String key : headsSec.getKeys(false)) {
                if (customIdx >= customSlots.length)
                    break;
                
                String base64 = headsSec.getString(key);
                if (base64 == null) continue;

                ItemStack headItem = fr.skynex.sethomex.util.HeadUtil.getCustomHead(base64);
                ItemMeta meta = headItem.getItemMeta();
                if (meta != null) {
                    // Affichage premium coloré pour la miniature 3D !
                    meta.displayName(gm.getComponent("menus.icons.items.category.name", "{category}", key));
                    meta.lore(gm.getLore("menus.icons.items.category.lore"));
                    
                    // Marquage action et injection texture persistante !
                    meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "select_icon");
                    meta.getPersistentDataContainer().set(textureKey, PersistentDataType.STRING, base64);
                    headItem.setItemMeta(meta);
                }
                inv.setItem(customSlots[customIdx++], headItem);
            }
        }

        // 3. Bouton Retour (ligne 5)
        inv.setItem(40, createGuiItem(Material.RED_BED, 
                gm.getComponent("common.buttons.back.name"), 
                gm.getLore("common.buttons.back.lore"), "back_to_main"));

        player.openInventory(inv);
        selectedHomeForIcon.put(player.getUniqueId(), home);
        openInventories.put(player.getUniqueId(), InventoryType.ICON_SELECTOR);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, 0.5f, 1.2f);
    }

    // -------------------------------------------------------------------------
    // 3. CATALOGUE PUBLIC
    // -------------------------------------------------------------------------
    public void openPublicCatalogGUI(Player player, int page) {
        openPublicCatalogGUI(player, page, currentSearchQueries.get(player.getUniqueId()));
    }

    public void openPublicCatalogGUI(Player player, int page, String searchQuery) {
        SortMode sort = currentSortModes.getOrDefault(player.getUniqueId(), SortMode.POPULARITY);
        int itemsPerPage = 21;

        plugin.getHomeManager().getPublicHomesCountAsync(searchQuery).thenAccept(totalItems -> {
            int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / itemsPerPage));
            int finalPage = Math.max(1, Math.min(page, totalPages));

            plugin.getHomeManager().getPublicHomesPageAsync(finalPage, itemsPerPage, sort.name(), searchQuery).thenAccept(publicHomes -> {
                plugin.getScheduler().runTaskAtEntity(player, () -> {
                    Component title = gm.getComponent("menus.public-catalog.title");
                    if (searchQuery != null && !searchQuery.isEmpty()) {
                        title = title.append(Component.text(" §7(§6" + searchQuery + "§7)"));
                    }
                    if (totalPages > 1) {
                        title = title.append(gm.getComponent("common.visuals.background-pane"))
                                .append(gm.getComponent("menus.icons.page-suffix", "{page}", String.valueOf(finalPage), "{max}", String.valueOf(totalPages)));
                    }

                    Inventory inv = Bukkit.createInventory(null, 45, title);
                    fillBackground(inv, 45);

                    int[] slots = { 10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34 };

                    for (int i = 0; i < publicHomes.size() && i < slots.length; i++) {
                        Home home = publicHomes.get(i);
                        String owner = plugin.getHomeManager().getPlayerName(home.getPlayerUuid());

                        String descText = home.getDescription() != null && !home.getDescription().isEmpty() ? home.getDescription() : "Aucune description";
                        String feeText = home.getVisitFee() > 0 ? plugin.getEconomyManager().format(home.getVisitFee()) : "Gratuit";
                        Component hName = gm.getComponent("menus.public-catalog.items.home.name", "{name}", home.getName());
                        List<Component> lore = gm.getLore("menus.public-catalog.items.home.lore", 
                                "{owner}", owner, 
                                "{visits}", String.valueOf(home.getVisits()),
                                "{likes}", String.valueOf(home.getLikesCount()),
                                "{fee}", feeText,
                                "{description}", descText);

                        inv.setItem(slots[i], createHomeItem(home, hName, lore));
                    }

                    // Info centrale
                    inv.setItem(40, createGuiItem(Material.MAP,
                            gm.getComponent("menus.public-catalog.items.info.name"),
                            gm.getLore("menus.public-catalog.items.info.lore", "{count}", String.valueOf(totalItems),
                                    "{ranking}", gm.getRawString(sort.getConfigPath(), "???")),
                            "info"));

                    // Navigation
                    if (finalPage > 1)
                        inv.setItem(39,
                                createGuiItem(Material.ARROW, 
                                        gm.getComponent("common.buttons.previous-page.name"), 
                                        gm.getLore("common.buttons.previous-page.lore"), "prev_page"));
                    if (finalPage < totalPages)
                        inv.setItem(41,
                                createGuiItem(Material.ARROW, 
                                        gm.getComponent("common.buttons.next-page.name"), 
                                        gm.getLore("common.buttons.next-page.lore"), "next_page"));

                    // Retour et Tri
                    inv.setItem(36,
                            createGuiItem(Material.RED_BED, 
                                    gm.getComponent("common.buttons.return-homes.name"), 
                                    gm.getLore("common.buttons.return-homes.lore"), "back_to_main"));

                    inv.setItem(44, createGuiItem(Material.HOPPER,
                            gm.getComponent("menus.public-catalog.items.sorting.name"),
                            gm.getLore("menus.public-catalog.items.sorting.lore", "{mode}",
                                    gm.getRawString(sort.getConfigPath(), "???")),
                            "toggle_sort"));

                    // Recherche (slot 43)
                    String searchName = searchQuery != null ? "§eRecherche active : §f" + searchQuery : "§aRechercher un home";
                    List<Component> searchLore = new ArrayList<>();
                    searchLore.add(Component.text("§7Recherchez par nom de home"));
                    searchLore.add(Component.text("§7ou nom de joueur dans le chat."));
                    if (searchQuery != null) {
                        searchLore.add(Component.text(""));
                        searchLore.add(Component.text("§c➔ Shift-Clic : Réinitialiser"));
                    }
                    inv.setItem(43, createGuiItem(Material.COMPASS, Component.text(searchName), searchLore, "search_public"));

                    player.openInventory(inv);
                    openInventories.put(player.getUniqueId(), InventoryType.PUBLIC_CATALOG);
                    currentGUIPage.put(player.getUniqueId(), finalPage);
                    player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.5f, 1.0f);
                });
            });
        });
    }

    // -------------------------------------------------------------------------
    // 4. HOMES PARTAGÉS (INVITATIONS)
    // -------------------------------------------------------------------------
    public void openSharedHomesGUI(Player player) {
        openSharedHomesGUI(player, 1);
    }

    public void openSharedHomesGUI(Player player, int page) {
        plugin.getHomeManager().getSharedHomesAsync(player.getUniqueId()).thenAccept(shared -> {
            plugin.getScheduler().runTaskAtEntity(player, () -> {
                shared.sort(Comparator.comparing(h -> h.getName().toLowerCase()));

                int itemsPerPage = 21;
                int totalPages = Math.max(1, (int) Math.ceil(shared.size() / (double) itemsPerPage));
                int finalPage = page;
                if (finalPage < 1) finalPage = 1;
                if (finalPage > totalPages) finalPage = totalPages;

                Component title = gm.getComponent("menus.shared.title");
                if (totalPages > 1) {
                    title = title.append(gm.getComponent("common.visuals.background-pane"))
                            .append(gm.getComponent("menus.icons.page-suffix", "{page}", String.valueOf(finalPage), "{max}", String.valueOf(totalPages)));
                }
                Inventory inv = Bukkit.createInventory(null, 45, title);
                fillBackground(inv, 45);

                int startIndex = (finalPage - 1) * itemsPerPage;
                int endIndex = Math.min(startIndex + itemsPerPage, shared.size());
                int[] slots = { 10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34 };

                int sIdx = 0;
                for (int i = startIndex; i < endIndex; i++) {
                    Home home = shared.get(i);
                    String owner = plugin.getHomeManager().getPlayerName(home.getPlayerUuid());

                    long exp = home.getExpiration(player.getUniqueId());
                    String timeStr = formatExpiration(exp);
                    List<Component> configLore = gm.getLore("menus.shared.items.home.lore", "{owner}", owner);
                    List<Component> finalLore = new ArrayList<>();
                    boolean replaced = false;
                    if (configLore != null) {
                        for (Component comp : configLore) {
                            Component newComp = comp.replaceText(net.kyori.adventure.text.TextReplacementConfig.builder()
                                    .matchLiteral("{duration}")
                                    .replacement(timeStr)
                                    .build());
                            if (!newComp.equals(comp)) {
                                replaced = true;
                            }
                            finalLore.add(newComp);
                        }
                    }
                    if (!replaced && exp != -1L) {
                        Component remainingLoreTemplate = gm.getComponent("common.time.remaining-lore", "{time}", timeStr);
                        finalLore.add(Component.empty());
                        finalLore.add(remainingLoreTemplate);
                    }

                    inv.setItem(slots[sIdx++], createHomeItem(home,
                            gm.getComponent("menus.shared.items.home.name", "{name}", home.getName()),
                            finalLore));
                }

                inv.setItem(40, createGuiItem(Material.RED_BED, 
                        gm.getComponent("common.buttons.back.name"), 
                        gm.getLore("common.buttons.back.lore"), "back_to_main"));

                if (finalPage > 1)
                    inv.setItem(39, createGuiItem(Material.ARROW,
                            gm.getComponent("common.buttons.previous-page-numbered.name", "{page}", String.valueOf(finalPage - 1)), 
                            gm.getLore("common.buttons.previous-page-numbered.lore", "{page}", String.valueOf(finalPage - 1)),
                            "prev_page"));
                if (finalPage < totalPages)
                    inv.setItem(41,
                            createGuiItem(Material.ARROW,
                                    gm.getComponent("common.buttons.next-page-numbered.name", "{page}", String.valueOf(finalPage + 1)),
                                    gm.getLore("common.buttons.next-page-numbered.lore", "{page}", String.valueOf(finalPage + 1)),
                                    "next_page"));

                player.openInventory(inv);
                openInventories.put(player.getUniqueId(), InventoryType.SHARED_HOMES);
                currentGUIPage.put(player.getUniqueId(), finalPage);
            });
        });
    }

    // -------------------------------------------------------------------------
    // 5. MENU DE CONFIRMATION SUPPRESSION
    // -------------------------------------------------------------------------
    public void openConfirmDeleteGUI(Player player, Home home) {
        Inventory inv = Bukkit.createInventory(null, 27, gm.getComponent("menus.confirm-delete.title"));
        fillBackground(inv, 27);

        // Item Central informatif
        inv.setItem(13, createGuiItem(home.getIconMaterial(),
                gm.getComponent("menus.confirm-delete.items.display.name", "{name}", home.getName()), null, "info"));

        // Boutons validation
        inv.setItem(11, createGuiItem(Material.LIME_WOOL, gm.getComponent("menus.confirm-delete.items.confirm.name"),
                gm.getLore("menus.confirm-delete.items.confirm.lore"), "confirm_delete"));

        inv.setItem(15, createGuiItem(Material.RED_WOOL, gm.getComponent("menus.confirm-delete.items.cancel.name"),
                gm.getLore("menus.confirm-delete.items.cancel.lore"), "cancel_delete"));

        player.openInventory(inv);
        pendingDeleteHome.put(player.getUniqueId(), home);
        openInventories.put(player.getUniqueId(), InventoryType.CONFIRM_DELETE);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f);
    }

    // =========================================================================
    // ECOUTEUR DE CLICS (LE CŒUR LOGIQUE SÉCURISÉ)
    // =========================================================================
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player))
            return;
        UUID uuid = player.getUniqueId();
        if (!openInventories.containsKey(uuid))
            return;

        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR)
            return;

        ItemMeta meta = clicked.getItemMeta();
        if (meta == null)
            return;

        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            String act = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
            if ("interact_home".equals(act)) {
                String ownerUuidStr = meta.getPersistentDataContainer().get(homeOwnerKey, PersistentDataType.STRING);
                String hName = meta.getPersistentDataContainer().get(homeNameKey, PersistentDataType.STRING);
                if (hName != null) {
                    UUID ownerUuid = (ownerUuidStr != null) ? UUID.fromString(ownerUuidStr) : player.getUniqueId();
                    plugin.getHomeManager().toggleFavorite(player, ownerUuid, hName);
                    
                    InventoryType type = openInventories.get(uuid);
                    int page = currentGUIPage.getOrDefault(uuid, 1);
                    if (type == InventoryType.MAIN_HOMES) {
                        openMainGUI(player, page, currentGUICategory.get(uuid));
                    } else if (type == InventoryType.PUBLIC_CATALOG) {
                        openPublicCatalogGUI(player, page);
                    } else if (type == InventoryType.SHARED_HOMES) {
                        openSharedHomesGUI(player, page);
                    }
                    return;
                }
            }
        }

        // Extraction ultra-sécurisée de l'action depuis la structure interne de
        // Minecraft !
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null || action.equals("background") || action.equals("info"))
            return;

        InventoryType invType = openInventories.get(uuid);
        int curPage = currentGUIPage.getOrDefault(uuid, 1);

        // --- LOGIQUE GÉNÉRIQUE ---
        if (action.equals("next_page")) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
            switch (invType) {
                case MAIN_HOMES:
                    openMainGUI(player, curPage + 1, currentGUICategory.get(uuid));
                    break;
                case PUBLIC_CATALOG:
                    openPublicCatalogGUI(player, curPage + 1);
                    break;
                case SHARED_HOMES:
                    openSharedHomesGUI(player, curPage + 1);
                    break;
                case ADD_TRUST:
                    Home hTrustNext = selectedHomeForTrust.get(uuid);
                    if (hTrustNext != null) {
                        openAddTrustSelectorGUI(player, hTrustNext, curPage + 1);
                    }
                    break;
                default:
                    break;
            }
            return;
        }
        if (action.equals("prev_page")) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 0.8f);
            switch (invType) {
                case MAIN_HOMES:
                    openMainGUI(player, curPage - 1, currentGUICategory.get(uuid));
                    break;
                case PUBLIC_CATALOG:
                    openPublicCatalogGUI(player, curPage - 1);
                    break;
                case SHARED_HOMES:
                    openSharedHomesGUI(player, curPage - 1);
                    break;
                case ADD_TRUST:
                    Home hTrustPrev = selectedHomeForTrust.get(uuid);
                    if (hTrustPrev != null) {
                        openAddTrustSelectorGUI(player, hTrustPrev, curPage - 1);
                    }
                    break;
                default:
                    break;
            }
            return;
        }
        if (action.equals("back_to_main")) {
            openMainGUI(player, 1);
            return;
        }

        // --- LOGIQUE PAR INVENTAIRE ---
        switch (invType) {
            case MAIN_HOMES:
                if (action.equals("open_catalog"))
                    openPublicCatalogGUI(player, 1);
                else if (action.equals("open_shared"))
                    openSharedHomesGUI(player);
                else if (action.equals("open_effects"))
                    openEffectsGUI(player);
                else if (action.equals("open_folder")) {
                    String folder = meta.getPersistentDataContainer().get(folderKey, PersistentDataType.STRING);
                    if (folder != null) {
                        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 1.0f);
                        openMainGUI(player, 1, folder);
                    }
                }
                else if (action.equals("interact_home"))
                    handleMainHomeInteract(player, event, meta, curPage);
                break;

            case PUBLIC_CATALOG:
                if (action.equals("toggle_sort"))
                    handleToggleSort(player);
                else if (action.equals("interact_home"))
                    handleExternalHomeTeleport(player, meta, event.getClick());
                else if (action.equals("search_public")) {
                    if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
                        currentSearchQueries.remove(uuid);
                        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                        openPublicCatalogGUI(player, 1, null);
                    } else {
                        player.closeInventory();
                        activeChatInputs.put(uuid, new ChatInputSession(ChatInputType.SEARCH_PUBLIC, null));
                        player.sendMessage("§7§m--------------------------------------");
                        player.sendMessage("§e§lSethomeX §8» §fRecherche de Homes Publics");
                        player.sendMessage("§7Veuillez entrer le nom du home ou le nom du joueur dans le chat.");
                        player.sendMessage("§7Tapez §ccancel §7pour annuler.");
                        player.sendMessage("§7§m--------------------------------------");
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                    }
                }
                break;

            case SHARED_HOMES:
                if (action.equals("interact_home"))
                    handleExternalHomeTeleport(player, meta, event.getClick());
                break;

            case CONFIRM_DELETE:
                Home ph = pendingDeleteHome.get(uuid);
                if (ph == null) {
                    player.closeInventory();
                    return;
                }

                if (action.equals("confirm_delete")) {
                    player.closeInventory();
                    if (plugin.getHomeManager().deleteHome(player, ph.getName())) {
                        plugin.getMessageManager().sendMessage(player, "gui.home-deleted", "{name}", ph.getName());
                        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                    }
                } else if (action.equals("cancel_delete")) {
                    openMainGUI(player, 1);
                }
                break;

            case ICON_SELECTOR:
                if (action.equals("select_icon")) {
                    Home h = selectedHomeForIcon.get(uuid);
                    if (h != null) {
                        String textureStr = meta.getPersistentDataContainer().get(textureKey, PersistentDataType.STRING);
                        plugin.getHomeManager().updateHomeIcon(h, clicked.getType(), textureStr);
                        plugin.getMessageManager().sendMessage(player, "gui.icon-updated", "{name}", h.getName());
                        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                    }
                    openMainGUI(player, 1);
                }
                break;

            case MANAGE_TRUSTS:
                if (action.equals("open_add_trust")) {
                    Home h = selectedHomeForTrust.get(uuid);
                    if (h != null) {
                        openAddTrustSelectorGUI(player, h, 1);
                    }
                } else if (action.equals("open_guest_trust_manager")) {
                    Home h = selectedHomeForTrust.get(uuid);
                    String guestUuidStr = meta.getPersistentDataContainer().get(homeOwnerKey, PersistentDataType.STRING);
                    if (h != null && guestUuidStr != null) {
                        UUID guestUuid = UUID.fromString(guestUuidStr);
                        openGuestTrustManagerGUI(player, h, guestUuid);
                    }
                } else if (action.equals("revoke_trust")) {
                    Home h = selectedHomeForTrust.get(uuid);
                    String guestUuidStr = meta.getPersistentDataContainer().get(homeOwnerKey, PersistentDataType.STRING);
                    if (h != null && guestUuidStr != null) {
                        UUID guestUuid = UUID.fromString(guestUuidStr);
                        plugin.getHomeManager().removeTrust(h, guestUuid);
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 0.8f);
                        openManageTrustsGUI(player, h); // Rerender
                    }
                }
                break;

            case ADD_TRUST:
                if (action.equals("select_guest_for_trust")) {
                    Home h = selectedHomeForTrust.get(uuid);
                    String guestUuidStr = meta.getPersistentDataContainer().get(homeOwnerKey, PersistentDataType.STRING);
                    if (h != null && guestUuidStr != null) {
                        UUID guestUuid = UUID.fromString(guestUuidStr);
                        plugin.getHomeManager().addTrust(h, guestUuid);
                        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                        openGuestTrustManagerGUI(player, h, guestUuid);
                    }
                } else if (action.equals("back_to_trusts")) {
                    Home h = selectedHomeForTrust.get(uuid);
                    if (h != null) {
                        openManageTrustsGUI(player, h);
                    }
                }
                break;

            case GUEST_TRUST_MANAGER:
                if (action.equals("trust_duration_revoke")) {
                    Home h = selectedHomeForTrust.get(uuid);
                    UUID guestUuid = selectedGuestForTrust.get(uuid);
                    if (h != null && guestUuid != null) {
                        plugin.getHomeManager().removeTrust(h, guestUuid);
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 0.8f);
                        openManageTrustsGUI(player, h);
                    }
                } else if (action.equals("trust_duration_permanent")) {
                    Home h = selectedHomeForTrust.get(uuid);
                    UUID guestUuid = selectedGuestForTrust.get(uuid);
                    if (h != null && guestUuid != null) {
                        plugin.getHomeManager().addTrust(h, guestUuid, -1L);
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                        openManageTrustsGUI(player, h);
                    }
                } else if (action.equals("trust_duration_30m")) {
                    Home h = selectedHomeForTrust.get(uuid);
                    UUID guestUuid = selectedGuestForTrust.get(uuid);
                    if (h != null && guestUuid != null) {
                        plugin.getHomeManager().addTrust(h, guestUuid, System.currentTimeMillis() + 30 * 60 * 1000L);
                        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
                        openManageTrustsGUI(player, h);
                    }
                } else if (action.equals("trust_duration_1h")) {
                    Home h = selectedHomeForTrust.get(uuid);
                    UUID guestUuid = selectedGuestForTrust.get(uuid);
                    if (h != null && guestUuid != null) {
                        plugin.getHomeManager().addTrust(h, guestUuid, System.currentTimeMillis() + 60 * 60 * 1000L);
                        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
                        openManageTrustsGUI(player, h);
                    }
                } else if (action.equals("trust_duration_1d")) {
                    Home h = selectedHomeForTrust.get(uuid);
                    UUID guestUuid = selectedGuestForTrust.get(uuid);
                    if (h != null && guestUuid != null) {
                        plugin.getHomeManager().addTrust(h, guestUuid, System.currentTimeMillis() + 24 * 60 * 60 * 1000L);
                        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
                        openManageTrustsGUI(player, h);
                    }
                } else if (action.equals("trust_toggle_role")) {
                    Home h = selectedHomeForTrust.get(uuid);
                    UUID guestUuid = selectedGuestForTrust.get(uuid);
                    if (h != null && guestUuid != null) {
                        String currentRole = h.getTrustRole(guestUuid);
                        String newRole = currentRole.equalsIgnoreCase("CO_OWNER") ? "VISITOR" : "CO_OWNER";
                        long exp = h.getExpiration(guestUuid);
                        plugin.getHomeManager().addTrust(h, guestUuid, exp, newRole);
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.2f);
                        openGuestTrustManagerGUI(player, h, guestUuid);
                    }
                } else if (action.equals("back_to_trusts")) {
                    Home h = selectedHomeForTrust.get(uuid);
                    if (h != null) {
                        openManageTrustsGUI(player, h);
                    }
                }
                break;

            case EFFECTS_SELECTOR:
                String particle = plugin.getTeleportManager().getPlayerParticle(uuid);
                String style = plugin.getTeleportManager().getPlayerStyle(uuid);
                String sound = plugin.getTeleportManager().getPlayerSound(uuid);
                String successSound = plugin.getTeleportManager().getPlayerSuccessSound(uuid);

                boolean changed = false;

                if (action.equals("style_shield")) {
                    style = "SHIELD";
                    changed = true;
                } else if (action.equals("style_spiral")) {
                    style = "SPIRAL";
                    changed = true;
                } else if (action.equals("style_ring")) {
                    style = "RING";
                    changed = true;
                } else if (action.equals("style_tornado")) {
                    style = "TORNADO";
                    changed = true;
                } else if (action.equals("style_progressive_ring")) {
                    style = "PROGRESSIVE_RING";
                    changed = true;
                } else if (action.equals("style_beacon")) {
                    style = "BEACON";
                    changed = true;
                } else if (action.equals("style_implosion")) {
                    style = "IMPLOSION";
                    changed = true;
                } else if (action.equals("part_portal")) {
                    particle = "PORTAL";
                    changed = true;
                } else if (action.equals("part_flame")) {
                    particle = "FLAME";
                    changed = true;
                } else if (action.equals("part_drip_water")) {
                    particle = "DRIP_WATER";
                    changed = true;
                } else if (action.equals("part_happy_villager")) {
                    particle = "HAPPY_VILLAGER";
                    changed = true;
                } else if (action.equals("part_cloud")) {
                    particle = "CLOUD";
                    changed = true;
                } else if (action.equals("part_soul_fire_flame")) {
                    particle = "SOUL_FIRE_FLAME";
                    changed = true;
                } else if (action.equals("part_witch")) {
                    particle = "WITCH";
                    changed = true;
                } else if (action.equals("sound_teleport")) {
                    sound = "ENTITY_ENDERMAN_TELEPORT";
                    changed = true;
                } else if (action.equals("sound_pling")) {
                    sound = "BLOCK_NOTE_BLOCK_PLING";
                    changed = true;
                } else if (action.equals("sound_levelup")) {
                    sound = "ENTITY_PLAYER_LEVELUP";
                    changed = true;
                }

                if (changed) {
                    plugin.getHomeManager().savePlayerCosmetics(uuid, particle, style, sound, successSound);
                    if (action.startsWith("style_") || action.startsWith("part_")) {
                        plugin.getTeleportManager().playCosmeticPreview(player, particle, style);
                    } else if (action.startsWith("sound_")) {
                        Sound snd = plugin.getTeleportManager().getSoundFromName(sound);
                        if (snd != null) {
                            float volume = (float) plugin.getConfig().getDouble("effects.countdown.volume", 0.8);
                            float pitch = (float) plugin.getConfig().getDouble("effects.countdown.pitch", 1.0);
                            player.playSound(player.getLocation(), snd, volume, pitch);
                        } else {
                            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
                        }
                    } else {
                        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
                    }
                    openEffectsGUI(player);
                }
                break;

            case HOME_SETTINGS:
                handleHomeSettingsInteract(player, action, meta, event.getClick());
                break;

            case JUKEBOX_SELECTOR:
                handleJukeboxInteract(player, action, meta);
                break;

            case TIME_WEATHER_SELECTOR:
                handleTimeWeatherInteract(player, action, meta);
                break;

            case BAN_LIST:
                handleBanListInteract(player, action, meta);
                break;

            case VISIT_HISTORY:
                handleVisitHistoryInteract(player, action, meta);
                break;

            case CATEGORY_SELECTOR:
                handleCategorySelectorInteract(player, action, meta);
                break;

            case ADMIN_PLAYERS:
                handleAdminPlayersInteract(player, action, meta, curPage);
                break;

            case ADMIN_HOMES:
                handleAdminHomesInteract(player, action, meta, curPage, event.getClick());
                break;
        }
    }

    // -------------------------------------------------------------------------
    // 5. MANAGE TRUSTS
    // -------------------------------------------------------------------------
    public void openManageTrustsGUI(Player player, Home home) {
        Component title = gm.getComponent("menus.trusts.title", "{name}", home.getName());
        Inventory inv = Bukkit.createInventory(null, 54, title);
        fillBackground(inv, 54);

        selectedHomeForTrust.put(player.getUniqueId(), home);

        // Affiche tous les joueurs de confiance
        int slot = 10;
        for (UUID trustedUuid : home.getTrustedPlayers()) {
            if (slot > 43) break; // limite visuelle de la page (on pourrait paginer)
            if (slot % 9 == 8) slot += 2;

            long exp = home.getExpiration(trustedUuid);
            String timeStr = formatExpiration(exp);

            org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(trustedUuid);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta meta = head.getItemMeta();
            if (meta instanceof org.bukkit.inventory.meta.SkullMeta skullMeta) {
                skullMeta.setOwningPlayer(target);
                String playerName = target.getName() != null ? target.getName() : "Unknown";
                skullMeta.displayName(gm.getComponent("menus.trusts.items.guest.name", "{player}", playerName));
                
                List<Component> configLore = gm.getLore("menus.trusts.items.guest.lore");
                List<Component> finalLore = new ArrayList<>();
                String role = home.getTrustRole(trustedUuid);
                String roleName = role.equalsIgnoreCase("CO_OWNER") ? "§bCo-Owner" : "§7Visitor";
                finalLore.add(Component.text("§6Role: " + roleName));
                finalLore.add(Component.empty());
                
                boolean replaced = false;
                if (configLore != null) {
                    for (Component comp : configLore) {
                        Component newComp = comp.replaceText(net.kyori.adventure.text.TextReplacementConfig.builder()
                                .matchLiteral("{duration}")
                                .replacement(timeStr)
                                .build());
                        if (!newComp.equals(comp)) {
                            replaced = true;
                        }
                        finalLore.add(newComp);
                    }
                }
                if (!replaced) {
                    Component durationLoreTemplate = gm.getComponent("common.time.duration-lore", "{time}", timeStr);
                    finalLore.add(Component.empty());
                    finalLore.add(durationLoreTemplate);
                }
                skullMeta.lore(finalLore);
                
                skullMeta.getPersistentDataContainer().set(homeOwnerKey, PersistentDataType.STRING, trustedUuid.toString());
                skullMeta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "open_guest_trust_manager");
            }
            head.setItemMeta(meta);
            inv.setItem(slot++, head);
        }

        // Bouton + Add Trust
        inv.setItem(49, createGuiItem(Material.PLAYER_HEAD,
                gm.getComponent("menus.trusts.items.add-guest.name"),
                gm.getLore("menus.trusts.items.add-guest.lore"), "open_add_trust"));

        // Bouton info comment ajouter
        inv.setItem(50, createGuiItem(Material.ANVIL,
                gm.getComponent("menus.trusts.items.info.name"),
                gm.getLore("menus.trusts.items.info.lore", "{name}", home.getName()), "info"));

        inv.setItem(45, createGuiItem(Material.ARROW, gm.getComponent("common.buttons.back-to-main.name"), null, "back_to_main"));

        player.openInventory(inv);
        openInventories.put(player.getUniqueId(), InventoryType.MANAGE_TRUSTS);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.0f);
    }

    public void openEffectsGUI(Player player) {
        Component title;
        if (gm.getConfig().contains("menus.effects.title")) {
            title = gm.getComponent("menus.effects.title");
        } else {
            title = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize("<yellow><bold>SethomeX <dark_gray>» <light_purple>TP Effects");
        }
        Inventory inv = Bukkit.createInventory(null, 54, title);
        fillBackground(inv, 54);

        UUID uuid = player.getUniqueId();
        String currentParticle = plugin.getTeleportManager().getPlayerParticle(uuid);
        String currentStyle = plugin.getTeleportManager().getPlayerStyle(uuid);
        String currentSound = plugin.getTeleportManager().getPlayerSound(uuid);

        // --- STYLES DE TÉLÉPORTATION ---
        inv.setItem(10, buildEffectConfigItem("menus.effects.items.style-shield", Material.SHIELD, "§eStyle: §6SHIELD (Bouclier)", 
            Arrays.asList("§7Effet de bulle de protection classique.", "§7", "{status}"), 
            "style_shield", currentStyle.equalsIgnoreCase("default") || currentStyle.equalsIgnoreCase("shield")));

        inv.setItem(11, buildEffectConfigItem("menus.effects.items.style-spiral", Material.AMETHYST_SHARD, "§eStyle: §6SPIRAL", 
            Arrays.asList("§7Spirale montante de particules.", "§7", "{status}"), 
            "style_spiral", currentStyle.equalsIgnoreCase("spiral")));

        inv.setItem(12, buildEffectConfigItem("menus.effects.items.style-ring", Material.GOLDEN_CARROT, "§eStyle: §6RING", 
            Arrays.asList("§7Double anneau rotatif autour de vous.", "§7", "{status}"), 
            "style_ring", currentStyle.equalsIgnoreCase("ring")));

        inv.setItem(13, buildEffectConfigItem("menus.effects.items.style-tornado", Material.FEATHER, "§eStyle: §6TORNADO", 
            Arrays.asList("§7Tornade ascendante dynamique.", "§7", "{status}"), 
            "style_tornado", currentStyle.equalsIgnoreCase("tornado")));

        inv.setItem(14, buildEffectConfigItem("menus.effects.items.style-progressive-ring", Material.CLAY_BALL, "§eStyle: §6PROGRESSIVE_RING", 
            Arrays.asList("§7Cercle de particules qui monte", "§7selon le warmup.", "§7", "{status}"), 
            "style_progressive_ring", currentStyle.equalsIgnoreCase("progressive_ring")));

        inv.setItem(15, buildEffectConfigItem("menus.effects.items.style-beacon", Material.BEACON, "§eStyle: §6BEACON", 
            Arrays.asList("§7Faisceau de balise temporaire.", "§7", "{status}"), 
            "style_beacon", currentStyle.equalsIgnoreCase("beacon")));

        inv.setItem(16, buildEffectConfigItem("menus.effects.items.style-implosion", Material.HEART_OF_THE_SEA, "§eStyle: §6IMPLOSION", 
            Arrays.asList("§7Vortex de particules se contractant", "§7vers vous.", "§7", "{status}"), 
            "style_implosion", currentStyle.equalsIgnoreCase("implosion")));

        // --- PARTICULES ---
        inv.setItem(28, buildEffectConfigItem("menus.effects.items.part-portal", Material.ENDER_PEARL, "§dParticule: §5PORTAL", 
            Arrays.asList("§7Particules de portail de l'Ender.", "§7", "{status}"), 
            "part_portal", currentParticle.equalsIgnoreCase("default") || currentParticle.equalsIgnoreCase("portal")));

        inv.setItem(29, buildEffectConfigItem("menus.effects.items.part-flame", Material.BLAZE_POWDER, "§dParticule: §cFLAME", 
            Arrays.asList("§7Flammes ardentes.", "§7", "{status}"), 
            "part_flame", currentParticle.equalsIgnoreCase("flame")));

        inv.setItem(30, buildEffectConfigItem("menus.effects.items.part-drip-water", Material.WATER_BUCKET, "§dParticule: §9DRIP_WATER", 
            Arrays.asList("§7Gouttes d'eau tombantes.", "§7", "{status}"), 
            "part_drip_water", currentParticle.equalsIgnoreCase("drip_water")));

        inv.setItem(31, buildEffectConfigItem("menus.effects.items.part-happy-villager", Material.EMERALD, "§dParticule: §aHAPPY_VILLAGER", 
            Arrays.asList("§7Étoiles vertes de bonheur.", "§7", "{status}"), 
            "part_happy_villager", currentParticle.equalsIgnoreCase("happy_villager")));

        inv.setItem(32, buildEffectConfigItem("menus.effects.items.part-cloud", Material.WHITE_WOOL, "§dParticule: §fCLOUD", 
            Arrays.asList("§7Nuages de fumée blanche.", "§7", "{status}"), 
            "part_cloud", currentParticle.equalsIgnoreCase("cloud")));

        inv.setItem(33, buildEffectConfigItem("menus.effects.items.part-soul-fire-flame", Material.SOUL_TORCH, "§dParticule: §3SOUL_FLAME", 
            Arrays.asList("§7Flammes bleues de l'âme.", "§7", "{status}"), 
            "part_soul_fire_flame", currentParticle.equalsIgnoreCase("soul_fire_flame")));

        inv.setItem(34, buildEffectConfigItem("menus.effects.items.part-witch", Material.GLOWSTONE_DUST, "§dParticule: §dWITCH", 
            Arrays.asList("§7Particules violettes magiques.", "§7", "{status}"), 
            "part_witch", currentParticle.equalsIgnoreCase("witch")));

        // --- SONS ---
        inv.setItem(46, buildEffectConfigItem("menus.effects.items.sound-teleport", Material.ENDER_EYE, "§bSon: §3TELEPORT", 
            Arrays.asList("§7Son classique d'Ender Teleport.", "§7", "{status}"), 
            "sound_teleport", currentSound.equalsIgnoreCase("default") || currentSound.equalsIgnoreCase("entity_enderman_teleport")));

        inv.setItem(48, buildEffectConfigItem("menus.effects.items.sound-pling", Material.NOTE_BLOCK, "§bSon: §3PLING", 
            Arrays.asList("§7Mélodie harmonieuse Pling.", "§7", "{status}"), 
            "sound_pling", currentSound.equalsIgnoreCase("block_note_block_pling")));

        inv.setItem(50, buildEffectConfigItem("menus.effects.items.sound-levelup", Material.EXPERIENCE_BOTTLE, "§bSon: §3LEVEL_UP", 
            Arrays.asList("§7Son triomphant de Level Up.", "§7", "{status}"), 
            "sound_levelup", currentSound.equalsIgnoreCase("entity_player_levelup")));

        inv.setItem(45, createGuiItem(Material.ARROW, gm.getComponent("common.buttons.back.name"), null, "back_to_main"));

        player.openInventory(inv);
        openInventories.put(uuid, InventoryType.EFFECTS_SELECTOR);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.0f);
    }

    private ItemStack buildEffectConfigItem(String configPath, Material defaultMat, String defaultName, List<String> defaultLore, String action, boolean selected) {
        String matStr = gm.getRawString(configPath + ".material", defaultMat.name());
        Material mat = defaultMat;
        try {
            mat = Material.valueOf(matStr.toUpperCase());
        } catch (Exception ignored) {}

        Component name = gm.getComponent(configPath + ".name");
        if (name.equals(Component.text(configPath + ".name")) || gm.getRawString(configPath + ".name", null) == null) {
            name = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(convertLegacyToMiniMessage(defaultName));
        }

        String statusSelected = gm.getRawString("menus.effects.items.status-selected", "&a✔ SELECTED");
        String statusUnselected = gm.getRawString("menus.effects.items.status-unselected", "&e► Click to select");
        String statusStr = selected ? statusSelected : statusUnselected;

        List<Component> lore = new ArrayList<>();
        List<String> rawLore = gm.getConfig().getStringList(configPath + ".lore");
        if (rawLore.isEmpty()) {
            for (String line : defaultLore) {
                String parsed = line.replace("{status}", statusStr);
                lore.add(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(convertLegacyToMiniMessage(parsed)));
            }
        } else {
            for (String line : rawLore) {
                String parsed = line.replace("{status}", statusStr);
                lore.add(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(convertLegacyToMiniMessage(parsed)));
            }
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            meta.lore(lore);
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
            if (selected) {
                org.bukkit.Registry<org.bukkit.enchantments.Enchantment> registry = io.papermc.paper.registry.RegistryAccess.registryAccess().getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT);
                if (registry != null) {
                    org.bukkit.enchantments.Enchantment unbreaking = registry.get(org.bukkit.NamespacedKey.minecraft("unbreaking"));
                    if (unbreaking != null) {
                        meta.addEnchant(unbreaking, 1, true);
                    }
                }
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private String formatDuration(long diff) {
        if (diff <= 0) {
            return gm.getRawString("common.time.expired", "Expired");
        }
        
        long secs = diff / 1000 % 60;
        long mins = diff / (60 * 1000) % 60;
        long hours = diff / (60 * 60 * 1000) % 24;
        long days = diff / (24 * 60 * 60 * 1000);
        
        String dSuffix = gm.getRawString("common.time.suffix-days", "d");
        String hSuffix = gm.getRawString("common.time.suffix-hours", "h");
        String mSuffix = gm.getRawString("common.time.suffix-minutes", "m");
        String sSuffix = gm.getRawString("common.time.suffix-seconds", "s");
        
        if (days > 0) {
            return days + dSuffix + " " + hours + hSuffix;
        } else if (hours > 0) {
            return hours + hSuffix + " " + mins + mSuffix;
        } else if (mins > 0) {
            return mins + mSuffix + " " + secs + sSuffix;
        } else {
            return secs + sSuffix;
        }
    }

    private String formatExpiration(long exp) {
        if (exp == -1L) {
            return gm.getRawString("common.time.permanent", "Permanent");
        }
        return formatDuration(exp - System.currentTimeMillis());
    }

    private String convertLegacyToMiniMessage(String text) {
        if (text == null)
            return "";
        return text.replace("&0", "<black>").replace("&1", "<dark_blue>").replace("&2", "<dark_green>")
                .replace("&3", "<dark_aqua>").replace("&4", "<dark_red>").replace("&5", "<dark_purple>")
                .replace("&6", "<gold>").replace("&7", "<gray>").replace("&8", "<dark_gray>")
                .replace("&9", "<blue>").replace("&a", "<green>").replace("&b", "<aqua>")
                .replace("&c", "<red>").replace("&d", "<light_purple>").replace("&e", "<yellow>")
                .replace("&f", "<white>").replace("&k", "<obfuscated>").replace("&l", "<bold>")
                .replace("&m", "<strikethrough>").replace("&n", "<underlined>").replace("&o", "<italic>")
                .replace("&r", "<reset>");
    }

    // =========================================================================
    // HANDLERS DÉDIÉS (SOUS-LOGIQUES)
    // =========================================================================

    private void handleMainHomeInteract(Player player, InventoryClickEvent event, ItemMeta meta, int curPage) {
        String ownerUuidStr = meta.getPersistentDataContainer().get(homeOwnerKey, PersistentDataType.STRING);
        String hName = meta.getPersistentDataContainer().get(homeNameKey, PersistentDataType.STRING);
        
        UUID ownerUuid = (ownerUuidStr != null) ? UUID.fromString(ownerUuidStr) : player.getUniqueId();
        
        if (!ownerUuid.equals(player.getUniqueId())) {
            ClickType click = event.getClick();
            handleExternalHomeTeleport(player, meta, click);
            return;
        }

        Home home = plugin.getHomeManager().getHome(player, hName);
        if (home == null)
            return;

        ClickType click = event.getClick();

        // 1. TELEPORTATION (Left)
        if (click == ClickType.LEFT) {
            player.closeInventory();
            plugin.getTeleportManager().startTeleport(player, home);
        }
        // 2. TOGGLE PUBLIC (Shift Left)
        else if (click == ClickType.SHIFT_LEFT) {
            if (!plugin.getConfig().getBoolean("homes.allow-public-homes", true)) {
                plugin.getMessageManager().sendMessage(player, "gui.error-public-disabled");
                return;
            }
            boolean newState = !home.isPublic();
            home.setPublic(newState);
            plugin.getHomeManager().updateHomeSocial(home);

            String tagPath = newState ? "menus.main.status-public" : "menus.main.status-private";
            String finalTag = gm.getRawString(tagPath, newState ? "PUBLIC" : "PRIVATE");

            plugin.getMessageManager().sendMessage(player, "gui.social-toggled", "{name}", home.getName(), "{status}",
                    finalTag);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, newState ? 1.5f : 0.8f);
            openMainGUI(player, curPage); // Rerender
        }
        // 3. TOGGLE RESPAWN (Middle)
        else if (click == ClickType.MIDDLE) {
            if (!plugin.getConfig().getBoolean("homes.allow-respawn-at-home", true)) {
                plugin.getMessageManager().sendMessage(player, "gui.error-respawn-disabled");
                return;
            }
            plugin.getHomeManager().setRespawnHome(player.getUniqueId(), home);

            if (home.isRespawn()) {
                plugin.getMessageManager().sendMessage(player, "gui.respawn-set", "{name}", home.getName());
                player.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 1.0f, 1.2f);
            } else {
                plugin.getMessageManager().sendMessage(player, "gui.respawn-removed", "{name}", home.getName());
                player.playSound(player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 1.0f);
            }
            openMainGUI(player, curPage);
        }
        // 4. SELECT ICON (Right)
        else if (click == ClickType.RIGHT) {
            openIconSelectorGUI(player, home);
        }
        // 5. DELETE CONFIRM (Shift Right)
        else if (click == ClickType.SHIFT_RIGHT) {
            openConfirmDeleteGUI(player, home);
        }
        // 6. HOME SETTINGS (Drop)
        else if (click == ClickType.DROP) {
            openHomeSettingsGUI(player, home);
        }
    }

    private void handleExternalHomeTeleport(Player player, ItemMeta meta, ClickType click) {
        String ownerUuidStr = meta.getPersistentDataContainer().get(homeOwnerKey, PersistentDataType.STRING);
        String hName = meta.getPersistentDataContainer().get(homeNameKey, PersistentDataType.STRING);
        if (ownerUuidStr == null || hName == null)
            return;

        UUID ownerUuid = UUID.fromString(ownerUuidStr);
        Home home = plugin.getHomeManager().getHome(ownerUuid, hName);
        if (home == null) return;

        if (click == ClickType.LEFT) {
            player.closeInventory();
            String ownerName = plugin.getHomeManager().getPlayerName(ownerUuid);
            plugin.getMessageManager().sendMessage(player, "social.visiting", "{player}",
                    ownerName, "{name}", hName);
            plugin.getTeleportManager().startTeleport(player, home);
        } else if (click == ClickType.DROP) {
            if (home.getPlayerUuid().equals(player.getUniqueId()) || player.hasPermission("sethomex.command.admin") || 
                (home.isTrusted(player.getUniqueId()) && "CO_OWNER".equalsIgnoreCase(home.getTrustRole(player.getUniqueId())))) {
                openHomeSettingsGUI(player, home);
            } else {
                player.sendMessage("§cErreur: Vous devez être Co-Propriétaire pour modifier ce home !");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            }
        } else if (click == ClickType.RIGHT) {
            plugin.getHomeManager().toggleLikeAsync(player.getUniqueId(), ownerUuid, hName).thenAccept(liked -> {
                plugin.getScheduler().runTaskAtEntity(player, () -> {
                    if (liked) {
                        player.sendMessage("§a[Catalog] Vous avez liké le home §e" + hName + " §a!");
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);
                    } else {
                        player.sendMessage("§e[Catalog] Vous avez retiré votre like sur le home §e" + hName + "§e.");
                        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.5f, 0.8f);
                    }
                    int page = currentGUIPage.getOrDefault(player.getUniqueId(), 1);
                    openPublicCatalogGUI(player, page);
                });
            });
        } else if (click == ClickType.SHIFT_RIGHT) {
            if (!plugin.getConfig().getBoolean("teleport.preview.enabled", true)) {
                plugin.getMessageManager().sendMessage(player, "gui.error-preview-disabled");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }
            player.closeInventory();
            plugin.getTeleportManager().startPreview(player, home);
        }
    }

    private void handleToggleSort(Player player) {
        UUID u = player.getUniqueId();
        SortMode current = currentSortModes.getOrDefault(u, SortMode.POPULARITY);
        SortMode next = SortMode.values()[(current.ordinal() + 1) % SortMode.values().length];
        currentSortModes.put(u, next);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.5f);
        openPublicCatalogGUI(player, 1);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        openInventories.remove(uuid);
        
        // Delay clearing player GUI state to next tick to handle transitions safely
        plugin.getScheduler().runTaskAtEntity(event.getPlayer(), () -> {
            if (!openInventories.containsKey(uuid)) {
                selectedHomeForIcon.remove(uuid);
                selectedHomeForTrust.remove(uuid);
                selectedGuestForTrust.remove(uuid);
                currentGUIPage.remove(uuid);
                pendingDeleteHome.remove(uuid);
                selectedAdminTarget.remove(uuid);
            }
        });
    }

    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            if (openInventories.containsKey(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    public void openAddTrustSelectorGUI(Player player, Home home, int page) {
        Component title = gm.getComponent("menus.add-trust.title");
        
        List<Player> eligible = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getUniqueId().equals(player.getUniqueId()) && !home.isTrusted(p.getUniqueId())) {
                eligible.add(p);
            }
        }
        eligible.sort(Comparator.comparing(p -> p.getName().toLowerCase()));

        int itemsPerPage = 21;
        int totalPages = Math.max(1, (int) Math.ceil((double) eligible.size() / itemsPerPage));
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        if (totalPages > 1) {
            title = title.append(gm.getComponent("common.visuals.background-pane"))
                    .append(gm.getComponent("menus.icons.page-suffix", "{page}", String.valueOf(page), "{max}", String.valueOf(totalPages)));
        }

        Inventory inv = Bukkit.createInventory(null, 45, title);
        fillBackground(inv, 45);

        int startIndex = (page - 1) * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, eligible.size());
        int[] slots = { 10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34 };

        int sIdx = 0;
        for (int i = startIndex; i < endIndex; i++) {
            Player p = eligible.get(i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta meta = head.getItemMeta();
            if (meta instanceof org.bukkit.inventory.meta.SkullMeta skullMeta) {
                skullMeta.setOwningPlayer(p);
                skullMeta.displayName(Component.text("§e" + p.getName()));
                skullMeta.lore(Arrays.asList(Component.text("§7Click to authorize this player.")));
                skullMeta.getPersistentDataContainer().set(homeOwnerKey, PersistentDataType.STRING, p.getUniqueId().toString());
                skullMeta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "select_guest_for_trust");
            }
            head.setItemMeta(meta);
            inv.setItem(slots[sIdx++], head);
        }

        // Back button (slot 40)
        inv.setItem(40, createGuiItem(Material.ARROW, gm.getComponent("common.buttons.back.name"), null, "back_to_trusts"));

        if (page > 1) {
            inv.setItem(39, createGuiItem(Material.ARROW,
                    gm.getComponent("common.buttons.previous-page.name"),
                    gm.getLore("common.buttons.previous-page.lore"), "prev_page"));
        }
        if (page < totalPages) {
            inv.setItem(41, createGuiItem(Material.ARROW,
                    gm.getComponent("common.buttons.next-page.name"),
                    gm.getLore("common.buttons.next-page.lore"), "next_page"));
        }

        player.openInventory(inv);
        openInventories.put(player.getUniqueId(), InventoryType.ADD_TRUST);
        currentGUIPage.put(player.getUniqueId(), page);
        selectedHomeForTrust.put(player.getUniqueId(), home);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.0f);
    }

    public void openGuestTrustManagerGUI(Player player, Home home, UUID guestUuid) {
        String playerName = plugin.getHomeManager().getPlayerName(guestUuid);
        Component title = gm.getComponent("menus.guest-trust-manager.title", "{player}", playerName);
        Inventory inv = Bukkit.createInventory(null, 45, title);
        fillBackground(inv, 45);

        selectedGuestForTrust.put(player.getUniqueId(), guestUuid);
        selectedHomeForTrust.put(player.getUniqueId(), home);

        // Guest head at slot 13
        OfflinePlayer target = Bukkit.getOfflinePlayer(guestUuid);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(target);
            skullMeta.displayName(Component.text("§e§l" + playerName));
            long exp = home.getExpiration(guestUuid);
            String timeStr = formatExpiration(exp);
            skullMeta.lore(Arrays.asList(
                Component.text("§7Active Trust Guest"),
                Component.empty(),
                Component.text("§7Current duration: §f" + timeStr)
            ));
        }
        head.setItemMeta(meta);
        inv.setItem(13, head);

        // Role button (slot 15)
        String currentRole = home.getTrustRole(guestUuid);
        Component roleName = Component.text("§6§lRôle: " + (currentRole.equalsIgnoreCase("CO_OWNER") ? "§bCo-Owner" : "§7Visitor"));
        List<Component> roleLore = Arrays.asList(
            Component.text("§7Click to toggle role."),
            Component.empty(),
            Component.text("§7Visitor: Can only teleport."),
            Component.text("§7Co-Owner: Can manage settings (except delete).")
        );
        inv.setItem(15, createGuiItem(Material.WRITABLE_BOOK, roleName, roleLore, "trust_toggle_role"));

        // Revoke button (slot 29)
        Component revokeName = gm.getComponent("menus.guest-trust-manager.items.revoke.name");
        List<Component> revokeLore = gm.getLore("menus.guest-trust-manager.items.revoke.lore");
        inv.setItem(29, createGuiItem(Material.RED_DYE, revokeName, revokeLore, "trust_duration_revoke"));

        // Durations
        Component permName = gm.getComponent("menus.guest-trust-manager.items.duration.permanent");
        Component m30Name = gm.getComponent("menus.guest-trust-manager.items.duration.30m");
        Component h1Name = gm.getComponent("menus.guest-trust-manager.items.duration.1h");
        Component d1Name = gm.getComponent("menus.guest-trust-manager.items.duration.1d");

        List<Component> permLore = Arrays.asList(Component.text("§7Give unlimited access to your home."));
        List<Component> m30Lore = Arrays.asList(Component.text("§7Give access for 30 minutes."));
        List<Component> h1Lore = Arrays.asList(Component.text("§7Give access for 1 hour."));
        List<Component> d1Lore = Arrays.asList(Component.text("§7Give access for 1 day."));

        inv.setItem(31, createGuiItem(Material.NETHERITE_INGOT, permName, permLore, "trust_duration_permanent"));
        inv.setItem(32, createGuiItem(Material.LIME_DYE, m30Name, m30Lore, "trust_duration_30m"));
        inv.setItem(33, createGuiItem(Material.LIGHT_BLUE_DYE, h1Name, h1Lore, "trust_duration_1h"));
        inv.setItem(34, createGuiItem(Material.MAGENTA_DYE, d1Name, d1Lore, "trust_duration_1d"));

        // Back button (slot 40)
        inv.setItem(40, createGuiItem(Material.ARROW, gm.getComponent("common.buttons.back.name"), null, "back_to_trusts"));

        player.openInventory(inv);
        openInventories.put(player.getUniqueId(), InventoryType.GUEST_TRUST_MANAGER);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.0f);
    }

    private String formatMaterialName(Material mat) {
        String name = mat.name().toLowerCase().replace("_", " ");
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }

    // =========================================================================
    // NOUVELLES INTERFACES GRAPHISQUES ET LOGIQUE CHAT INPUT
    // =========================================================================

    public void openHomeSettingsGUI(Player player, Home home) {
        Component title = gm.getComponent("menus.home-settings.title", "{name}", home.getName());
        if (title.equals(Component.text("menus.home-settings.title"))) {
            title = Component.text("§e§lParamètres §8» §f" + home.getName());
        }
        Inventory inv = Bukkit.createInventory(null, 27, title);
        fillBackground(inv, 27);

        selectedHomeForTrust.put(player.getUniqueId(), home);

        // 10. Icône (Current icon)
        ItemStack iconItem = home.getIconTexture() != null && !home.getIconTexture().isEmpty() ?
                fr.skynex.sethomex.util.HeadUtil.getCustomHead(home.getIconTexture()) :
                new ItemStack(home.getIconMaterial());
        ItemMeta iconMeta = iconItem.getItemMeta();
        if (iconMeta != null) {
            iconMeta.displayName(gm.getComponent("menus.home-settings.items.icon.name"));
            iconMeta.lore(gm.getLore("menus.home-settings.items.icon.lore", "{item}", formatMaterialName(home.getIconMaterial())));
            iconMeta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "settings_change_icon");
            iconItem.setItemMeta(iconMeta);
        }
        inv.setItem(10, iconItem);

        // 11. Public status
        Material pubMat = home.isPublic() ? Material.LIME_DYE : Material.GRAY_DYE;
        String pubState = home.isPublic() ? gm.getRawString("menus.main.status-public", "PUBLIC") : gm.getRawString("menus.main.status-private", "PRIVATE");
        inv.setItem(11, createGuiItem(pubMat,
                gm.getComponent("menus.home-settings.items.public.name", "{status}", pubState),
                gm.getLore("menus.home-settings.items.public.lore", "{status}", pubState),
                "settings_toggle_public"));

        // 12. Respawn status
        Material respMat = home.isRespawn() ? Material.RED_BED : Material.WHITE_BED;
        String respState = home.isRespawn() ? gm.getRawString("menus.main.respawn-yes", "OUI") : gm.getRawString("menus.main.respawn-no", "NON");
        inv.setItem(12, createGuiItem(respMat,
                gm.getComponent("menus.home-settings.items.respawn.name", "{respawn}", respState),
                gm.getLore("menus.home-settings.items.respawn.lore", "{respawn}", respState),
                "settings_toggle_respawn"));

        // 13. Trusts
        inv.setItem(13, createGuiItem(Material.PLAYER_HEAD,
                gm.getComponent("menus.home-settings.items.trusts.name"),
                gm.getLore("menus.home-settings.items.trusts.lore"),
                "settings_manage_trusts"));

        // 14. Category/Folder
        inv.setItem(14, createGuiItem(Material.CHEST,
                gm.getComponent("menus.home-settings.items.category.name", "{category}", home.getCategory()),
                gm.getLore("menus.home-settings.items.category.lore", "{category}", home.getCategory()),
                "settings_change_category"));

        // 15. Description
        String desc = home.getDescription() != null && !home.getDescription().isEmpty() ? home.getDescription() : "Aucune description";
        inv.setItem(15, createGuiItem(Material.WRITABLE_BOOK,
                gm.getComponent("menus.home-settings.items.description.name"),
                gm.getLore("menus.home-settings.items.description.lore", "{description}", desc),
                "settings_edit_description"));

        // 16. Welcome message
        String welcome = home.getWelcomeMessage() != null && !home.getWelcomeMessage().isEmpty() ? home.getWelcomeMessage() : "Aucun message";
        inv.setItem(16, createGuiItem(Material.PAPER,
                gm.getComponent("menus.home-settings.items.welcome.name"),
                gm.getLore("menus.home-settings.items.welcome.lore", "{welcome}", welcome),
                "settings_edit_welcome"));

        // 17. Fee/Tax setting
        String feeVal = home.getVisitFee() > 0 ? plugin.getEconomyManager().format(home.getVisitFee()) : "Gratuit";
        inv.setItem(17, createGuiItem(Material.GOLD_INGOT,
                gm.getComponent("menus.home-settings.items.fee.name"),
                gm.getLore("menus.home-settings.items.fee.lore", "{fee}", feeVal),
                "settings_edit_fee"));

        // 19. Jukebox / Music
        String musicDiscName = home.getMusicDisc() != null && !home.getMusicDisc().equalsIgnoreCase("none") ? home.getMusicDisc() : "Aucune";
        List<Component> musicLore = new ArrayList<>();
        musicLore.add(Component.text("§7Musique jouée à l'arrivée des visiteurs."));
        musicLore.add(Component.text("§7Actuelle : §b" + musicDiscName));
        musicLore.add(Component.text(""));
        musicLore.add(Component.text("§e➔ Cliquez pour modifier"));
        inv.setItem(19, createGuiItem(Material.JUKEBOX,
                Component.text("§d§lMusique d'ambiance"),
                musicLore,
                "settings_edit_music"));

        // 20. Time & Weather Lock
        String timeLockText = home.getTimeLock() == -1 ? "Normal (Serveur)" : home.getTimeLock() + " ticks";
        String weatherLockText = home.getWeatherLock() != null && !home.getWeatherLock().equalsIgnoreCase("none") ? home.getWeatherLock().toUpperCase() : "Normal (Serveur)";
        List<Component> twLore = new ArrayList<>();
        twLore.add(Component.text("§7Verrouille le temps/météo pour les visiteurs."));
        twLore.add(Component.text("§7Heure : §f" + timeLockText));
        twLore.add(Component.text("§7Météo : §f" + weatherLockText));
        twLore.add(Component.text(""));
        twLore.add(Component.text("§e➔ Cliquez pour modifier"));
        inv.setItem(20, createGuiItem(Material.CLOCK,
                Component.text("§e§lAmbiance Temps & Météo"),
                twLore,
                "settings_edit_time_weather"));

        // 21. Manage Bans
        List<Component> banLore = new ArrayList<>();
        banLore.add(Component.text("§7Gérer les joueurs interdits d'accès"));
        banLore.add(Component.text("§7à ce home public/partagé."));
        banLore.add(Component.text(""));
        banLore.add(Component.text("§e➔ Cliquez pour gérer"));
        inv.setItem(21, createGuiItem(Material.IRON_BARS,
                Component.text("§c§lJoueurs Bannis"),
                banLore,
                "settings_manage_bans"));

        // 23. Visit History
        List<Component> historyLore = new ArrayList<>();
        historyLore.add(Component.text("§7Voir qui a visité ce home récemment."));
        historyLore.add(Component.text(""));
        historyLore.add(Component.text("§e➔ Cliquez pour voir"));
        inv.setItem(23, createGuiItem(Material.BOOK,
                Component.text("§a§lHistorique des Visites"),
                historyLore,
                "settings_view_history"));

        // 24. Sponsor
        String sponsorState = home.isSponsored() ? "§aACTIVE" : "§cINACTIVE";
        List<Component> sponsorLore = new ArrayList<>();
        sponsorLore.add(Component.text("§7Met en avant votre home public"));
        sponsorLore.add(Component.text("§7dans le catalogue."));
        sponsorLore.add(Component.text("§7Statut : " + sponsorState));
        sponsorLore.add(Component.text(""));
        sponsorLore.add(Component.text("§e➔ Cliquez pour sponsoriser"));
        inv.setItem(24, createGuiItem(Material.EMERALD,
                Component.text("§b§lSponsoriser le Home"),
                sponsorLore,
                "settings_sponsor_home"));

        // 22. Delete
        inv.setItem(22, createGuiItem(Material.BARRIER,
                gm.getComponent("menus.home-settings.items.delete.name"),
                gm.getLore("menus.home-settings.items.delete.lore"),
                "settings_delete_home"));

        // 18. Back
        inv.setItem(18, createGuiItem(Material.ARROW,
                gm.getComponent("common.buttons.back-to-main.name"),
                null,
                "back_to_main"));

        player.openInventory(inv);
        openInventories.put(player.getUniqueId(), InventoryType.HOME_SETTINGS);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.0f);
    }

    public void handleHomeSettingsInteract(Player player, String action, ItemMeta meta, ClickType click) {
        UUID uuid = player.getUniqueId();
        Home home = selectedHomeForTrust.get(uuid);
        if (home == null) return;

        if (action.equals("settings_change_icon")) {
            openIconSelectorGUI(player, home);
        } else if (action.equals("settings_toggle_public")) {
            if (!plugin.getConfig().getBoolean("homes.allow-public-homes", true)) {
                plugin.getMessageManager().sendMessage(player, "gui.error-public-disabled");
                return;
            }
            boolean newState = !home.isPublic();
            home.setPublic(newState);
            plugin.getHomeManager().updateHomeSocial(home);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, newState ? 1.5f : 0.8f);
            openHomeSettingsGUI(player, home);
        } else if (action.equals("settings_toggle_respawn")) {
            if (!plugin.getConfig().getBoolean("homes.allow-respawn-at-home", true)) {
                plugin.getMessageManager().sendMessage(player, "gui.error-respawn-disabled");
                return;
            }
            plugin.getHomeManager().setRespawnHome(player.getUniqueId(), home);
            player.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 1.0f, 1.2f);
            openHomeSettingsGUI(player, home);
        } else if (action.equals("settings_manage_trusts")) {
            openManageTrustsGUI(player, home);
        } else if (action.equals("settings_change_category")) {
            openCategorySelectorGUI(player, home);
        } else if (action.equals("settings_edit_music")) {
            openJukeboxSelectorGUI(player, home);
        } else if (action.equals("settings_edit_time_weather")) {
            openTimeWeatherSelectorGUI(player, home);
        } else if (action.equals("settings_manage_bans")) {
            openBanListGUI(player, home);
        } else if (action.equals("settings_view_history")) {
            openVisitHistoryGUI(player, home);
        } else if (action.equals("settings_sponsor_home")) {
            player.closeInventory();
            activeChatInputs.put(uuid, new ChatInputSession(ChatInputType.SPONSOR_DAYS, home.getName()));
            player.sendMessage("§7§m--------------------------------------");
            player.sendMessage("§e§lSethomeX §8» §fSponsoriser le Home");
            player.sendMessage("§7Veuillez entrer le nombre de jours de sponsor dans le chat.");
            player.sendMessage("§7Prix par jour : §6" + plugin.getConfig().getDouble("economy.sponsor-price-per-day", 1000.0) + "$");
            player.sendMessage("§7Tapez §ccancel §7pour annuler.");
            player.sendMessage("§7§m--------------------------------------");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
        } else if (action.equals("settings_edit_description")) {
            player.closeInventory();
            activeChatInputs.put(uuid, new ChatInputSession(ChatInputType.SET_DESCRIPTION, home.getName()));
            player.sendMessage("§7§m--------------------------------------");
            player.sendMessage("§e§lSethomeX §8» §fNouvelle Description");
            player.sendMessage("§7Veuillez entrer la nouvelle description du home dans le chat.");
            player.sendMessage("§7Tapez §ccancel §7pour annuler.");
            player.sendMessage("§7§m--------------------------------------");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
        } else if (action.equals("settings_edit_welcome")) {
            player.closeInventory();
            activeChatInputs.put(uuid, new ChatInputSession(ChatInputType.SET_WELCOME, home.getName()));
            player.sendMessage("§7§m--------------------------------------");
            player.sendMessage("§e§lSethomeX §8» §fMessage d'Accueil");
            player.sendMessage("§7Veuillez entrer le message d'accueil pour ce home dans le chat.");
            player.sendMessage("§7Tapez §ccancel §7pour annuler.");
            player.sendMessage("§7§m--------------------------------------");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
        } else if (action.equals("settings_edit_fee")) {
            if (!home.isPublic()) {
                player.sendMessage("§cErreur: Vous ne pouvez configurer une taxe que sur les homes publics !");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }
            player.closeInventory();
            activeChatInputs.put(uuid, new ChatInputSession(ChatInputType.SET_FEE, home.getName()));
            player.sendMessage("§7§m--------------------------------------");
            player.sendMessage("§e§lSethomeX §8» §fTaxe de Visite");
            player.sendMessage("§7Veuillez entrer le montant de la taxe de visite dans le chat.");
            player.sendMessage("§7Tapez §ccancel §7pour annuler.");
            player.sendMessage("§7§m--------------------------------------");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
        } else if (action.equals("settings_delete_home")) {
            if (!home.getPlayerUuid().equals(player.getUniqueId()) && !player.hasPermission("sethomex.command.admin")) {
                player.sendMessage("§cErreur: Seul le propriétaire ou un administrateur peut supprimer ce home !");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }
            openConfirmDeleteGUI(player, home);
        } else if (action.equals("back_to_main")) {
            openMainGUI(player, 1);
        }
    }

    public void openCategorySelectorGUI(Player player, Home home) {
        Component title = gm.getComponent("menus.category-selector.title");
        if (title.equals(Component.text("menus.category-selector.title"))) {
            title = Component.text("§e§lCatégories §8» §f" + home.getName());
        }
        Inventory inv = Bukkit.createInventory(null, 45, title);
        fillBackground(inv, 45);

        selectedHomeForTrust.put(player.getUniqueId(), home);

        // Trouver toutes les catégories existantes de l'utilisateur
        Set<String> categories = new java.util.HashSet<>();
        for (Home h : plugin.getHomeManager().getPlayerHomes(player)) {
            if (h.getCategory() != null && !h.getCategory().equalsIgnoreCase("none")) {
                categories.add(h.getCategory());
            }
        }
        List<String> sortedCategories = new ArrayList<>(categories);
        sortedCategories.sort(String.CASE_INSENSITIVE_ORDER);

        int[] slots = { 10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34 };
        int sIdx = 0;
        for (String cat : sortedCategories) {
            if (sIdx >= slots.length) break;
            ItemStack catItem = createGuiItem(Material.CHEST,
                    gm.getComponent("menus.category-selector.items.category.name", "{category}", cat),
                    gm.getLore("menus.category-selector.items.category.lore", "{category}", cat),
                    "category_select");
            ItemMeta meta = catItem.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().set(folderKey, PersistentDataType.STRING, cat);
                catItem.setItemMeta(meta);
            }
            inv.setItem(slots[sIdx++], catItem);
        }

        // Éléments de commande
        inv.setItem(39, createGuiItem(Material.BARRIER,
                gm.getComponent("menus.category-selector.items.remove.name"),
                gm.getLore("menus.category-selector.items.remove.lore"),
                "category_remove"));

        inv.setItem(40, createGuiItem(Material.ARROW,
                gm.getComponent("common.buttons.back.name"),
                null,
                "back_to_settings"));

        inv.setItem(41, createGuiItem(Material.WRITABLE_BOOK,
                gm.getComponent("menus.category-selector.items.create.name"),
                gm.getLore("menus.category-selector.items.create.lore"),
                "category_create"));

        player.openInventory(inv);
        openInventories.put(player.getUniqueId(), InventoryType.CATEGORY_SELECTOR);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.0f);
    }

    public void handleCategorySelectorInteract(Player player, String action, ItemMeta meta) {
        UUID uuid = player.getUniqueId();
        Home home = selectedHomeForTrust.get(uuid);
        if (home == null) return;

        if (action.equals("category_select")) {
            String folder = meta.getPersistentDataContainer().get(folderKey, PersistentDataType.STRING);
            if (folder != null) {
                home.setCategory(folder);
                plugin.getHomeManager().updateHomeCategory(home);
                player.sendMessage("§a[Dossiers] Dossier mis à jour : §f" + folder);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                openHomeSettingsGUI(player, home);
            }
        } else if (action.equals("category_remove")) {
            home.setCategory("none");
            plugin.getHomeManager().updateHomeCategory(home);
            player.sendMessage("§e[Dossiers] Home retiré de son dossier.");
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            openHomeSettingsGUI(player, home);
        } else if (action.equals("category_create")) {
            player.closeInventory();
            activeChatInputs.put(uuid, new ChatInputSession(ChatInputType.CREATE_CATEGORY, home.getName()));
            player.sendMessage("§7§m--------------------------------------");
            player.sendMessage("§e§lSethomeX §8» §fNouveau Dossier");
            player.sendMessage("§7Veuillez entrer le nom du nouveau dossier dans le chat.");
            player.sendMessage("§7Tapez §ccancel §7pour annuler.");
            player.sendMessage("§7§m--------------------------------------");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
        } else if (action.equals("back_to_settings")) {
            openHomeSettingsGUI(player, home);
        }
    }

    public void openAdminPlayersGUI(Player player, int page) {
        plugin.getHomeManager().getPlayersWithHomesAsync().thenAccept(players -> {
            plugin.getScheduler().runTaskAtEntity(player, () -> {
                Component title = gm.getComponent("menus.admin-players.title");
                if (title.equals(Component.text("menus.admin-players.title"))) {
                    title = Component.text("§c§lAdmin §8» §fJoueurs");
                }
                int itemsPerPage = 21;
                int totalPages = Math.max(1, (int) Math.ceil((double) players.size() / itemsPerPage));
                int finalPage = Math.max(1, Math.min(page, totalPages));

                if (totalPages > 1) {
                    title = title.append(gm.getComponent("common.visuals.background-pane"))
                            .append(gm.getComponent("menus.icons.page-suffix", "{page}", String.valueOf(finalPage), "{max}", String.valueOf(totalPages)));
                }

                Inventory inv = Bukkit.createInventory(null, 45, title);
                fillBackground(inv, 45);

                int startIndex = (finalPage - 1) * itemsPerPage;
                int endIndex = Math.min(startIndex + itemsPerPage, players.size());
                int[] slots = { 10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34 };

                int sIdx = 0;
                for (int i = startIndex; i < endIndex; i++) {
                    HomeManager.OfflinePlayerInfo info = players.get(i);
                    ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                    ItemMeta meta = head.getItemMeta();
                    if (meta instanceof org.bukkit.inventory.meta.SkullMeta skullMeta) {
                        skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(info.getUuid()));
                        skullMeta.displayName(gm.getComponent("menus.admin-players.items.player.name", "{player}", info.getName()));
                        skullMeta.lore(gm.getLore("menus.admin-players.items.player.lore", "{player}", info.getName()));
                        skullMeta.getPersistentDataContainer().set(homeOwnerKey, PersistentDataType.STRING, info.getUuid().toString());
                        skullMeta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "admin_select_player");
                        head.setItemMeta(skullMeta);
                    }
                    inv.setItem(slots[sIdx++], head);
                }

                inv.setItem(40, createGuiItem(Material.ARROW, gm.getComponent("common.buttons.back-to-main.name"), null, "back_to_main"));

                if (finalPage > 1) {
                    inv.setItem(39, createGuiItem(Material.ARROW,
                            gm.getComponent("common.buttons.previous-page.name"),
                            gm.getLore("common.buttons.previous-page.lore"), "prev_page"));
                }
                if (finalPage < totalPages) {
                    inv.setItem(41, createGuiItem(Material.ARROW,
                            gm.getComponent("common.buttons.next-page.name"),
                            gm.getLore("common.buttons.next-page.lore"), "next_page"));
                }

                player.openInventory(inv);
                openInventories.put(player.getUniqueId(), InventoryType.ADMIN_PLAYERS);
                currentGUIPage.put(player.getUniqueId(), finalPage);
                player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.0f);
            });
        });
    }

    public void handleAdminPlayersInteract(Player player, String action, ItemMeta meta, int page) {
        if (action.equals("admin_select_player")) {
            String uuidStr = meta.getPersistentDataContainer().get(homeOwnerKey, PersistentDataType.STRING);
            if (uuidStr != null) {
                org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(uuidStr));
                selectedAdminTarget.put(player.getUniqueId(), target);
                openAdminPlayerHomesGUI(player, target, 1);
            }
        } else if (action.equals("back_to_main")) {
            openMainGUI(player, 1);
        }
    }

    public void openAdminPlayerHomesGUI(Player admin, org.bukkit.OfflinePlayer target, int page) {
        Collection<Home> targetHomes = plugin.getHomeManager().getPlayerHomes(target.getUniqueId());
        
        if (targetHomes.isEmpty()) {
            databaseExecutorLoadHomes(admin, target, page);
            return;
        }

        renderAdminPlayerHomesGUI(admin, target, targetHomes, page);
    }

    private void databaseExecutorLoadHomes(Player admin, org.bukkit.OfflinePlayer target, int page) {
        plugin.getScheduler().runTaskAsync(() -> {
            List<Home> list = new ArrayList<>();
            String query = "SELECT * FROM sethomex_homes WHERE player_uuid = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, target.getUniqueId().toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                        String name = rs.getString("home_name");
                        String worldName = rs.getString("world_name");
                        double x = rs.getDouble("x");
                        double y = rs.getDouble("y");
                        double z = rs.getDouble("z");
                        float yaw = rs.getFloat("yaw");
                        float pitch = rs.getFloat("pitch");
                        String iconMatName = rs.getString("icon_material");
                        String iconTexture = rs.getString("icon_texture");
                        boolean isPublic = rs.getInt("is_public") == 1;
                        long visits = rs.getLong("visits");
                        boolean isRespawn = rs.getInt("is_respawn") == 1;
                        String category = rs.getString("category");
                        if (category == null) category = "none";
                        String description = rs.getString("description");
                        if (description == null) description = "";
                        String welcome = rs.getString("welcome_message");
                        if (welcome == null) welcome = "";

                        Material iconMat;
                        try {
                            iconMat = Material.valueOf(iconMatName);
                        } catch (IllegalArgumentException e) {
                            iconMat = Material.RED_BED;
                        }

                        Home home = new Home(uuid, name, worldName, x, y, z, yaw, pitch, iconMat, iconTexture, isPublic, visits, isRespawn);
                        home.setCategory(category);
                        home.setDescription(description);
                        home.setWelcomeMessage(welcome);
                        list.add(home);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("DB Error loading homes for admin view: " + e.getMessage());
            }

            plugin.getScheduler().runTaskAtEntity(admin, () -> {
                renderAdminPlayerHomesGUI(admin, target, list, page);
            });
        });
    }

    private void renderAdminPlayerHomesGUI(Player admin, org.bukkit.OfflinePlayer target, Collection<Home> homes, int page) {
        String targetName = target.getName() != null ? target.getName() : "Unknown";
        Component title = gm.getComponent("menus.admin-homes.title", "{player}", targetName);
        if (title.equals(Component.text("menus.admin-homes.title"))) {
            title = Component.text("§c§lAdmin §8» §fHomes de " + targetName);
        }

        List<Home> list = new ArrayList<>(homes);
        list.sort(Comparator.comparing(h -> h.getName().toLowerCase()));

        int itemsPerPage = 21;
        int totalPages = Math.max(1, (int) Math.ceil((double) list.size() / itemsPerPage));
        int finalPage = Math.max(1, Math.min(page, totalPages));

        if (totalPages > 1) {
            title = title.append(gm.getComponent("common.visuals.background-pane"))
                    .append(gm.getComponent("menus.icons.page-suffix", "{page}", String.valueOf(finalPage), "{max}", String.valueOf(totalPages)));
        }

        Inventory inv = Bukkit.createInventory(null, 45, title);
        fillBackground(inv, 45);

        int startIndex = (finalPage - 1) * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, list.size());
        int[] slots = { 10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34 };

        int sIdx = 0;
        for (int i = startIndex; i < endIndex; i++) {
            Home home = list.get(i);
            Component hName = gm.getComponent("menus.admin-homes.items.home.name", "{name}", home.getName());
            List<Component> lore = gm.getLore("menus.admin-homes.items.home.lore",
                    "{world}", home.getWorldName(),
                    "{x}", String.valueOf((int) home.getX()),
                    "{y}", String.valueOf((int) home.getY()),
                    "{z}", String.valueOf((int) home.getZ()),
                    "{status}", home.isPublic() ? "PUBLIC" : "PRIVATE");
            
            ItemStack item = createHomeItem(home, hName, lore);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "admin_interact_home");
                meta.getPersistentDataContainer().set(homeOwnerKey, PersistentDataType.STRING, home.getPlayerUuid().toString());
                meta.getPersistentDataContainer().set(homeNameKey, PersistentDataType.STRING, home.getName());
                item.setItemMeta(meta);
            }
            inv.setItem(slots[sIdx++], item);
        }

        // Info book
        inv.setItem(40, createGuiItem(Material.BOOK,
                gm.getComponent("menus.admin-homes.items.info.name", "{player}", targetName),
                gm.getLore("menus.admin-homes.items.info.lore", "{count}", String.valueOf(list.size())),
                "info"));

        // Back to players list
        inv.setItem(44, createGuiItem(Material.ARROW,
                gm.getComponent("common.buttons.back.name"),
                null,
                "back_to_admin_players"));

        if (finalPage > 1) {
            inv.setItem(39, createGuiItem(Material.ARROW,
                    gm.getComponent("common.buttons.previous-page.name"),
                    gm.getLore("common.buttons.previous-page.lore"), "prev_page"));
        }
        if (finalPage < totalPages) {
            inv.setItem(41, createGuiItem(Material.ARROW,
                    gm.getComponent("common.buttons.next-page.name"),
                    gm.getLore("common.buttons.next-page.lore"), "next_page"));
        }

        admin.openInventory(inv);
        openInventories.put(admin.getUniqueId(), InventoryType.ADMIN_HOMES);
        currentGUIPage.put(admin.getUniqueId(), finalPage);
        admin.playSound(admin.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.0f);
    }

    public void handleAdminHomesInteract(Player admin, String action, ItemMeta meta, int page, ClickType click) {
        org.bukkit.OfflinePlayer target = selectedAdminTarget.get(admin.getUniqueId());
        if (target == null) return;

        if (action.equals("admin_interact_home")) {
            String hName = meta.getPersistentDataContainer().get(homeNameKey, PersistentDataType.STRING);
            if (hName != null) {
                Home home = null;
                Collection<Home> ownHomes = plugin.getHomeManager().getPlayerHomes(target.getUniqueId());
                for (Home h : ownHomes) {
                    if (h.getName().equalsIgnoreCase(hName)) {
                        home = h;
                        break;
                    }
                }
                
                if (home == null) {
                    home = plugin.getHomeManager().getHome(target.getUniqueId(), hName);
                }

                if (home == null) return;

                if (click == ClickType.SHIFT_RIGHT) {
                    admin.closeInventory();
                    if (plugin.getHomeManager().deleteHome(target.getUniqueId(), hName, target.getName())) {
                        admin.sendMessage("§a[Admin] Le home §e" + hName + " §ade §e" + target.getName() + " §aa été supprimé.");
                        admin.playSound(admin.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                    }
                } else {
                    admin.closeInventory();
                    admin.sendMessage("§d[Admin] Téléportation vers le home de §e" + target.getName() + " §7(" + hName + ")...");
                    plugin.getTeleportManager().startTeleport(admin, home);
                }
            }
        } else if (action.equals("back_to_admin_players")) {
            openAdminPlayersGUI(admin, 1);
        }
    }

    // =========================================================================
    // ENREGISTREMENT ET ECOUTEUR D'ENTREE CHAT SÉCURISÉ
    // =========================================================================

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGH)
    public void onPlayerChat(io.papermc.paper.event.player.AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        ChatInputSession session = activeChatInputs.get(uuid);
        if (session == null) return;

        event.setCancelled(true);
        activeChatInputs.remove(uuid);
        String message = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        if (message.equalsIgnoreCase("cancel") || message.equalsIgnoreCase("annuler")) {
            player.sendMessage("§c[SethomeX] Saisie annulée.");
            plugin.getScheduler().runTaskAtEntity(player, () -> {
                if (session.type == ChatInputType.SEARCH_PUBLIC) {
                    openPublicCatalogGUI(player, 1);
                } else if (session.homeName != null) {
                    Home home = plugin.getHomeManager().getHome(player, session.homeName);
                    if (home != null) {
                        openHomeSettingsGUI(player, home);
                    } else {
                        openMainGUI(player, 1);
                    }
                } else {
                    openMainGUI(player, 1);
                }
            });
            return;
        }

        plugin.getScheduler().runTaskAtEntity(player, () -> {
            switch (session.type) {
                case SEARCH_PUBLIC:
                    currentSearchQueries.put(uuid, message);
                    openPublicCatalogGUI(player, 1, message);
                    break;
                case SET_DESCRIPTION:
                    Home homeDesc = plugin.getHomeManager().getHome(player, session.homeName);
                    if (homeDesc != null) {
                        homeDesc.setDescription(message);
                        plugin.getHomeManager().updateHomeDescription(homeDesc);
                        player.sendMessage("§a[Description] Mise à jour effectuée : §f" + message);
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                        openHomeSettingsGUI(player, homeDesc);
                    }
                    break;
                case SET_WELCOME:
                    Home homeWelcome = plugin.getHomeManager().getHome(player, session.homeName);
                    if (homeWelcome != null) {
                        homeWelcome.setWelcomeMessage(message);
                        plugin.getHomeManager().updateHomeWelcomeMessage(homeWelcome);
                        player.sendMessage("§a[Bienvenue] Message d'accueil mis à jour : §f" + message);
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                        openHomeSettingsGUI(player, homeWelcome);
                    }
                    break;
                case CREATE_CATEGORY:
                    Home homeCat = plugin.getHomeManager().getHome(player, session.homeName);
                    if (homeCat != null) {
                        String categoryName = message;
                        if (categoryName.length() > 16) {
                            categoryName = categoryName.substring(0, 16);
                        }
                        homeCat.setCategory(categoryName);
                        plugin.getHomeManager().updateHomeCategory(homeCat);
                        player.sendMessage("§a[Dossiers] Le home est maintenant dans la catégorie : §f" + categoryName);
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                        openHomeSettingsGUI(player, homeCat);
                    }
                    break;
                case SET_FEE:
                    Home homeFee = plugin.getHomeManager().getHome(player, session.homeName);
                    if (homeFee != null) {
                        try {
                            double amount = Double.parseDouble(message);
                            double maxFee = plugin.getConfig().getDouble("economy.max-visit-fee", 5000.0);
                            if (amount < 0) {
                                player.sendMessage("§c[Taxe] Le montant ne peut pas être négatif.");
                                openHomeSettingsGUI(player, homeFee);
                                return;
                            }
                            if (amount > maxFee) {
                                player.sendMessage("§c[Taxe] Le montant maximal autorisé est de " + maxFee + ".");
                                openHomeSettingsGUI(player, homeFee);
                                return;
                            }
                            homeFee.setVisitFee(amount);
                            plugin.getHomeManager().updateHomeVisitFee(homeFee);
                            player.sendMessage("§a[Taxe] La taxe de visite a été configurée à : §f" + plugin.getEconomyManager().format(amount));
                            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                            openHomeSettingsGUI(player, homeFee);
                        } catch (NumberFormatException e) {
                            player.sendMessage("§c[Taxe] Veuillez entrer un montant numérique valide.");
                            openHomeSettingsGUI(player, homeFee);
                        }
                    }
                    break;
                case SPONSOR_DAYS:
                    Home homeSponsor = plugin.getHomeManager().getHome(player, session.homeName);
                    if (homeSponsor != null) {
                        try {
                            int days = Integer.parseInt(message);
                            if (days <= 0) {
                                player.sendMessage("§c[Sponsor] Le nombre de jours doit être supérieur à 0.");
                                openHomeSettingsGUI(player, homeSponsor);
                                return;
                            }
                            double price = plugin.getConfig().getDouble("economy.sponsor-price-per-day", 1000.0) * days;
                            if (plugin.getEconomyManager().isEnabled()) {
                                if (!plugin.getEconomyManager().withdraw(player, price)) {
                                    player.sendMessage("§c[Sponsor] Solde insuffisant. Il vous faut " + plugin.getEconomyManager().format(price));
                                    openHomeSettingsGUI(player, homeSponsor);
                                    return;
                                }
                            }
                            long now = System.currentTimeMillis();
                            long currentUntil = homeSponsor.getSponsoredUntil();
                            long newUntil = Math.max(now, currentUntil) + ((long) days * 24 * 3600 * 1000);
                            homeSponsor.setSponsored(true);
                            homeSponsor.setSponsoredUntil(newUntil);
                            plugin.getHomeManager().updateHomeSponsored(homeSponsor);
                            player.sendMessage("§a[Sponsor] Votre home a été sponsorisé pour " + days + " jours !");
                            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.2f);
                            openHomeSettingsGUI(player, homeSponsor);
                        } catch (NumberFormatException e) {
                            player.sendMessage("§c[Sponsor] Veuillez entrer un nombre valide.");
                            openHomeSettingsGUI(player, homeSponsor);
                        }
                    }
                    break;
                case ADD_BAN_PLAYER:
                    Home homeBan = plugin.getHomeManager().getHome(player, session.homeName);
                    if (homeBan != null) {
                        OfflinePlayer target = Bukkit.getOfflinePlayer(message);
                        if (target == null || target.getUniqueId() == null) {
                            player.sendMessage("§c[Bans] Joueur inconnu.");
                        } else if (target.getUniqueId().equals(player.getUniqueId())) {
                            player.sendMessage("§c[Bans] Vous ne pouvez pas vous bannir vous-même.");
                        } else {
                            plugin.getHomeManager().addBan(homeBan, target.getUniqueId());
                            player.sendMessage("§a[Bans] Le joueur " + message + " a été banni.");
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.2f);
                        }
                        openBanListGUI(player, homeBan);
                    }
                    break;
            }
        });
    }

    public void openJukeboxSelectorGUI(Player player, Home home) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text("§d§lJukebox §8» §f" + home.getName()));
        fillBackground(inv, 27);
        selectedHomeForTrust.put(player.getUniqueId(), home);

        Material[] discs = {
            Material.MUSIC_DISC_13, Material.MUSIC_DISC_CAT, Material.MUSIC_DISC_BLOCKS,
            Material.MUSIC_DISC_CHIRP, Material.MUSIC_DISC_FAR, Material.MUSIC_DISC_MALL,
            Material.MUSIC_DISC_MELLOHI, Material.MUSIC_DISC_STAL, Material.MUSIC_DISC_WARD,
            Material.MUSIC_DISC_11, Material.MUSIC_DISC_WAIT, Material.MUSIC_DISC_OTHERSIDE,
            Material.MUSIC_DISC_5, Material.MUSIC_DISC_PIGSTEP
        };

        int slot = 0;
        for (Material disc : discs) {
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("§7Cliquez pour choisir ce disque"));
            inv.setItem(slot++, createGuiItem(disc, Component.text("§e" + disc.name().replace("MUSIC_DISC_", "")), lore, "music_" + disc.name()));
        }

        // Option: None
        List<Component> clearLore = new ArrayList<>();
        clearLore.add(Component.text("§7Aucune musique d'ambiance."));
        inv.setItem(22, createGuiItem(Material.BARRIER, Component.text("§c§lDésactiver la musique"), clearLore, "music_none"));

        // Back
        inv.setItem(26, createGuiItem(Material.ARROW, Component.text("§7Retour"), null, "back_to_settings"));

        player.openInventory(inv);
        openInventories.put(player.getUniqueId(), InventoryType.JUKEBOX_SELECTOR);
    }

    public void handleJukeboxInteract(Player player, String action, ItemMeta meta) {
        Home home = selectedHomeForTrust.get(player.getUniqueId());
        if (home == null) return;

        if (action.equals("back_to_settings")) {
            openHomeSettingsGUI(player, home);
            return;
        }

        if (action.startsWith("music_")) {
            String music = action.substring("music_".length());
            home.setMusicDisc(music);
            plugin.getHomeManager().updateHomeMusicDisc(home);
            player.sendMessage("§a[Musique] Ambiance mise à jour !");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.0f);
            openHomeSettingsGUI(player, home);
        }
    }

    public void openTimeWeatherSelectorGUI(Player player, Home home) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text("§e§lTemps & Météo §8» §f" + home.getName()));
        fillBackground(inv, 27);
        selectedHomeForTrust.put(player.getUniqueId(), home);

        // Time Buttons
        inv.setItem(2, createGuiItem(Material.CLOCK, Component.text("§e§lPar défaut (Serveur)"), null, "time_-1"));
        inv.setItem(3, createGuiItem(Material.GOLDEN_HELMET, Component.text("§6Aube (1000 ticks)"), null, "time_1000"));
        inv.setItem(4, createGuiItem(Material.GLOWSTONE, Component.text("§eMidi (6000 ticks)"), null, "time_6000"));
        inv.setItem(5, createGuiItem(Material.REDSTONE_LAMP, Component.text("§cCrépuscule (12000 ticks)"), null, "time_12000"));
        inv.setItem(6, createGuiItem(Material.OBSIDIAN, Component.text("§8Minuit (18000 ticks)"), null, "time_18000"));

        // Weather Buttons
        inv.setItem(11, createGuiItem(Material.WATER_BUCKET, Component.text("§b§lPar défaut (Serveur)"), null, "weather_none"));
        inv.setItem(13, createGuiItem(Material.SUNFLOWER, Component.text("§aSoleil éternel"), null, "weather_clear"));
        inv.setItem(15, createGuiItem(Material.PRISMARINE_SHARD, Component.text("§9Pluie / Neige"), null, "weather_downfall"));

        // Back
        inv.setItem(26, createGuiItem(Material.ARROW, Component.text("§7Retour"), null, "back_to_settings"));

        player.openInventory(inv);
        openInventories.put(player.getUniqueId(), InventoryType.TIME_WEATHER_SELECTOR);
    }

    public void handleTimeWeatherInteract(Player player, String action, ItemMeta meta) {
        Home home = selectedHomeForTrust.get(player.getUniqueId());
        if (home == null) return;

        if (action.equals("back_to_settings")) {
            openHomeSettingsGUI(player, home);
            return;
        }

        if (action.startsWith("time_")) {
            long time = Long.parseLong(action.substring("time_".length()));
            home.setTimeLock(time);
            plugin.getHomeManager().updateHomeTimeLock(home);
            player.sendMessage("§a[Ambiance] Heure locale verrouillée !");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.2f);
            openTimeWeatherSelectorGUI(player, home);
        } else if (action.startsWith("weather_")) {
            String weather = action.substring("weather_".length());
            home.setWeatherLock(weather);
            plugin.getHomeManager().updateHomeWeatherLock(home);
            player.sendMessage("§a[Ambiance] Météo locale verrouillée !");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.2f);
            openTimeWeatherSelectorGUI(player, home);
        }
    }

    public void openBanListGUI(Player player, Home home) {
        Inventory inv = Bukkit.createInventory(null, 45, Component.text("§c§lBans §8» §f" + home.getName()));
        fillBackground(inv, 45);
        selectedHomeForTrust.put(player.getUniqueId(), home);

        // Add Ban Button
        List<Component> addLore = new ArrayList<>();
        addLore.add(Component.text("§7Bannir un nouveau joueur."));
        inv.setItem(40, createGuiItem(Material.ANVIL, Component.text("§a§l+ Bannir un joueur"), addLore, "add_ban"));

        int slot = 0;
        for (UUID bannedUuid : home.getBannedPlayers()) {
            if (slot >= 36) break;
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(bannedUuid);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta headMeta = head.getItemMeta();
            if (headMeta instanceof org.bukkit.inventory.meta.SkullMeta skullMeta) {
                skullMeta.setOwningPlayer(offlinePlayer);
                String name = plugin.getHomeManager().getPlayerName(bannedUuid);
                skullMeta.displayName(Component.text("§e" + name));
                List<Component> headLore = new ArrayList<>();
                headLore.add(Component.text("§7Cliquez pour débannir"));
                skullMeta.lore(headLore);
                skullMeta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "unban_" + bannedUuid.toString());
                head.setItemMeta(skullMeta);
            }
            inv.setItem(slot++, head);
        }

        // Back
        inv.setItem(44, createGuiItem(Material.ARROW, Component.text("§7Retour"), null, "back_to_settings"));

        player.openInventory(inv);
        openInventories.put(player.getUniqueId(), InventoryType.BAN_LIST);
    }

    public void handleBanListInteract(Player player, String action, ItemMeta meta) {
        Home home = selectedHomeForTrust.get(player.getUniqueId());
        if (home == null) return;

        if (action.equals("back_to_settings")) {
            openHomeSettingsGUI(player, home);
            return;
        }

        if (action.equals("add_ban")) {
            player.closeInventory();
            activeChatInputs.put(player.getUniqueId(), new ChatInputSession(ChatInputType.ADD_BAN_PLAYER, home.getName()));
            player.sendMessage("§7§m--------------------------------------");
            player.sendMessage("§e§lSethomeX §8» §fBannir un joueur");
            player.sendMessage("§7Veuillez entrer le nom du joueur à bannir du home dans le chat.");
            player.sendMessage("§7Tapez §ccancel §7pour annuler.");
            player.sendMessage("§7§m--------------------------------------");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
            return;
        }

        if (action.startsWith("unban_")) {
            UUID bannedUuid = UUID.fromString(action.substring("unban_".length()));
            plugin.getHomeManager().removeBan(home, bannedUuid);
            player.sendMessage("§a[Bans] Joueur débanni !");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.2f);
            openBanListGUI(player, home);
        }
    }

    public void openVisitHistoryGUI(Player player, Home home) {
        Inventory inv = Bukkit.createInventory(null, 54, Component.text("§a§lVisites §8» §f" + home.getName()));
        fillBackground(inv, 54);
        selectedHomeForTrust.put(player.getUniqueId(), home);

        List<HomeManager.VisitRecord> history = plugin.getHomeManager().getVisitHistory(home.getPlayerUuid(), home.getName());
        int slot = 0;
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM HH:mm");
        for (HomeManager.VisitRecord record : history) {
            if (slot >= 45) break;
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(record.getVisitorName());
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta headMeta = head.getItemMeta();
            if (headMeta instanceof org.bukkit.inventory.meta.SkullMeta skullMeta) {
                skullMeta.setOwningPlayer(offlinePlayer);
                skullMeta.displayName(Component.text("§e" + record.getVisitorName()));
                List<Component> headLore = new ArrayList<>();
                headLore.add(Component.text("§7Visite le : §f" + sdf.format(new java.util.Date(record.getTimestamp()))));
                skullMeta.lore(headLore);
                skullMeta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "none");
                head.setItemMeta(skullMeta);
            }
            inv.setItem(slot++, head);
        }

        // Back
        inv.setItem(49, createGuiItem(Material.ARROW, Component.text("§7Retour"), null, "back_to_settings"));

        player.openInventory(inv);
        openInventories.put(player.getUniqueId(), InventoryType.VISIT_HISTORY);
    }

    public void handleVisitHistoryInteract(Player player, String action, ItemMeta meta) {
        Home home = selectedHomeForTrust.get(player.getUniqueId());
        if (home == null) return;

        if (action.equals("back_to_settings")) {
            openHomeSettingsGUI(player, home);
        }
    }

    public static void cleanPlayerSession(UUID uuid) {
        if (uuid == null) return;
        openInventories.remove(uuid);
        selectedHomeForIcon.remove(uuid);
        currentGUIPage.remove(uuid);
        currentSortModes.remove(uuid);
        pendingDeleteHome.remove(uuid);
        selectedHomeForTrust.remove(uuid);
        currentGUICategory.remove(uuid);
        selectedGuestForTrust.remove(uuid);
        activeChatInputs.remove(uuid);
        currentSearchQueries.remove(uuid);
        selectedAdminTarget.remove(uuid);
    }
}
