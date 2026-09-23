package com.infuse.craft;

import com.infuse.EffectType;
import com.infuse.InfusePlugin;
import com.infuse.data.DataManager;
import com.infuse.data.RitualData;
import com.infuse.effect.EffectManager;
import com.infuse.util.Messages;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

public class CraftManager implements Listener {

    // 27-slot (3 row) container; border is pane, effects fill the inner slots.
    private static final int GUI_SIZE = 27;
    private static final int[] INNER_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

    private final InfusePlugin plugin;
    private final DataManager data;
    private final EffectManager effects;
    private final RitualBossBar bossBar;
    private final NamespacedKey guiKey;

    public CraftManager(InfusePlugin plugin, DataManager data, EffectManager effects, RitualBossBar bossBar) {
        this.plugin = plugin;
        this.data = data;
        this.effects = effects;
        this.bossBar = bossBar;
        this.guiKey = new NamespacedKey(plugin, "infuse_gui_effect");
    }

    // ------------------------------------------------------------ opening

    @EventHandler
    public void onBrewingStandUse(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;
        if (event.getClickedBlock().getType() != Material.BREWING_STAND) return;
        event.setCancelled(true);
        openCrafting(event.getPlayer());
    }

    @EventHandler
    public void onBrewingOpen(InventoryOpenEvent event) {
        if (plugin.getConfig().getBoolean("brewing-enabled", false)) return;
        if (event.getInventory().getType() == InventoryType.BREWING) {
            event.setCancelled(true);
        }
    }

    private Component craftTitle() {
        return Messages.comp(plugin.getConfig().getString("gui.title", "&dInfuse Crafting"));
    }

