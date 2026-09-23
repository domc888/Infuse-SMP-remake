# InfuseSMP

Paper 1.21.11 / Java 21 Infuse-style SMP plugin.

## Build
Use GitHub Actions -> Actions -> Manual JAR Builder -> Run workflow.

The resulting JAR is uploaded as the workflow artifact InfuseSMP-1.21.11.

## Commands
- /lspark and /rspark: activate slot 1/2
- /ldrain and /rdrain: drain slot 1/2
- /swap: swap slots
- /trust and /untrust: manage trusted players
- /controls: toggle command/offhand control mode
- /infuse give <effect> [augmented]: admin test command
- /infuse clear <player>: admin reset
- /infuse cooldown <player>: admin cooldown reset

Effects: emerald, ender, feather, fire, frost, haste, heart, invis, ocean, regen, speed, strength, thunder, apophis, thief.

This is an independent reimplementation of documented Infuse-style mechanics targeting Paper 1.21.11.