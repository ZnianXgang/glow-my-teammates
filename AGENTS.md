# AGENTS.md — Glow My Teammates

## Project Overview

Server-side Fabric mod that makes teammates glow for each other. Uses vanilla `/team` system — no custom team management. Supports **Minecraft 26.1 and 26.2** via Stonecutter multi-version build system.

## Tech Stack

- **Minecraft**: 26.1, 26.2 (Mojang mappings, no Yarn)
- **Fabric Loader**: 0.18.4+ (26.1) / 0.19.3+ (26.2)
- **Fabric API**: 0.155.2+
- **Java**: 25
- **Build**: Gradle 9.5.1 + Fabric Loom 1.17 + Stonecutter 0.9.7

## Project Structure

```
├── settings.gradle               Stonecutter plugin + version definitions
├── build.gradle                  Shared build script (version-aware via sc.current)
├── gradle.properties              Mod-level properties (mod_version, maven_group)
├── versions/
│   ├── 26.1/gradle.properties     MC 26.1 dependency versions
│   └── 26.2/gradle.properties     MC 26.2 dependency versions
└── src/main/java/com/glow/teammates/
    ├── GlowMyTeammates.java        Mod entry (ModInitializer)
    ├── config/GlowConfigManager.java  World-save JSON config
    ├── command/GlowCommand.java     /teamglow command
    └── mixin/
        ├── EntityAccessor.java      @Accessor for Entity.DATA_SHARED_FLAGS_ID
        └── ServerEntityMixin.java   Core: per-client glow via Mixin (version-gated)
```

## How It Works

Two Mixin injection points on `ServerEntity`:

1. **`@ModifyVariable`** on `SynchedEntityData.packDirty()` return value:
   - `packDirty()` returns `null` when no data changed → inject current shared flags
   - Ensures packet always created for Player entities
   - Transition ON→OFF sends one cleanup packet (`wasSyncing` flag)

2. **`@Redirect`** on `Synchronizer.sendToTrackingPlayersAndSelf()`:
   - **26.2**: Uses `sendToTrackingPlayersFiltered(Packet, Predicate)` for engine-level per-client filtering
   - **26.1**: Uses `sendToTrackingPlayersAndSelf()` + manual player iteration (fallback)
   - Teammates: packet with glow bit set (0x40)
   - Non-teammates: packet with glow bit cleared
   - Checks `Scoreboard.getPlayersTeam()` for team membership
   - Skips when vanilla `GLOWING` effect active (spectral arrows, potions)

## Stonecutter — Version Management

This project uses [Stonecutter](https://stonecutter.kikugie.dev/) to maintain a single codebase targeting multiple Minecraft versions.

### Key Concepts

- **VCS version** (26.2): The canonical source in the repository. Commit from this version.
- **Active version**: The version currently open in the IDE. Switch with the Gradle task.
- **Versioned comments**: `//? if 26.2 { ... } //?} else { ... }` gates code per version.

### Common Commands

```bash
# Build all versions
./gradlew build

# Switch active version in IDE
./gradlew setActiveVersion -Pversion=26.1

# Reset source to VCS version (run before committing!)
./gradlew resetActiveVersion
```

### Adding a New Version

1. Add to `settings.gradle`: `versions '26.1', '26.2', '26.3'`
2. Create `versions/26.3/gradle.properties` with the correct dependencies
3. Add version-gated code blocks as needed: `//? if >=26.3 { ... }`

### Current Version Differences

| API | 26.1 | 26.2 |
|---|---|---|
| Per-client packet send | `sendToTrackingPlayersAndSelf` + manual loop | `sendToTrackingPlayersFiltered(Packet, Predicate)` |
| Permission check | `Commands.LEVEL_GAMEMASTERS.check(permissions())` | Same |
| Scoreboard access | `entity.level().getScoreboard()` | Same |
| Identifier factory | `Identifier.fromNamespaceAndPath()` | Same |

## Building

```bash
./gradlew build
```

Output JARs:
- `versions/26.2/build/libs/glow-my-teammates-1.0.1+26.2.jar`
- `versions/26.1/build/libs/glow-my-teammates-1.0.1+26.1.jar`

## Config

Per-world JSON at `<world>/glow-my-teammates.json`:

```json
{
  "enabled": true,
  "teams": ["red", "blue"]
}
```

## Commands

```
/teamglow on|off|status
/teamglow team add|remove|list <team>
```

OP level 2 required for on/off/add/remove.

## Rules for Contributors

- **Do NOT use `Entity.getServer()`** — removed in 26.1+. Use `entity.level().getScoreboard()`.
- **Do NOT toggle entity data directly** — corrupts state. Inject missing packets via `@ModifyVariable`.
- The `Synchronizer` inner interface is importable as `ServerEntity.Synchronizer`.
- **No Yarn mappings.** All class/method names are Mojang (official).
- **Always commit from VCS version (26.2).** Run `resetActiveVersion` before committing to avoid Stonecutter preprocessor noise in Git history.
- **New version-gated code**: Use `//? if <version> { ... }` syntax. Avoid raw `/* */` comments for versioning.
