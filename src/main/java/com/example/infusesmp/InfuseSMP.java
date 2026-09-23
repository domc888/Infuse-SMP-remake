package com.example.infusesmp;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class InfuseSMP extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private NamespacedKey effectKey;
    private NamespacedKey typeKey;

    public enum EffectType { PRIMARY, SUPPORT }

    public enum InfuseEffect {
        // Primary
        STRENGTH("Strength", EffectType.PRIMARY, PotionEffectType.INCREASE_DAMAGE, 60),
        HASTE("Haste", EffectType.PRIMARY, PotionEffectType.FAST_DIGGING, 60),
        HEART("Heart", EffectType.PRIMARY, PotionEffectType.HEALTH_BOOST, 120),
        REGEN("Regeneration", EffectType.PRIMARY, PotionEffectType.REGENERATION, 60),
        FEATHER("Feather", EffectType.PRIMARY, PotionEffectType.SLOW_FALLING, 60),
        INVISIBILITY("Invisibility", EffectType.PRIMARY, PotionEffectType.INVISIBILITY, 90),
        // Support
        SPEED("Speed", EffectType.SUPPORT, PotionEffectType.SPEED, 20),
        FIRE("Fire Resistance", EffectType.SUPPORT, PotionEffectType.FIRE_RESISTANCE, 60);

        public final String name;
        public final EffectType slotType;
        public final PotionEffectType potion;
        public final int cooldownSeconds;

        InfuseEffect(String name, EffectType slotType, PotionEffectType potion, int cooldownSeconds) {
            this.name = name;
            this.slotType = slotType;
            this.potion = potion;
            this.cooldownSeconds = cooldownSeconds;
        }
    }

    private final Map<UUID, Map<EffectType, InfuseEffect>> playerEquipped = new HashMap<>();
    private final Map<UUID, Map<EffectType, Long>> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        effectKey = new NamespacedKey(this, "infuse_effect");
        typeKey = new NamespacedKey(this, "infuse_type");

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("spark")).setExecutor(this);
        Objects.requireNonNull(getCommand("drain")).setExecutor(this);
        Objects.requireNonNull(getCommand("infuseadmin")).setExecutor(this);

        loadPlayerData();
        startPassiveTask();
        startActionBarTask();
    }

    @Override
    public void onDisable() {
        savePlayerData();
    }

    // =========================================================================
    // DATA PERSISTENCE
    // =========================================================================
    private void loadPlayerData() {
        FileConfiguration config = getConfig();
        if (!config.contains("players")) return;

        for (String uuidStr : Objects.requireNonNull(config.getConfigurationSection("players")).getKeys(false)) {
            UUID uuid = UUID.fromString(uuidStr);
            Map<EffectType, InfuseEffect> map = new HashMap<>();

            String prim = config.getString("players." + uuidStr + ".PRIMARY");
            String supp = config.getString("players." + uuidStr + ".SUPPORT");

            if (prim != null) map.put(EffectType.PRIMARY, InfuseEffect.valueOf(prim));
            if (supp != null) map.put(EffectType.SUPPORT, InfuseEffect.valueOf(supp));

            playerEquipped.put(uuid, map);
        }
    }

    private void savePlayerData() {
        FileConfiguration config = getConfig();
        config.set("players", null);
        for (Map.Entry<UUID, Map<EffectType, InfuseEffect>> entry : playerEquipped.entrySet()) {
            String path = "players." + entry.getKey().toString() + ".";
            Map<EffectType, InfuseEffect> map = entry.getValue();
            if (map.containsKey(EffectType.PRIMARY)) config.set(path + "PRIMARY", map.get(EffectType.PRIMARY).name());
            if (map.containsKey(EffectType.SUPPORT)) config.set(path + "SUPPORT", map.get(EffectType.SUPPORT).name());
        }
        saveConfig();
    }

    // =========================================================================
    // TASKS
    // =========================================================================
    private void startPassiveTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getGameMode() == GameMode.SPECTATOR) continue;
                    Map<EffectType, InfuseEffect> map = playerEquipped.get(p.getUniqueId());
                    if (map == null) continue;

                    for (InfuseEffect effect : map.values()) {
                        p.addPotionEffect(new PotionEffect(effect.potion, 40, 0, false, false, false));
                    }
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void startActionBarTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    Map<EffectType, InfuseEffect> map = playerEquipped.getOrDefault(p.getUniqueId(), Collections.emptyMap());
                    InfuseEffect prim = map.get(EffectType.PRIMARY);
                    InfuseEffect supp = map.get(EffectType.SUPPORT);

                    String primText = (prim != null) ? ChatColor.GREEN + "[" + prim.name + "]" : ChatColor.RED + "[Empty]";
                    String suppText = (supp != null) ? ChatColor.AQUA + "[" + supp.name + "]" : ChatColor.RED + "[Empty]";

                    p.sendTitle("", primText + ChatColor.RESET + " | " + suppText, 0, 20, 0);
                }
            }
        }.runTaskTimer(this, 0L, 10L);
    }

    // =========================================================================
    // EVENTS
    // =========================================================================
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (!playerEquipped.containsKey(p.getUniqueId())) {
            Map<EffectType, InfuseEffect> map = new HashMap<>();
            map.put(EffectType.SUPPORT, InfuseEffect.SPEED);
            playerEquipped.put(p.getUniqueId(), map);
            savePlayerData();
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) return;

        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        if (pdc.has(effectKey, PersistentDataType.STRING)) {
            Player p = event.getPlayer();
            String effectEnumName = pdc.get(effectKey, PersistentDataType.STRING);
            InfuseEffect effect = InfuseEffect.valueOf(effectEnumName);

            Map<EffectType, InfuseEffect> map = playerEquipped.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());

            if (map.containsKey(effect.slotType)) {
                p.sendMessage(ChatColor.RED + "You already have a " + effect.slotType.name() + " effect equipped! Drain it first.");
                return;
            }

            map.put(effect.slotType, effect);
            item.setAmount(item.getAmount() - 1);
            p.sendMessage(ChatColor.GREEN + "Equipped " + effect.name + " to your " + effect.slotType.name() + " slot!");
            p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_DIAMOND, 1.0f, 1.0f);

            if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(GameMode.SURVIVAL);

            savePlayerData();
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        Map<EffectType, InfuseEffect> victimMap = playerEquipped.get(victim.getUniqueId());
        if (victimMap == null || victimMap.isEmpty()) return;

        List<EffectType> keys = new ArrayList<>(victimMap.keySet());
        EffectType lostSlot = keys.get(new Random().nextInt(keys.size()));
        InfuseEffect lostEffect = victimMap.remove(lostSlot);

        victim.sendMessage(ChatColor.RED + "You lost your " + lostEffect.name + " effect!");

        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            Map<EffectType, InfuseEffect> killerMap = playerEquipped.computeIfAbsent(killer.getUniqueId(), k -> new HashMap<>());
            if (!killerMap.containsKey(lostEffect.slotType)) {
                killerMap.put(lostEffect.slotType, lostEffect);
                killer.sendMessage(ChatColor.GREEN + "You stole " + victim.getName() + "'s " + lostEffect.name + "!");
            } else {
                killer.getWorld().dropItemNaturally(killer.getLocation(), createOrb(lostEffect));
                killer.sendMessage(ChatColor.GOLD + "Stole " + lostEffect.name + "! Slot full, dropped on ground.");
            }
        } else {
            victim.getWorld().dropItemNaturally(victim.getLocation(), createOrb(lostEffect));
        }

        if (victimMap.isEmpty()) {
            victim.setGameMode(GameMode.SPECTATOR);
            Bukkit.broadcastMessage(ChatColor.DARK_RED + victim.getName() + " lost all infusions and was eliminated!");
        }

        savePlayerData();
    }

    @EventHandler
    public void onGUI(InventoryClickEvent event) {
        if (event.getView().getTitle().contains("Infusions")) {
            event.setCancelled(true);
        }
    }

    // =========================================================================
    // MECHANICS
    // =========================================================================
    private ItemStack createOrb(InfuseEffect effect) {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Infusion Orb: " + effect.name);
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Slot: " + ChatColor.YELLOW + effect.slotType.name(),
                    ChatColor.GRAY + "Right-click to absorb this effect."
            ));
            meta.getPersistentDataContainer().set(effectKey, PersistentDataType.STRING, effect.name());
            meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, effect.slotType.name());
            item.setItemMeta(meta);
        }
        return item;
    }

    private void triggerSpark(Player p, EffectType slot) {
        Map<EffectType, InfuseEffect> map = playerEquipped.get(p.getUniqueId());
        if (map == null || !map.containsKey(slot)) {
            p.sendMessage(ChatColor.RED + "No effect equipped in " + slot.name() + " slot.");
            return;
        }

        InfuseEffect effect = map.get(slot);
        long now = System.currentTimeMillis();

        Map<EffectType, Long> pCooldowns = cooldowns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        long lastUse = pCooldowns.getOrDefault(slot, 0L);
        long cdMillis = effect.cooldownSeconds * 1000L;

        if (now - lastUse < cdMillis) {
            long remaining = (cdMillis - (now - lastUse)) / 1000;
            p.sendMessage(ChatColor.RED + "Spark on cooldown! " + remaining + "s left.");
            return;
        }

        pCooldowns.put(slot, now);

        switch (effect) {
            case STRENGTH -> p.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, 200, 1));
            case SPEED -> p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 2));
            case REGEN -> p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 2));
            default -> p.addPotionEffect(new PotionEffect(effect.potion, 200, 1));
        }

        p.playSound(p.getLocation(), Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 0.5f, 1.2f);
        p.sendMessage(ChatColor.LIGHT_PURPLE + "Activated " + effect.name + " Spark!");
    }

    // =========================================================================
    // COMMANDS
    // =========================================================================
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;

        if (cmd.getName().equalsIgnoreCase("spark") && args.length > 0) {
            try {
                EffectType type = EffectType.valueOf(args[0].toUpperCase());
                triggerSpark(p, type);
            } catch (IllegalArgumentException e) {
                p.sendMessage(ChatColor.RED + "Usage: /spark <primary|support>");
            }
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("drain") && args.length > 0) {
            try {
                EffectType type = EffectType.valueOf(args[0].toUpperCase());
                Map<EffectType, InfuseEffect> map = playerEquipped.get(p.getUniqueId());
                if (map != null && map.containsKey(type)) {
                    InfuseEffect effect = map.remove(type);
                    p.getInventory().addItem(createOrb(effect));
                    p.sendMessage(ChatColor.YELLOW + "Drained " + effect.name + " into an item.");
                    if (map.isEmpty()) p.setGameMode(GameMode.SPECTATOR);
                    savePlayerData();
                } else {
                    p.sendMessage(ChatColor.RED + "Nothing equipped in " + type.name() + " slot.");
                }
            } catch (IllegalArgumentException e) {
                p.sendMessage(ChatColor.RED + "Usage: /drain <primary|support>");
            }
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("infuseadmin") && p.hasPermission("infusesmp.admin")) {
            if (args.length >= 2 && args[0].equalsIgnoreCase("give")) {
                try {
                    InfuseEffect effect = InfuseEffect.valueOf(args[1].toUpperCase());
                    p.getInventory().addItem(createOrb(effect));
                    p.sendMessage(ChatColor.GREEN + "Gave " + effect.name + " orb.");
                } catch (IllegalArgumentException e) {
                    p.sendMessage(ChatColor.RED + "Invalid effect name.");
                }
            }
            return true;
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1 && (cmd.getName().equalsIgnoreCase("spark") || cmd.getName().equalsIgnoreCase("drain"))) {
            return Arrays.asList("PRIMARY", "SUPPORT");
        }
        if (args.length == 2 && cmd.getName().equalsIgnoreCase("infuseadmin")) {
            List<String> names = new ArrayList<>();
            for (InfuseEffect e : InfuseEffect.values()) names.add(e.name());
            return names;
        }
        return Collections.emptyList();
    }
}
