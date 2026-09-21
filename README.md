# TimedCommands

A lightweight Fabric 26.2 server-side mod for temporary command effects.

## Temporary gamemode

Use:

`/timed <duration> gamemode <mode> <targets>`

Examples:

`/timed 5s gamemode creative Player`
`/timed 7s gamemode spectator Player`
`/timed 30s gamemode survival @a`

The mod saves each target's current gamemode, changes it immediately, and restores the saved gamemode when the timer expires.

Duration supports seconds (`s`), minutes (`m`), ticks (`t`), or a plain number interpreted as seconds.

Only moderators with the normal Minecraft command permission can use `/timed`.

## Requirements

- Minecraft Java Edition 26.2
- Fabric Loader 0.19.5+
- Fabric API 0.161.0+26.2
- Java 25+

## License

MIT
