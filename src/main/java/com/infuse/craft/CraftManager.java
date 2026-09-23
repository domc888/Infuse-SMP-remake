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