    private ItemStack pane() {
        Material mat = Material.WHITE_STAINED_GLASS_PANE;
        Material configured = Material.matchMaterial(
                plugin.getConfig().getString("gui.pane-material", "WHITE_STAINED_GLASS_PANE"));
        if (configured != null) mat = configured;
        ItemStack pane = new ItemStack(mat);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Component.space());
        pane.setItemMeta(meta);
        return pane;
    }

    public void openCrafting(Player player) {
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, craftTitle());
        ItemStack pane = pane();
        for (int i = 0; i < GUI_SIZE; i++) inv.setItem(i, pane);
        EffectType[] types = EffectType.values();
        for (int i = 0; i < types.length && i < INNER_SLOTS.length; i++) {
            inv.setItem(INNER_SLOTS[i], guiBottle(types[i]));
        }
        player.openInventory(inv);
    }

    public void openGiveGui(Player player) {
        Component giveTitle = Messages.comp("&dInfuse Effects &7(admin)");
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, giveTitle);
        ItemStack pane = pane();
        for (int i = 0; i < GUI_SIZE; i++) inv.setItem(i, pane);
        EffectType[] types = EffectType.values();
        for (int i = 0; i < types.length && i < INNER_SLOTS.length; i++) {
            ItemStack item = guiBottle(types[i]);
            inv.setItem(INNER_SLOTS[i], item);
        }
        player.openInventory(inv);
    }

    private ItemStack guiBottle(EffectType type) {
        ItemStack
 item = effects.createBottle(type, false);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(guiKey, PersistentDataType.STRING, type.getKey());
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------ clicking

    @EventHandler
    public void onGuiClick(InventoryClickEvent event) {
        Component title = event.getView().title();
        boolean isCraft = title.equals(craftTitle());
        boolean isGive = title.equals(Messages.comp("&dInfuse Effects &7(admin)"));
        if (!isCraft && !isGive) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String key = clicked.getItemMeta().getPersistentDataContainer()
                .get(guiKey, PersistentDataType.STRING);
        if (key == null) return;
        EffectType type = EffectType.fromKey(key);
        if (type == null) return;

        if (isGive) {
            if (!player.hasPermission("infuse.admin")) {
                Messages.send(player, plugin, "no-permission");
                return;
            }
            effects.giveBottle(player, type.getKey(), false);
            player.closeInventory();
            return;
        }

        attemptCraft(player, type);
    }

    // ------------------------------------------------------------ crafting

    private int regularLimit() {
        return plugin.getConfig().getInt("ritual.regular-per-effect",
                plugin.getConfig().getInt("regular-per-effect", 3));
    }

    private void attemptCraft(Player player, EffectType type) {
        int crafted = data.getCrafted(type.getKey());

        if (crafted == 0) {
            if (!hasMaterials(player, type)) return;
            consumeMaterials(player, type);
            data.addCrafted(type.getKey());
            startRitual(player, type);
        } else if (crafted <= regularLimit()) {
            if (!hasMaterials(player, type)) return;
            consumeMaterials(player, type);
            data.addCrafted(type.getKey());
            effects.giveBottle(player, type.getKey(), false);
            broadcastCraft(player, type, "broadcast-craft");
            Messages.send(player, plugin, "crafted",
                    "%effect%", type.getDisplay(),
                    "%x%", String.valueOf(player.getLocation().getBlockX()),
                    "%y%", String.valueOf(player.getLocation().getBlockY()),
                    "%z%", String.valueOf(player.getLocation().getBlockZ()),
                    "%world%", player.getWorld().getName());
        } else {
            Messages.send(player, plugin, "uncraftable", "%effect%", type.getDisplay());
        }
    }

    private List<ItemStack> recipe(EffectType type) {
        List<ItemStack> items = new ArrayList<>();
        for (String entry : plugin.getConfig().getStringList("recipes." + type.getKey())) {
            String[] parts = entry.split(":");
            if (parts.length != 2) continue;
            Material mat = Material.matchMaterial(parts[0]);
            if (mat == null) continue;
            int amount;
            try {
                amount = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            items.add(new ItemStack(mat, amount));
        }
        return items;
    }

    private boolean hasMaterials(Player player, EffectType type) {
        List<String> missing = new ArrayList<>();
        PlayerInventory inv = player.getInventory();
        for (ItemStack need : recipe(type)) {
            int have = 0;
            for (ItemStack stack : inv.getContents()) {
                if (stack != null && stack.getType() == need.getType()) have += stack.getAmount();
            }
            if (have < need.getAmount()) {
                missing.add(need.getAmount() + "x " + pretty(need.getType().name()));
            }
        }
        if (!missing.isEmpty()) {
            Messages.send(player, plugin, "no-materials", "%list%", String.join(", ", missing));
            return false;
        }
        return true;
    }

    private void consumeMaterials(Player player, EffectType type) {
        PlayerInventory inv = player.getInventory();
        for (ItemStack need : recipe(type)) {
            int remaining = need.getAmount();
            ItemStack[] contents = inv.getContents();
            for (int i = 0; i < contents.length && remaining > 0; i++) {
                ItemStack stack = contents[i];
                if (stack == null || stack.getType() != need.getType()) continue;
                int take = Math.min(stack.getAmount(), remaining);
                stack.setAmount(stack.getAmount() - take);
                remaining -= take;
            }
        }
    }

    private String pretty(String materialName) {
        String[] words = materialName.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    private void broadcastCraft(Player player, EffectType type, String messageKey) {
        Location loc = player.getLocation();
        String replaced = plugin.getConfig().getString("messages." + messageKey, "")
                .replace("%player%", player.getName())
                .replace("%effect%", type.getDisplay())
                .replace("%x%", String.valueOf(loc.getBlockX()))
                .replace("%y%", String.valueOf(loc.getBlockY()))
                .replace("%z%", String.valueOf(loc.getBlockZ()))
                .replace("%world%", loc.getWorld().getName());
        for (Player online : Bukkit.getOnlinePlayers()) {
            Messages.raw(online, replaced);
        }
    }

    // ------------------------------------------------------------ rituals

    private void startRitual(Player player, EffectType type) {
        RitualData ritual = new RitualData();
        ritual.effectKey = type.getKey();
        Location loc = player.getLocation();
        ritual.world = loc.getWorld().getUID().toString();
        ritual.x = loc.getBlockX() + 0.5;
        ritual.y = loc.getBlockY();
        ritual.z = loc.getBlockZ() + 0.5;
        long minutes = plugin.getConfig().getLong("ritual.minutes",
                plugin.getConfig().getLong("ritual-minutes", 10));
        ritual.endTime = System.currentTimeMillis() + minutes * 60_000L;
        data.addRitual(ritual);
        scheduleRitual(ritual);
        bossBar.start(ritual);

        for (Player online : Bukkit.getOnlinePlayers()) {
            Messages.send(online, plugin, "ritual-start",
                    "%player%", player.getName(),
                    "%effect%", type.getDisplay(),
                    "%x%", String.valueOf(loc.getBlockX()),
                    "%y%", String.valueOf(loc.getBlockY()),
                    "%z%", String.valueOf(loc.getBlockZ()),
                    "%minutes%", String.valueOf(minutes));
        }
    }

    public void scheduleRitual(RitualData ritual) {
        long remainingMs = ritual.endTime - System.currentTimeMillis();
        long ticks = Math.max(1L, remainingMs / 50L);
        new BukkitRunnable() {
            @Override
            public void run() {
                finishRitual(ritual);
            }
        }.runTaskLater(plugin, ticks);
    }

    private void finishRitual(RitualData ritual) {
        if (!data.getRituals().contains(ritual)) return;
        data.removeRitual(ritual);
        bossBar.stop(ritual);
        World world = Bukkit.getWorld(java.util.UUID.fromString(ritual.world));
        EffectType type = EffectType.fromKey(ritual.effectKey);
        if (world == null || type == null) return;
        Location loc = new Location(world, ritual.x, ritual.y, ritual.z);
        world.dropItem(loc, effects.createBottle(type, true));
        for (Player online : Bukkit.getOnlinePlayers()) {
            Messages.send(online, plugin, "ritual-done",
                    "%effect%", type.getDisplay(),
                    "%x%", String.valueOf(loc.getBlockX()),
                    "%y%", String.valueOf(loc.getBlockY()),
                    "%z%", String.valueOf(loc.getBlockZ()),
                    "%world%", world.getName());
        }
    }
}
