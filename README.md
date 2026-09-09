# Glow My Teammates

> **Teammates glow for each other** — a server-side Fabric mod built on the vanilla `/team` system. No client mod, no custom team management, works on dedicated servers, singleplayer and LAN.

<details>
<summary>💡 What does it look like?</summary>

Members of an enabled team see their teammates outlined in a bright glow, from any distance. Non-teammates see nothing different. The glow is rendered natively by the game — every vanilla client supports it out of the box.
</details>

## ✨ Features

- **Per-viewer glow** — every player sees their own teammates glowing; nobody else does.
- **Opt-in per team** — one command enables glow for a team; nothing about vanilla behavior changes until you enable something.
- **Mobs can glow too** — with `non_player_glow`, mobs in a glow-enabled team glow for their teammates (off by default).
- **Locator-bar filtering** — optionally, viewers in glow-enabled teams see *only their teammates* on the locator bar.
- **Fine-grained permissions** — every command gated by a permission node, compatible with LuckPerms.
- **Vanilla-friendly** — the glowing effect and an entity's `Glowing` tag are never touched. Removing a team via `/team remove` cleans up instantly — no stale glow.

## 📦 Requirements

| | Minecraft 26.1 | Minecraft 26.2 |
|---|---|---|
| Fabric Loader | >= 0.19.3 | >= 0.19.3 |
| Fabric API | >= 0.155.3+26.1.2 | >= 0.157.0+26.2 |

Install like any Fabric mod: drop the jar into the server's `mods/` folder. **Server-side only** — players with vanilla clients can connect without installing anything. Command feedback is translated server-side (English & Simplified Chinese).

## 🚀 Quick start

```
/team add red
/team join red @a
/teamglow team add red
```

Done — members of `red` now glow for each other.

## 🛠️ Commands

Every command is gated by a permission node under `glow-my-teammates.command.*` (compatible with LuckPerms). Without a permission mod, management commands fall back to OP level 2; `/teamglow status` is available to everyone.

| Command | Permission node (fallback) | Description |
|---|---|---|
| `/teamglow status` | `glow-my-teammates.command.status` (all) | Show whether team glow is enabled |
| `/teamglow toggle` | `glow-my-teammates.command.toggle` (OP 2) | Toggle team glow globally |
| `/teamglow team list` | `glow-my-teammates.command.team` (OP 2) | List teams with glow enabled |
| `/teamglow team add <team>` | `glow-my-teammates.command.team` (OP 2) | Enable glow for a team (with autocompletion) |
| `/teamglow team remove <team>` | `glow-my-teammates.command.team` (OP 2) | Disable glow for a team (with autocompletion) |
| `/teamglow config list` | `glow-my-teammates.command.config` (OP 2) | List feature switches and their values |
| `/teamglow config get <switch>` | `glow-my-teammates.command.config` (OP 2) | Show one feature switch |
| `/teamglow config set <switch> <true\|false>` | `glow-my-teammates.command.config` (OP 2) | Set a feature switch |
| `/teamglow config reset <switch>` | `glow-my-teammates.command.config` (OP 2) | Reset a feature switch to its default |

> **Upgrading from 1.1.x?** Permission nodes were consolidated — `command.on` / `.off` became `command.toggle`, and `command.team.add` / `.remove` / `.list` became `command.team`. Update any LuckPerms grants; `command.config` is unchanged.

### Feature switches

| Switch | Default | Effect |
|---|---|---|
| `non_player_glow` | `false` | Mobs in a glow-enabled team glow for their teammates. Note: every dirty entity-data packet then goes through the mod's per-packet path — keep it off on mob-dense farms unless you actually need it. |
| `locator_bar_teammates_only` | `false` | A viewer in a glow-enabled team sees only their own teammates on the locator bar; members of other teams and teamless players are hidden. Viewers outside glow-enabled teams see everyone, unchanged. |

## ⚙️ Configuration

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

## 🤔 Behaviour notes

- **Vanilla glowing is untouched** — the glowing effect and an entity's `Glowing` tag still work; the mod only adds or clears its own bit on top.
- **`/team remove <team>` cleans up immediately** — no stale glow until re-login.
- **No self-glow in third person** — deliberate; players always receive the no-glow variant for themselves.
- **Network footprint** — each data update sends one extra tiny packet per teammate. Negligible for small groups; on large servers keep glow enabled only for the teams that need it.

## 🧱 Building from source

Uses [Stonecutter](https://stonecutter.kikugie.dev/) to build both supported versions from one codebase.

```bash
# Dev build (default) — jar version carries a "-dev" suffix
./gradlew build
# Release build — clean version, no suffix
./gradlew build -Prelease
```

Output: `versions/<mc>/build/libs/glow-my-teammates-<version>+<mc>.jar`, e.g. dev `glow-my-teammates-1.2.0-dev+26.2.jar` or release `glow-my-teammates-1.2.0+26.2.jar`. The Server-Translations API is bundled — a single jar is all you need to install.

## 📋 Changelog

See [CHANGELOG.md](CHANGELOG.md) for release notes and breaking changes.

## 📜 License

CC0-1.0 — do whatever you want with it.
