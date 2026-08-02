# Glow My Teammates

Make teammates glow for each other — and decide exactly who sees that glow, on which entities, and whether the locator bar follows the same rules.

A server-side Fabric mod for Minecraft 26.1 / 26.2 built on the vanilla `/team` system. No custom team management, no client mod required.

## What it does

- Players in the same team see each other glowing (the vanilla `0x40` glow flag, customized per viewer).
- Glow is opt-in per team: only teams you enable through `/teamglow team add` participate.
- Nothing about vanilla behavior changes until you enable something.

## Feature overview

| Feature | How to enable |
|---|---|
| Team glow for players | `/teamglow team add <team>` |
| Glow for non-player entities (mobs) | `/teamglow config non_player_glow true` |
| Locator bar: glow-enabled teams hide each other | `/teamglow config locator_bar_hide_other_glowing_teams true` |
| Fine-grained command permissions | Any LuckPerms-compatible permission mod |

## Requirements

| | Minecraft 26.1 | Minecraft 26.2 |
|---|---|---|
| Fabric Loader | >= 0.18.4 | >= 0.19.3 |
| Fabric API | any | any |
| Java | >= 25 | >= 25 |

Works on dedicated servers, singleplayer and LAN worlds. The mod also loads on the client side of a singleplayer session, but all logic runs on the integrated server — vanilla clients on a server can connect without installing anything.

## Quick start

1. Create teams with vanilla commands:
   ```
   /team add red
   /team join red @a
   ```
2. Enable glow for the team:
   ```
   /teamglow team add red
   ```
3. Done — teammates now glow for each other.

## Commands

Every command is gated by a permission node under `glow-my-teammates:command.*`, compatible with LuckPerms. Without a permission mod, commands fall back to vanilla OP checks: management commands need OP level 2, read-only commands are available to everyone.

| Command | Permission node (fallback) | Description |
|---|---|---|
| `/teamglow on` | `command.on` (OP 2) | Enable team glow globally |
| `/teamglow off` | `command.off` (OP 2) | Disable team glow globally |
| `/teamglow status` | `command.status` (all) | Show global state and enabled teams |
| `/teamglow team add <team>` | `command.team.add` (OP 2) | Enable glow for a team |
| `/teamglow team remove <team>` | `command.team.remove` (OP 2) | Disable glow for a team |
| `/teamglow team list` | `command.team.list` (all) | List teams with glow enabled |
| `/teamglow config` | `command.config` (OP 2) | Show current feature switches |
| `/teamglow config <switch> <true\|false>` | `command.config` (OP 2) | Toggle a feature switch |

### Feature switches

| Switch | Default | Effect |
|---|---|---|
| `non_player_glow` | `false` | When on, mobs that are in a glow-enabled team glow for their teammates. Note: with this on, every dirty entity-data packet of every tracked entity goes through the mod's per-packet path — keep it off on mob-dense farms unless you actually need it. |
| `locator_bar_hide_other_glowing_teams` | `false` | When on, the locator bar follows asymmetric rules: a viewer who is in a glow-enabled team **hides members of other glow-enabled teams** (competitors), while same-team members, non-glow teams and teamless players stay visible. Viewers who are not in a glow-enabled team see everyone, unchanged. |

## Config file

Stored per world at `<world>/glow-my-teammates.json`:

```json
{
  "enabled": true,
  "teams": ["red", "blue"],
  "config": {
    "locator_bar_hide_other_glowing_teams": false,
    "non_player_glow": false
  },
  "config_version": [1, 0]
}
```

- `config_version` is the disk schema version. Configs written before it existed are migrated automatically on first load — you never have to edit the file by hand.
- Edits made by commands are written atomically (temp file + atomic move); if writing fails, you are told in chat instead of silently losing the change.

## How it interacts with vanilla

- **Vanilla glowing is untouched.** Spectral arrows, potions, `/effect glowing` and `setGlowingTag` still work — the mod only adds or clears its own bit on top.
- **`/team remove <team>` cleans up immediately.** When a team is deleted, viewers stop seeing the glow right away (no stale glow until re-login).
- **No client mod needed.** The glow flag is just an entity-data bit; vanilla clients render it natively. Server-side translations (English & Simplified Chinese) mean even command feedback shows readable text on vanilla clients.
- **No self-glow in third person.** A glowing player does not see their own glow in F5 view — only teammates do. Deliberate: self always receives the no-glow variant.
- **Network footprint.** For each glowing entity, every data update sends one extra tiny packet per teammate. Negligible for small groups; on servers with dozens of players and many glowing entities (or mob glow on dense farms) the extra bandwidth adds up — keep glow enabled only for the teams that need it.

## Building from source

This project uses [Stonecutter](https://stonecutter.kikugie.dev/) to build both supported Minecraft versions from one codebase.

```bash
./gradlew build
```

Output:
- `versions/26.1/build/libs/glow-my-teammates-1.1.0+26.1.jar`
- `versions/26.2/build/libs/glow-my-teammates-1.1.0+26.2.jar`

The Server-Translations API dependency is bundled into the jar — a single jar is all you need to install.

## Built with

This mod was built with [OpenCode](https://opencode.ai) and [Claude Code](https://claude.com/claude-code) using the DeepSeek V4 model.

## License

CC0-1.0
