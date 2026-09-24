# Infuse SMP remake

Paper 1.21.11 / Java 21 recreation for the `domc888/Infuse-SMP-remake` repository. The mechanics are independently implemented and informed by the public [Infuse 2.4.5 project](https://modrinth.com/plugin/infusesmp), its [public source repository](https://github.com/PluginMart/Infuse), and the public [Season 2 Revamped feature listing](https://builtbybit.com/resources/infusesmp-season-2-revamped.77615/). The YouTube reference does not expose video metadata through the available reader, so this repo targets the documented Infuse behavior rather than claiming byte-for-byte identity with a particular server build.

## Included gameplay

- Potion-style infusion items with configurable ability summaries, augmented variants, and recipe output.
- Crafting through the brewing stand interface, first-craft rituals, configured global limits, and recipe definitions loaded from `recipes.yml`.
- Two effect slots, sparks, draining, swapping, trust, and configurable offhand or command-key activation.
- Panel switches for Season 1 one-support/one-primary slots, the unrestricted two-slot mode, replacing slot 2 when drinking with full slots, protection of dropped infusions, and augmented-priority death drops.
- Effect browser at `/infuses`, active ability display at `/infuse abilities`, and a clickable recipe browser at `/recipes`.
- Ender, Apophis, and Thief remain available as compatibility effects and are disabled by default.

## Install and build

Use GitHub Actions → **Manual JAR Builder** → **Run workflow**. Download the `InfuseSMP-1.21.11` artifact from the completed run. The same workflow also runs on pushes to `main`.

For a local build, install JDK 21 and Maven, then run:

```sh
mvn -B clean package
```

Upload `target/InfuseSMP-1.21.11.jar` to the server panel's `plugins/` folder, then restart the server.

## Panel configuration

Edit `plugins/Infuse/config.yml` in your host panel. It controls effect timing, craft limits, rituals, effect descriptions, slot rules, drop rules, and brewing behavior. Edit `plugins/Infuse/recipes.yml` for shaped recipes and ingredients. Run `/infuse reload` after changing either file.

Important switches:

- `settings.enforce-role-slots: true`: keep one support and one primary effect. Set false for two unrestricted slots.
- `crafting.require-brewing-stand: true`: only craft Infusions through the brewing stand interface.
- `items.equip-on-consume: true`: drink an infusion to equip it; when both slots are full, the second slot's old infusion is returned if there is inventory room.
- `items.protect-from-fire` and `items.protect-from-explosions`: keep dropped infusion items safe.
- `effect_drops: prefer_augmented`: drop one augmented slot first on death. Other modes include `random`, `prefer_1`, `prefer_2`, `only_1`, `only_2`, `both`, and `none`.

## Commands

- `/infuses`, `/effects`: browse effect previews.
- `/recipes`, `/infuse recipes`: browse recipes and ingredients.
- `/lspark`, `/rspark`: activate slot 1/2.
- `/ldrain`, `/rdrain`: return a slotted effect as an infusion.
- `/swap`: swap effect slots.
- `/controls [offhand|command]`: choose ability controls.
- `/trust`, `/untrust`: manage trusted players.
- `/craftedeffects`, `/augments`: view craft totals and the next craft tier.
- `/whohaseffect <effect>`: operator lookup for online holders.
- `/infuse giveEffect <effect> [augmented]`, `/give_effects <player> [effect]`: operator test items.
- `/infuse seteffect <player> <slot> <effect|empty> [augmented]`: operator slot editing.
- `/cleareffects <player>`, `/cooldown <player>`, `/start_ritual <effect>`: operator tools.

`/infuse gui` opens the effect picker for operators with `infuse.commands.infuse.gui`; selected items can only be taken by operators with `infuse.commands.infuse.giveEffect`.
