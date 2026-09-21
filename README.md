# TimedCommands

A lightweight, **server-side Fabric 26.2** mod for safely making supported Minecraft and modded commands temporary.

## Commands

Timed commands use:

`/timed <duration> <command> <arguments>`

Examples:

`/timed 5s gamemode creative Player`
`/timed 7s gamemode spectator Player`
`/timed 10m op Player`
`/timed 10m deop Player`

The command is checked against the server's live Brigadier command dispatcher first. TimedCommands only runs a command when the command is actually registered on the server **and** a safe rollback handler exists for it.

Current built-in timed handlers:

- `gamemode` — saves and restores each target's previous gamemode.
- `op` — temporarily grants operator status, then restores the player's previous operator state.
- `deop` — temporarily removes operator status, then restores the player's previous operator state.

The `op` and `deop` handlers require the administrator permission. The base `/timed` command requires the moderator permission.

## Command scanning

`/timed scan`

Scans the live server command dispatcher and reports how many command roots are registered and how many currently have TimedCommands rollback handlers.

`/timed supported`

Lists the registered commands that currently have safe TimedCommands handlers.

This means commands added by other Fabric mods are detected automatically as registered commands, but they are not automatically considered safe to time. A mod can integrate its own reversible handler through:

`TimedCommands.registerTimedHandler("mycommand", handler)`

That design avoids guessing how an arbitrary command should be undone.

## Timer management

`/timed list`

Shows active timers and their remaining time.

`/timed cancel <player>`

Cancels all timers for the player and restores the saved state where a rollback is available.

Timers continue while a player is offline. For temporary gamemodes, the saved state is restored when the player returns after the timer expires.

## Duration format

- `s` = seconds
- `m` = minutes
- `h` = hours
- `t` = ticks
- A plain number is treated as seconds.

Examples:

`6s` = 6 seconds  
`2m` = 2 minutes  
`1h` = 1 hour  
`100t` = 100 ticks  
`600` = 600 seconds

The maximum duration is 7 days.

## Server-side

TimedCommands is explicitly marked as a **server-only** mod. Players do not need to install the mod on their clients to use a server running it.

## Requirements

- Minecraft Java Edition 26.2
- Fabric Loader 0.19.5+
- Fabric API 0.161.0+26.2
- Java 25+

## License

MIT
