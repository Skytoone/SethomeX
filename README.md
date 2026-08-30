# 🏠 SethomeX

<div align="center">

[![Plugin Version](https://img.shields.io/badge/Version-1.0.7-FFD700?style=for-the-badge&logo=minecraft)](https://github.com/Skytoone/SethomeX)
[![Java Version](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk)](https://www.oracle.com/java/)
[![Platform](https://img.shields.io/badge/Platform-Paper%20%7C%20Spigot%20%7C%20Folia-005B9A?style=for-the-badge)](https://papermc.io/)
[![License](https://img.shields.io/badge/License-GPLv3-4CAF50?style=for-the-badge)](https://www.gnu.org/licenses/gpl-3.0.html)
[![Discord](https://img.shields.io/badge/Discord-Join-5865F2?style=for-the-badge&logo=discord)](https://discord.gg/3QzcDHC6)

**The ultimate next-generation home management plugin for Paper, Spigot, and Folia servers.**

*3D Particle Warmups • Interactive GUI • Cross-Server SQL Sync • Claims Protection • Web Map Integration*

</div>

---

## 🌟 What is SethomeX?

**SethomeX** elevates your Minecraft server's home teleportation mechanics into a modern, tactile visual experience. Moving away from boring text-based home commands, SethomeX introduces a sleek **GUI interface**, custom **GUI icon pickers**, procedural **3D particle teleport warmups**, **safety matrices** to prevent lava/void deaths, and **cross-server synchronization** powered by HikariCP database pooling.

Designed from the ground up for high-performance servers, SethomeX natively supports **Folia** multi-threading, **PlaceholderAPI**, web maps (**Dynmap**, **BlueMap**, **Squaremap**), land claim plugins, and one-click data importers from legacy plugins.

---

## 🎮 Core Features

### ✨ Visual & Interactive Design
| Feature | Description |
|---|---|
| **Modern Home GUI** | Responsive, paginated inventory GUI to view, teleport to, rename, and manage all your homes |
| **GUI Icon Selector** | Custom icon picker menu allowing players to assign personalized item textures/heads to each home |
| **3D Particle Warmups** | Procedural particle shapes (Spiral, Ring, Helix, Shield) swirling around the player during countdown |
| **Spatial Audio & BossBars** | Dynamic sound cues and visual bossbar countdowns during teleportation warmups |
| **Respawn at Home** | Option to automatically respawn players at their primary home upon death |
| **Home Signs** | Interactive `[Home]` signs for direct click-to-teleport |

### 🛡️ Safety & Protection
| Feature | Description |
|---|---|
| **Hardened Safety Matrix** | Scans destination coordinates to prevent warping into lava, suffocation blocks, or the void |
| **Smart Interruption** | Cancels teleport warmups on player movement, taken damage, or active combat |
| **Land Claims Integration** | Respects region boundaries in **WorldGuard**, **GriefPrevention**, **Towny**, **Lands**, and **Residence** |
| **Permission-Based Limits** | Tiered home limits linked directly to LuckPerms/vault permission nodes (e.g., `sethomex.limit.vip`) |

### ⚡ Performance & Infrastructure
| Feature | Description |
|---|---|
| **Cross-Server Sync** | High-performance MySQL/MariaDB integration via **HikariCP** for multi-server proxy networks |
| **Local Database Engine** | Fast, zero-dependency SQLite database for standalone servers |
| **Folia Native** | Full multi-threaded regionized scheduling support for Folia 1.20+ |
| **Caffeine Caching** | High-speed memory cache for instant home lookups with zero main-thread lag |
| **1-Click Data Importers** | Import existing home data seamlessly from **EssentialsX**, **CMI**, **BetterHomes**, **Sunlight**, or **UltimateHomes** |

---

## 🗺️ Web Map Integrations

SethomeX automatically projects player homes onto interactive web maps:

| Plugin | Feature |
|---|---|
| 🗺️ **Dynmap** | Custom map marker layer for player homes |
| 🌐 **BlueMap** | 3D web map markers with custom icons |
| 🟩 **Squaremap** | High-performance 2D canvas marker layer |

*Markers can be globally toggled or restricted via configuration.*

---

## 🛠️ Commands & Permissions

| Command | Aliases | Description | Default Permission |
|---|---|---|---|
| `/home [name]` | `/h` | Teleport to a home or open the GUI | `sethomex.use` |
| `/sethome <name> [--respawn]` | `/sh` | Set a new home at your location (optionally as primary respawn) | `sethomex.set` |
| `/delhome <name>` | `/dh`, `/rmhome` | Delete an existing home | `sethomex.delete` |
| `/home invite <player> [home]` | | Send a temporary 60s home visit invitation | `sethomex.use` |
| `/home accept [player]` | | Accept a pending home invitation with interactive chat button | `sethomex.use` |
| `/home setrespawn <name>` | | Designate a home as your primary respawn location | `sethomex.set` |
| `/homes` | | Open the visual home management GUI | `sethomex.use` |
| `/sethomex` | `/shx`, `/seth` | Main plugin command / info | None |
| `/shx reload` | | Reload configuration and messages | `sethomex.admin` |
| `/shx import <plugin>` | | Import homes (`essentials`, `cmi`, `betterhomes`, `sunlight`, `ultimatehomes`) | `sethomex.admin` |
| `/shx list [player]` | | View homes of a specific player (Admin) | `sethomex.admin` |

### Key Permissions
| Permission Node | Description |
|---|---|
| `sethomex.use` | Basic permission to use `/home` and open GUI |
| `sethomex.set` | Permission to create homes via `/sethome` |
| `sethomex.delete` | Permission to delete homes via `/delhome` |
| `sethomex.limit.<group>` | Grants home limit defined in config (e.g. `sethomex.limit.vip`) |
| `sethomex.limit.unlimited` | Bypass home quantity limits |
| `sethomex.bypass.cooldown` | Bypass teleport cooldown timers |
| `sethomex.bypass.warmup` | Instant teleport without warmup countdown |
| `sethomex.bypass.claims` | Set homes inside protected land claims |
| `sethomex.bypass.safety` | Bypass destination safety checks |
| `sethomex.admin` | Full administrative access |

---

## 📊 PlaceholderAPI Support

SethomeX provides native placeholders for scoreboards, chat formats, and tab lists:

| Placeholder | Description |
|---|---|
| `%sethomex_count%` | Number of homes currently owned by the player |
| `%sethomex_max%` | Maximum allowed homes for the player's rank |
| `%sethomex_list%` | Formatted comma-separated list of home names |
| `%sethomex_home_name_<id>%` | Name of the home at index `<id>` |
| `%sethomex_home_world_<name>%` | World name of a specific home |

---

## 💻 Import Utilities

Import your legacy homes seamlessly:

```bash
/shx import essentials       # Import homes from EssentialsX
/shx import cmi              # Import homes from CMI
/shx import betterhomes      # Import homes from BetterHomes
/shx import sunlight         # Import homes from Sunlight
/shx import ultimatehomes    # Import homes from UltimateHomes
```

---

## 💻 Developer API & Events

SethomeX exposes a clean developer API through the `SethomeX-API` module, published on **JitPack**.

### Adding the Dependency

#### Maven (`pom.xml`)
```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.Skytoone</groupId>
        <artifactId>SethomeX-API</artifactId>
        <version>1.0.7</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

#### Gradle (`build.gradle`)
```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    compileOnly 'com.github.Skytoone:SethomeX-API:1.0.7'
}
```

---

### Obtaining the API Instance

```java
import fr.skynex.sethomex.api.SethomeXAPI;
import fr.skynex.sethomex.api.util.SethomeXHook;

// Recommended: via SethomeXHook (null-safe Optional)
SethomeXHook.getAPI().ifPresent(api -> {
    // use api here
});

// Alternative: via Bukkit Services Manager
SethomeXAPI api = getServer().getServicesManager()
    .load(SethomeXAPI.class);
```

---

### 🔧 API Method Reference

```java
// Synchronous API Methods
Optional<Home> home = api.getHome(player.getUniqueId(), "base");
List<Home> homes = api.getHomes(player.getUniqueId());
boolean setSuccess = api.setHome(player.getUniqueId(), "shop", location);
boolean deleteSuccess = api.deleteHome(player.getUniqueId(), "old_base");
boolean tpStarted = api.teleportToHome(player, "base");
boolean exists = api.hasHome(player.getUniqueId(), "base");
int count = api.getHomeCount(player.getUniqueId());
int limit = api.getMaxHomes(player);
List<Home> publicHomes = api.getPublicHomes();
boolean isTrusted = api.isTrusted(home, friendUuid);
boolean isBanned = api.isBanned(home, enemyUuid);

// Asynchronous API Methods (CompletableFuture)
api.getHomeAsync(playerUuid, "base").thenAccept(optHome -> {
    optHome.ifPresent(h -> System.out.println("Found home: " + h.getName()));
});
api.getHomesAsync(playerUuid).thenAccept(homesList -> { ... });
api.setHomeAsync(playerUuid, "shop", location).thenAccept(success -> { ... });
api.deleteHomeAsync(playerUuid, "old_base").thenAccept(success -> { ... });
```

---

### 📡 Custom Events

SethomeX fires three cancellable Bukkit events for developers:

#### `PlayerSetHomeEvent`
Fired when a player attempts to create a new home point.

```java
@EventHandler
public void onSetHome(PlayerSetHomeEvent event) {
    Player player = event.getPlayer();
    Home home = event.getHome();

    if (home.getLocation().getWorld().getName().equalsIgnoreCase("spawn")) {
        event.setCancelled(true);
        player.sendMessage("You cannot set homes in spawn!");
    }
}
```

#### `PlayerTeleportHomeEvent`
Fired when a player initiates a home teleportation.

```java
@EventHandler
public void onHomeTeleport(PlayerTeleportHomeEvent event) {
    Player player = event.getPlayer();
    Home home = event.getHome();

    // Custom logic before warmup starts
}
```

#### `PlayerDeleteHomeEvent`
Fired when a home is deleted.

```java
@EventHandler
public void onDeleteHome(PlayerDeleteHomeEvent event) {
    Player player = event.getPlayer();
    Home home = event.getHome();
}
```

---

## ⚙️ Building from Source

### Prerequisites
- **JDK 21** or higher
- **Apache Maven 3.8+**

### Compilation
```bash
git clone https://github.com/Skytoone/SethomeX.git
cd SethomeX
mvn clean package
```

The compiled plugin JAR will be available at `target/SethomeX-1.0.7.jar`.

### Project Structure
```
SethomeX/
├── SethomeX-API/           # Public API module (events, interface, hook, models)
│   └── src/main/java/fr/skynex/sethomex/
│       ├── api/
│       │   ├── SethomeXAPI.java                 # Main API interface
│       │   ├── util/SethomeXHook.java           # Safe API accessor
│       │   └── events/
│       │       ├── PlayerSetHomeEvent.java
│       │       ├── PlayerTeleportHomeEvent.java
│       │       └── PlayerDeleteHomeEvent.java
│       └── models/Home.java                    # Home data model
└── SethomeX-Core/          # Plugin implementation module
    └── src/main/java/fr/skynex/sethomex/
        ├── SethomeX.java                       # Main plugin class & API impl
        ├── commands/
        ├── gui/
        ├── integration/
        ├── listeners/
        ├── managers/
        ├── storage/
        └── util/
```

---

## 📄 License

SethomeX is licensed under the [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html).

