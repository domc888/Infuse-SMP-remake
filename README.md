# Infuse SMP Season 1 recreation

Paper 1.21.11 / Java 21 plugin, independently implemented from the publicly documented Season 1 mechanics. This is not the original creator's source code or binary.

## Season 1 effects

The 12 Season 1 effects are enabled by default: Emerald, Ocean, Speed, Fire (support); Invisibility, Thunder, Regeneration, Frost, Feather, Haste, Strength, Heart (primary). The plugin enforces one support and one primary effect at a time. Ender, Apophis, and Thief remain in the code/config for optional compatibility but are disabled by default.

## Build a JAR

Use GitHub Actions → Manual JAR Builder → Run workflow. Download the InfuseSMP-1.21.11 artifact from the completed run. The workflow also builds on pushes to main. For a local build, install JDK 21 and Maven, then run mvn -B clean package.

Upload target/InfuseSMP-1.21.11.jar to your server panel's plugins/ folder and restart.

## Panel config

Edit plugins/Infuse/config.yml in your host panel. It includes effect durations/cooldowns, crafting limits, recipes/ritual settings, and gameplay toggles. Run /infuse reload after edits.

## Commands

- /lspark, /rspark: activate the ability in slot 1/2
- /ldrain, /rdrain: turn an equipped effect back into its infusion item
- /swap: swap slots
- /trust, /untrust: manage trusted players
- /controls or /infuse settings control: switch activation controls
- /infuse giveEffect <effect> [augmented]: admin test item
- /infuse clearEffects <player>, /infuse cooldown <player>: admin actions

Crafting is limited by craft_limits: first craft is augmented and starts the configured ritual; regular crafts follow. Recipes and configurable effect behavior are in the bundled resource files.
