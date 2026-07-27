# Glow My Teammates

Team members glow for each other — visible only to teammates.

## Background

I was playing a custom map that needed teammate glow for better gameplay, but couldn't find any existing mod that does this. So I built it with AI.

## Features

- Server-side only, no client installation needed
- Uses vanilla `/team` command system for team management
- `/teamglow` command controls which teams have glow enabled
- Config stored in world save folder (`glow-my-teammates.json`)
- Does not interfere with vanilla glowing (spectral arrows, potions, etc.)

## Commands

`/teamglow` requires OP level 2 for management commands. Status/list commands are available to all players.

| Command | Permission | Description |
|---|---|---|
| `/teamglow on` | OP 2 | Enable team glow globally |
| `/teamglow off` | OP 2 | Disable team glow globally |
| `/teamglow status` | Everyone | Show current state and enabled teams |
| `/teamglow team add <team>` | OP 2 | Enable glow for a team |
| `/teamglow team remove <team>` | OP 2 | Disable glow for a team |
| `/teamglow team list` | Everyone | List all teams with glow enabled |

## Usage

1. Create teams and add members with vanilla commands: `/team add <name>`, `/team join <name>`
2. Enable glow: `/teamglow team add <name>`
3. Teammates automatically see each other glowing

## Config

Located in the world save folder: `<world>/glow-my-teammates.json`

```json
{
  "enabled": true,
  "teams": ["red", "blue"]
}
```

## Dependencies

- Minecraft 26.2
- Fabric Loader >= 0.19.3
- Fabric API
- Java >= 25

## Author

ZnianXgang

Built with [OpenCode](https://opencode.ai) using DeepSeek V4.

## License

CC0-1.0
