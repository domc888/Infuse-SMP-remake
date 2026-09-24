# Infuse SMP HUD icon pack

This optional Minecraft Java 1.21.11 resource pack supplies the small item icons used in the Infuse SMP two-slot action-bar HUD. It reuses vanilla item textures, so it does not replace other pack textures.

## Install

1. Download the `InfuseSMP-HUD-Icons-1.21.11` artifact from the latest successful **Manual JAR Builder** run in GitHub Actions.
2. Put the ZIP in `.minecraft/resourcepacks` and enable it under **Options → Resource Packs**.
3. Keep `hud.resourcepack_icons: true` in the server's `plugins/Infuse/config.yml`. Set it to `false` if players are not using the pack; effect names remain visible either way.
4. Rejoin the server (or use `/infuse reload` after editing config; pack activation is client-side).

For a hosted SMP, you can also upload the ZIP to a direct HTTPS URL and configure it as the server's resource pack so players are prompted to download it. Do not require the pack unless all players can access that URL.

The HUD shows two boxed slots with the effect icon, name, and state (ready, active, or cooldown). Pack compatibility is declared for Java resource-pack format 75.0 (1.21.11).
