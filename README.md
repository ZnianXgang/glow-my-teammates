# Glow My Teammates

Team members glow for each other — visible only to teammates.

## Features

- Server-side only, no client installation needed
- Uses vanilla `/team` command system for team management
- `/teamglow` command controls which teams have glow enabled
- Config stored in world save folder (`glow-my-teammates.json`)
- Does not interfere with vanilla glowing (spectral arrows, potions, etc.)

## Commands

| Command | Description |
|---|---|
| `/teamglow on` | Enable team glow globally |
| `/teamglow off` | Disable team glow globally |
| `/teamglow status` | Show current state and enabled teams |
| `/teamglow team add <team>` | Enable glow for a team |
| `/teamglow team remove <team>` | Disable glow for a team |
| `/teamglow team list` | List all teams with glow enabled |

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
