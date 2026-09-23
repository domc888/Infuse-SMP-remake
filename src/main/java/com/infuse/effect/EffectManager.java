package com.infuse.effect;

import com.infuse.EffectType;
import com.infuse.InfusePlugin;
import com.infuse.data.DataManager;
import com.infuse.data.PlayerState;
import com.infuse.util.Messages;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public class EffectManager {

    private final InfusePlugin plugin;
    private final DataManager data;
    private final NamespacedKey effectKey;
    private final NamespacedKey augKey;

    public EffectManager(InfusePlugin plugin, DataManager data) {
        this.plugin = plugin;
        this.data = data;
        this.effectKey = new NamespacedKey(plugin, "infuse_effect");
        this.augKey = new NamespacedKey(plugin, "infuse_aug");
    }

    public NamespacedKey getEffectKey() {
        return effectKey;
    }

    public NamespacedKey getAugKey() {
        return augKey;
    }

    public PlayerState state(Player player) {
        return data.state(player.getUniqueId());
    }

    public String getEffectKey(Player player, String slot) {
        PlayerState s = state(player);
        return "left".equals(slot) ? s.left : s.right;
    }

    public boolean isAugmented(Player player, String slot) {
        PlayerState s = state(player);
        return "left".equals(slot) ? s.leftAug : s.rightAug;
    }

    public int getCooldown(EffectType type) {
        return plugin.getConfig().getInt("cooldowns." + type.getKey(), 60);
    }

    // ---------------------------------------------------------------- sparks

    public void spark(Player player, String slot) {
        String key = getEffectKey(player, slot);
        if (key == null) {
            Messages.send(player, plugin, "no-effect", "%slot%", slot);
            return;
        }
        PlayerState s = state(player);
        long expiry = "left".equals(slot) ? s.leftCd : s.rightCd;
        if (System.currentTimeMillis() < expiry) {
            long remaining = (expiry - System.currentTimeMillis()) / 1000L;
            Messages.send(player, plugin, "on-cooldown", "%time%", String.valueOf(remaining));
            return;
        }
        EffectType type = EffectType.fromKey(key);
        if (type == null) {
            clearSlot(player, slot);
            return;
        }
        boolean aug = isAugmented(player, slot);
        execute(player, type, "left".equals(slot), aug);

        int cooldown = getCooldown(type);
        if (aug) cooldown = Math.max(1, cooldown / 2);
        long newExpiry = System.currentTimeMillis() + cooldown * 1000L;
        if ("left".equals(slot)) s.leftCd = newExpiry; else s.rightCd = newExpiry;

        Messages.send(player, plugin, "spark-used", "%effect%", type.getDisplay());
    }

    private int dur(int ticks, boolean aug) {
        return aug ? ticks * 3 / 2 : ticks;
    }

    private void effect(Player p, PotionEffectType type, int ticks, int amplifier) {
        p.addPotionEffect(new PotionEffect(type, ticks, amplifier, true, true, true));
    }

    private void execute(Player p, EffectType type, boolean left, boolean aug) {
        int amp = aug ? 1 : 0;
        switch (type) {
            case SPEED -> {
                if (left) {
                    effect(p, PotionEffectType.SPEED, dur(200, aug), 1 + amp);
                    Vector dir = p.getLocation().getDirection().setY(0);
                    if (dir.lengthSquared() > 0) dir.normalize();
                    p.setVelocity(dir.multiply(1.6).setY(0.35));
                } else {
                    effect(p, PotionEffectType.SPEED, dur(600, aug), amp);
                }
            }
            case FIRE -> {
                if (left) {
                    Fireball ball = p.launchProjectile(Fireball.class);
                    ball.setVelocity(p.getLocation().getDirection().multiply(1.5));
                } else {
                    effect(p, PotionEffectType.FIRE_RESISTANCE, dur(400, aug), amp);
                    for (Entity e : p.getNearbyEntities(5, 5, 5)) {
                        if (e instanceof LivingEntity && !e.equals(p)) e.setFireTicks(120);
                    }
                }
            }
            case INVISIBILITY -> {
                if (left) effect(p, PotionEffectType.INVISIBILITY, dur(300, aug), amp);
                else effect(p, PotionEffectType.INVISIBILITY, dur(160, aug), amp);
            }
            case THUNDER -> {
                if (left) {
                    strikeTargetBlock(p);
                } else {
                    LivingEntity nearest = null;
                    double best = 225; // 15 blocks squared
                    for (Entity e : p.getNearbyEntities(15, 15, 15)) {
                        if (!(e instanceof LivingEntity) || e.equals(p)) continue;
                        double dist = e.getLocation().distanceSquared(p.getLocation());
                        if (dist < best) {
                            best = dist;
                            nearest = (LivingEntity) e;
                        }
                    }
                    if (nearest != null) {
                        p.getWorld().strikeLightning(nearest.getLocation());
                    } else {
                        strikeTargetBlock(p);
                    }
                }
            }
            case REGENERATION -> {
                if (left) {
                    effect(p, PotionEffectType.REGENERATION, dur(160, aug), 1 + amp);
                } else {
                    heal(p, 8);
                    effect(p, PotionEffectType.REGENERATION, dur(100, aug), amp);
                }
            }
            case FROST -> {
                if (left) {
                    for (Entity e : p.getNearbyEntities(5, 5, 5)) {
                        if (!(e instanceof LivingEntity) || e.equals(p)) continue;
                        LivingEntity target = (LivingEntity) e;
                        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 9, true, true, true));
                        target.setFreezeTicks(140);
                        if (target instanceof Player tp) {
                            Messages.raw(tp, Messages.comp("&bYou were frozen by " + p.getName() + "!"));
                        }
                    }
                } else {
                    buildIceBridge(p, aug);
                }
            }
            case FEATHER -> {
                if (left) {
                    effect(p, PotionEffectType.SLOW_FALLING, dur(400, aug), amp);
                } else {
                    p.setVelocity(p.getVelocity().setY(1.3));
                    effect(p, PotionEffectType.SLOW_FALLING, dur(200, aug), amp);
                }
            }
            case HASTE -> {
                if (left) effect(p, PotionEffectType.HASTE, dur(600, aug), 2 + amp);
                else effect(p, PotionEffectType.HASTE, dur(1200, aug), 1 + amp);
            }
            case STRENGTH -> {
                if (left) effect(p, PotionEffectType.STRENGTH, dur(400, aug), 1 + amp);
                else effect(p, PotionEffectType.STRENGTH, dur(160, aug), amp);
            }
            case HEART -> {
                if (left) {
                    effect(p, PotionEffectType.ABSORPTION, dur(1200, aug), 1 + amp);
                } else {
                    heal(p, 4);
                    effect(p, PotionEffectType.REGENERATION, dur(100, aug), 1 + amp);
                }
            }
            case ENDER -> {
                if (left) {
                    blink(p, aug ? 16 : 12);
                } else {
                    EnderPearl pearl = p.launchProjectile(EnderPearl.class);
                    pearl.setVelocity(p.getLocation().getDirection().multiply(1.4));
                }
            }
        }
    }

    private void strikeTargetBlock(Player p) {
        Block block = p.getTargetBlockExact(30);
        Location loc;
        if (block != null) {
            loc = block.getLocation();
        } else {
            loc = p.getLocation().add(p.getLocation().getDirection().multiply(10));
        }
        p.getWorld().strikeLightning(loc);
    }

    private void heal(Player p, double amount) {
        double max = p.getMaxHealth();
        p.setHealth(Math.min(max, p.getHealth() + amount));
    }

    private void blink(Player p, int maxDistance) {
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() == 0) dir = new Vector(1, 0, 0);
        dir.normalize();
        Location base = p.getLocation();
        for (int d = 1; d <= maxDistance; d++) {
            Location check = base.clone().add(dir.clone().multiply(d));
            Block feet = check.getBlock();
            Block head = check.clone().add(0, 1, 0).getBlock();
            Block ground = check.clone().subtract(0, 1, 0).getBlock();
            if (feet.isPassable() && head.isPassable() && !ground.isPassable()) {
                p.teleport(check.setDirection(p.getLocation().getDirection()));
                return;
            }
        }
        Messages.raw(p, Messages.comp("&cNo safe landing spot!"));
    }

    private void buildIceBridge(Player p, boolean aug) {
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() == 0) dir = new Vector(1, 0, 0);
        dir.normalize();
        int length = aug ? 10 : 7;
        Location base = p.getLocation().subtract(0, 1, 0);
        for (int d = 1; d <= length; d++) {
            Location place = base.clone().add(dir.clone().multiply(d));
            Block block = place.getBlock();
            if (block.getType() == Material.AIR) {
                block.setType(Material.FROSTED_ICE, false);
                Location revertAt = place.clone();
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (revertAt.getBlock().getType() == Material.FROSTED_ICE) {
                            revertAt.getBlock().setType(Material.AIR, false);
                        }
                    }
                }.runTaskLater(plugin, 200L);
            }
        }
    }

    // ---------------------------------------------------------------- slots

    public void clearSlot(Player player, String slot) {
        PlayerState s = state(player);
        if ("left".equals(slot)) {
            s.left = null;
            s.leftAug = false;
            s.leftCd = 0;
        } else {
            s.right = null;
            s.rightAug = false;
            s.rightCd = 0;
        }
    }

    public void clearAll(Player player) {
        clearSlot(player, "left");
        clearSlot(player, "right");
    }

    public void resetCooldowns(Player player) {
        PlayerState s = state(player);
        s.leftCd = 0;
        s.rightCd = 0;
    }

    public void swap(Player player) {
        PlayerState s = state(player);
        String tmp = s.left;
        s.left = s.right;
        s.right = tmp;
        boolean tmpAug = s.leftAug;
        s.leftAug = s.rightAug;
        s.rightAug = tmpAug;
        Messages.send(player, plugin, "swapped");
    }

    // ---------------------------------------------------------------- bottles

    public ItemStack createBottle(EffectType type, boolean aug) {
        ItemStack item = new ItemStack(Material.POTION);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(effectKey, PersistentDataType.STRING, type.getKey());
        if (aug) {
            meta.getPersistentDataContainer().set(augKey, PersistentDataType.BYTE, (byte) 1);
        }
        String augSuffix = aug ? " &6&lAUGMENTED" : "";
        meta.displayName(Messages.comp(type.getDisplay() + " Effect" + augSuffix));
        item.setItemMeta(meta);
        return item;
    }

    public boolean isBottle(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(effectKey, PersistentDataType.STRING);
    }

    public String getBottleEffect(ItemStack item) {
        return item.getItemMeta().getPersistentDataContainer().get(effectKey, PersistentDataType.STRING);
    }

    public boolean isBottleAugmented(ItemStack item) {
        Byte value = item.getItemMeta().getPersistentDataContainer().get(augKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    public void giveBottle(Player player, String key, boolean aug) {
        EffectType type = EffectType.fromKey(key);
        if (type == null) return;
        player.getInventory().addItem(createBottle(type, aug)).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    public void drain(Player player, String slot) {
        String key = getEffectKey(player, slot);
        if (key == null) {
            Messages.send(player, plugin, "no-effect", "%slot%", slot);
            return;
        }
        boolean aug = isAugmented(player, slot);
        clearSlot(player, slot);
        giveBottle(player, key, aug);
        Messages.send(player, plugin, "drained", "%effect%", EffectType.fromKey(key).getDisplay());
    }

    public void applyBottle(Player player, ItemStack item) {
        String key = getBottleEffect(item);
        boolean aug = isBottleAugmented(item);
        EffectType type = EffectType.fromKey(key);
        if (type == null) return;
        PlayerState s = state(player);
        if (s.left == null) {
            s.left = type.getKey();
            s.leftAug = aug;
            s.leftCd = 0;
            consumeOne(item);
            Messages.send(player, plugin, "applied", "%effect%", type.getDisplay(), "%slot%", "left");
        } else if (s.right == null) {
            s.right = type.getKey();
            s.rightAug = aug;
            s.rightCd = 0;
            consumeOne(item);
            Messages.send(player, plugin, "applied", "%effect%", type.getDisplay(), "%slot%", "right");
        } else {
            Messages.send(player, plugin, "slots-full");
        }
    }

    private void consumeOne(ItemStack item) {
        item.setAmount(item.getAmount() - 1);
    }

    public void setSlot(Player player, String slot, String key, boolean aug) {
        PlayerState s = state(player);
        if ("left".equals(slot)) {
            s.left = key;
            s.leftAug = aug;
            s.leftCd = 0;
        } else {
            s.right = key;
            s.rightAug = aug;
            s.rightCd = 0;
        }
    }

    public List<String> effectKeys() {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (EffectType t : EffectType.values()) keys.add(t.getKey());
        return keys;
    }
}
