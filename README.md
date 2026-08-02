# Glow My Teammates

Team members glow for each other — visible only to teammates.

## Background

I was playing a custom map that needed teammate glow for better gameplay, but couldn't find any existing mod that does this. So I built it with AI.

## Features

- Server-side logic — works on dedicated servers, singleplayer and LAN (no client mod needed)
- Uses vanilla `/team` command system for team management
- `/teamglow` command controls which teams have glow enabled
- Config stored in world save folder (`glow-my-teammates.json`), schema-versioned (`config_version`)
- Does not interfere with vanilla glowing (spectral arrows, potions, etc.)
- Supports Minecraft 26.1 and 26.2
- `/team remove` clears glow state immediately — no stale glow left on clients
- Config is saved atomically; save failures are reported to the admin in chat
- Membership changes in non-glow teams don't trigger unnecessary server-wide resyncs
- **Permission nodes** via Fabric API `permission.v1` (LuckPerms-compatible), falling back to vanilla OP levels
- **Server-side translations** — messages are translated on the server, works for vanilla clients
- **Non-player entity glow** — mobs in glowing teams glow (behind the `non_player_glow` switch)
- **Locator bar filter** — glow-enabled teams hide each other on the locator bar (behind the `locator_bar_teammates_only` switch)

## Commands

Every command is backed by a permission node (`glow-my-teammates:command/...`), compatible with LuckPerms. Without a permission mod, commands fall back to vanilla OP levels — management commands require OP level 2, status/list require nothing.

| Command | Permission node (fallback) | Description |
|---|---|---|
| `/teamglow on` | `command/on` (OP 2) | Enable team glow globally |
| `/teamglow off` | `command/off` (OP 2) | Disable team glow globally |
| `/teamglow status` | `command/status` (everyone) | Show current state and enabled teams |
| `/teamglow team add <team>` | `command/team/add` (OP 2) | Enable glow for a team |
| `/teamglow team remove <team>` | `command/team/remove` (OP 2) | Disable glow for a team |
| `/teamglow team list` | `command/team/list` (everyone) | List all teams with glow enabled |
| `/teamglow config list` | `command/config` (OP 2) | Show feature switches |
| `/teamglow config <switch> <true\|false>` | `command/config` (OP 2) | Toggle a feature switch |

### Feature switches

| Switch | Default | Effect |
|---|---|---|
| `non_player_glow` | `false` | When on, mobs in glowing teams glow too (mob-dense farms pay per-dirty-packet overhead — keep off unless needed) |
| `locator_bar_teammates_only` | `false` | When on, a viewer in a glow-enabled team hides members of other glow-enabled teams on the locator bar; same-team, non-glow and teamless players stay visible. Viewers outside glow teams see everyone |

## Usage

1. Create teams and add members with vanilla commands: `/team add <name>`, `/team join <name>`
2. Enable glow: `/teamglow team add <name>`
3. Teammates automatically see each other glowing
4. Optional: turn on mob glow (`/teamglow config non_player_glow true`) and/or the locator bar filter (`/teamglow config locator_bar_teammates_only true`)

## Config

Located in the world save folder: `<world>/glow-my-teammates.json`

```json
{
  "enabled": true,
  "teams": ["red", "blue"],
  "config": {
    "locator_bar_teammates_only": false,
    "non_player_glow": false
  },
  "config_version": [1, 0]
}
```

Legacy configs (without `config_version`) are migrated automatically on first load.

## Dependencies

| | Minecraft 26.1 | Minecraft 26.2 |
|---|---|---|
| Fabric Loader | >= 0.18.4 | >= 0.19.3 |
| Fabric API | Any | Any |
| Java | >= 25 | >= 25 |

The [Server-Translations API](https://maven.nucleoid.xyz/xyz/nucleoid/server-translations-api/) is bundled into the mod jar — no extra install needed.

## Building

This mod uses [Stonecutter](https://stonecutter.kikugie.dev/) to target multiple Minecraft versions from a single codebase.

```bash
# Build all versions
./gradlew build
```

Output:
- `versions/26.1/build/libs/glow-my-teammates-1.1.0+26.1.jar`
- `versions/26.2/build/libs/glow-my-teammates-1.1.0+26.2.jar`

## Changelog

**v1.1.0** — all roadmap features implemented:
- Permission nodes (Fabric API `permission.v1`, LuckPerms-compatible)
- Removed `§` format codes in favor of `Component` styles
- `/teamglow config` sub-command + schema versioning (`config_version`)
- Server-side translations (NucleoidMC Server-Translations)
- Non-player entity glow (behind `non_player_glow` switch)
- Locator bar filter (behind `locator_bar_teammates_only` switch)

Development happens on the `future-plan` branch and lands on `main` feature by feature.

## Author

ZnianXgang

Built with [OpenCode](https://opencode.ai) using DeepSeek V4.

## License

CC0-1.0
