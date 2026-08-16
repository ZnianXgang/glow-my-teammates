# Glow My Teammates

Make teammates glow for each other — and decide exactly who sees that glow, on which entities, and whether the locator bar follows the same rules.

A server-side Fabric mod for Minecraft 26.1 / 26.2 built on the vanilla `/team` system. No custom team management, no client mod required.

## Features

- Teammates see each other glowing (the vanilla `0x40` glow flag, customized per viewer).
- Glow is opt-in per team: `/teamglow team add <team>`.
- Nothing about vanilla behavior changes until you enable something.

| Feature | How to enable |
|---|---|
| Team glow for players | `/teamglow team add <team>` |
| Glow for non-player entities (mobs) | `/teamglow config non_player_glow true` |
| Locator bar: viewers in glow-enabled teams see only teammates | `/teamglow config locator_bar_teammates_only true` |
| Fine-grained command permissions | Any LuckPerms-compatible permission mod |

## Requirements

| | Minecraft 26.1 | Minecraft 26.2 |
|---|---|---|
| Fabric Loader | >= 0.18.4 | >= 0.19.3 |
| Fabric API | any | any |
| Java | >= 25 | >= 25 |

Works on dedicated servers, singleplayer and LAN worlds; vanilla clients on a server can connect without installing anything.

## Quick start

```
/team add red
/team join red @a
/teamglow team add red
```

Done — teammates now glow for each other.

## Commands

Every command is gated by a permission node under `glow-my-teammates.command.*`, compatible with LuckPerms. Without a permission mod, management commands fall back to OP level 2, read-only commands are available to everyone.

| Command | Permission node (fallback) | Description |
|---|---|---|
| `/teamglow on` / `off` | `glow-my-teammates.command.on` / `.off` (OP 2) | Enable / disable team glow globally |
| `/teamglow status` | `glow-my-teammates.command.status` (all) | Show global state and enabled teams |
| `/teamglow team add <team>` | `glow-my-teammates.command.team.add` (OP 2) | Enable glow for a team |
| `/teamglow team remove <team>` | `glow-my-teammates.command.team.remove` (OP 2) | Disable glow for a team |
| `/teamglow team list` | `glow-my-teammates.command.team.list` (all) | List teams with glow enabled |
| `/teamglow config` | `glow-my-teammates.command.config` (OP 2) | Show feature switches |
| `/teamglow config <switch> <true\|false>` | `glow-my-teammates.command.config` (OP 2) | Toggle a feature switch |

### Feature switches

| Switch | Default | Effect |
|---|---|---|
| `non_player_glow` | `false` | Mobs in a glow-enabled team glow for their teammates. Note: every dirty entity-data packet then goes through the mod's per-packet path — keep it off on mob-dense farms unless you actually need it. |
| `locator_bar_teammates_only` | `false` | A viewer in a glow-enabled team sees only their own teammates on the locator bar; members of other teams and teamless players are hidden. Viewers outside glow-enabled teams see everyone, unchanged. |

## Config file

Stored per world at `<world>/glow-my-teammates.json` (schema `[1, 1]`):

```json
{
  "enabled": true,
  "teams": ["red", "blue"],
  "configVersion": [1, 1],
  "config": {
    "locatorBarTeammatesOnly": false,
    "nonPlayerGlow": false
  }
}
```

- Legacy configs are migrated automatically on first load — never edit the file by hand.
- Command edits are written atomically (temp file + atomic move); a failed write is reported in chat instead of silently losing the change.

## How it interacts with vanilla

- **Vanilla glowing is untouched** — spectral arrows, potions, `/effect glowing` and `setGlowingTag` still work; the mod only adds or clears its own bit on top.
- **`/team remove <team>` cleans up immediately** — no stale glow until re-login.
- **No client mod needed** — the glow flag is just an entity-data bit, vanilla clients render it natively; command feedback is translated server-side (English & Simplified Chinese).
- **No self-glow in third person** — deliberate; self always receives the no-glow variant.
- **Network footprint** — each data update sends one extra tiny packet per teammate. Negligible for small groups; on large servers keep glow enabled only for the teams that need it.

## Building from source

Uses [Stonecutter](https://stonecutter.kikugie.dev/) to build both supported versions from one codebase.

```bash
./gradlew build
```

Output: `versions/26.1/build/libs/glow-my-teammates-1.1.2+26.1.jar` and `versions/26.2/build/libs/glow-my-teammates-1.1.2+26.2.jar`. The Server-Translations API is bundled — a single jar is all you need to install.

## Built with

[OpenCode](https://opencode.ai) and [Claude Code](https://claude.com/claude-code) using the DeepSeek V4 model.

## License

CC0-1.0
