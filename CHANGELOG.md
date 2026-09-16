# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.2.1] - 2026-09-16

### Breaking Changes

- Minimum Fabric API for Minecraft 26.3 is now `0.160.5+26.3` (was `0.160.3+26.3`), and the 26.3 build now targets the final release instead of `26.3-rc-3`.

### Fixed

- `locator_bar_teammates_only` now re-evaluates the locator-bar connections of **non-player transmitters** — mobs whose `waypoint_transmit_range` attribute was raised above 0. The rebuild paths iterated the player list, so such an entity kept its old visibility until a connection happened to break, which a stationary mob and an AFK viewer may never cause. Team membership resolution now also covers an entity's UUID string, so joining or leaving a team by a mob triggers the same rebuild players get.

## [1.2.0] - 2026-09-11

### Breaking Changes

- `/teamglow on` and `/teamglow off` are replaced by **`/teamglow toggle`**.
- Bare `/teamglow config` no longer prints the switch list — that output moved to **`/teamglow config list`**, and `/teamglow config` on its own now reports an incomplete command.
- Setting a switch gained an explicit verb: `/teamglow config <switch> <value>` is now **`/teamglow config set <switch> <value>`**.
- `/teamglow status` reports only the global on/off state; it no longer lists enabled teams or switch values.
- Permission nodes were consolidated: `command.on` / `command.off` → `command.toggle`, and `command.team.add` / `.remove` / `.list` → `command.team`. `command.config` is unchanged. Update any LuckPerms grants.
- `/teamglow team list` now requires **OP 2**; it used to be available to everyone.
- Minimum Fabric Loader is now **0.19.3** for 26.1, 26.2 and 26.3 (26.1 previously required 0.18.4). Minimum Fabric API is `0.155.3+26.1.2` (26.1), `0.157.0+26.2` (26.2) and `0.160.3+26.3` (26.3).

### Added

- `/teamglow config list` — every switch with its current value.
- `/teamglow config get <switch>` — the current value of a single switch.
- `/teamglow config reset <switch>` — restore a switch to its default value.
- Support for **Minecraft 26.3**, built from the same codebase.

### Changed

- Development builds now carry a `-dev` version suffix, so they cannot be mistaken for release jars.

### Fixed

- `/teamglow team add` / `/teamglow team remove` suggestions now narrow as you type, matching vanilla's own team argument.
- Schema-1 config files with a null `config` object are repaired on load instead of being skipped.
- Hardened the locator-bar rebuild and non-player glow cleanup paths against stale levels and players disconnecting mid-cleanup.
- A config file that could not be **read** (temporarily locked, permission denied, unreadable disk) is no longer overwritten with defaults — the previous behavior destroyed a valid team list on a transient I/O error. The file is left untouched, defaults are used for the session, and every save is refused until a restart reads it successfully. The load path now distinguishes a *read* failure from a *parse* failure, so genuinely unparseable JSON, an empty file, or a literal `null` is still repaired on the spot as before.
- A config whose `configVersion` declares a **newer major** than this build understands now logs a warning at startup. Its fields are still parsed on a best-effort basis, but it is no longer silently rewritten as `[1, 1]` without a trace.
- A `.tmp` file orphaned by a save interrupted at the wrong moment (killed JVM, power loss) is removed once at startup instead of lingering in the world directory forever.
- The locator-bar rebuild drain is now bounded to a fixed number of rounds per tick, so a rebuild that keeps re-marking its dimension (another mod rewriting team membership from inside its own connection callbacks) can no longer spin the drain loop forever and wedge the tick. Reaching the cap defers the remainder to the next tick and logs it instead of dropping it.
- The `LICENSE` file is now actually bundled into the jar. Stonecutter builds each version as a subproject under `versions/<mc>/`, so the bare `from("LICENSE")` resolved to a nonexistent `versions/<mc>/LICENSE` and silently included nothing.

### Performance

- Config-switch changes skip the locator-bar rebuild when the filter is inert.

## [1.1.2] - 2026-08-13

### Fixed

- Teammates stayed visible after leaving a glowing team when the glowing player was constantly generating entity updates (drowning, freezing, burning, mob-farm entities) — the outline now clears immediately on the team change.
- Item frames, boats and other non-living entities with vanilla glow are handled correctly when they first come into view, matching the behavior of players and mobs.
- Locator bar (`locator_bar_teammates_only`): a player joining or leaving a glowing team now sees their own bar update right away, instead of keeping non-teammates until a connection happened to refresh on its own — which might never happen for an AFK player.
- Players switching between two glow-enabled teams no longer see everyone on their locator bar.
- The config file path in the server log no longer shows a redundant separator.

### Performance

- Fewer scoreboard lookups and fewer redundant packets per entity update.
- Dimensions with the locator bar game rule disabled now skip unnecessary connection rebuilding.

## [1.1.1] - 2026-08-03

### Changed

- **Locator bar behavior changed**: viewers in a glow-enabled team now see only their own teammates (team isolation), instead of merely hiding other glow-enabled teams.
- **Switch renamed**: `locator_bar_hide_other_glowing_teams` → `locator_bar_teammates_only` (the old command no longer works).
- Existing configs migrate automatically on first load — settings are preserved.

## [1.1.0] - 2026-08-02

### Added

- **Permission nodes** for every `/teamglow` command, compatible with LuckPerms.
- **`/teamglow config`** — view and toggle feature switches without editing files.
- **Server-side translations** — English and Simplified Chinese, working for vanilla clients.
- **Non-player entity glow** — mobs in glowing teams glow for their teammates (off by default).
- **Locator bar filter** — glow-enabled teams hide each other on the locator bar (off by default).
- **Singleplayer and LAN** support — no longer dedicated servers only.

### Fixed

- Config saves failing on Windows when the file was locked.
- Stale settings leaking between worlds from an old config file.
- Already-glowing mobs not clearing when non-player glow is turned off.
- Locator bar filter not resetting when the mod is disabled.
- Wrong "team not found" message when adding an existing team.
- Command changes being silently lost after a failed save.

### Performance

- Reduced packet allocation for glowing entities (cached overlays, zero-copy fast paths).
- Faster cleanup when toggling non-player glow.

## [1.0.3] - 2026-08-01

### Fixed

- The glow packet no longer wipes other entity flags (fall-flying, sprinting, on-fire, invisible) when sent alongside health or potion changes — teammates no longer snap out of elytra pose mid-flight.
- Config saves are crash-safe — team glow settings survive an unexpected shutdown.
- Fixed a rare crash when a player disconnects at just the wrong moment.

### Performance

- Reduced per-tick overhead: glow state checks skip unnecessary scoreboard lookups when nothing has changed.
- Entity data packet processing is roughly 60% lighter per viewer, improving TPS on larger servers.

## [1.0.2] - 2026-07-31

### Performance

- Massively reduced network traffic — glow packets are event-driven, sent only when a player enters tracking range, team membership changes, or config changes.

## [1.0.1] - 2026-07-30

### Added

- Support for **Minecraft 26.1 and 26.2** from a single codebase via the Stonecutter build system.

## [1.0.0] - 2026-07-28

### Added

- Teammates glow for each other — visible only to members of the same team.
- Fully server-side; no client mod required.
- Built on the vanilla `/team` system — no custom team management.
- `/teamglow` command to control which teams have glow enabled.
- Permission-controlled commands (OP level 2 for `/teamglow on`, `/teamglow off`, `/teamglow team add` and `/teamglow team remove`).
- Per-world config saved as `glow-my-teammates.json` in the world folder.
- Does not interfere with vanilla glowing (spectral arrows, potions).
