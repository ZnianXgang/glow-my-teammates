# AGENTS.md — Glow My Teammates

## Project Overview

Server-side Fabric mod (Minecraft 26.2) that makes teammates glow for each other. Uses vanilla `/team` system — no custom team management.

## Tech Stack

- **Minecraft**: 26.2 (Mojang mappings, no Yarn)
- **Fabric Loader**: 0.19.3+
- **Fabric API**: 0.155.2+ (includes `fabric-permission-api-v1`)
- **Java**: 25
- **Build**: Gradle 9.5.1 + Fabric Loom 1.17

## Project Structure

```
src/main/java/com/glow/teammates/
├── GlowMyTeammates.java          Mod entry (ModInitializer)
├── config/GlowConfigManager.java  World-save JSON config
├── command/GlowCommand.java       /teamglow command
└── mixin/
    ├── EntityAccessor.java        @Accessor for Entity.DATA_SHARED_FLAGS_ID
    └── ServerEntityMixin.java     Core: per-client glow via Mixin
```

## How It Works

Two Mixin injection points on `ServerEntity`:

1. **`@ModifyVariable`** on `synchEntityData.packDirty()` return value:
   - `packDirty()` returns `null` when no data changed → inject current shared flags
   - Ensures packet always created for Player entities
   - Transition ON→OFF sends one cleanup packet (`wasSyncing` flag)

2. **`@Redirect`** on `Synchronizer.sendToTrackingPlayersAndSelf()`:
   - Replaces single broadcast with two `sendToTrackingPlayersFiltered` calls
   - Teammates: packet with glow bit set (0x40)
   - Non-teammates: packet with glow bit cleared
   - Checks `Scoreboard.getPlayersTeam()` for team membership
   - Skips when vanilla `GLOWING` effect active (spectral arrows, potions)

## Key 26.2 API Differences

| Old (1.21.x) | New (26.2) |
|---|---|
| `Entity.broadcast(Packet)` | `Synchronizer.sendToTrackingPlayersFiltered(Packet, Predicate)` |
| `ServerEntity.seenBy` (Set) | Tracking managed internally by Synchronizer |
| `hasPermission(int)` | `Commands.LEVEL_GAMEMASTERS.check(permissions())` |
| `GameProfile.getName()` | `Entity.getScoreboardName()` |

## Building

```bash
./gradlew build          # Compile + JAR
```

Output: `build/libs/glow-my-teammates-1.0.0.jar`

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

- Do NOT use `Entity.getServer()` — removed in 26.2. Use `entity.level().getScoreboard()` instead.
- Do NOT toggle entity data directly — corrupts state. Inject missing packets via `@ModifyVariable`.
- The `Synchronizer` inner interface is importable as `ServerEntity.Synchronizer`.
- No Yarn mappings. All class/method names are Mojang.
