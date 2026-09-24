# Infuse SMP remake

Paper 1.21.11 plugin recreation built with Java 21 and Maven. It combines the publicly documented Infuse mechanics with the Season 2 options requested for this server. The behavior is independently implemented; the original YouTuber server build and its private settings are not part of this repository, so exact video-for-video identity cannot be guaranteed.

## Features

- Twelve core effects: Emerald, Feather, Fire, Frost, Haste, Heart, Invisibility, Ocean, Regeneration, Speed, Strength, and Thunder.
- Optional Ender, Apophis, and Thief compatibility effects. They are disabled by default and can be enabled in `config.yml`.
- Two effect slots, regular and augmented infusions, sparks, cooldowns, drains, swapping, trust lists, and offhand or command activation.
- Configurable crafting through the brewing-stand interface, global craft limits, first-craft rituals, player craft totals, and shaped recipes from `recipes.yml`.
- Effect and recipe GUIs, infusion selector, configurable death drops, panel-editable effect descriptions, and separate persistent player data.
- Effect mechanics include Emerald loot and XP, Feather fall protection and slam, Fire arrows and smelting, Frost movement and freezing, Haste tool enchantments, Heart health, Invisibility, Ocean control, Regeneration, Speed stacks, Strength damage and shield pressure, and Thunder chains.
- Apophis combines Fire, Emerald, and Heart traits; Thief disguises after kills and can temporarily take an opponent's effect spark.

## Build the JAR

To build it on GitHub, open **Actions → Manual JAR Builder → Run workflow**. After the run succeeds, download the `InfuseSMP-1.21.11` artifact from that run. The workflow also builds and uploads an artifact for pushes to `main`.

For a local build, install JDK 21 and Maven, then run:

```sh
mvn -B clean package
```

The output is `target/InfuseSMP-1.21.11.jar`. Upload it to the server panel's `plugins/` directory and restart the Paper 1.21.11 server.

## Panel configuration

Edit `plugins/Infuse/config.yml` in the server panel. It controls effect enablement, durations, cooldowns, hit thresholds, craft limits, rituals, slot rules, death drops, brewing behavior, and effect descriptions. Edit `plugins/Infuse/recipes.yml` for shaped ingredients and layouts. Player state is kept separately in `plugins/Infuse/data.yml`. Run `/infuse reload` after changing config or recipes.

Common settings:

- `settings.enforce-role-slots`: enable one support and one primary effect per player.
- `crafting.require-brewing-stand`: restrict infusion crafting to the brewing-stand interface.
- `items.equip-on-consume` and `settings.replace-second-on-consume`: control how drinking an infusion equips it when both slots are full.
- `allow_infinite_effects` and `craft_limits`: control global crafting totals.
- `effect_drops`: choose `random`, `prefer_1`, `prefer_2`, `prefer_augmented`, `only_1`, `only_2`, `both`, or `none`.

## Commands

- `/infuses` or `/effects`: browse effects. `/infuse abilities`: view equipped effects.
- `/recipes`: browse recipes and ingredients. `/augments`: browse augmented infusion items.
- `/lspark`, `/rspark`: activate slot 1 or 2. `/ldrain`, `/rdrain`: return a slotted infusion item.
- `/swap`: swap slots. `/controls [offhand|command]`: choose spark controls.
- `/trust <player>`, `/untrust <player>`: manage allies.
- `/craftedeffects`: show personal craft totals. `/whohaseffect <effect>`: list online holders.
- `/giveselector <player|@a|*>`: give an effect selector.
- `/infuse giveEffect <effect> [augmented]`, `/give_effects <player> [effect]`: grant test items.
- `/infuse seteffect <player> <slot> <effect|empty> [augmented]`: edit an online player's slot.
- `/cleareffects <player>`, `/cooldown <player>`, `/start_ritual <effect>`: administrative tools.

`/infuse gui` opens the picker for operators with `infuse.commands.infuse.gui`. Taking an infusion from that picker also requires `infuse.commands.infuse.giveEffect`.
