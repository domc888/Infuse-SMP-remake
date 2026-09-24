package com.infuse;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.attribute.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.scheduler.*;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class InfusePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private NamespacedKey effectKey, augmentedKey;
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
    private final Map<UUID, Map<Effect,Deque<Long>>> hits = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> commandKeys = new ConcurrentHashMap<>();

    private boolean ritualActive;
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

    @Override public void onEnable() {
        saveDefaultConfig();
        effectKey = new NamespacedKey(this, "effect");
        augmentedKey = new NamespacedKey(this, "augmented");
        loadData();
        registerRecipes();
        getServer().getPluginManager().registerEvents(this, this);
        String[] commands = {"infuse","lspark","rspark","ldrain","rdrain","swap","controls","trust","untrust","draw","cleareffects","cooldown"};
        for (String name : commands) {
            PluginCommand c = getCommand(name);
            if (c != null) { c.setExecutor(this); c.setTabCompleter(this); }
        }
        getServer().getScheduler().runTaskTimer(this, this::tickEffects, 10L, 10L);
        getLogger().info("InfuseSMP remake enabled.");
    }

    @Override public void onDisable() { saveData(); stopRitual(false); }

    private ItemStack effectItem(Effect e, boolean augmented) {
        Material mat = switch (e) {
            case EMERALD -> Material.EMERALD;
            case ENDER -> Material.ENDER_EYE;
            case FEATHER -> Material.FEATHER;
            case FIRE -> Material.BLAZE_POWDER;
            case FROST -> Material.BLUE_ICE;
            case HASTE -> Material.GOLDEN_PICKAXE;
            case HEART -> Material.RED_DYE;
            case INVIS -> Material.PHANTOM_MEMBRANE;
            case OCEAN -> Material.HEART_OF_THE_SEA;
            case REGEN -> Material.GHAST_TEAR;
            case SPEED -> Material.RABBIT_FOOT;
            case STRENGTH -> Material.BLAZE_ROD;
            case THUNDER -> Material.LIGHTNING_ROD;
            case APOPHIS -> Material.NETHER_STAR;
            case THIEF -> Material.SHEARS;
            default -> Material.GLASS_BOTTLE;
        };
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text((augmented ? "Augmented " : "") + e.display() + " Infusion", NamedTextColor.LIGHT_PURPLE));
        meta.getPersistentDataContainer().set(effectKey, PersistentDataType.STRING, e.id());
        meta.getPersistentDataContainer().set(augmentedKey, PersistentDataType.BYTE, (byte)(augmented ? 1 : 0));
        meta.lore(List.of(Component.text("Right-click while sneaking to equip", NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
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

    private void registerRecipes() {
        for (Effect e : Effect.values()) if (e != Effect.EMPTY) {
            removeRecipe("craft_"+e.id());
            removeRecipe("aug_"+e.id());
            if (!getConfig().getBoolean(e.id()+".enabled", true)) continue;
            int global = totalCrafts(e);
            int[] limit = limits(e);
            if (global < limit[0]) addRecipe(e, true);
            else if (global < limit[0] + limit[1] || getConfig().getBoolean("allow_infinite_effects",false)) addRecipe(e,false);
        }
    }
    private int totalCrafts(Effect e) {
        int n=0;
        for (Map<Effect,Integer> m:crafts.values()) n += m.getOrDefault(e,0);
        return n;
    }
    private void removeRecipe(String id) {
        try { Bukkit.removeRecipe(new NamespacedKey(this,id)); } catch(Exception ignored){}
    }

    private void addRecipe(Effect e, boolean augmented) {
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
        int[] limit=limits(e);
        if(!getConfig().getBoolean(e.id()+".enabled",true) || (total>=limit[0]+limit[1] && !getConfig().getBoolean("allow_infinite_effects",false))) event.getInventory().setResult(null);
        else event.getInventory().setResult(effectItem(e,total<limit[0]));
    }

    @EventHandler public void onCraft(CraftItemEvent event) {
        if(!(event.getWhoClicked() instanceof Player p) || event.getRecipe()==null) return;
        Effect e=itemEffect(event.getRecipe().getResult());
        if(e==Effect.EMPTY) return;
        if(event.isShiftClick()) { event.setCancelled(true); return; }
        int total=totalCrafts(e);
        int[] limit=limits(e);
        if(!getConfig().getBoolean(e.id()+".enabled",true) || (total>=limit[0]+limit[1] && !getConfig().getBoolean("allow_infinite_effects",false))) {event.setCancelled(true);return;}
        if(total<limit[0]) {
            if(ritualActive) {event.setCancelled(true);p.sendMessage("§cA ritual is already active.");return;}
            startRitual(p,e,event.getInventory().getLocation());
            event.setCurrentItem(null);
            setCrafted(p.getUniqueId(),e,crafted(p.getUniqueId(),e)+1);
            saveData(); registerRecipes();
        } else {
            setCrafted(p.getUniqueId(),e,crafted(p.getUniqueId(),e)+1);
            broadcastCraft(p,e);
            saveData();
            registerRecipes();
        }
    }

    private void broadcastCraft(Player p, Effect e) {
        Location l=p.getLocation();
        Bukkit.broadcast(Component.text(p.getName()+" crafted "+e.display()+" at "+l.getBlockX()+" "+l.getBlockY()+" "+l.getBlockZ()+" in "+l.getWorld().getName()+".",NamedTextColor.LIGHT_PURPLE));
    }

    private void startRitual(Player p,Effect e,Location loc) {
        if(loc==null) loc=p.getLocation();
        ritualActive=true; ritualEffect=e; ritualLocation=loc.clone();
        broadcastCraft(p,e);
        Bukkit.broadcast(Component.text(p.getName()+" started the "+e.display()+" ritual at "+loc.getBlockX()+" "+loc.getBlockY()+" "+loc.getBlockZ()+".",NamedTextColor.GOLD));
        int seconds=e==Effect.ENDER?getConfig().getInt("rituals.ender_duration",3600):getConfig().getInt("rituals.duration",600);
        ritualBar=Bukkit.createBossBar("Ritual: "+e.display(),BarColor.PURPLE,BarStyle.SOLID);
        for(Player q:Bukkit.getOnlinePlayers()) ritualBar.addPlayer(q);
        ritualBar.setProgress(1);
        ritualTask=new org.bukkit.scheduler.BukkitRunnable(){int left=seconds*20;
            public void run(){left--; ritualBar.setProgress(Math.max(0,left/(double)(seconds*20)));
                if(left<=0){finishRitual();cancel();}
            }}.runTaskTimer(this,1,1);
        loc.getWorld().spawnParticle(Particle.END_ROD,loc.clone().add(.5,1,.5),30,.6,.8,.6,.02);
    }

    private void finishRitual() {
        if(!ritualActive || ritualLocation==null) return;
        Location l=ritualLocation.clone().add(.5,1,.5);
        l.getWorld().dropItem(l,effectItem(ritualEffect,true));
        Bukkit.broadcast(Component.text("Ritual complete: "+ritualEffect.display()+" is available at "+l.getBlockX()+" "+l.getBlockY()+" "+l.getBlockZ()+".",NamedTextColor.GREEN));
        ritualActive=false; ritualEffect=Effect.EMPTY; ritualLocation=null;
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
        ritualActive=false; ritualEffect=Effect.EMPTY; ritualLocation=null;
    }

    @EventHandler public void onBlockBreak(BlockBreakEvent e) {
        if(ritualActive && ritualLocation!=null && e.getBlock().getLocation().distanceSquared(ritualLocation)<4 && !getConfig().getBoolean("rituals.immortal_brewing_stands",true))
            stopRitual(true);
    }

    @EventHandler public void onPlayerInteract(PlayerInteractEvent e) {
        if(e.getAction()==org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && e.getClickedBlock()!=null && e.getClickedBlock().getType()==Material.BREWING_STAND) {
            if(getConfig().getBoolean("brewing_gui",true)) {
                e.setCancelled(true);
                e.getPlayer().openWorkbench(e.getClickedBlock().getLocation(), true);
                return;
            }
        }
        if(!e.getAction().isRightClick()) return;
        ItemStack held=e.getItem();
        Effect effect=itemEffect(held);
        if(effect==Effect.EMPTY || !e.getPlayer().isSneaking()) return;
        e.setCancelled(true);
        if(applyEffect(e.getPlayer(),effect,isAugmented(held)) && held!=null && held.getAmount()>0) held.setAmount(held.getAmount()-1);
    }

    private boolean isSupport(Effect e) {
        return e==Effect.EMERALD || e==Effect.OCEAN || e==Effect.SPEED || e==Effect.FIRE;
    }

    private boolean applyEffect(Player p,Effect e,boolean augmented) {
        Effect[] s=slots(p);
        int slot=s[0]==Effect.EMPTY?0:s[1]==Effect.EMPTY?1:-1;
        if(slot<0){p.sendMessage("§cBoth effect slots are full.");return false;}
        if(s[1-slot]!=Effect.EMPTY && isSupport(s[1-slot])==isSupport(e)) {
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
        long duration=duration(p,e,aug);
        long cd=cooldown(p,e,aug);
        c[slot]=now+(duration+cd)*1000L;
        activeUntil.computeIfAbsent(p.getUniqueId(),k->new long[2])[slot]=now+duration*1000L;
        executeSpark(p,e,slot,aug);
        return true;
    }

    private long duration(Player p,Effect e,boolean augmented) {
        long regular=getConfig().getLong(e.id()+".duration.default",15);
        long aug=getConfig().getLong(e.id()+".duration.augmented",regular);
        return augmented?aug:regular;
    }

    private long cooldown(Player p,Effect e,boolean augmented) {
        long regular=getConfig().getLong(e.id()+".cooldown.default",60);
        long aug=getConfig().getLong(e.id()+".cooldown.augmented",Math.max(1,regular/2));
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
                    if(jump!=null) jump.setBaseValue(0.1);
                    getServer().getScheduler().runTaskLater(this,()->{AttributeInstance j=q.getAttribute(Attribute.JUMP_STRENGTH);if(j!=null)j.setBaseValue(0.42);},ticks);
                }
            }
            case HASTE -> p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,(int)ticks,3,false,false));
            case HEART -> {
                AttributeInstance a=p.getAttribute(Attribute.MAX_HEALTH);
                if(a!=null){a.setBaseValue(a.getBaseValue()+10);p.setHealth(a.getValue());getServer().getScheduler().runTaskLater(this,()->{AttributeInstance x=p.getAttribute(Attribute.MAX_HEALTH);if(x!=null)x.setBaseValue(Math.max(20,x.getBaseValue()-10));},ticks);}
            }
            case INVIS -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,(int)ticks,0,false,false));
                double r=10;
                for(Player q:Bukkit.getOnlinePlayers()) if(q.getWorld()==p.getWorld()&&q.getLocation().distanceSquared(l)<=r*r&&trusted(p,q)&&q!=p) for(Player o:Bukkit.getOnlinePlayers()) if(o!=q&&!trusted(q,o)) o.hidePlayer(this,q);
                getServer().getScheduler().runTaskLater(this,()->{for(Player q:Bukkit.getOnlinePlayers())for(Player o:Bukkit.getOnlinePlayers())if(o!=q)o.showPlayer(this,q);},ticks);
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
            case STRENGTH -> p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,(int)ticks,1,false,false));
            case THUNDER -> {
                double base=getConfig().getDouble("thunder.spark.base_radius",10), per=getConfig().getDouble("thunder.spark.per_player_boost_radius",0.3);
                int maxHits=getConfig().getInt("thunder.spark.strikes_per_player",3);
                getServer().getScheduler().runTaskTimer(this,new BukkitRunnable(){int ticks=0;Map<UUID,Integer> hits=new HashMap<>();public void run(){if(ticks>=ticksDuration()){cancel();return;}double r=base+per*Bukkit.getOnlinePlayers().stream().filter(q->q.getWorld()==p.getWorld()&&q.getLocation().distanceSquared(p.getLocation())<=base*base).count();for(Entity x:p.getNearbyEntities(r,r,r))if(x instanceof Player q&&q!=p&&!trusted(p,q)&&hits.getOrDefault(q.getUniqueId(),0)<maxHits){p.getWorld().strikeLightning(q.getLocation());hits.merge(q.getUniqueId(),1,Integer::sum);}ticks+=10;}private long ticksDuration(){return duration(p,e,augmented)*20L;}},0L,10L);
            }
            case APOPHIS -> {
                double r=getConfig().getDouble("apophis.spark.radius",5);
                for(Entity x:p.getNearbyEntities(r,r,r))if(x instanceof LivingEntity le&&!trusted(p,x)){le.damage(8,p);le.setFireTicks(100);}
            }
            case THIEF -> {
                for(Entity x:p.getNearbyEntities(4,2,4))if(x instanceof Player q&&!trusted(p,q)){int xp=Math.min(q.getTotalExperience(),100);q.giveExp(-xp);p.giveExp(xp);}
            }
            default -> {}
        }
        p.sendActionBar(Component.text(e.display()+" spark activated",NamedTextColor.LIGHT_PURPLE));
    }

    private void tickEffects() {
        for(Player p:Bukkit.getOnlinePlayers()) {
            Effect[] ss=slots(p);
            for(int i=0;i<2;i++) {
                Effect e=ss[i]; if(e==Effect.EMPTY) continue;
                if(getConfig().getStringList(e.id()+".blacklisted_worlds").contains(p.getWorld().getKey().toString())) continue;
                switch(e) {
                    case EMERALD -> {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE,40,2,true,false));
                        if(isSparkActive(p,i)) p.addPotionEffect(new PotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE,40,4,true,false));
                    }
                    case ENDER -> {}
                    case FEATHER -> p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,40,0,true,false));
                    case FIRE -> p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,40,0,true,false));
                    case FROST -> {
                        Material below=p.getLocation().subtract(0,1,0).getBlock().getType();
                        if(below.name().contains("ICE") || below.name().contains("SNOW")) p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,40,1,true,false));
                        int r=getConfig().getInt("frost.passive.snow_changing_radius",3);
                        if(p.isSneaking()) for(int x=-r;x<=r;x++) for(int z=-r;z<=r;z++){Location q=p.getLocation().add(x,-1,z);Material m=q.getBlock().getType();if(m==Material.POWDER_SNOW||m==Material.SNOW||m==Material.SNOW_BLOCK)q.getBlock().setType(Material.ICE);}
                    }
                    case HASTE -> p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,40,1,true,false));
                    case HEART -> {
                        AttributeInstance max=p.getAttribute(Attribute.MAX_HEALTH);
                        if(max!=null && max.getBaseValue()<30) { max.setBaseValue(30); if(p.getHealth()>30) p.setHealth(30); }
                    }
                    case INVIS -> p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,40,0,true,false));
                    case OCEAN -> {
                        if(p.isInWater()) p.addPotionEffect(new PotionEffect(PotionEffectType.CONDUIT_POWER,40,0,true,false));
                        if(isSparkActive(p,i)) {
                            double r=getConfig().getDouble("ocean.spark.drown_radius",5);
                            for(Entity x:p.getNearbyEntities(r,r,r)) if(x instanceof LivingEntity le&&!trusted(p,x)){le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,30,getConfig().getInt("ocean.spark.drown_strength",20)-1));le.damage(getConfig().getDouble("ocean.spark.drown_damage",2),p);}
                        }
                    }
                    case REGEN -> p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,40,1,true,false));
                    case SPEED -> p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,40,1,true,false));
                    case STRENGTH -> {}
                    case THUNDER -> {}
                    case APOPHIS -> {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,40,0,true,false));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,40,0,true,false));
                    }
                    case THIEF -> {}
                    default -> {}
                }
            }
            AttributeInstance max=p.getAttribute(Attribute.MAX_HEALTH);
            boolean hasHeart=Arrays.asList(ss).contains(Effect.HEART);
            if(max!=null) {
                if(hasHeart && max.getBaseValue()<30) max.setBaseValue(30);
                else if(!hasHeart && max.getBaseValue()==30) { max.setBaseValue(20); if(p.getHealth()>20) p.setHealth(20); }
            }
        }
    }

    @EventHandler public void onDamage(EntityDamageByEntityEvent e) {
        if(!(e.getEntity() instanceof Player victim) || !(e.getDamager() instanceof Player attacker)) return;
        Effect[] equipped=slots(attacker);
        if(Arrays.asList(equipped).contains(Effect.STRENGTH)) e.setDamage(e.getDamage()+2.0);
        if(Arrays.asList(equipped).contains(Effect.OCEAN) && attacker.isInWater()) e.setDamage(e.getDamage()+2.0);
        if(Arrays.asList(equipped).contains(Effect.THUNDER)) attacker.getWorld().strikeLightningEffect(victim.getLocation());
    }

    @EventHandler public void onDeath(PlayerDeathEvent e) {
        Player p=e.getEntity();
        if(!getConfig().getBoolean("drop_on_natural_death",true) && p.getKiller()==null) return;
        String mode=getConfig().getString("effect_drops","random");
        Effect[] s=slots(p);
        List<Integer> drop=new ArrayList<>();
        switch(mode) {
            case "prefer_1" -> drop.add(s[0]!=Effect.EMPTY?0:1);
            case "prefer_2" -> drop.add(s[1]!=Effect.EMPTY?1:0);
            case "only_1" -> drop.add(0);
            case "only_2" -> drop.add(1);
            case "none" -> {}
            default -> { if(s[0]!=Effect.EMPTY&&s[1]!=Effect.EMPTY)drop.add(new Random().nextBoolean()?0:1); else if(s[0]!=Effect.EMPTY)drop.add(0); else if(s[1]!=Effect.EMPTY)drop.add(1); }
        }
        for(int i:drop) if(i<2&&s[i]!=Effect.EMPTY)e.getDrops().add(effectItem(s[i],augSlots(p)[i]));
        Arrays.fill(s,Effect.EMPTY); Arrays.fill(augSlots(p),false);
        saveData();
    }

    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e) {
        if(Boolean.TRUE.equals(commandKeys.getOrDefault(e.getPlayer().getUniqueId(),false))) return;
        if(e.getPlayer().isSneaking()) { e.setCancelled(true); spark(e.getPlayer(),0); }
    }

    private void drain(Player p,int slot) {
        Effect[] s=slots(p);
        if(s[slot]==Effect.EMPTY){p.sendMessage("§cNo effect in slot "+(slot+1)+".");return;}
        p.getInventory().addItem(effectItem(s[slot],augSlots(p)[slot]));
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

    private void openGui(Player p) {
        Inventory inv=Bukkit.createInventory(null,27,Component.text("Infuse"));
        Effect[] s=slots(p);
        inv.setItem(11,s[0]==Effect.EMPTY?named(Material.GRAY_STAINED_GLASS_PANE,"Empty Slot"):effectItem(s[0],false));
        inv.setItem(15,s[1]==Effect.EMPTY?named(Material.GRAY_STAINED_GLASS_PANE,"Empty Slot"):effectItem(s[1],false));
        inv.setItem(13,named(Material.BOOK,"Recipes: /infuse recipes"));
        p.openInventory(inv);
    }
    private ItemStack named(Material m,String name){ItemStack i=new ItemStack(m);ItemMeta x=i.getItemMeta();x.displayName(Component.text(name));i.setItemMeta(x);return i;}

    @EventHandler public void guiClick(InventoryClickEvent e) {
        if(!(e.getWhoClicked() instanceof Player p))return;
        if(!e.getView().title().equals(Component.text("Infuse")))return;
        e.setCancelled(true);
        if(e.getRawSlot()==11)spark(p,0);
        if(e.getRawSlot()==15)spark(p,1);
    }

    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] a) {
        if(!(sender instanceof Player p)){sender.sendMessage("Players only.");return true;}
        String n=cmd.getName().toLowerCase(Locale.ROOT);
        switch(n) {
            case "lspark" -> spark(p,0);
            case "rspark" -> spark(p,1);
            case "ldrain" -> drain(p,0);
            case "rdrain" -> drain(p,1);
            case "swap" -> {Effect[] s=slots(p);Effect t=s[0];s[0]=s[1];s[1]=t; boolean[] aug=augSlots(p);boolean ab=aug[0];aug[0]=aug[1];aug[1]=ab;p.sendMessage("§aEffects swapped.");saveData();}
            case "controls" -> {boolean v=!commandKeys.getOrDefault(p.getUniqueId(),false);commandKeys.put(p.getUniqueId(),v);p.sendMessage("§aActivation mode: "+(v?"command keys":"offhand"));saveData();}
            case "trust" -> trustCommand(p,a,true);
            case "untrust" -> trustCommand(p,a,false);
            case "cleareffects" -> {
                if(!p.hasPermission("infuse.commands.infuse.clearEffects")||a.length<1){p.sendMessage("§cNo permission or player missing.");return true;}
                Player q=Bukkit.getPlayerExact(a[0]);if(q!=null){effects.remove(q.getUniqueId());p.sendMessage("§aCleared "+q.getName()+"'s effects.");saveData();}
            }
            case "cooldown" -> {
                if(!p.hasPermission("infuse.commands.infuse.cooldown")||a.length<1){p.sendMessage("§cNo permission or player missing.");return true;}
                Player q=Bukkit.getPlayerExact(a[0]);if(q!=null){cooldownUntil.remove(q.getUniqueId());p.sendMessage("§aReset "+q.getName()+"'s cooldowns.");}
            }
            case "draw" -> p.sendMessage("§7/draw is reserved for visual debugging in the original plugin.");
            case "infuse" -> infuseCommand(p,a);
        }
        return true;
    }

    private void infuseCommand(Player p,String[] a) {
        if(a.length==0){openGui(p);return;}
        switch(a[0].toLowerCase(Locale.ROOT)) {
            case "gui" -> openGui(p);
            case "recipes" -> showRecipes(p);
            case "reload" -> {if(p.hasPermission("infuse.commands.infuse.reload")){reloadConfig();registerRecipes();p.sendMessage("§aReloaded.");}}
            case "controls" -> p.performCommand("controls");
            case "settings" -> {
                if(a.length>1 && a[1].equalsIgnoreCase("control")) p.performCommand("controls");
                else p.sendMessage("§d/infuse settings control");
            }
            case "giveeffect" -> {
                if(!p.hasPermission("infuse.commands.infuse.giveEffect")||a.length<2)return;
                Effect e=Effect.parse(a[1]);if(e!=Effect.EMPTY)p.getInventory().addItem(effectItem(e,a.length>2&&a[2].equalsIgnoreCase("augmented")));
            }
            default -> p.sendMessage("§d/infuse gui §7| §d/infuse recipes §7| §d/infuse reload §7| §d/infuse giveEffect <effect> [augmented]");
        }
    }

    private void showRecipes(Player p) {
        p.sendMessage("§dInfuse recipes:");
        for(Effect e:Effect.values())if(e!=Effect.EMPTY)p.sendMessage("§f- §d"+e.display()+" §7(see recipes.yml)");
    }

    @Override public List<String> onTabComplete(CommandSender s,Command c,String a,String[] args) {
        if(c.getName().equalsIgnoreCase("infuse")) {
            if(args.length==1)return List.of("gui","recipes","reload","giveEffect","clearEffects","cooldown","controls");
            if(args.length==2 && args[0].equalsIgnoreCase("giveEffect"))return Arrays.stream(Effect.values()).filter(x->x!=Effect.EMPTY).map(Effect::id).toList();
        }
        return List.of();
    }

    private void saveData() {
        getConfig().set("players",null);
        for(var x:effects.entrySet()){String u=x.getKey().toString();getConfig().set("players."+u+".slot1",x.getValue()[0].id());getConfig().set("players."+u+".slot2",x.getValue()[1].id()); boolean[] a=augmentedSlots.getOrDefault(x.getKey(),new boolean[]{false,false});getConfig().set("players."+u+".aug1",a[0]);getConfig().set("players."+u+".aug2",a[1]);}
        getConfig().set("crafts",null);
        for(var x:crafts.entrySet())for(var y:x.getValue().entrySet())getConfig().set("crafts."+x.getKey()+"."+y.getKey().id(),y.getValue());
        for(var x:trusted.entrySet())getConfig().set("trusted."+x.getKey().toString(),x.getValue().stream().map(UUID::toString).toList());
        for(var x:commandKeys.entrySet())getConfig().set("command_keys."+x.getKey().toString(),x.getValue());
        saveConfig();
    }

    private void loadData() {
        var ps=getConfig().getConfigurationSection("players");
        if(ps!=null)for(String id:ps.getKeys(false))try{UUID u=UUID.fromString(id);effects.put(u,new Effect[]{Effect.parse(getConfig().getString("players."+id+".slot1")),Effect.parse(getConfig().getString("players."+id+".slot2"))});augmentedSlots.put(u,new boolean[]{getConfig().getBoolean("players."+id+".aug1",false),getConfig().getBoolean("players."+id+".aug2",false)});}catch(Exception ignored){}
        var cs=getConfig().getConfigurationSection("crafts");
        if(cs!=null)for(String id:cs.getKeys(false))try{UUID u=UUID.fromString(id);Map<Effect,Integer> m=new EnumMap<>(Effect.class);for(String e:cs.getConfigurationSection(id).getKeys(false))m.put(Effect.parse(e),getConfig().getInt("crafts."+id+"."+e));crafts.put(u,m);}catch(Exception ignored){}
        var ts=getConfig().getConfigurationSection("trusted");
        if(ts!=null)for(String id:ts.getKeys(false))try{UUID u=UUID.fromString(id);Set<UUID> set=ConcurrentHashMap.newKeySet();for(String q:ts.getStringList(id))try{set.add(UUID.fromString(q));}catch(Exception ignored){}trusted.put(u,set);}catch(Exception ignored){}
        var ks=getConfig().getConfigurationSection("command_keys");
        if(ks!=null)for(String id:ks.getKeys(false))try{commandKeys.put(UUID.fromString(id),getConfig().getBoolean("command_keys."+id));}catch(Exception ignored){}
    }
}
