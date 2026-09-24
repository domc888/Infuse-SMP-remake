package com.infuse;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.attribute.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.scheduler.*;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class InfusePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private NamespacedKey effectKey, augmentedKey, selectorKey, cursingProjectileKey;
    private final Map<UUID,Long> enderFireballCooldown = new ConcurrentHashMap<>();
    private final Map<UUID,ThiefDisguise> thiefDisguises = new ConcurrentHashMap<>();
    private record ThiefDisguise(Component displayName, Component customName, boolean customNameVisible,
                                 com.destroystokyo.paper.profile.PlayerProfile profile, long expiresAt,
                                 Set<UUID> unlistedViewers) {}
    private final Map<UUID,Long> cursedPlayers = new ConcurrentHashMap<>();
    private final Set<UUID> curseDamageGuard = ConcurrentHashMap.newKeySet();
    private final Set<UUID> thunderDamageGuard = ConcurrentHashMap.newKeySet();
    private final Set<UUID> experienceTransferGuard = ConcurrentHashMap.newKeySet();
    private final Set<UUID> forcedFoodChanges = ConcurrentHashMap.newKeySet();
    private final Map<UUID,Double> heartBaseHealth = new ConcurrentHashMap<>();
    private YamlConfiguration recipeConfig, dataConfig;
    private File dataFile;
    private enum Effect {
        EMPTY, EMERALD, ENDER, FEATHER, FIRE, FROST, HASTE, HEART, INVIS, OCEAN,
        REGEN, SPEED, STRENGTH, THUNDER, APOPHIS, THIEF;
        static Effect parse(String s) {
            if (s == null) return EMPTY;
            s = s.toLowerCase(Locale.ROOT).replace("invisibility","invis").replace("regeneration","regen");
            try { return valueOf(s.toUpperCase(Locale.ROOT)); } catch (Exception ignored) { return EMPTY; }
        }
        String id() { return name().toLowerCase(Locale.ROOT); }
        String display() { return name().charAt(0)+name().substring(1).toLowerCase(Locale.ROOT); }
    }

    private final Map<UUID, Effect[]> effects = new ConcurrentHashMap<>();
    private final Map<UUID, boolean[]> augmentedSlots = new ConcurrentHashMap<>();
    private final Map<UUID, long[]> activeUntil = new ConcurrentHashMap<>();
    private final Map<UUID, long[]> cooldownUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> trusted = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Effect,Integer>> crafts = new ConcurrentHashMap<>();
    private final Map<Effect,Integer> existingCounts = new EnumMap<>(Effect.class);
    private final Map<UUID, Map<Effect,Deque<Long>>> hits = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> commandKeys = new ConcurrentHashMap<>();
    private final Map<Location,Boolean> frostSnowBlocks = new ConcurrentHashMap<>();
    private final Map<UUID,TextDisplay> heartHealthDisplays = new ConcurrentHashMap<>();
    private final Map<UUID,Map<UUID,Set<UUID>>> invisSparkHidden = new ConcurrentHashMap<>();
    private final Map<UUID, Long> oceanDrownAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> oceanPullAt = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> speedLevels = new ConcurrentHashMap<>();
    private final Map<UUID, Long> speedLastHit = new ConcurrentHashMap<>();
    private final Map<UUID, boolean[]> thiefSparkUsed = new ConcurrentHashMap<>();
    private final Map<String, ThiefSteal> thiefSteals = new ConcurrentHashMap<>();
    private final Map<UUID, Long> foodXpLockedUntil = new ConcurrentHashMap<>();
    private record ThiefSteal(UUID thiefId, int thiefSlot, UUID victimId, int victimSlot,
                              Effect effect, boolean augmented, long expiresAt) {}

    private boolean ritualActive;
    private long ritualEndsAt;
    private Effect ritualEffect = Effect.EMPTY;
    private Location ritualLocation;
    private BukkitTask ritualTask;
    private BossBar ritualBar;

    private Effect[] slots(Player p) {
        return effects.computeIfAbsent(p.getUniqueId(), k -> new Effect[]{Effect.EMPTY, Effect.EMPTY});
    }
    private boolean[] augSlots(Player p) {
        return augmentedSlots.computeIfAbsent(p.getUniqueId(), k -> new boolean[]{false, false});
    }
    private boolean isSparkActive(Player p, int slot) {
        long[] a = activeUntil.get(p.getUniqueId());
        return a != null && a[slot] > System.currentTimeMillis();
    }

    private boolean reachedHitThreshold(Player player, Effect effect, int threshold) {
        long now = System.currentTimeMillis();
        long decay = Math.max(1,getConfig().getInt("hit_counter_decay_seconds",15))*1000L;
        Deque<Long> hitsForEffect = hits.computeIfAbsent(player.getUniqueId(), key -> new ConcurrentHashMap<>())
            .computeIfAbsent(effect, key -> new ArrayDeque<>());
        while (!hitsForEffect.isEmpty() && now-hitsForEffect.peekFirst() > decay) hitsForEffect.removeFirst();
        hitsForEffect.addLast(now);
        threshold = Math.max(1,getConfig().getInt("hit_threshold",threshold));
        if (hitsForEffect.size() < threshold) return false;
        hitsForEffect.clear();
        return true;
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        effectKey = new NamespacedKey(this, "effect");
        augmentedKey = new NamespacedKey(this, "augmented");
        selectorKey = new NamespacedKey(this, "selector");
        cursingProjectileKey = new NamespacedKey(this,"cursing_projectile");
        reloadRecipeConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        boolean migrateLegacyData = !dataFile.exists();
        if (migrateLegacyData) {
            for (String key : List.of("players", "crafts", "trusted", "command_keys", "effect_counts")) {
                if (getConfig().contains(key)) dataConfig.set(key, getConfig().get(key));
            }
        }
        loadData();
        if (migrateLegacyData) saveData();
        registerRecipes();
        getServer().getPluginManager().registerEvents(this, this);
        String[] commands = {"infuse","infuses","giveselector","lspark","rspark","ldrain","rdrain","swap","controls","trust","untrust","cleareffects","cleareffect","cooldown","effects","recipes","craftedeffects","augments","whohaseffect","give_effects","start_ritual","reloadtrust"};
        for (String name : commands) {
            PluginCommand c = getCommand(name);
            if (c != null) { c.setExecutor(this); c.setTabCompleter(this); }
        }
        resumeRitual();
        getServer().getScheduler().runTaskTimer(this, this::tickEffects, 10L, 10L);
        getLogger().info("InfuseSMP remake enabled.");
    }

    @Override public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (ItemStack item : player.getInventory().getContents()) restoreHasteEnchantments(item);
            removeThiefDisguise(player);
            AttributeInstance maxHealth=player.getAttribute(Attribute.MAX_HEALTH);
            Double original=heartBaseHealth.remove(player.getUniqueId());
            if (maxHealth!=null && original!=null) {
                maxHealth.setBaseValue(original);
                if (player.getHealth()>maxHealth.getValue()) player.setHealth(maxHealth.getValue());
            }
        }
        restoreInvisibilitySparkVisibility();
        for (TextDisplay display : heartHealthDisplays.values()) if (display.isValid()) display.remove();
        heartHealthDisplays.clear();
        restoreAllFrostSnow();
        saveData();
        stopRitual(false);
    }

    private ItemStack effectItem(Effect e, boolean augmented) {
        ItemStack item = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta)item.getItemMeta();
        meta.setColor(effectColor(e));
        meta.displayName(Component.text((augmented ? "Augmented " : "") + e.display() + " Infusion", NamedTextColor.LIGHT_PURPLE));
        List<Component> lore = new ArrayList<>();
        for (String line : getConfig().getStringList("effect_info." + e.id())) lore.add(Component.text(line, NamedTextColor.GRAY));
        lore.add(Component.text(augmented ? "Augmented variant" : "Drink to equip", NamedTextColor.DARK_AQUA));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(effectKey, PersistentDataType.STRING, e.id());
        meta.getPersistentDataContainer().set(augmentedKey, PersistentDataType.BYTE, (byte)(augmented ? 1 : 0));
        item.setItemMeta(meta);
        return item;
    }

    private Color effectColor(Effect effect) {
        return switch (effect) {
            case EMERALD -> Color.fromRGB(0x009420);
            case ENDER -> Color.fromRGB(0x6200A0);
            case FEATHER -> Color.fromRGB(0xBEA3CA);
            case FIRE -> Color.fromRGB(0xE85720);
            case FROST -> Color.fromRGB(0x75D9F5);
            case HASTE -> Color.fromRGB(0xBD934F);
            case HEART -> Color.fromRGB(0xE3304A);
            case INVIS -> Color.fromRGB(0x2B0078);
            case OCEAN -> Color.fromRGB(0x1E87D2);
            case REGEN -> Color.fromRGB(0xB0009A);
            case SPEED -> Color.fromRGB(0xE8BD74);
            case STRENGTH -> Color.fromRGB(0x8B0000);
            case THUNDER -> Color.fromRGB(0xE2D500);
            case APOPHIS -> Color.fromRGB(0x5E237F);
            case THIEF -> Color.fromRGB(0x7D1A38);
            default -> Color.WHITE;
        };
    }

    private Effect itemEffect(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return Effect.EMPTY;
        String id = item.getItemMeta().getPersistentDataContainer().get(effectKey, PersistentDataType.STRING);
        return Effect.parse(id);
    }

    private boolean isAugmented(ItemStack item) {
        return item != null && item.hasItemMeta() &&
            item.getItemMeta().getPersistentDataContainer().getOrDefault(augmentedKey, PersistentDataType.BYTE, (byte)0) == 1;
    }

    private int crafted(UUID id, Effect e) {
        return crafts.computeIfAbsent(id,k->new EnumMap<>(Effect.class)).getOrDefault(e,0);
    }
    private void setCrafted(UUID id, Effect e, int n) {
        crafts.computeIfAbsent(id,k->new EnumMap<>(Effect.class)).put(e,n);
    }

    private int[] limits(Effect e) {
        List<Integer> l = getConfig().getIntegerList("craft_limits."+e.id());
        if (l.size() >= 2) return new int[]{l.get(0),l.get(1)};
        return new int[]{1,3};
    }

    private boolean nextCraftIsAugmented(Effect e, int existing) {
        return limits(e)[0] < 0 || existing < limits(e)[0];
    }

    private boolean craftLimitReached(Effect e, int existing) {
        if (getConfig().getBoolean("allow_infinite_effects", false)) return false;
        int[] limit = limits(e);
        if (limit[0] < 0 || existing < limit[0] || limit[1] < 0) return false;
        return existing >= limit[0] + limit[1];
    }

    private boolean isBrewingCraft(CraftingInventory inventory) {
        if (!getConfig().getBoolean("crafting.require-brewing-stand", true)) return true;
        Location location = inventory.getLocation();
        return location != null && location.getBlock().getType() == Material.BREWING_STAND;
    }

    private void countNewEffect(Effect effect) {
        existingCounts.merge(effect, 1, Integer::sum);
    }

    private void reloadRecipeConfig() {
        File file = new File(getDataFolder(), "recipes.yml");
        if (!file.exists()) saveResource("recipes.yml", false);
        recipeConfig = YamlConfiguration.loadConfiguration(file);
    }

    private void registerRecipes() {
        for (Effect e : Effect.values()) if (e != Effect.EMPTY) {
            removeRecipe("craft_"+e.id());
            removeRecipe("aug_"+e.id());
            if (!getConfig().getBoolean(e.id()+".enabled", true)) continue;
            int global = totalCrafts(e);
            int[] limit = limits(e);
            if (limit[0] < 0 || global < limit[0]) addRecipe(e, true);
            else if (limit[1] < 0 || global < limit[0] + limit[1] || getConfig().getBoolean("allow_infinite_effects",false)) addRecipe(e,false);
        }
    }
    private int totalCrafts(Effect e) {
        return Math.max(0, existingCounts.getOrDefault(e, 0));
    }
    private void removeRecipe(String id) {
        try { Bukkit.removeRecipe(new NamespacedKey(this,id)); } catch(Exception ignored){}
    }

    private boolean addConfiguredRecipe(Effect e, boolean augmented) {
        if (recipeConfig == null) return false;
        ConfigurationSection def = recipeConfig.getConfigurationSection((augmented ? "aug_" : "") + e.id());
        if (def == null && augmented) def = recipeConfig.getConfigurationSection(e.id());
        if (def == null || !def.getString("type", "shaped").equalsIgnoreCase("shaped")) return false;

        List<String> rows = def.getStringList("shape");
        if (rows.isEmpty() || rows.size() > 3) {
            getLogger().warning("Invalid recipe shape for " + e.id() + " in recipes.yml; using the built-in recipe.");
            return false;
        }
        String[] shape = rows.toArray(new String[0]);
        for (String row : shape) if (row.length() < 1 || row.length() > 3) {
            getLogger().warning("Invalid recipe row for " + e.id() + " in recipes.yml; using the built-in recipe.");
            return false;
        }

        ConfigurationSection configured = def.getConfigurationSection("ingredients");
        if (configured == null) {
            getLogger().warning("Missing ingredients for " + e.id() + " in recipes.yml; using the built-in recipe.");
            return false;
        }
        Map<Character, Material> ingredients = new HashMap<>();
        for (String key : configured.getKeys(false)) {
            if (key.length() != 1 || key.charAt(0) == ' ') continue;
            Material material = Material.matchMaterial(configured.getString(key, ""));
            if (material == null) {
                getLogger().warning("Unknown material '" + configured.getString(key) + "' in recipe " + e.id() + "; using the built-in recipe.");
                return false;
            }
            ingredients.put(key.charAt(0), material);
        }
        for (String row : shape) for (char symbol : row.toCharArray()) if (symbol != ' ' && !ingredients.containsKey(symbol)) {
            getLogger().warning("Recipe " + e.id() + " uses undefined key '" + symbol + "'; using the built-in recipe.");
            return false;
        }

        try {
            ShapedRecipe recipe = new ShapedRecipe(new NamespacedKey(this, (augmented ? "aug_" : "craft_") + e.id()), effectItem(e, augmented));
            recipe.shape(shape);
            for (Map.Entry<Character, Material> ingredient : ingredients.entrySet()) recipe.setIngredient(ingredient.getKey(), ingredient.getValue());
            Bukkit.addRecipe(recipe);
            return true;
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Invalid recipe " + e.id() + " in recipes.yml: " + ex.getMessage() + "; using the built-in recipe.");
            return false;
        }
    }

    private void addRecipe(Effect e, boolean augmented) {
        if (addConfiguredRecipe(e, augmented)) return;
        String[] shape;
        Map<Character,Material> m=new HashMap<>();
        switch(e) {
            case EMERALD -> {shape=new String[]{"WSW","OEO","CTC"};m.put('W',Material.WILD_ARMOR_TRIM_SMITHING_TEMPLATE);m.put('S',Material.SNIFFER_EGG);m.put('O',Material.OMINOUS_BOTTLE);m.put('E',Material.EMERALD_BLOCK);m.put('C',Material.ENDER_CHEST);m.put('T',Material.ENCHANTING_TABLE);}
            case FEATHER -> {shape=new String[]{"BPB","FHF","BPB"};m.put('B',Material.BREEZE_ROD);m.put('P',Material.PHANTOM_MEMBRANE);m.put('F',Material.FEATHER);m.put('H',Material.HEAVY_CORE);}
            case FIRE -> {shape=new String[]{"URU","NPN","URU"};m.put('U',Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE);m.put('R',Material.RESPAWN_ANCHOR);m.put('N',Material.NETHERITE_INGOT);m.put('P',Material.MUSIC_DISC_PIGSTEP);}
            case ENDER -> {shape=new String[]{"RAR","APA","RAR"};m.put('R',Material.DRAGON_HEAD);m.put('A',Material.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE);m.put('P',augmented?Material.DRAGON_EGG:Material.ENDER_EYE);}
            case FROST -> {shape=new String[]{"TPT","HDH","TPT"};m.put('T',Material.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE);m.put('P',Material.PEARLESCENT_FROGLIGHT);m.put('H',Material.GOAT_HORN);m.put('D',Material.MUSIC_DISC_CREATOR);}
            case HASTE -> {shape=new String[]{"DBD","GEG","DND"};m.put('D',Material.DIAMOND_BLOCK);m.put('B',Material.BEACON);m.put('G',Material.GOLD_BLOCK);m.put('E',Material.DEEPSLATE_EMERALD_ORE);m.put('N',Material.NETHERITE_PICKAXE);}
            case HEART -> {shape=new String[]{"PTP","EBE","PTP"};m.put('P',Material.POTION);m.put('T',Material.TOTEM_OF_UNDYING);m.put('E',Material.ENCHANTED_GOLDEN_APPLE);m.put('B',Material.BEETROOT);}
            case INVIS -> {shape=new String[]{"EME","SOS","ERE"};m.put('E',Material.ENDER_EYE);m.put('M',Material.MUSIC_DISC_5);m.put('S',Material.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE);m.put('O',Material.OMINOUS_TRIAL_KEY);m.put('R',Material.RECOVERY_COMPASS);}
            case OCEAN -> {shape=new String[]{"EHE","TCT","EHE"};m.put('E',Material.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE);m.put('H',Material.HEART_OF_THE_SEA);m.put('T',Material.TRIDENT);m.put('C',Material.CONDUIT);}
            case REGEN -> {shape=new String[]{"EHE","PAP","EME"};m.put('E',Material.END_CRYSTAL);m.put('H',Material.HONEY_BLOCK);m.put('P',Material.PEARLESCENT_FROGLIGHT);m.put('A',Material.AXOLOTL_BUCKET);m.put('M',Material.MUSIC_DISC_CREATOR_MUSIC_BOX);}
            case SPEED -> {shape=new String[]{"RSR","DHE","RNR"};m.put('R',Material.RABBIT_FOOT);m.put('S',Material.SADDLE);m.put('D',Material.DUNE_ARMOR_TRIM_SMITHING_TEMPLATE);m.put('H',Material.DIAMOND_HORSE_ARMOR);m.put('E',Material.EYE_ARMOR_TRIM_SMITHING_TEMPLATE);m.put('N',Material.NETHERITE_BOOTS);}
            case STRENGTH -> {shape=new String[]{"PRP","SWA","PRP"};m.put('P',Material.PLAYER_HEAD);m.put('R',Material.RIB_ARMOR_TRIM_SMITHING_TEMPLATE);m.put('S',Material.NETHERITE_SWORD);m.put('W',Material.WITHER_ROSE);m.put('A',Material.NETHERITE_AXE);}
            case THUNDER -> {shape=new String[]{"BLB","TCT","BLB"};m.put('B',Material.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE);m.put('L',Material.LODESTONE);m.put('T',Material.TRIDENT);m.put('C',Material.CREEPER_HEAD);}
            case APOPHIS -> {shape=new String[]{"BLB","LCL","BLB"};m.put('B',Material.RIB_ARMOR_TRIM_SMITHING_TEMPLATE);m.put('L',Material.NETHERITE_INGOT);m.put('C',Material.NETHER_STAR);}
            case THIEF -> {shape=new String[]{"BLB","CDC","ZXZ"};m.put('B',Material.ENCHANTED_GOLDEN_APPLE);m.put('L',Material.ENDER_EYE);m.put('C',Material.PLAYER_HEAD);m.put('D',Material.SHEARS);m.put('Z',Material.RABBIT_FOOT);m.put('X',Material.SCULK_CATALYST);}
            default -> { return; }
        }
        ShapedRecipe r=new ShapedRecipe(new NamespacedKey(this,(augmented?"aug_":"craft_")+e.id()),effectItem(e,augmented));
        r.shape(shape);
        for(var x:m.entrySet()) r.setIngredient(x.getKey(),x.getValue());
        Bukkit.addRecipe(r);
    }

    @EventHandler public void onPrepare(PrepareItemCraftEvent event) {
        if(event.getRecipe()==null) return;
        Effect e=itemEffect(event.getRecipe().getResult());
        if(e==Effect.EMPTY) return;
        int total=totalCrafts(e);
        if(!getConfig().getBoolean(e.id()+".enabled",true) || !isBrewingCraft(event.getInventory()) || craftLimitReached(e,total)) {
            event.getInventory().setResult(null);
        } else {
            event.getInventory().setResult(effectItem(e,nextCraftIsAugmented(e,total)));
        }
    }

    @EventHandler public void onCraft(CraftItemEvent event) {
        if(!(event.getWhoClicked() instanceof Player p) || event.getRecipe()==null) return;
        Effect e=itemEffect(event.getRecipe().getResult());
        if(e==Effect.EMPTY) return;
        if(event.isShiftClick()) { event.setCancelled(true); return; }
        int total=totalCrafts(e);
        if(!getConfig().getBoolean(e.id()+".enabled",true) || !isBrewingCraft(event.getInventory()) || craftLimitReached(e,total)) {
            event.setCancelled(true);
            p.sendMessage("§cInfusions must be crafted through a brewing stand.");
            return;
        }
        boolean augmented = isAugmented(event.getRecipe().getResult());
        if(augmented) {
            if(ritualActive) {event.setCancelled(true);p.sendMessage("§cA ritual is already active.");return;}
            startRitual(p,e,event.getInventory().getLocation());
            event.setCurrentItem(null);
            setCrafted(p.getUniqueId(),e,crafted(p.getUniqueId(),e)+1);
            countNewEffect(e);
            saveData(); registerRecipes();
        } else {
            setCrafted(p.getUniqueId(),e,crafted(p.getUniqueId(),e)+1);
            countNewEffect(e);
            broadcastCraft(p,e);
            saveData();
            registerRecipes();
        }
    }

    private void broadcastCraft(Player p, Effect e) {
        Location l=p.getLocation();
        if (getConfig().getBoolean("rituals.broadcast_regular", true))
            Bukkit.broadcast(Component.text(p.getName()+" crafted "+e.display()+" at "+l.getBlockX()+" "+l.getBlockY()+" "+l.getBlockZ()+" in "+l.getWorld().getName()+".",NamedTextColor.LIGHT_PURPLE));
    }

    private void startRitual(Player p,Effect e,Location loc) {
        if(loc==null) loc=p.getLocation();
        ritualActive=true; ritualEffect=e; ritualLocation=loc.clone();
        broadcastCraft(p,e);
        Bukkit.broadcast(Component.text(p.getName()+" started the "+e.display()+" ritual at "+loc.getBlockX()+" "+loc.getBlockY()+" "+loc.getBlockZ()+".",NamedTextColor.GOLD));
        int seconds=Math.max(1,e==Effect.ENDER?getConfig().getInt("rituals.ender_duration",3600):getConfig().getInt("rituals.duration",600));
        ritualEndsAt=System.currentTimeMillis()+seconds*1000L;
        createRitualBar();
        scheduleRitualTimer(seconds*20L);
        if (getConfig().getBoolean("rituals.beacon",true))
            loc.getWorld().spawnParticle(Particle.END_ROD,loc.clone().add(.5,1,.5),30,.6,.8,.6,.02);
        saveData();
    }

    private void createRitualBar() {
        if (ritualBar != null) ritualBar.removeAll();
        ritualBar=Bukkit.createBossBar("Ritual: "+ritualEffect.display(),BarColor.PURPLE,BarStyle.SOLID);
        for(Player player:Bukkit.getOnlinePlayers()) ritualBar.addPlayer(player);
        ritualBar.setProgress(1);
    }

    private void scheduleRitualTimer(long ticks) {
        long total=Math.max(1,ticks);
        ritualTask=new org.bukkit.scheduler.BukkitRunnable(){long left=total;
            public void run(){
                left--;
                if (ritualBar != null) ritualBar.setProgress(Math.max(0,left/(double)total));
                if(left<=0){finishRitual();cancel();}
            }
        }.runTaskTimer(this,1L,1L);
    }

    private void resumeRitual() {
        if (dataConfig == null || !dataConfig.getBoolean("ritual.active",false)) return;
        Effect effect=Effect.parse(dataConfig.getString("ritual.effect"));
        World world;
        try { world=Bukkit.getWorld(UUID.fromString(dataConfig.getString("ritual.world"))); }
        catch (Exception ignored) { world=null; }
        if (effect==Effect.EMPTY || world==null) {
            dataConfig.set("ritual.active",false);
            saveData();
            return;
        }
        ritualActive=true;
        ritualEffect=effect;
        ritualLocation=new Location(world,dataConfig.getDouble("ritual.x"),dataConfig.getDouble("ritual.y"),dataConfig.getDouble("ritual.z"));
        ritualEndsAt=dataConfig.getLong("ritual.ends_at",0L);
        long remaining=ritualEndsAt-System.currentTimeMillis();
        if (remaining<=0) { finishRitual(); return; }
        createRitualBar();
        scheduleRitualTimer((remaining+49L)/50L);
    }

    private void finishRitual() {
        if(!ritualActive || ritualLocation==null) return;
        Location l=ritualLocation.clone().add(.5,1,.5);
        l.getWorld().dropItem(l,effectItem(ritualEffect,true));
        Bukkit.broadcast(Component.text("Ritual complete: "+ritualEffect.display()+" is available at "+l.getBlockX()+" "+l.getBlockY()+" "+l.getBlockZ()+".",NamedTextColor.GREEN));
        ritualActive=false; ritualEndsAt=0; ritualEffect=Effect.EMPTY; ritualLocation=null;
        if(ritualBar!=null) ritualBar.removeAll();
        ritualBar=null;
        registerRecipes();
        saveData();
    }

    private void stopRitual(boolean announce) {
        if(ritualTask!=null) ritualTask.cancel();
        if(ritualBar!=null) ritualBar.removeAll();
        ritualTask=null; ritualBar=null;
        if(announce && ritualActive) getLogger().info("Ritual stopped.");
        ritualActive=false; ritualEndsAt=0; ritualEffect=Effect.EMPTY; ritualLocation=null;
        if (announce) saveData();
    }

    @EventHandler public void onBlockBreak(BlockBreakEvent e) {
        if(ritualActive && ritualLocation!=null && e.getBlock().getLocation().distanceSquared(ritualLocation)<4 && !getConfig().getBoolean("rituals.immortal_brewing_stands",true))
            stopRitual(true);
    }

    @EventHandler public void useEnderDragonBreath(PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND
            || !(event.getAction().isRightClick())
            || !Arrays.asList(slots(event.getPlayer())).contains(Effect.ENDER)) return;
        ItemStack hand = event.getItem();
        if (hand == null || hand.getType() != Material.DRAGON_BREATH) return;
        Player player = event.getPlayer();
        long readyAt = enderFireballCooldown.getOrDefault(player.getUniqueId(),0L);
        if (readyAt > System.currentTimeMillis()) {
            player.sendMessage("§cCursing projectile cooldown: "+((readyAt-System.currentTimeMillis()+999)/1000)+"s");
            event.setCancelled(true);
            return;
        }
        DragonFireball fireball = player.launchProjectile(DragonFireball.class);
        fireball.setIsIncendiary(false);
        fireball.setYield(0f);
        fireball.getPersistentDataContainer().set(cursingProjectileKey,PersistentDataType.BYTE,(byte)1);
        fireball.setVelocity(fireball.getVelocity().multiply(2));
        enderFireballCooldown.put(player.getUniqueId(),System.currentTimeMillis()+Math.max(1,getConfig().getLong("ender.passive.cursing_projectile_cooldown_seconds",30))*1000L);
        saveData();
        if (hand.getAmount() <= 1) player.getInventory().setItemInMainHand(null);
        else hand.setAmount(hand.getAmount()-1);
        event.setCancelled(true);
        player.playSound(player.getLocation(),Sound.ENTITY_ENDERMAN_TELEPORT,1f,0.8f);
    }

    @EventHandler public void enderCurseProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof DragonFireball fireball)
            || !fireball.getPersistentDataContainer().has(cursingProjectileKey,PersistentDataType.BYTE)
            || !(event.getHitEntity() instanceof Player target)
            || !(fireball.getShooter() instanceof Player shooter) || trusted(shooter,target)) return;
        cursedPlayers.merge(target.getUniqueId(),System.currentTimeMillis()+Math.max(1,getConfig().getLong("ender.passive.curse_duration_seconds",60))*1000L,Math::max);
        target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,1200,0,false,false));
    }

    @EventHandler public void enderCurseProjectileDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof DragonFireball fireball)
            || !fireball.getPersistentDataContainer().has(cursingProjectileKey,PersistentDataType.BYTE)
            || !(event.getEntity() instanceof Player target)
            || !(fireball.getShooter() instanceof Player shooter) || trusted(shooter,target)) return;
        cursedPlayers.merge(target.getUniqueId(),System.currentTimeMillis()+Math.max(1,getConfig().getLong("ender.passive.curse_duration_seconds",60))*1000L,Math::max);
        target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,1200,0,false,false));
        event.setDamage(0);
    }

    @EventHandler public void shareEnderCurseDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player damaged)
            || curseDamageGuard.contains(damaged.getUniqueId())) return;
        long now = System.currentTimeMillis();
        long expires = cursedPlayers.getOrDefault(damaged.getUniqueId(),0L);
        if (expires <= now) {
            if (expires > 0) cursedPlayers.remove(damaged.getUniqueId(),expires);
            return;
        }
        for (var entry : cursedPlayers.entrySet()) {
            UUID otherId = entry.getKey();
            if (otherId.equals(damaged.getUniqueId()) || entry.getValue() <= now) continue;
            Player other = Bukkit.getPlayer(otherId);
            if (other == null || other.isDead() || !other.isOnline()) continue;
            curseDamageGuard.add(otherId);
            try { other.damage(event.getFinalDamage(),damaged); }
            finally { curseDamageGuard.remove(otherId); }
        }
    }

    @EventHandler public void onPlayerInteract(PlayerInteractEvent e) {
        if(e.getAction()==org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && e.getClickedBlock()!=null && e.getClickedBlock().getType()==Material.BREWING_STAND) {
            if(getConfig().getBoolean("brewing_gui",true)) {
                e.setCancelled(true);
                e.getPlayer().openWorkbench(e.getClickedBlock().getLocation(), true);
                return;
            }
        }
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || !e.getAction().isRightClick()) return;
        ItemStack held=e.getItem();
        if (held != null && held.hasItemMeta()
            && held.getItemMeta().getPersistentDataContainer().has(selectorKey, PersistentDataType.BYTE)) {
            e.setCancelled(true);
            openSelectorGui(e.getPlayer());
            return;
        }
        Effect effect=itemEffect(held);
        if(effect==Effect.EMPTY || !e.getPlayer().isSneaking() || !getConfig().getBoolean("items.sneak-equip", false)) return;
        e.setCancelled(true);
        if(applyEffect(e.getPlayer(),effect,isAugmented(held)) && held!=null && held.getAmount()>0) held.setAmount(held.getAmount()-1);
    }

    private boolean equipConsumedEffect(Player p, Effect e, boolean augmented) {
        Effect[] equipped = slots(p);
        int slot = equipped[0] == Effect.EMPTY ? 0 : equipped[1] == Effect.EMPTY ? 1
            : getConfig().getBoolean("settings.replace-second-on-consume", true) ? 1 : -1;
        if (slot < 0) { p.sendMessage("§cBoth effect slots are full."); return false; }
        if (getConfig().getBoolean("settings.enforce-role-slots", false)
            && equipped[1-slot] != Effect.EMPTY && isSupport(equipped[1-slot]) == isSupport(e)) {
            p.sendMessage(isSupport(e) ? "§cYou can only equip one support effect." : "§cYou can only equip one primary effect.");
            return false;
        }
        if (equipped[slot] != Effect.EMPTY) {
            if (p.getInventory().firstEmpty() == -1) { p.sendMessage("§cMake room to replace the second slot."); return false; }
            p.getInventory().addItem(effectItem(equipped[slot], augSlots(p)[slot]));
        }
        equipped[slot] = e;
        augSlots(p)[slot] = augmented;
        p.sendMessage("§aApplied " + (augmented ? "Augmented " : "") + e.display() + " to slot " + (slot+1) + ".");
        saveData();
        return true;
    }

    private boolean isSupport(Effect e) {
        return e==Effect.EMERALD || e==Effect.OCEAN || e==Effect.SPEED || e==Effect.FIRE;
    }

    private boolean applyEffect(Player p,Effect e,boolean augmented) {
        Effect[] s=slots(p);
        int slot=s[0]==Effect.EMPTY?0:s[1]==Effect.EMPTY?1:-1;
        if(slot<0){p.sendMessage("§cBoth effect slots are full.");return false;}
        if(getConfig().getBoolean("settings.enforce-role-slots", false) && s[1-slot]!=Effect.EMPTY && isSupport(s[1-slot])==isSupport(e)) {
            p.sendMessage(isSupport(e)?"§cYou can only equip one support effect.":"§cYou can only equip one primary effect.");
            return false;
        }
        s[slot]=e;
        augSlots(p)[slot]=augmented;
        p.sendMessage("§aApplied "+(augmented?"Augmented ":"")+e.display()+" to slot "+(slot+1)+".");
        saveData();
        return true;
    }

    private boolean trusted(Player a,Entity b) {
        return b instanceof Player q && (q.equals(a) || trusted.getOrDefault(a.getUniqueId(),Set.of()).contains(q.getUniqueId()));
    }

    private boolean spark(Player p,int slot) {
        Effect e=slots(p)[slot];
        if(e==Effect.EMPTY){p.sendMessage("§cNo effect in slot "+(slot+1)+".");return false;}
        long[] c=cooldownUntil.computeIfAbsent(p.getUniqueId(),k->new long[2]);
        long now=System.currentTimeMillis();
        if(c[slot]>now){p.sendMessage("§cCooldown: "+((c[slot]-now+999)/1000)+"s");return false;}
        boolean aug=augSlots(p)[slot];
        if (e == Effect.THIEF) thiefSparkUsed.computeIfAbsent(p.getUniqueId(), k -> new boolean[2])[slot] = false;
        long duration=duration(p,e,aug);
        long cd=cooldown(p,e,aug);
        c[slot]=now+(duration+cd)*1000L;
        activeUntil.computeIfAbsent(p.getUniqueId(),k->new long[2])[slot]=now+duration*1000L;
        executeSpark(p,e,slot,aug);
        saveData();
        return true;
    }

    private long duration(Player p,Effect e,boolean augmented) {
        long regular=getConfig().getLong(e.id()+".duration.default",15);
        long aug=getConfig().getLong(e.id()+".duration.augmented",regular);
        return augmented?aug:regular;
    }

    private long cooldown(Player p,Effect e,boolean augmented) {
        long regular=getConfig().getLong(e.id()+".cooldown.default",60);
        long aug=getConfig().getLong(e.id()+".cooldown.augmented",Math.max(1,(regular+1)/2));
        return augmented?aug:regular;
    }

    private void executeSpark(Player p,Effect e,int slot,boolean augmented) {
        Location l=p.getLocation();
        long ticks=duration(p,e,augmented)*20L;
        p.playSound(l, Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1f);
        switch(e) {
            case EMERALD -> p.addPotionEffect(new PotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE,(int)ticks,4,false,false));
            case ENDER -> {
                Location start=p.getEyeLocation();
                Vector dir=start.getDirection().normalize();
                Location target=null;
                int max=getConfig().getInt("ender.spark.max_distance",15);
                for(int i=1;i<=max;i++){Location q=start.clone().add(dir.clone().multiply(i));if(q.getBlock().isPassable()&&q.clone().add(0,1,0).getBlock().isPassable())target=q;else break;}
                if(target!=null){target.setYaw(p.getYaw());target.setPitch(p.getPitch());p.teleport(target);}
            }
            case FEATHER -> { p.setVelocity(new Vector(0,1,0)); p.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION,20,10)); }
            case FIRE -> {
                double r=getConfig().getDouble("fire.spark.radius",5);
                for(Entity x:p.getNearbyEntities(r,r,r)) if(x instanceof LivingEntity le && x!=p && !trusted(p,x)) le.setFireTicks(100);
                p.getWorld().spawnParticle(Particle.EXPLOSION,l,1);
            }
            case FROST -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.UNLUCK,300,0));
                double r=getConfig().getDouble("frost.spark.radius",5);
                for(Entity x:p.getNearbyEntities(r,r,r)) if(x instanceof Player q && !trusted(p,q)){
                    AttributeInstance jump=q.getAttribute(Attribute.JUMP_STRENGTH);
                    if (jump==null) continue;
                    double originalJump=jump.getBaseValue();
                    jump.setBaseValue(0.1);
                    getServer().getScheduler().runTaskLater(this,()->{
                        AttributeInstance currentJump=q.getAttribute(Attribute.JUMP_STRENGTH);
                        if(currentJump!=null && Math.abs(currentJump.getBaseValue()-0.1)<0.0001) currentJump.setBaseValue(originalJump);
                    },ticks);
                }
            }
            case HASTE -> p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,(int)ticks,3,false,false));
            case HEART -> {
                AttributeInstance a=p.getAttribute(Attribute.MAX_HEALTH);
                if(a!=null){a.setBaseValue(a.getBaseValue()+10);p.setHealth(a.getValue());getServer().getScheduler().runTaskLater(this,()->{AttributeInstance x=p.getAttribute(Attribute.MAX_HEALTH);if(x!=null)x.setBaseValue(Math.max(20,x.getBaseValue()-10));},ticks);}
            }
            case INVIS -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,(int)ticks,0,false,false));
                double radius = getConfig().getDouble("invis.spark.radius",10);
                Map<UUID,Set<UUID>> hidden = new HashMap<>();
                for (Player target : Bukkit.getOnlinePlayers()) {
                    if (target.getWorld() != p.getWorld() || target == p
                        || target.getLocation().distanceSquared(l) > radius*radius || !trusted(p,target)) continue;
                    Set<UUID> viewers = new HashSet<>();
                    for (Player viewer : Bukkit.getOnlinePlayers()) {
                        if (viewer == target || trusted(target,viewer)) continue;
                        viewer.hidePlayer(this,target);
                        viewers.add(viewer.getUniqueId());
                    }
                    hidden.put(target.getUniqueId(),viewers);
                }
                invisSparkHidden.put(p.getUniqueId(),hidden);
                getServer().getScheduler().runTaskLater(this,() -> {
                    for (var entry : hidden.entrySet()) {
                        Player target = Bukkit.getPlayer(entry.getKey());
                        if (target == null) continue;
                        for (UUID viewerId : entry.getValue()) {
                            Player viewer = Bukkit.getPlayer(viewerId);
                            if (viewer != null) viewer.showPlayer(this,target);
                        }
                    }
                    invisSparkHidden.remove(p.getUniqueId(),hidden);
                },ticks);
            }
            case OCEAN -> {
                double r=getConfig().getDouble("ocean.spark.drown_radius",5);
                for(Entity x:p.getNearbyEntities(r,r,r)) if(x instanceof LivingEntity le && !trusted(p,x)) le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,(int)ticks,3));
            }
            case REGEN -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,(int)ticks,2,false,false));
                double r=getConfig().getDouble("regen.spark.heal_trusted_radius",5);
                for(Entity x:p.getNearbyEntities(r,r,r)) if(x instanceof Player q && trusted(p,q)) q.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,(int)ticks,1,false,false));
            }
            case SPEED -> {
                Vector boost=p.getEyeLocation().getDirection().normalize();
                double mult=getConfig().getDouble("speed.spark.dash_multiplier",2);
                p.setVelocity(p.getVelocity().add(boost.multiply(mult)));
            }
            case STRENGTH -> { /* The spark grants a temporary critical hit, handled by onDamage. */ }
            case THUNDER -> {
                double base=getConfig().getDouble("thunder.spark.base_radius",10), per=getConfig().getDouble("thunder.spark.per_player_boost_radius",0.3);
                int maxHits=getConfig().getInt("thunder.spark.strikes_per_player",3);
                getServer().getScheduler().runTaskTimer(this,new BukkitRunnable(){int ticks=0;Map<UUID,Integer> hits=new HashMap<>();public void run(){if(ticks>=ticksDuration()){cancel();return;}double r=base+per*Bukkit.getOnlinePlayers().stream().filter(q->q.getWorld()==p.getWorld()&&q.getLocation().distanceSquared(p.getLocation())<=base*base).count();for(Entity x:p.getNearbyEntities(r,r,r))if(x instanceof Player q&&q!=p&&!trusted(p,q)&&hits.getOrDefault(q.getUniqueId(),0)<maxHits){thunderStrike(q,p);hits.merge(q.getUniqueId(),1,Integer::sum);}ticks+=10;}private long ticksDuration(){return duration(p,e,augmented)*20L;}},0L,10L);
            }
            case APOPHIS -> {
                double r=getConfig().getDouble("apophis.spark.radius",5);
                for(Entity x:p.getNearbyEntities(r,r,r)) if(x instanceof LivingEntity le && !trusted(p,x)) {
                    le.damage(8,p);
                    le.setFireTicks(100);
                    if (x instanceof Player target) target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,60,0,false,false));
                }
                p.addPotionEffect(new PotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE,(int)ticks,4,false,false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,(int)ticks,1,false,false));
                AttributeInstance maxHealth=p.getAttribute(Attribute.MAX_HEALTH);
                if (maxHealth!=null) {
                    double base=maxHealth.getBaseValue();
                    maxHealth.setBaseValue(base+10);
                    p.setHealth(Math.min(maxHealth.getValue(),p.getHealth()+10));
                    getServer().getScheduler().runTaskLater(this,()->{
                        AttributeInstance current=p.getAttribute(Attribute.MAX_HEALTH);
                        if (current==null) return;
                        current.setBaseValue(Math.max(base,current.getBaseValue()-10));
                        if (p.getHealth()>current.getValue()) p.setHealth(current.getValue());
                    },ticks);
                }
                double finalRadius=Math.max(0,getConfig().getDouble("apophis.spark.explosion_radius",5));
                double finalDamage=Math.max(0,getConfig().getDouble("apophis.spark.explosion_damage",6));
                double finalPush=Math.max(0,getConfig().getDouble("apophis.spark.explosion_push",1.2));
                getServer().getScheduler().runTaskLater(this,()->{
                    if (!p.isOnline() || p.isDead()) return;
                    Location center=p.getLocation();
                    p.getWorld().spawnParticle(Particle.EXPLOSION,center,1);
                    p.getWorld().playSound(center,Sound.ENTITY_GENERIC_EXPLODE,1f,1f);
                    for (Entity x : p.getWorld().getNearbyEntities(center,finalRadius,finalRadius,finalRadius))
                        if (x instanceof LivingEntity living && x!=p && !trusted(p,x)) {
                            living.damage(finalDamage,p);
                            living.setFireTicks(100);
                            Vector push=living.getLocation().toVector().subtract(center.toVector());
                            if (push.lengthSquared()>0.0001) living.setVelocity(push.normalize().multiply(finalPush).setY(0.45));
                        }
                },ticks);
            }
            case THIEF -> { /* Steals one of the target's sparks on the next hit. */ }
            default -> {}
        }
        p.sendActionBar(Component.text(e.display()+" spark activated",NamedTextColor.LIGHT_PURPLE));
    }

    private void showTargetHealth(LivingEntity target) {
        TextDisplay old = heartHealthDisplays.remove(target.getUniqueId());
        if (old != null && old.isValid()) old.remove();
        TextDisplay display = target.getWorld().spawn(target.getLocation().add(0,2.5,0),TextDisplay.class);
        display.setPersistent(false);
        display.setGravity(false);
        display.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
        display.setSeeThrough(true);
        target.addPassenger(display);
        heartHealthDisplays.put(target.getUniqueId(),display);
        new BukkitRunnable() {
            int elapsed = 0;
            @Override public void run() {
                if (!target.isValid() || target.isDead() || !display.isValid() || elapsed >= 200) {
                    if (display.isValid()) display.remove();
                    heartHealthDisplays.remove(target.getUniqueId(),display);
                    cancel();
                    return;
                }
                double health = target.getHealth();
                double absorption = target.getAbsorptionAmount();
                display.setText(String.format(Locale.ROOT,"%.1f ❤",health+absorption));
                display.setCustomNameVisible(true);
                elapsed += 10;
            }
        }.runTaskTimer(this,0L,10L);
    }

    private void applyThiefDisguise(Player thief, Player victim) {
        removeThiefDisguise(thief);
        long disguiseDuration=Math.max(1,getConfig().getLong("thief.passive.disguise_duration_seconds",3600));
        ThiefDisguise old = new ThiefDisguise(thief.displayName(),thief.customName(),
            thief.isCustomNameVisible(),thief.getPlayerProfile(),System.currentTimeMillis()+disguiseDuration*1000L,
            ConcurrentHashMap.newKeySet());
        thiefDisguises.put(thief.getUniqueId(),old);
        thief.displayName(victim.displayName());
        thief.customName(victim.customName() == null ? Component.text(victim.getName()) : victim.customName());
        thief.setCustomNameVisible(true);
        com.destroystokyo.paper.profile.PlayerProfile disguised = thief.getPlayerProfile();
        disguised.setTextures(victim.getPlayerProfile().getTextures());
        thief.setPlayerProfile(disguised);
        for (Player viewer : Bukkit.getOnlinePlayers()) hideDisguisedPlayer(viewer,thief);
        Bukkit.getScheduler().runTaskLater(this,() -> {
            if (thiefDisguises.get(thief.getUniqueId()) == old) removeThiefDisguise(thief);
        },disguiseDuration*20L);
    }

    private void restoreInvisibilitySparkVisibility() {
        for (Map<UUID,Set<UUID>> hidden : invisSparkHidden.values())
            for (var entry : hidden.entrySet()) {
                Player target=Bukkit.getPlayer(entry.getKey());
                if (target==null) continue;
                for (UUID viewerId : entry.getValue()) {
                    Player viewer=Bukkit.getPlayer(viewerId);
                    if (viewer!=null) viewer.showPlayer(this,target);
                }
            }
        invisSparkHidden.clear();
    }

    private void hideDisguisedPlayer(Player viewer, Player disguised) {
        ThiefDisguise state=thiefDisguises.get(disguised.getUniqueId());
        if (state==null || viewer==disguised || !viewer.isOnline() || !viewer.isListed(disguised)) return;
        if (viewer.unlistPlayer(disguised)) state.unlistedViewers().add(viewer.getUniqueId());
    }

    private void removeThiefDisguise(Player player) {
        ThiefDisguise disguise = thiefDisguises.remove(player.getUniqueId());
        if (disguise == null) return;
        player.displayName(disguise.displayName());
        player.customName(disguise.customName());
        player.setCustomNameVisible(disguise.customNameVisible());
        player.setPlayerProfile(disguise.profile());
        for (UUID viewerId : disguise.unlistedViewers()) {
            Player viewer=Bukkit.getPlayer(viewerId);
            if (viewer==null || !viewer.isOnline()) continue;
            try { if (viewer.canSee(player) && !viewer.isListed(player)) viewer.listPlayer(player); }
            catch (IllegalStateException ignored) {}
        }
    }

    @EventHandler public void clearThiefDisguiseOnQuit(PlayerQuitEvent event) {
        Player player=event.getPlayer();
        UUID id=player.getUniqueId();
        removeThiefDisguise(player);
        speedLevels.remove(id);
        speedLastHit.remove(id);
        oceanDrownAt.remove(id);
        oceanPullAt.remove(id);
        activeUntil.remove(id);
    }

    private void restoreAllFrostSnow() {
        for (Location location : new ArrayList<>(frostSnowBlocks.keySet())) {
            World world=location.getWorld();
            if (world==null) { frostSnowBlocks.remove(location); continue; }
            if (!world.isChunkLoaded(location.getBlockX() >> 4,location.getBlockZ() >> 4)) continue;
            Block block=location.getBlock();
            if (block.getType()==Material.SNOW_BLOCK) block.setType(Material.POWDER_SNOW);
            frostSnowBlocks.remove(location);
        }
    }

    @EventHandler public void restoreFrostSnowOnChunkLoad(ChunkLoadEvent event) {
        for (Location location : new ArrayList<>(frostSnowBlocks.keySet())) {
            if (location.getWorld()!=event.getWorld()
                || (location.getBlockX() >> 4)!=event.getChunk().getX()
                || (location.getBlockZ() >> 4)!=event.getChunk().getZ()) continue;
            Block block=location.getBlock();
            if (block.getType()==Material.SNOW_BLOCK) block.setType(Material.POWDER_SNOW);
            frostSnowBlocks.remove(location);
        }
    }

    private void updateFrostSnow(Player player) {
        int radius = Math.max(1,getConfig().getInt("frost.passive.snow_changing_radius",3));
        Location center = player.getLocation();
        for (int dx=-radius; dx<=radius; dx++)
            for (int dy=-radius; dy<=radius; dy++)
                for (int dz=-radius; dz<=radius; dz++) {
                    Block block = center.clone().add(dx,dy,dz).getBlock();
                    if (block.getType() != Material.POWDER_SNOW
                        || block.getRelative(org.bukkit.block.BlockFace.UP).getType() != Material.AIR) continue;
                    Location location = block.getLocation().clone();
                    block.setType(Material.SNOW_BLOCK);
                    frostSnowBlocks.put(location,Boolean.TRUE);
                }
        restoreFrostSnow();
    }

    private void restoreFrostSnow() {
        int radius = Math.max(1,getConfig().getInt("frost.passive.snow_changing_radius",3));
        for (Location location : new ArrayList<>(frostSnowBlocks.keySet())) {
            World world=location.getWorld();
            if (world==null) { frostSnowBlocks.remove(location); continue; }
            if (!world.isChunkLoaded(location.getBlockX() >> 4,location.getBlockZ() >> 4)) continue;
            Block block = location.getBlock();
            if (block.getType() != Material.SNOW_BLOCK) {
                frostSnowBlocks.remove(location);
                continue;
            }
            boolean frostPlayerNearby = Bukkit.getOnlinePlayers().stream().anyMatch(player ->
                player.getWorld().equals(location.getWorld())
                    && Arrays.asList(slots(player)).contains(Effect.FROST)
                    && player.getLocation().distanceSquared(location) <= radius*radius);
            if (!frostPlayerNearby) {
                block.setType(Material.POWDER_SNOW);
                frostSnowBlocks.remove(location);
            }
        }
    }

    private void boostLavaMovement(Player player, String configPath) {
        if (!player.isInLava()) return;
        Vector velocity=player.getVelocity();
        if (velocity.lengthSquared()<0.01) return;
        Vector direction=player.getLocation().getDirection().setY(0);
        if (direction.lengthSquared()<0.001) return;
        double speed=Math.max(0,getConfig().getDouble(configPath,0.6));
        direction.normalize().multiply(speed);
        player.setVelocity(new Vector(direction.getX(),velocity.getY(),direction.getZ()));
    }

    private void tickEffects() {
        long tickNow=System.currentTimeMillis();
        cursedPlayers.entrySet().removeIf(entry -> entry.getValue()<=tickNow);
        foodXpLockedUntil.entrySet().removeIf(entry -> entry.getValue()<=tickNow);
        restoreExpiredThiefSteals();
        restoreFrostSnow();
        for(Player p:Bukkit.getOnlinePlayers()) {
            updateHasteItems(p);
            Effect[] ss=slots(p);
            if (!Arrays.asList(ss).contains(Effect.THIEF)) removeThiefDisguise(p);
            for(int i=0;i<2;i++) {
                Effect e=ss[i]; if(e==Effect.EMPTY) continue;
                if(getConfig().getStringList(e.id()+".blacklisted_worlds").contains(p.getWorld().getKey().toString())) continue;
                switch(e) {
                    case EMERALD -> {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE,40,2,true,false));
                        if(isSparkActive(p,i)) p.addPotionEffect(new PotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE,40,4,true,false));
                    }
                    case ENDER -> {
                        double radius = getConfig().getDouble("ender.passive.radius",10);
                        for (Player target : p.getWorld().getPlayers()) {
                            if (target == p || trusted(p,target)
                                || target.getLocation().distanceSquared(p.getLocation()) > radius*radius) continue;
                            target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,30,0,true,false));
                        }
                    }
                    case FEATHER -> {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,40,0,true,false));
                        if (isSparkActive(p,i) && p.isOnGround() && p.getVelocity().getY() <= 0.1) {
                            activeUntil.computeIfAbsent(p.getUniqueId(), k -> new long[2])[i] = 0;
                            double radius = getConfig().getDouble("feather.land.radius", 4);
                            double damage = getConfig().getDouble("feather.land.damage", 8);
                            for (Entity target : p.getNearbyEntities(radius,radius,radius)) {
                                if (!(target instanceof LivingEntity living) || target == p || trusted(p,target)) continue;
                                living.damage(damage,p);
                                living.setVelocity(living.getVelocity().add(new Vector(0,1,0)));
                                living.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,80,0,false,false));
                            }
                            p.getWorld().spawnParticle(Particle.CLOUD,p.getLocation(),50,0,0,0,0.2);
                            p.getWorld().playSound(p.getLocation(),Sound.ITEM_MACE_SMASH_GROUND_HEAVY,1.5f,1f);
                        }
                    }
                    case FIRE -> {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,40,0,true,false));
                        boostLavaMovement(p,"fire.passive.lava_walk_speed");
                    }
                    case FROST -> {
                        Material below=p.getLocation().subtract(0,1,0).getBlock().getType();
                        if(below.name().contains("ICE")) p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,40,2,true,false));
                        if (p.isInPowderedSnow() && p.getVelocity().lengthSquared() > 0.01) {
                            p.setGliding(true);
                            p.setVelocity(p.getLocation().getDirection().normalize()
                                .multiply(getConfig().getDouble("frost.passive.powdered_snow_walk_speed",0.6)));
                        }
                        updateFrostSnow(p);
                    }
                    case HASTE -> p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,40,1,true,false));
                    case HEART -> {
                        AttributeInstance max=p.getAttribute(Attribute.MAX_HEALTH);
                        if(max!=null && max.getBaseValue()<30) { max.setBaseValue(30); if(p.getHealth()>30) p.setHealth(30); }
                    }
                    case INVIS -> p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,40,0,true,false));
                    case OCEAN -> {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING,40,0,true,false));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE,40,0,true,false));
                        long now = System.currentTimeMillis();
                        boolean spark = isSparkActive(p,i);
                        long drownInterval = Math.max(1, getConfig().getInt(spark ? "ocean.spark.drown_interval" : "ocean.passive.drown_interval",1))*1000L;
                        if (now-oceanDrownAt.getOrDefault(p.getUniqueId(),0L) >= drownInterval) {
                            oceanDrownAt.put(p.getUniqueId(),now);
                            String root = spark ? "ocean.spark." : "ocean.passive.";
                            double radius = getConfig().getDouble(root+"drown_radius",5);
                            int strength = Math.max(1,getConfig().getInt(root+"drown_strength",spark?20:5));
                            double damage = getConfig().getDouble(root+"drown_damage",spark?2:1);
                            for (Player target : p.getWorld().getPlayers()) {
                                if (target == p || trusted(p,target) || target.getLocation().distanceSquared(p.getLocation()) > radius*radius) continue;
                                int air = Math.max(target.getRemainingAir()-strength,-20);
                                target.setRemainingAir(air);
                                if (air <= 0) target.damage(damage,p);
                            }
                        }
                        if (spark) {
                            long pullInterval = Math.max(1,getConfig().getInt("ocean.spark.pull_interval",20))*50L;
                            if (now-oceanPullAt.getOrDefault(p.getUniqueId(),0L) >= pullInterval) {
                                oceanPullAt.put(p.getUniqueId(),now);
                                double radius = getConfig().getDouble("ocean.spark.pull_radius",5);
                                double pull = getConfig().getDouble("ocean.spark.pull_strength",0.3);
                                for (Player target : p.getWorld().getPlayers()) {
                                    if (target == p || trusted(p,target) || target.getLocation().distanceSquared(p.getLocation()) > radius*radius) continue;
                                    Vector direction = p.getLocation().toVector().subtract(target.getLocation().toVector());
                                    if (direction.lengthSquared() > 0.0001) target.setVelocity(direction.normalize().multiply(pull));
                                }
                            }
                        }
                    }
                    case REGEN -> p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,40,1,true,false));
                    case SPEED -> {
                        long lastHit = speedLastHit.getOrDefault(p.getUniqueId(),0L);
                        if (System.currentTimeMillis()-lastHit > 1000L) speedLevels.put(p.getUniqueId(),0);
                        int level = speedLevels.getOrDefault(p.getUniqueId(),0);
                        int multiplier = Math.max(1,getConfig().getInt("speed.spark.player_velocity_multiplier",2));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,40,Math.min(10,level*multiplier),true,false));
                    }
                    case STRENGTH -> {}
                    case THUNDER -> {}
                    case APOPHIS -> {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,40,0,true,false));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,40,0,true,false));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE,40,2,true,false));
                        boostLavaMovement(p,"apophis.passive.lava_walk_speed");
                    }
                    case THIEF -> {}
                    default -> {}
                }
            }
            AttributeInstance max=p.getAttribute(Attribute.MAX_HEALTH);
            boolean hasHeart=Arrays.asList(ss).contains(Effect.HEART) || Arrays.asList(ss).contains(Effect.APOPHIS);
            if(max!=null) {
                if (hasHeart) {
                    heartBaseHealth.putIfAbsent(p.getUniqueId(),max.getBaseValue()<30?max.getBaseValue():20.0);
                    if(max.getBaseValue()<30) max.setBaseValue(30);
                } else {
                    Double original=heartBaseHealth.remove(p.getUniqueId());
                    if (max.getBaseValue()==30) max.setBaseValue(original==null?20.0:original);
                    if(p.getHealth()>max.getValue()) p.setHealth(max.getValue());
                }
            }
        }
    }

    @EventHandler public void emeraldLooting(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player) return;
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        int level = 0;
        for (Effect effect : slots(killer)) if (effect == Effect.EMERALD || effect == Effect.APOPHIS)
            level = Math.max(level,getConfig().getInt(effect.id()+".passive.looting_level",0));
        if (level <= 0 || event.getDrops().isEmpty()) return;
        List<ItemStack> originals = new ArrayList<>(event.getDrops());
        Random random = new Random();
        for (ItemStack original : originals) {
            int bonus = 0;
            for (int roll=0; roll<level; roll++)
                if (random.nextDouble() < 1.0/(level+1.0)) bonus++;
            for (int i=0; i<bonus; i++) {
                ItemStack extra = original.clone();
                extra.setAmount(1);
                event.getDrops().add(extra);
            }
        }
    }

    @EventHandler public void emeraldFoodLock(PlayerItemConsumeEvent event) {
        long lockedUntil = foodXpLockedUntil.getOrDefault(event.getPlayer().getUniqueId(),0L);
        if (lockedUntil > System.currentTimeMillis()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cYour food and experience are locked for "
                + Math.max(1,(lockedUntil-System.currentTimeMillis()+999)/1000) + "s.");
        } else if (lockedUntil > 0) foodXpLockedUntil.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler public void regenAlwaysEat(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick() || event.getItem() == null
            || !event.getItem().getType().isEdible()
            || !Arrays.asList(slots(event.getPlayer())).contains(Effect.REGEN)
            || foodXpLockedUntil.getOrDefault(event.getPlayer().getUniqueId(),0L)>System.currentTimeMillis()
            || event.getPlayer().getFoodLevel() < 20) return;
        UUID id=event.getPlayer().getUniqueId();
        forcedFoodChanges.add(id);
        try { event.getPlayer().setFoodLevel(19); }
        finally { forcedFoodChanges.remove(id); }
    }

    @EventHandler public void regenFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player
            && !forcedFoodChanges.contains(player.getUniqueId())
            && Arrays.asList(slots(player)).contains(Effect.REGEN)) event.setFoodLevel(20);
    }

    @EventHandler public void regenEating(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (event.isCancelled() || itemEffect(event.getItem()) != Effect.EMPTY || !Arrays.asList(slots(player)).contains(Effect.REGEN)) return;
        player.setSaturation(Math.min(20f,player.getSaturation()+6f));
    }

    @EventHandler public void featherFallDamage(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL
            && event.getEntity() instanceof Player player
            && Arrays.asList(slots(player)).contains(Effect.FEATHER)) event.setCancelled(true);
    }

    @EventHandler public void featherWindChargeCooldown(PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || !event.getAction().isRightClick()
            || event.getItem() == null || event.getItem().getType() != Material.WIND_CHARGE
            || !Arrays.asList(slots(event.getPlayer())).contains(Effect.FEATHER)) return;
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this,() -> player.setCooldown(Material.WIND_CHARGE,5),1L);
    }

    @EventHandler public void frostCannotUseWindChargeWhileFrozen(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!event.getAction().isRightClick() || event.getItem() == null
            || event.getItem().getType() != Material.WIND_CHARGE
            || player.getFreezeTicks() <= 1
            || !Arrays.asList(slots(player)).contains(Effect.FROST)) return;
        event.setCancelled(true);
    }

    @EventHandler public void featherWindCharge(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof WindCharge windCharge)
            || !(windCharge.getShooter() instanceof Player player)
            || !Arrays.asList(slots(player)).contains(Effect.FEATHER)) return;
        windCharge.setVelocity(player.getEyeLocation().getDirection().normalize().multiply(2));
    }

    @EventHandler public void fireFallProtection(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL
            || !(event.getEntity() instanceof Player player)
            || !(Arrays.asList(slots(player)).contains(Effect.FIRE) || Arrays.asList(slots(player)).contains(Effect.APOPHIS))) return;
        Material current = player.getLocation().getBlock().getType();
        Material below = player.getLocation().subtract(0,1,0).getBlock().getType();
        if (current == Material.LAVA || current == Material.LAVA_CAULDRON || below == Material.LAVA)
            event.setCancelled(true);
    }

    @EventHandler public void fireCookableBlockDrops(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!(Arrays.asList(slots(player)).contains(Effect.FIRE) || Arrays.asList(slots(player)).contains(Effect.APOPHIS))
            || player.getInventory().getItemInMainHand().containsEnchantment(org.bukkit.enchantments.Enchantment.SILK_TOUCH))
            return;
        ItemStack input = new ItemStack(event.getBlock().getType());
        if (!input.getType().isItem()) return;
        Iterator<Recipe> recipes = Bukkit.recipeIterator();
        while (recipes.hasNext()) {
            Recipe recipe = recipes.next();
            if (!(recipe instanceof CookingRecipe<?> cooking)
                || !cooking.getInputChoice().test(input)) continue;
            event.setDropItems(false);
            event.getBlock().getWorld().dropItemNaturally(
                event.getBlock().getLocation().add(0.5,0.5,0.5),cooking.getResult().clone());
            return;
        }
    }

    @EventHandler public void fireBowIgnition(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)
            || !(Arrays.asList(slots(player)).contains(Effect.FIRE) || Arrays.asList(slots(player)).contains(Effect.APOPHIS))
            || event.getForce() < 1.0f || !(event.getProjectile() instanceof Projectile projectile)) return;
        projectile.setFireTicks(100);
    }

    @EventHandler public void speedBowVelocity(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)
            || !Arrays.asList(slots(player)).contains(Effect.SPEED)) return;
        double bonus=Math.max(0,getConfig().getDouble("speed.passive.bow_velocity_multiplier",1.0));
        event.getProjectile().setVelocity(event.getProjectile().getVelocity().multiply(1.0 + Math.max(0.0,event.getForce())*bonus));
    }

    @EventHandler public void strengthArrowPiercing(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow) || !(arrow.getShooter() instanceof Player player)
            || !Arrays.asList(slots(player)).contains(Effect.STRENGTH)) return;
        arrow.setPierceLevel(1);
    }

    @EventHandler public void invisArrowBlindness(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow) || !(arrow.getShooter() instanceof Player shooter)
            || !(event.getHitEntity() instanceof Player target)
            || !Arrays.asList(slots(shooter)).contains(Effect.INVIS) || trusted(shooter,target)) return;
        target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,80,0,false,false));
        target.getWorld().spawnParticle(Particle.SQUID_INK,target.getLocation().add(0,1,0),24,.5,.7,.5,.01);
    }

    @EventHandler public void hasteOreFortune(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!Arrays.asList(slots(player)).contains(Effect.HASTE)) return;
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!tool.getType().name().endsWith("_PICKAXE") || tool.containsEnchantment(org.bukkit.enchantments.Enchantment.SILK_TOUCH)) return;
        Material block = event.getBlock().getType();
        if (!block.name().endsWith("_ORE")) return;
        int level = Math.max(1,getConfig().getInt("haste.passive.fortune_level",5));
        ItemStack boostedTool = tool.clone();
        boostedTool.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.FORTUNE,level);
        Collection<ItemStack> drops = event.getBlock().getDrops(boostedTool,player);
        event.setDropItems(false);
        for (ItemStack drop : drops) event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(),drop);
    }

    private List<org.bukkit.enchantments.Enchantment> hasteEnchantments(ItemStack item) {
        if (item == null) return List.of();
        String type = item.getType().name();
        if (type.endsWith("_PICKAXE") || type.endsWith("_AXE")
            || type.endsWith("_SHOVEL") || type.endsWith("_HOE")) return List.of(
            org.bukkit.enchantments.Enchantment.FORTUNE,
            org.bukkit.enchantments.Enchantment.EFFICIENCY,
            org.bukkit.enchantments.Enchantment.UNBREAKING);
        return List.of();
    }

    private NamespacedKey hasteOriginalKey(org.bukkit.enchantments.Enchantment enchantment) {
        return new NamespacedKey(this,"haste_original_"+enchantment.getKey().getKey());
    }

    private boolean isHasteModified(ItemStack item) {
        return item != null && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(new NamespacedKey(this,"haste_modified"),PersistentDataType.BYTE);
    }

    private void setUnsafeEnchantment(ItemStack item, org.bukkit.enchantments.Enchantment enchantment, int level) {
        if (item.getEnchantmentLevel(enchantment) != level)
            item.addUnsafeEnchantment(enchantment,level);
    }

    private void applyHasteEnchantments(ItemStack item) {
        List<org.bukkit.enchantments.Enchantment> enchantments = hasteEnchantments(item);
        if (enchantments.isEmpty()) return;
        ItemMeta meta = item.getItemMeta();
        var data = meta.getPersistentDataContainer();
        NamespacedKey marker = new NamespacedKey(this,"haste_modified");
        if (!data.has(marker,PersistentDataType.BYTE)) {
            data.set(marker,PersistentDataType.BYTE,(byte)1);
            for (var enchantment : enchantments)
                data.set(hasteOriginalKey(enchantment),PersistentDataType.INTEGER,item.getEnchantmentLevel(enchantment));
            item.setItemMeta(meta);
        }
        int fortune = Math.max(1,getConfig().getInt("haste.passive.fortune_level",5));
        int efficiency = Math.max(1,getConfig().getInt("haste.passive.efficiency_level",10));
        int unbreaking = Math.max(1,getConfig().getInt("haste.passive.unbreaking_level",5));
        setUnsafeEnchantment(item,org.bukkit.enchantments.Enchantment.FORTUNE,fortune);
        setUnsafeEnchantment(item,org.bukkit.enchantments.Enchantment.EFFICIENCY,efficiency);
        setUnsafeEnchantment(item,org.bukkit.enchantments.Enchantment.UNBREAKING,unbreaking);
    }

    private void restoreHasteEnchantments(ItemStack item) {
        if (!isHasteModified(item)) return;
        ItemMeta meta = item.getItemMeta();
        var data = meta.getPersistentDataContainer();
        for (var enchantment : hasteEnchantments(item)) {
            NamespacedKey key = hasteOriginalKey(enchantment);
            Integer level = data.get(key,PersistentDataType.INTEGER);
            if (level != null && level > 0) meta.addEnchant(enchantment,level,true);
            else meta.removeEnchant(enchantment);
            data.remove(key);
        }
        data.remove(new NamespacedKey(this,"haste_modified"));
        item.setItemMeta(meta);
    }

    private void updateHasteItems(Player player) {
        boolean hasHaste = Arrays.asList(slots(player)).contains(Effect.HASTE);
        int heldSlot = player.getInventory().getHeldItemSlot();
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot=0; slot<contents.length; slot++) {
            ItemStack item = contents[slot];
            if (slot == heldSlot && hasHaste) applyHasteEnchantments(item);
            else if (isHasteModified(item)) restoreHasteEnchantments(item);
        }
    }

    @EventHandler public void hasteItemDropped(PlayerDropItemEvent event) {
        restoreHasteEnchantments(event.getItemDrop().getItemStack());
    }

    @EventHandler public void hasteItemMoved(InventoryClickEvent event) {
        restoreHasteEnchantments(event.getCurrentItem());
    }

    @EventHandler public void hasteItemDragged(InventoryDragEvent event) {
        restoreHasteEnchantments(event.getOldCursor());
    }

    @EventHandler public void invisiblePlayersAvoidMobs(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player player
            && Arrays.asList(slots(player)).contains(Effect.INVIS)) event.setCancelled(true);
    }

    @EventHandler public void emeraldExperience(PlayerExpChangeEvent event) {
        Player player = event.getPlayer();
        if (experienceTransferGuard.contains(player.getUniqueId())) return;
        long lockedUntil = foodXpLockedUntil.getOrDefault(player.getUniqueId(),0L);
        if (lockedUntil > System.currentTimeMillis()) { event.setAmount(0); return; }
        if (lockedUntil > 0) foodXpLockedUntil.remove(player.getUniqueId());
        Effect[] equipped = slots(player);
        double multiplier = 1.0;
        for (int slot = 0; slot < equipped.length; slot++) {
            Effect effect = equipped[slot];
            if (effect != Effect.EMERALD && effect != Effect.APOPHIS) continue;
            String root = effect.id();
            double configured = getConfig().getDouble(root + ".passive.xp_multiplier", 1.0);
            if (isSparkActive(player, slot)) configured = getConfig().getDouble(root + ".spark.xp_multiplier", configured);
            multiplier = Math.max(multiplier, configured);
        }
        int original = event.getAmount();
        int boosted = Math.max(0, (int)Math.round(original * multiplier));
        event.setAmount(boosted);
        double sharePercent = 0;
        for (int slot=0; slot<equipped.length; slot++) {
            Effect effect = equipped[slot];
            if ((effect == Effect.EMERALD || effect == Effect.APOPHIS) && isSparkActive(player,slot))
                sharePercent = Math.max(sharePercent,getConfig().getDouble(effect.id()+".passive.percent_xp_to_share",0));
        }
        int shared = Math.min(boosted, Math.max(0,(int)Math.floor(boosted * sharePercent)));
        if (shared <= 0) return;
        List<Player> allies = player.getWorld().getPlayers().stream()
            .filter(ally -> ally != player && trusted(player,ally) && ally.getLocation().distanceSquared(player.getLocation()) <= 16*16)
            .toList();
        if (allies.isEmpty()) return;
        int each = shared / allies.size(), remainder = shared % allies.size();
        for (int i=0; i<allies.size(); i++) {
            int amount = each + (i < remainder ? 1 : 0);
            if (amount > 0) allies.get(i).giveExp(amount);
        }
    }

    @EventHandler public void thunderTrident(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Trident trident) || !(trident.getShooter() instanceof Player player)) return;
        if (!Arrays.asList(slots(player)).contains(Effect.THUNDER) || event.getHitEntity() != null) return;
        Location hit = event.getHitBlock() == null ? trident.getLocation() : event.getHitBlock().getLocation().add(0.5,0.5,0.5);
        player.getWorld().strikeLightning(hit);
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void strengthBreaksShield(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof Player victim)
            || !event.isCancelled() || !victim.isBlocking()
            || !Arrays.asList(slots(attacker)).contains(Effect.STRENGTH)
            || !attacker.getInventory().getItemInMainHand().getType().name().endsWith("_AXE")) return;
        victim.getWorld().playSound(victim.getLocation(),Sound.ITEM_SHIELD_BREAK,1f,1f);
        victim.setCooldown(Material.SHIELD,Math.max(1,getConfig().getInt("strength.passive.shield_disable_ticks",200)));
        double followup = Math.max(1.0,event.getDamage()*Math.max(0,getConfig().getDouble("strength.passive.shield_followup_damage_multiplier",0.5)));
        Bukkit.getScheduler().runTask(this,() -> { if (victim.isOnline() && !victim.isDead()) victim.damage(followup,attacker); });
    }

    private void thunderStrike(LivingEntity target, Player attacker) {
        target.getWorld().strikeLightningEffect(target.getLocation());
        thunderDamageGuard.add(attacker.getUniqueId());
        try { target.damage(2.0,attacker); }
        finally { thunderDamageGuard.remove(attacker.getUniqueId()); }
    }

    private void chainThunder(Player attacker, Player first) {
        Set<UUID> hit = new HashSet<>();
        hit.add(attacker.getUniqueId());
        LivingEntity current = first;
        for (int count=0; count<10; count++) {
            if (current instanceof Player player && trusted(attacker,player)) break;
            hit.add(current.getUniqueId());
            thunderStrike(current,attacker);
            Location lastLocation = current.getLocation();
            Player next = current.getWorld().getPlayers().stream()
                .filter(player -> !hit.contains(player.getUniqueId()) && !trusted(attacker,player)
                    && player.getLocation().distanceSquared(lastLocation) <= 9)
                .min(Comparator.comparingDouble(player -> player.getLocation().distanceSquared(lastLocation)))
                .orElse(null);
            if (next == null) break;
            current = next;
        }
    }

    @EventHandler public void thunderTridentDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Trident trident)
            || !(trident.getShooter() instanceof Player attacker)
            || !(event.getEntity() instanceof LivingEntity target)
            || !Arrays.asList(slots(attacker)).contains(Effect.THUNDER)
            || target instanceof Player player && trusted(attacker,player)) return;
        thunderStrike(target,attacker);
    }

    @EventHandler public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker) || e.isCancelled()) return;
        if (thunderDamageGuard.contains(attacker.getUniqueId())) return;
        if (e.getEntity() instanceof LivingEntity mob && !(mob instanceof Player)
            && Arrays.asList(slots(attacker)).contains(Effect.ENDER)
            && (isSparkActive(attacker,0) && slots(attacker)[0] == Effect.ENDER
                || isSparkActive(attacker,1) && slots(attacker)[1] == Effect.ENDER)) {
            mob.setHealth(0);
            return;
        }
        Effect[] equipped = slots(attacker);
        if (Arrays.asList(equipped).contains(Effect.SPEED)) {
            long now = System.currentTimeMillis();
            long last = speedLastHit.getOrDefault(attacker.getUniqueId(),0L);
            if (now-last >= 50L) {
                if (now-last > 1000L) speedLevels.put(attacker.getUniqueId(),0);
                speedLevels.merge(attacker.getUniqueId(),1,Integer::sum);
                speedLastHit.put(attacker.getUniqueId(),now);
            }
        }
        if (Arrays.asList(equipped).contains(Effect.REGEN)) {
            attacker.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,60,1,false,false));
            if (isSparkActive(attacker,0) || isSparkActive(attacker,1)) {
                double radius = getConfig().getDouble("regen.spark.heal_trusted_radius",5);
                for (Player ally : attacker.getWorld().getPlayers()) {
                    if (ally == attacker || !trusted(attacker,ally)
                        || ally.getLocation().distanceSquared(attacker.getLocation()) > radius*radius) continue;
                    AttributeInstance maxHealth = ally.getAttribute(Attribute.MAX_HEALTH);
                    if (maxHealth != null) ally.setHealth(Math.min(ally.getHealth()+e.getDamage()/2,maxHealth.getValue()));
                }
            }
        }
        boolean hasFeather = Arrays.asList(equipped).contains(Effect.FEATHER);
        if (hasFeather && attacker.getFallDistance() >= 7.0f) {
            attacker.getWorld().playSound(attacker.getLocation(),Sound.ITEM_MACE_SMASH_AIR,1f,1f);
            attacker.getWorld().spawnParticle(Particle.GUST_EMITTER_SMALL,attacker.getLocation(),1,0,0,0,0);
            attacker.setVelocity(new Vector(0,1.8,0));
            e.setDamage(e.getDamage()*1.1);
        }
        if ((Arrays.asList(equipped).contains(Effect.HEART) || Arrays.asList(equipped).contains(Effect.APOPHIS))
            && e.getEntity() instanceof LivingEntity target
            && reachedHitThreshold(attacker,Arrays.asList(equipped).contains(Effect.HEART)?Effect.HEART:Effect.APOPHIS,10)) showTargetHealth(target);
        boolean hasStrength = Arrays.asList(equipped).contains(Effect.STRENGTH);
        if (hasStrength) {
            AttributeInstance maxHealth = attacker.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null) e.setDamage(e.getDamage() + Math.max(0,maxHealth.getValue()-attacker.getHealth())
                *Math.max(0,getConfig().getDouble("strength.passive.missing_health_damage_multiplier",0.3)));
            long[] active = activeUntil.get(attacker.getUniqueId());
            if (active != null && ((equipped[0] == Effect.STRENGTH && active[0] > System.currentTimeMillis())
                || (equipped[1] == Effect.STRENGTH && active[1] > System.currentTimeMillis())))
                e.setDamage(e.getDamage()*Math.max(0,getConfig().getDouble("strength.spark.critical_damage_multiplier",1.5)));
            if (!(e.getEntity() instanceof Player)) e.setDamage(e.getDamage()*Math.max(0,getConfig().getDouble("strength.passive.mob_damage_multiplier",2.0)));
        }
        if (e.getEntity() instanceof Player victim) {
            if (!trusted(attacker,victim)) for (Effect effect : equipped) if (effect == Effect.EMERALD || effect == Effect.APOPHIS) {
                if (!reachedHitThreshold(attacker,effect,10)) continue;
                double seconds = Math.max(0,getConfig().getDouble(effect.id()+".passive.lock_duration_seconds",0));
                if (seconds > 0) {
                    foodXpLockedUntil.merge(victim.getUniqueId(),System.currentTimeMillis()+(long)(seconds*1000),Math::max);
                    victim.sendMessage("§cYour food and experience are locked for "+(int)seconds+" seconds.");
                    saveData();
                }
            }
            if (!trusted(attacker,victim) && Arrays.asList(equipped).contains(Effect.REGEN)
                && reachedHitThreshold(attacker,Effect.REGEN,10)) {
                UUID id=victim.getUniqueId();
                forcedFoodChanges.add(id);
                try { victim.setFoodLevel(Math.max(0,victim.getFoodLevel()-2)); }
                finally { forcedFoodChanges.remove(id); }
            }
            if (Arrays.asList(equipped).contains(Effect.ENDER) && !trusted(attacker,victim))
                cursedPlayers.merge(victim.getUniqueId(),System.currentTimeMillis()+Math.max(1,getConfig().getLong("ender.passive.curse_duration_seconds",60))*1000L,Math::max);
            int stolenExperience = 0;
            if (!trusted(attacker,victim)) for (int slot = 0; slot < equipped.length; slot++) {
                Effect effect = equipped[slot];
                if (effect != Effect.EMERALD && effect != Effect.APOPHIS) continue;
                String root = effect.id() + ".passive.";
                int flat = Math.max(0,getConfig().getInt(root+"xp_stolen_per_hit",0));
                double percent = Math.max(0,getConfig().getDouble(root+"xp_stolen_percent",0));
                stolenExperience = Math.max(stolenExperience, flat + Math.max(0,(int)Math.floor(victim.getTotalExperience()*percent/100.0)));
            }
            if (!trusted(attacker,victim) && stolenExperience > 0 && victim.getTotalExperience() > 0) {
                int amount = Math.min(stolenExperience,victim.getTotalExperience());
                experienceTransferGuard.add(victim.getUniqueId());
                try { victim.giveExp(-amount); }
                finally { experienceTransferGuard.remove(victim.getUniqueId()); }
                attacker.giveExp(amount);
            }
            if (Arrays.asList(equipped).contains(Effect.THUNDER)
                && reachedHitThreshold(attacker,Effect.THUNDER,10) && !trusted(attacker,victim))
                chainThunder(attacker,victim);
            if ((Arrays.asList(equipped).contains(Effect.FIRE) || Arrays.asList(equipped).contains(Effect.APOPHIS))
                && !trusted(attacker,victim)
                && reachedHitThreshold(attacker,Arrays.asList(equipped).contains(Effect.FIRE)?Effect.FIRE:Effect.APOPHIS,10)) victim.setFireTicks(100);
            if (Arrays.asList(equipped).contains(Effect.APOPHIS) && !trusted(attacker,victim))
                victim.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,60,0,false,false));
            if (Arrays.asList(equipped).contains(Effect.FEATHER) && !trusted(attacker,victim)
                && reachedHitThreshold(attacker,Effect.FEATHER,10)) {
                victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,100,2,false,false));
                Location chargeLocation = attacker.getLocation().add(0,1,0);
                WindCharge charge = attacker.getWorld().spawn(chargeLocation,WindCharge.class);
                charge.setShooter(attacker);
                Vector direction = victim.getLocation().toVector().subtract(chargeLocation.toVector());
                if (direction.lengthSquared() > 0.0001) charge.setVelocity(direction.normalize());
                attacker.setVelocity(new Vector(0,0.5,0));
            }
            if (Arrays.asList(equipped).contains(Effect.FROST) && !trusted(attacker,victim)) {
                if (attacker.hasPotionEffect(PotionEffectType.UNLUCK)
                    || isSparkActive(attacker,equipped[0] == Effect.FROST ? 0 : 1))
                    victim.setFreezeTicks(Math.max(victim.getFreezeTicks(),200));
                if (reachedHitThreshold(attacker,Effect.FROST,10))
                    victim.setFreezeTicks(Math.max(victim.getFreezeTicks(),200));
            }
            if (Arrays.asList(equipped).contains(Effect.INVIS) && !trusted(attacker,victim)
                && reachedHitThreshold(attacker,Effect.INVIS,10)) {
                victim.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,80,0,false,false));
                victim.getWorld().spawnParticle(Particle.SQUID_INK,victim.getLocation().add(0,1,0),24,.5,.7,.5,.01);
            }
            boolean[] used = thiefSparkUsed.get(attacker.getUniqueId());
            long[] active = activeUntil.get(attacker.getUniqueId());
            if (used != null && active != null) for (int slot = 0; slot < 2; slot++) {
                if (equipped[slot] != Effect.THIEF || used[slot] || active[slot] <= System.currentTimeMillis()
                    || trusted(attacker,victim)) continue;
                Effect[] victimEffects = slots(victim);
                List<Integer> choices = new ArrayList<>();
                for (int i = 0; i < 2; i++) if (victimEffects[i] != Effect.EMPTY && victimEffects[i] != Effect.THIEF) choices.add(i);
                if (!choices.isEmpty()) {
                    int victimSlot = choices.get(new Random().nextInt(choices.size()));
                    Effect stolen = victimEffects[victimSlot];
                    boolean augmented = augSlots(victim)[victimSlot];
                    long expiresAt = System.currentTimeMillis() + duration(attacker,stolen,augmented)*1000L;
                    victimEffects[victimSlot] = Effect.EMPTY;
                    augSlots(victim)[victimSlot] = false;
                    executeSpark(attacker,stolen,slot,augmented);
                    active[slot] = expiresAt;
                    used[slot] = true;
                    thiefSteals.put(attacker.getUniqueId()+"_"+slot,
                        new ThiefSteal(attacker.getUniqueId(),slot,victim.getUniqueId(),victimSlot,stolen,augmented,expiresAt));
                    attacker.sendMessage("§dYou stole " + stolen.display() + " temporarily.");
                    victim.sendMessage("§cYour " + stolen.display() + " was stolen temporarily.");
                    saveData();
                }
                break;
            }
        }
    }

    @EventHandler public void onDeath(PlayerDeathEvent e) {
        Player p=e.getEntity();
        Player killer = p.getKiller();
        removeThiefDisguise(p);
        if (killer != null && killer != p && Arrays.asList(slots(killer)).contains(Effect.THIEF))
            applyThiefDisguise(killer,p);
        if ((getConfig().getBoolean("invis.hide_deaths", false) && Arrays.asList(slots(p)).contains(Effect.INVIS))
            || (killer != null && getConfig().getBoolean("invis.hide_kills", false) && Arrays.asList(slots(killer)).contains(Effect.INVIS)))
            e.deathMessage((Component)null);
        speedLevels.remove(p.getUniqueId());
        speedLastHit.remove(p.getUniqueId());
        if (getConfig().getBoolean("player_head_drops", true)) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta headMeta = (SkullMeta)head.getItemMeta();
            headMeta.setOwningPlayer(p);
            head.setItemMeta(headMeta);
            e.getDrops().add(head);
        }
        if(!getConfig().getBoolean("drop_on_natural_death",true) && p.getKiller()==null) return;
        String mode=getConfig().getString("effect_drops","random");
        Effect[] s=slots(p);
        List<Integer> drop=new ArrayList<>();
        switch(mode) {
            case "prefer_1" -> drop.add(s[0]!=Effect.EMPTY?0:1);
            case "prefer_2" -> drop.add(s[1]!=Effect.EMPTY?1:0);
            case "prefer_augmented" -> {
                boolean[] aug = augSlots(p);
                if (s[0] != Effect.EMPTY && aug[0] && s[1] != Effect.EMPTY && aug[1]) drop.add(new Random().nextBoolean() ? 0 : 1);
                else if (s[0] != Effect.EMPTY && aug[0]) drop.add(0);
                else if (s[1] != Effect.EMPTY && aug[1]) drop.add(1);
                else if (s[0] != Effect.EMPTY) drop.add(0);
                else if (s[1] != Effect.EMPTY) drop.add(1);
            }
            case "only_1" -> drop.add(0);
            case "only_2" -> drop.add(1);
            case "both" -> { drop.add(0); drop.add(1); }
            case "none" -> {}
            default -> { if(s[0]!=Effect.EMPTY&&s[1]!=Effect.EMPTY)drop.add(new Random().nextBoolean()?0:1); else if(s[0]!=Effect.EMPTY)drop.add(0); else if(s[1]!=Effect.EMPTY)drop.add(1); }
        }
        for(int i:drop) if(i<2&&s[i]!=Effect.EMPTY)e.getDrops().add(effectItem(s[i],augSlots(p)[i]));
        Arrays.fill(s,Effect.EMPTY); Arrays.fill(augSlots(p),false);
        saveData();
    }

    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e) {
        if(Boolean.TRUE.equals(commandKeys.getOrDefault(e.getPlayer().getUniqueId(),false))) return;
        e.getPlayer().performCommand(e.getPlayer().isSneaking() ? "rspark" : "lspark");
    }

    @EventHandler public void heartFoodBonus(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (event.isCancelled() || itemEffect(event.getItem()) != Effect.EMPTY
            || !(Arrays.asList(slots(player)).contains(Effect.HEART) || Arrays.asList(slots(player)).contains(Effect.APOPHIS))) return;
        if (event.getItem().getType() == Material.ENCHANTED_GOLDEN_APPLE)
            player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,2400,4,false,false));
        else player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,600,0,false,false));
    }

    @EventHandler public void onEffectConsume(PlayerItemConsumeEvent event) {
        if (!getConfig().getBoolean("items.equip-on-consume", true)) return;
        ItemStack item = event.getItem();
        Effect effect = itemEffect(item);
        if (effect == Effect.EMPTY || item.getType() != Material.POTION) return;
        if (!equipConsumedEffect(event.getPlayer(), effect, isAugmented(item))) event.setCancelled(true);
    }

    @EventHandler public void protectInfusionItems(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item) || itemEffect(item.getItemStack()) == Effect.EMPTY) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        boolean fire = cause == EntityDamageEvent.DamageCause.FIRE
            || cause == EntityDamageEvent.DamageCause.FIRE_TICK
            || cause == EntityDamageEvent.DamageCause.LAVA
            || cause == EntityDamageEvent.DamageCause.HOT_FLOOR
            || cause == EntityDamageEvent.DamageCause.MELTING;
        boolean explosion = cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
            || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION;
        if ((fire && getConfig().getBoolean("items.protect-from-fire", true))
            || (explosion && getConfig().getBoolean("items.protect-from-explosions", true))) event.setCancelled(true);
    }

    private void drain(Player p,int slot) {
        Effect[] s=slots(p);
        if(s[slot]==Effect.EMPTY){p.sendMessage("§cNo effect in slot "+(slot+1)+".");return;}
        giveItemOrDrop(p, effectItem(s[slot],augSlots(p)[slot]));
        p.sendMessage("§aDrained "+s[slot].display()+".");
        s[slot]=Effect.EMPTY; augSlots(p)[slot]=false;
        saveData();
    }

    private void trustCommand(Player p,String[] a,boolean add) {
        if(a.length<1){p.sendMessage("§c/"+(add?"trust":"untrust")+" <player>");return;}
        Player q=Bukkit.getPlayerExact(a[0]);
        if(q==null){p.sendMessage("§cPlayer not found.");return;}
        trusted.computeIfAbsent(p.getUniqueId(),k->new HashSet<>());
        if(add)trusted.get(p.getUniqueId()).add(q.getUniqueId());else trusted.get(p.getUniqueId()).remove(q.getUniqueId());
        p.sendMessage("§a"+(add?"Trusted ":"Untrusted ")+q.getName()+".");
        saveData();
    }

    private void openGui(Player p) { openGui(p, "Infuses"); }
    private void openSelectorGui(Player p) { openGui(p, "Infuse Selector"); }

    private void openGui(Player p, String title) {
        Inventory inv = Bukkit.createInventory(null, 54, Component.text(title));
        int[] positions = {12,23,20,32,35,33,29,22,21,30,14,31,40,41,39};
        Effect[] effectsInMenu = {Effect.FROST,Effect.ENDER,Effect.FEATHER,Effect.FIRE,Effect.EMERALD,
            Effect.HASTE,Effect.HEART,Effect.INVIS,Effect.OCEAN,Effect.REGEN,Effect.SPEED,
            Effect.STRENGTH,Effect.THUNDER,Effect.APOPHIS,Effect.THIEF};
        Set<Integer> used = new HashSet<>();
        for (int position : positions) used.add(position);
        for (int slot=0; slot<54; slot++) if (!used.contains(slot))
            inv.setItem(slot, named(Material.PURPLE_STAINED_GLASS_PANE, " "));
        for (int i=0; i<effectsInMenu.length; i++) {
            Effect effect = effectsInMenu[i];
            if (effect == Effect.EMPTY || !getConfig().getBoolean(effect.id()+".enabled", true)) continue;
            inv.setItem(positions[i], effectItem(effect, false));
        }
        p.openInventory(inv);
    }

    private void openAbilityGui(Player p) {
        Inventory inv=Bukkit.createInventory(null,27,Component.text("Infuse Abilities"));
        Effect[] equipped=slots(p);
        inv.setItem(11,equipped[0]==Effect.EMPTY?named(Material.GRAY_STAINED_GLASS_PANE,"Empty Slot"):effectItem(equipped[0],augSlots(p)[0]));
        inv.setItem(15,equipped[1]==Effect.EMPTY?named(Material.GRAY_STAINED_GLASS_PANE,"Empty Slot"):effectItem(equipped[1],augSlots(p)[1]));
        inv.setItem(13,named(Material.BOOK,"Recipes: /infuse recipes"));
        p.openInventory(inv);
    }

    private void openEffectChoice(Player p, Effect effect) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text("Choose Effect"));
        for (int slot=0; slot<27; slot++) inv.setItem(slot, named(Material.GRAY_STAINED_GLASS_PANE, " "));
        inv.setItem(11, effectItem(effect, false));
        inv.setItem(15, effectItem(effect, true));
        p.openInventory(inv);
    }

    private void openRecipeList(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, Component.text("Infuse Recipes"));
        int[] positions = {12,23,20,32,13,33,29,22,21,30,14,31,40,41,39};
        int index = 0;
        for (Effect effect : Effect.values()) {
            if (effect == Effect.EMPTY || !getConfig().getBoolean(effect.id()+".enabled", true)) continue;
            int[] craftLimits = limits(effect);
            int total = totalCrafts(effect);
            ItemStack icon = effectItem(effect, nextCraftIsAugmented(effect,total));
            ItemMeta meta = icon.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.text("Remaining: " + (craftLimits[0] < 0 || craftLimits[1] < 0 ? "unlimited" : Math.max(0, craftLimits[0]+craftLimits[1]-total)), NamedTextColor.YELLOW));
            meta.lore(lore);
            icon.setItemMeta(meta);
            inv.setItem(positions[index++], icon);
        }
        p.openInventory(inv);
    }

    private void openRecipePreview(Player p, Effect effect) {
        Inventory inv = Bukkit.createInventory(null, 45, Component.text("Recipe: " + effect.display()));
        for (int slot=0; slot<45; slot++) inv.setItem(slot, named(Material.RED_STAINED_GLASS_PANE, " "));
        int total = totalCrafts(effect);
        boolean augmented = nextCraftIsAugmented(effect,total);
        String key = augmented ? "aug_" + effect.id() : effect.id();
        ConfigurationSection def = recipeConfig == null ? null : recipeConfig.getConfigurationSection(key);
        if (def == null && augmented) def = recipeConfig == null ? null : recipeConfig.getConfigurationSection(effect.id());
        if (def != null) {
            List<String> shape = def.getStringList("shape");
            ConfigurationSection ingredients = def.getConfigurationSection("ingredients");
            for (int row=0; row<Math.min(3,shape.size()); row++) {
                String line = shape.get(row);
                for (int col=0; col<Math.min(3,line.length()); col++) {
                    char symbol = line.charAt(col);
                    if (symbol == ' ' || ingredients == null) continue;
                    Material material = Material.matchMaterial(ingredients.getString(String.valueOf(symbol), ""));
                    if (material != null) inv.setItem(10 + row*9 + col, new ItemStack(material));
                }
            }
        }
        inv.setItem(25, effectItem(effect, augmented));
        p.openInventory(inv);
    }

    private ItemStack named(Material m,String name){ItemStack i=new ItemStack(m);ItemMeta x=i.getItemMeta();x.displayName(Component.text(name));i.setItemMeta(x);return i;}

    @EventHandler public void guiClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();
        if (title.equals("Infuse Abilities")) {
            e.setCancelled(true);
            if (e.getRawSlot()==11) spark(p,0);
            if (e.getRawSlot()==15) spark(p,1);
            return;
        }
        if (title.equals("Infuse Selector")) {
            e.setCancelled(true);
            if (e.getRawSlot() >= e.getView().getTopInventory().getSize()) return;
            Effect effect = itemEffect(e.getCurrentItem());
            if (effect == Effect.EMPTY) return;
            Map<Integer,ItemStack> leftover = p.getInventory().addItem(effectItem(effect, false));
            for (ItemStack item : leftover.values()) p.getWorld().dropItemNaturally(p.getLocation(), item);
            p.closeInventory();
            return;
        }
        if (title.equals("Infuses")) {
            e.setCancelled(true);
            if (e.getRawSlot() < e.getView().getTopInventory().getSize()) {
                Effect effect = itemEffect(e.getCurrentItem());
                if (effect != Effect.EMPTY) openEffectChoice(p, effect);
            }
            return;
        }
        if (title.equals("Choose Effect")) {
            e.setCancelled(true);
            if (e.getRawSlot() >= e.getView().getTopInventory().getSize()) return;
            Effect effect = itemEffect(e.getCurrentItem());
            if (effect == Effect.EMPTY) return;
            if (!p.hasPermission("infuse.commands.infuse.giveEffect")) {
                p.sendMessage("§7Preview only. An administrator can grant infusion items from this menu.");
                return;
            }
            ItemStack chosen = effectItem(effect, isAugmented(e.getCurrentItem()));
            Map<Integer,ItemStack> leftover = p.getInventory().addItem(chosen);
            for (ItemStack item : leftover.values()) p.getWorld().dropItemNaturally(p.getLocation(), item);
            p.closeInventory();
            return;
        }
        if (title.equals("Augmented Infuses")) {
            e.setCancelled(true);
            if (e.getRawSlot() >= e.getView().getTopInventory().getSize()) return;
            Effect effect = itemEffect(e.getCurrentItem());
            if (effect == Effect.EMPTY) return;
            if (!p.hasPermission("infuse.commands.infuse.giveEffect")) {
                p.sendMessage("§7Preview only. An administrator can grant augmented infusion items.");
                return;
            }
            Map<Integer,ItemStack> leftover = p.getInventory().addItem(effectItem(effect, true));
            for (ItemStack item : leftover.values()) p.getWorld().dropItemNaturally(p.getLocation(), item);
            p.closeInventory();
            return;
        }
        if (title.equals("Infuse Recipes")) {
            e.setCancelled(true);
            if (e.getRawSlot() < e.getView().getTopInventory().getSize()) {
                Effect effect = itemEffect(e.getCurrentItem());
                if (effect != Effect.EMPTY) openRecipePreview(p, effect);
            }
            return;
        }
        if (title.startsWith("Recipe: ")) e.setCancelled(true);
    }

    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] a) {
        String n=cmd.getName().toLowerCase(Locale.ROOT);
        if(n.equals("whohaseffect")) { whoHasEffect(sender, a); return true; }
        if(n.equals("give_effects")) { giveEffects(sender, a); return true; }
        if(n.equals("giveselector")) { giveSelector(sender, a); return true; }
        if(n.equals("reloadtrust")) {
            if (!sender.hasPermission("infuse.commands.infuse.reload")) { sender.sendMessage("§cNo permission."); return true; }
            reloadConfig();
            dataConfig = YamlConfiguration.loadConfiguration(dataFile);
            trusted.clear();
            loadData();
            sender.sendMessage("§aTrust data reloaded.");
            return true;
        }
        if (n.equals("cleareffect") || n.equals("cleareffects")) {
            if (!sender.hasPermission("infuse.commands.infuse.clearEffects")) { sender.sendMessage("§cNo permission."); return true; }
            if (a.length < 1) { sender.sendMessage("§cUsage: /cleareffects <player>"); return true; }
            Player target = Bukkit.getPlayerExact(a[0]);
            if (target == null) { sender.sendMessage("§cPlayer not found."); return true; }
            effects.remove(target.getUniqueId());
            augmentedSlots.remove(target.getUniqueId());
            activeUntil.remove(target.getUniqueId());
            cooldownUntil.remove(target.getUniqueId());
            sender.sendMessage("§aCleared " + target.getName() + "'s effects.");
            saveData();
            return true;
        }
        if (n.equals("cooldown")) {
            if (!sender.hasPermission("infuse.commands.infuse.cooldown")) { sender.sendMessage("§cNo permission."); return true; }
            if (a.length < 1) { sender.sendMessage("§cUsage: /cooldown <player>"); return true; }
            Player target = Bukkit.getPlayerExact(a[0]);
            if (target == null) { sender.sendMessage("§cPlayer not found."); return true; }
            cooldownUntil.remove(target.getUniqueId());
            saveData();
            sender.sendMessage("§aReset " + target.getName() + "'s cooldowns.");
            return true;
        }
        if(!(sender instanceof Player p)){sender.sendMessage("Players only.");return true;}
        switch(n) {
            case "lspark" -> spark(p,0);
            case "rspark" -> spark(p,1);
            case "ldrain" -> drain(p,0);
            case "rdrain" -> drain(p,1);
            case "swap" -> {Effect[] s=slots(p);Effect t=s[0];s[0]=s[1];s[1]=t; boolean[] aug=augSlots(p);boolean ab=aug[0];aug[0]=aug[1];aug[1]=ab;p.sendMessage("§aEffects swapped.");saveData();}
            case "controls" -> {
                boolean current = commandKeys.getOrDefault(p.getUniqueId(),false);
                boolean v = !current;
                if (a.length > 0) {
                    if (a[0].equalsIgnoreCase("command") || a[0].equalsIgnoreCase("command_keys")) v = true;
                    else if (a[0].equalsIgnoreCase("offhand")) v = false;
                    else { p.sendMessage("§cUsage: /controls [offhand|command]"); return true; }
                }
                commandKeys.put(p.getUniqueId(),v);
                p.sendMessage("§aActivation mode: "+(v?"command keys":"offhand"));
                saveData();
            }
            case "trust" -> trustCommand(p,a,true);
            case "untrust" -> trustCommand(p,a,false);
            case "effects", "infuses" -> openGui(p);
            case "recipes" -> showRecipes(p);
            case "craftedeffects" -> showCraftedEffects(p);
            case "abilities" -> openAbilityGui(p);
            case "augments" -> showAugments(p);
            case "start_ritual" -> startAdminRitual(p, a);
            case "infuse" -> infuseCommand(p,a);
        }
        return true;
    }

    private void infuseCommand(Player p,String[] a) {
        if(a.length==0){openGui(p);return;}
        switch(a[0].toLowerCase(Locale.ROOT)) {
            case "gui" -> { if (p.hasPermission("infuse.commands.infuse.gui")) openGui(p); else p.sendMessage("§cNo permission."); }
            case "abilities" -> openAbilityGui(p);
            case "recipes" -> showRecipes(p);
            case "reload" -> {if(p.hasPermission("infuse.commands.infuse.reload")){reloadConfig();reloadRecipeConfig();registerRecipes();p.sendMessage("§aReloaded config.yml and recipes.yml.");}}
            case "seteffect" -> setEffectCommand(p, a);
            case "controls" -> p.performCommand(a.length > 1 ? "controls " + a[1] : "controls");
            case "settings" -> {
                if(a.length>1 && a[1].equalsIgnoreCase("control")) p.performCommand(a.length > 2 ? "controls " + a[2] : "controls");
                else p.sendMessage("§d/infuse settings control");
            }
            case "giveeffect" -> {
                if(!p.hasPermission("infuse.commands.infuse.giveEffect")||a.length<2)return;
                Effect e=Effect.parse(a[1]);if(e!=Effect.EMPTY)giveItemOrDrop(p,effectItem(e,a.length>2&&a[2].equalsIgnoreCase("augmented")));
            }
            default -> p.sendMessage("§d/infuse gui §7| §d/infuse recipes §7| §d/infuse reload §7| §d/infuse giveEffect <effect> [augmented] §7| §d/infuse seteffect <player> <slot> <effect> [augmented]");
        }
    }

    private void showRecipes(Player p) {
        openRecipeList(p);
    }

    private void showCraftedEffects(Player p) {
        p.sendMessage("§dYour crafted effects:");
        boolean any = false;
        for (Effect e : Effect.values()) if (e != Effect.EMPTY && crafted(p.getUniqueId(), e) > 0) {
            p.sendMessage("§f- §d"+e.display()+" §7x"+crafted(p.getUniqueId(), e));
            any = true;
        }
        if (!any) p.sendMessage("§7You have not crafted any effects yet.");
    }

    private void showAugments(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, Component.text("Augmented Infuses"));
        int[] positions = {12,23,20,32,35,33,29,22,21,30,14,31,40,41,39};
        int index = 0;
        for (int slot = 0; slot < 54; slot++) inv.setItem(slot, named(Material.PURPLE_STAINED_GLASS_PANE, " "));
        for (Effect e : Effect.values()) {
            if (e == Effect.EMPTY || !getConfig().getBoolean(e.id()+".enabled", true)) continue;
            if (index >= positions.length) break;
            inv.setItem(positions[index++], effectItem(e, true));
        }
        p.openInventory(inv);
    }

    private void whoHasEffect(CommandSender sender, String[] args) {
        if (!sender.hasPermission("infuse.commands.infuse.whoHasEffect")) { sender.sendMessage("§cNo permission."); return; }
        if (args.length < 1 || Effect.parse(args[0]) == Effect.EMPTY) {
            sender.sendMessage("§cUsage: /whohaseffect <effect>"); return;
        }
        Effect wanted = Effect.parse(args[0]);
        List<String> holders = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) if (Arrays.asList(slots(player)).contains(wanted)) holders.add(player.getName());
        sender.sendMessage("§d"+wanted.display()+" holders online: §f"+(holders.isEmpty() ? "none" : String.join(", ", holders)));
    }

    private void giveItemOrDrop(Player target, ItemStack item) {
        Map<Integer,ItemStack> leftovers = target.getInventory().addItem(item);
        for (ItemStack leftover : leftovers.values()) target.getWorld().dropItemNaturally(target.getLocation(), leftover);
    }

    private ItemStack selectorItem() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Infuse Effect Selector", NamedTextColor.LIGHT_PURPLE));
        meta.lore(List.of(Component.text("Right-click to browse available effects", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(selectorKey, PersistentDataType.BYTE, (byte)1);
        item.setItemMeta(meta);
        return item;
    }

    private void giveSelector(CommandSender sender, String[] args) {
        if (!sender.hasPermission("infuse.commands.infuse.giveEffect")) { sender.sendMessage("§cNo permission."); return; }
        if (args.length < 1) { sender.sendMessage("§cUsage: /giveselector <player|@a|*>"); return; }
        if (args[0].equalsIgnoreCase("@a") || args[0].equals("*")) {
            for (Player target : Bukkit.getOnlinePlayers()) giveItemOrDrop(target, selectorItem());
            sender.sendMessage("§aGave an effect selector to all online players.");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) { sender.sendMessage("§cPlayer not found."); return; }
        giveItemOrDrop(target, selectorItem());
        sender.sendMessage("§aGave an effect selector to " + target.getName() + ".");
    }

    private void giveEffects(CommandSender sender, String[] args) {
        if (!sender.hasPermission("infuse.commands.infuse.giveEffect")) { sender.sendMessage("§cNo permission."); return; }
        if (args.length < 1) { sender.sendMessage("§cUsage: /give_effects <player> [effect]"); return; }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) { sender.sendMessage("§cPlayer not found."); return; }
        if (args.length > 1) {
            Effect effect = Effect.parse(args[1]);
            if (effect == Effect.EMPTY) { sender.sendMessage("§cUnknown effect."); return; }
            giveItemOrDrop(target, effectItem(effect, false));
        } else {
            for (Effect effect : Effect.values()) if (effect != Effect.EMPTY && getConfig().getBoolean(effect.id()+".enabled", true)) giveItemOrDrop(target, effectItem(effect, false));
        }
        sender.sendMessage("§aGave infusion item(s) to "+target.getName()+".");
    }

    private void setEffectCommand(Player sender, String[] args) {
        if (!sender.hasPermission("infuse.commands.infuse.setEffect")) { sender.sendMessage("§cNo permission."); return; }
        if (args.length < 4) { sender.sendMessage("§cUsage: /infuse seteffect <player> <slot 1|2> <effect|empty> [augmented]"); return; }
        Player target = Bukkit.getPlayerExact(args[1]);
        int slot;
        try { slot = Integer.parseInt(args[2]) - 1; } catch (NumberFormatException ex) { slot = -1; }
        Effect effect = args[3].equalsIgnoreCase("empty") ? Effect.EMPTY : Effect.parse(args[3]);
        if (target == null || slot < 0 || slot > 1 || (effect == Effect.EMPTY && !args[3].equalsIgnoreCase("empty"))) {
            sender.sendMessage("§cInvalid player, slot, or effect."); return;
        }
        Effect[] equipped = slots(target);
        if (effect != Effect.EMPTY && getConfig().getBoolean("settings.enforce-role-slots", false)
            && equipped[1-slot] != Effect.EMPTY && isSupport(equipped[1-slot]) == isSupport(effect)) {
            sender.sendMessage("§cThat effect conflicts with the other slot's role."); return;
        }
        equipped[slot] = effect;
        augSlots(target)[slot] = effect != Effect.EMPTY && args.length > 4 && args[4].equalsIgnoreCase("augmented");
        sender.sendMessage("§aUpdated "+target.getName()+"'s slot "+(slot+1)+".");
        target.sendMessage("§aYour slot "+(slot+1)+" was updated by an administrator.");
        saveData();
    }

    private void startAdminRitual(Player p, String[] args) {
        if (!p.hasPermission("infuse.commands.infuse.startRitual")) { p.sendMessage("§cNo permission."); return; }
        if (ritualActive) { p.sendMessage("§cA ritual is already active."); return; }
        if (args.length < 1) { p.sendMessage("§cUsage: /start_ritual <effect>"); return; }
        Effect effect = Effect.parse(args[0]);
        if (effect == Effect.EMPTY || !getConfig().getBoolean(effect.id()+".enabled", true)) { p.sendMessage("§cThat effect is not enabled."); return; }
        Block stand = p.getLocation().getBlock().getRelative(org.bukkit.block.BlockFace.DOWN);
        if (stand.getType() != Material.BREWING_STAND) { p.sendMessage("§cStand on a brewing stand to start the ritual."); return; }
        startRitual(p, effect, stand.getLocation());
    }

    @EventHandler public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            if (ritualActive && ritualBar!=null) ritualBar.addPlayer(player);
            for (Player disguised : Bukkit.getOnlinePlayers()) hideDisguisedPlayer(player,disguised);
        });
        if (!getConfig().getBoolean("join_effects_enabled", false)) return;
        Effect[] equipped = slots(player);
        if (equipped[0] != Effect.EMPTY || equipped[1] != Effect.EMPTY) return;
        List<Effect> choices = new ArrayList<>();
        for (String id : getConfig().getStringList("join_effects")) {
            Effect effect = Effect.parse(id);
            if (effect != Effect.EMPTY && getConfig().getBoolean(effect.id()+".enabled", true)) choices.add(effect);
        }
        if (choices.isEmpty()) return;
        Effect selected = choices.get(new Random().nextInt(choices.size()));
        equipped[0] = selected;
        saveData();
        Bukkit.getScheduler().runTask(this, () -> player.sendMessage("§dYou received the " + selected.display() + " Infusion."));
    }

    @Override public List<String> onTabComplete(CommandSender s,Command c,String a,String[] args) {
        if(c.getName().equalsIgnoreCase("infuse")) {
            if(args.length==1)return List.of("gui","abilities","recipes","reload","giveEffect","seteffect","clearEffects","cooldown","controls");
            if(args.length==2 && args[0].equalsIgnoreCase("giveEffect"))return Arrays.stream(Effect.values()).filter(x->x!=Effect.EMPTY).map(Effect::id).toList();
        }
        if (c.getName().equalsIgnoreCase("controls") && args.length == 1) return List.of("offhand","command");
        return List.of();
    }

    private void saveData() {
        if (dataConfig == null || dataFile == null) return;
        dataConfig.set("players", null);
        for (var x : effects.entrySet()) {
            String u = x.getKey().toString();
            dataConfig.set("players."+u+".slot1", x.getValue()[0].id());
            dataConfig.set("players."+u+".slot2", x.getValue()[1].id());
            boolean[] a = augmentedSlots.getOrDefault(x.getKey(), new boolean[]{false,false});
            dataConfig.set("players."+u+".aug1", a[0]);
            dataConfig.set("players."+u+".aug2", a[1]);
        }
        dataConfig.set("crafts", null);
        for (var x : crafts.entrySet()) for (var y : x.getValue().entrySet())
            dataConfig.set("crafts."+x.getKey()+"."+y.getKey().id(), y.getValue());
        dataConfig.set("effect_counts", null);
        for (var x : existingCounts.entrySet()) dataConfig.set("effect_counts."+x.getKey().id(), x.getValue());
        dataConfig.set("trusted", null);
        for (var x : trusted.entrySet())
            dataConfig.set("trusted."+x.getKey().toString(), x.getValue().stream().map(UUID::toString).toList());
        dataConfig.set("command_keys", null);
        for (var x : commandKeys.entrySet()) dataConfig.set("command_keys."+x.getKey().toString(), x.getValue());
        dataConfig.set("cooldowns",null);
        for (var entry : cooldownUntil.entrySet()) {
            dataConfig.set("cooldowns."+entry.getKey()+".slot1",entry.getValue()[0]);
            dataConfig.set("cooldowns."+entry.getKey()+".slot2",entry.getValue()[1]);
        }
        dataConfig.set("ender_fireball_cooldowns",null);
        for (var entry : enderFireballCooldown.entrySet())
            dataConfig.set("ender_fireball_cooldowns."+entry.getKey(),entry.getValue());
        dataConfig.set("food_xp_locks",null);
        for (var entry : foodXpLockedUntil.entrySet())
            dataConfig.set("food_xp_locks."+entry.getKey(),entry.getValue());
        dataConfig.set("frost_snow",frostSnowBlocks.keySet().stream().filter(location -> location.getWorld()!=null)
            .map(location -> location.getWorld().getUID()+";"+location.getBlockX()+";"+location.getBlockY()+";"+location.getBlockZ()).toList());
        dataConfig.set("ritual.active",ritualActive && ritualLocation!=null && ritualEndsAt>0);
        if (ritualActive && ritualLocation!=null && ritualEndsAt>0) {
            dataConfig.set("ritual.effect",ritualEffect.id());
            dataConfig.set("ritual.world",ritualLocation.getWorld().getUID().toString());
            dataConfig.set("ritual.x",ritualLocation.getX());
            dataConfig.set("ritual.y",ritualLocation.getY());
            dataConfig.set("ritual.z",ritualLocation.getZ());
            dataConfig.set("ritual.ends_at",ritualEndsAt);
        } else {
            dataConfig.set("ritual.effect",null);
            dataConfig.set("ritual.world",null);
            dataConfig.set("ritual.x",null);
            dataConfig.set("ritual.y",null);
            dataConfig.set("ritual.z",null);
            dataConfig.set("ritual.ends_at",null);
        }
        dataConfig.set("thief_steals", null);
        for (var x : thiefSteals.entrySet()) {
            String key = "thief_steals." + x.getKey();
            ThiefSteal steal = x.getValue();
            dataConfig.set(key+".thief", steal.thiefId().toString());
            dataConfig.set(key+".thief_slot", steal.thiefSlot());
            dataConfig.set(key+".victim", steal.victimId().toString());
            dataConfig.set(key+".victim_slot", steal.victimSlot());
            dataConfig.set(key+".effect", steal.effect().id());
            dataConfig.set(key+".augmented", steal.augmented());
            dataConfig.set(key+".expires_at", steal.expiresAt());
        }
        try {
            dataConfig.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Could not save player data: " + ex.getMessage());
        }
    }

    private void restoreExpiredThiefSteals() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (var entry : thiefSteals.entrySet()) {
            ThiefSteal steal = entry.getValue();
            if (now < steal.expiresAt()) continue;
            Effect[] equipped = effects.computeIfAbsent(steal.victimId(), key -> new Effect[]{Effect.EMPTY,Effect.EMPTY});
            boolean[] augmented = augmentedSlots.computeIfAbsent(steal.victimId(), key -> new boolean[]{false,false});
            if (equipped[steal.victimSlot()] == Effect.EMPTY) {
                equipped[steal.victimSlot()] = steal.effect();
                augmented[steal.victimSlot()] = steal.augmented();
            } else if (equipped[steal.victimSlot()] != steal.effect()) {
                Player victim = Bukkit.getPlayer(steal.victimId());
                if (victim == null) continue;
                giveItemOrDrop(victim,effectItem(steal.effect(),steal.augmented()));
            }
            thiefSteals.remove(entry.getKey(),steal);
            Player thief = Bukkit.getPlayer(steal.thiefId());
            if (thief != null) thief.sendMessage("§aThe stolen " + steal.effect().display() + " returned to its owner.");
            Player victim = Bukkit.getPlayer(steal.victimId());
            if (victim != null) victim.sendMessage("§aYour " + steal.effect().display() + " has returned.");
            changed = true;
        }
        if (changed) saveData();
    }

    private void loadData() {
        effects.clear();
        augmentedSlots.clear();
        crafts.clear();
        trusted.clear();
        commandKeys.clear();
        cooldownUntil.clear();
        activeUntil.clear();
        enderFireballCooldown.clear();
        foodXpLockedUntil.clear();
        thiefSteals.clear();

        var ps = dataConfig.getConfigurationSection("players");
        if (ps != null) for (String id : ps.getKeys(false)) try {
            UUID u = UUID.fromString(id);
            effects.put(u, new Effect[]{
                Effect.parse(dataConfig.getString("players."+id+".slot1")),
                Effect.parse(dataConfig.getString("players."+id+".slot2"))
            });
            augmentedSlots.put(u, new boolean[]{
                dataConfig.getBoolean("players."+id+".aug1", false),
                dataConfig.getBoolean("players."+id+".aug2", false)
            });
        } catch (Exception ignored) {}
        var cs = dataConfig.getConfigurationSection("crafts");
        if (cs != null) for (String id : cs.getKeys(false)) try {
            UUID u = UUID.fromString(id);
            Map<Effect,Integer> m = new EnumMap<>(Effect.class);
            for (String e : cs.getConfigurationSection(id).getKeys(false))
                m.put(Effect.parse(e), dataConfig.getInt("crafts."+id+"."+e));
            crafts.put(u,m);
        } catch (Exception ignored) {}
        var ts = dataConfig.getConfigurationSection("trusted");
        if (ts != null) for (String id : ts.getKeys(false)) try {
            UUID u = UUID.fromString(id);
            Set<UUID> set = ConcurrentHashMap.newKeySet();
            for (String q : ts.getStringList(id)) try { set.add(UUID.fromString(q)); } catch (Exception ignored) {}
            trusted.put(u,set);
        } catch (Exception ignored) {}
        var ks = dataConfig.getConfigurationSection("command_keys");
        if (ks != null) for (String id : ks.getKeys(false)) try {
            commandKeys.put(UUID.fromString(id), dataConfig.getBoolean("command_keys."+id));
        } catch (Exception ignored) {}
        var cooldowns = dataConfig.getConfigurationSection("cooldowns");
        if (cooldowns != null) for (String id : cooldowns.getKeys(false)) try {
            cooldownUntil.put(UUID.fromString(id),new long[]{
                dataConfig.getLong("cooldowns."+id+".slot1",0L),
                dataConfig.getLong("cooldowns."+id+".slot2",0L)
            });
        } catch (Exception ignored) {}
        var fireballCooldowns = dataConfig.getConfigurationSection("ender_fireball_cooldowns");
        if (fireballCooldowns != null) for (String id : fireballCooldowns.getKeys(false)) try {
            enderFireballCooldown.put(UUID.fromString(id),fireballCooldowns.getLong(id));
        } catch (Exception ignored) {}
        var foodLocks = dataConfig.getConfigurationSection("food_xp_locks");
        if (foodLocks != null) for (String id : foodLocks.getKeys(false)) try {
            foodXpLockedUntil.put(UUID.fromString(id),foodLocks.getLong(id));
        } catch (Exception ignored) {}
        var steals = dataConfig.getConfigurationSection("thief_steals");
        if (steals != null) for (String key : steals.getKeys(false)) try {
            String path = "thief_steals."+key+".";
            ThiefSteal steal = new ThiefSteal(
                UUID.fromString(dataConfig.getString(path+"thief")),
                dataConfig.getInt(path+"thief_slot"),
                UUID.fromString(dataConfig.getString(path+"victim")),
                dataConfig.getInt(path+"victim_slot"),
                Effect.parse(dataConfig.getString(path+"effect")),
                dataConfig.getBoolean(path+"augmented",false),
                dataConfig.getLong(path+"expires_at",0L));
            if (steal.effect() != Effect.EMPTY) thiefSteals.put(key,steal);
        } catch (Exception ignored) {}

        frostSnowBlocks.clear();
        for (String encoded : dataConfig.getStringList("frost_snow")) try {
            String[] parts=encoded.split(";");
            if (parts.length!=4) continue;
            World world=Bukkit.getWorld(UUID.fromString(parts[0]));
            if (world==null) continue;
            frostSnowBlocks.put(new Location(world,Integer.parseInt(parts[1]),Integer.parseInt(parts[2]),Integer.parseInt(parts[3])),Boolean.TRUE);
        } catch (Exception ignored) {}

        existingCounts.clear();
        var counts = dataConfig.getConfigurationSection("effect_counts");
        for (Effect effect : Effect.values()) if (effect != Effect.EMPTY) {
            int legacy = 0;
            for (Map<Effect,Integer> m : crafts.values()) legacy += m.getOrDefault(effect, 0);
            existingCounts.put(effect, Math.max(0, counts == null ? legacy : counts.getInt(effect.id(), legacy)));
        }
    }
}
