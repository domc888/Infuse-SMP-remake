package com.infuse;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import java.util.*;

public final class InfusePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
 private NamespacedKey effectKey,augKey; private boolean ritualActive=false; private Effect ritualEffect=Effect.EMPTY; private Location ritualLocation; private BukkitTask ritualTask; private final Map<UUID,Effect[]> slots=new HashMap<>(); private final Map<UUID,long[]> cd=new HashMap<>(); private final Map<UUID,Set<UUID>> trust=new HashMap<>();
 enum Effect {EMPTY,EMERALD,ENDER,FEATHER,FIRE,FROST,HASTE,HEART,INVIS,OCEAN,REGEN,SPEED,STRENGTH,THUNDER,APOPHIS,THIEF;
  static Effect of(String s){try{return valueOf(s.toUpperCase(Locale.ROOT));}catch(Exception e){return EMPTY;}} String id(){return name().toLowerCase(Locale.ROOT);}
 }
 @Override public void onEnable(){saveDefaultConfig();effectKey=new NamespacedKey(this,"effect");augKey=new NamespacedKey(this,"augmented");load();registerRecipes();getServer().getPluginManager().registerEvents(this,this);String[] cs={"infuse","lspark","rspark","ldrain","rdrain","swap","controls","cleareffects","cooldown"};for(String c:cs){PluginCommand x=getCommand(c);if(x!=null){x.setExecutor(this);x.setTabCompleter(this);}}getLogger().info("InfuseSMP enabled on Paper 1.21.11");new BukkitRunnable(){public void run(){passives();}}.runTaskTimer(this,20,20);}
 @Override public void onDisable(){save();}
 private Effect[] s(Player p){return slots.computeIfAbsent(p.getUniqueId(),x->new Effect[]{Effect.EMPTY,Effect.EMPTY});}
 private ItemStack item(Effect e,boolean aug){Material m=switch(e){case EMERALD->Material.EMERALD;case ENDER->Material.ENDER_EYE;case FEATHER->Material.FEATHER;case FIRE->Material.BLAZE_POWDER;case FROST->Material.BLUE_ICE;case HASTE->Material.GOLDEN_PICKAXE;case HEART->Material.RED_DYE;case INVIS->Material.PHANTOM_MEMBRANE;case OCEAN->Material.HEART_OF_THE_SEA;case REGEN->Material.GHAST_TEAR;case SPEED->Material.RABBIT_FOOT;case STRENGTH->Material.BLAZE_ROD;case THUNDER->Material.LIGHTNING_ROD;case APOPHIS->Material.NETHER_STAR;case THIEF->Material.SHEARS;default->Material.GLASS_BOTTLE;};ItemStack i=new ItemStack(m);ItemMeta q=i.getItemMeta();q.displayName(Component.text((aug?"Augmented ":"")+cap(e.id())+" Infusion"));q.getPersistentDataContainer().set(effectKey,PersistentDataType.STRING,e.id());q.getPersistentDataContainer().set(augKey,PersistentDataType.BYTE,(byte)(aug?1:0));i.setItemMeta(q);return i;}
 private String cap(String x){return x.substring(0,1).toUpperCase()+x.substring(1);}
 private Effect read(ItemStack i){if(i==null||!i.hasItemMeta())return Effect.EMPTY;String x=i.getItemMeta().getPersistentDataContainer().get(effectKey,PersistentDataType.STRING);return x==null?Effect.EMPTY:Effect.of(x);}
 private boolean aug(ItemStack i){return i!=null&&i.hasItemMeta()&&i.getItemMeta().getPersistentDataContainer().has(augKey,PersistentDataType.BYTE);}
 private void give(Player p,Effect e,boolean a){Effect[] z=s(p);int n=z[0]==Effect.EMPTY?0:z[1]==Effect.EMPTY?1:-1;if(n<0){p.sendMessage("§cBoth effect slots are full.");return;}z[n]=e;p.getInventory().addItem(item(e,a));p.sendMessage("§aApplied "+(a?"augmented ":"")+cap(e.id())+" to slot "+(n+1)+".");save();}
 private boolean friendly(Player a,Entity b){return b instanceof Player p&&(p.equals(a)||trust.getOrDefault(a.getUniqueId(),Set.of()).contains(p.getUniqueId()));}
 private void spark(Player p,int n){Effect e=s(p)[n];if(e==Effect.EMPTY){p.sendMessage("§cNo effect in that slot.");return;}long[] c=cd.computeIfAbsent(p.getUniqueId(),x->new long[2]);long now=System.currentTimeMillis();if(c[n]>now){p.sendMessage("§cCooldown: "+((c[n]-now+999)/1000)+"s");return;}c[n]=now+getConfig().getLong("cooldowns."+e.id(),60)*1000;Location l=p.getLocation();switch(e){
 case EMERALD->p.giveExp(30);
 case ENDER->{Block b=p.getTargetBlockExact(15);Location t=b==null?l.clone().add(l.getDirection().multiply(10)):b.getLocation().add(.5,1,.5);p.teleport(t);}
 case FEATHER->{p.setVelocity(new Vector(0,1.1,0).add(l.getDirection().multiply(.5)));p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,40,0));}
 case FIRE->{for(Entity x:p.getNearbyEntities(5,3,5))if(x instanceof LivingEntity le&&!friendly(p,x)){le.setFireTicks(100);le.damage(4,p);}}
 case FROST->{for(Entity x:p.getNearbyEntities(5,2,5))if(x instanceof LivingEntity le&&!friendly(p,x))le.setFreezeTicks(100);}
 case HASTE->p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,300,2));
 case HEART->p.setHealth(Math.min(p.getMaxHealth(),p.getHealth()+8));
 case INVIS->p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,400,0,true,false));
 case OCEAN->{for(Entity x:p.getNearbyEntities(5,3,5))if(x instanceof LivingEntity le&&!friendly(p,x))le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,100,3));}
 case REGEN->{p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,300,1));for(Player t:Bukkit.getOnlinePlayers())if(trust.getOrDefault(p.getUniqueId(),Set.of()).contains(t.getUniqueId())&&t.getLocation().distanceSquared(l)<25)t.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,100,0));}
 case SPEED->p.setVelocity(l.getDirection().multiply(2).setY(.25));
 case STRENGTH->p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,300,1));
 case THUNDER->{for(Entity x:p.getNearbyEntities(10,5,10))if(x instanceof LivingEntity le&&!friendly(p,x))p.getWorld().strikeLightning(le.getLocation());}
 case APOPHIS->{for(Entity x:p.getNearbyEntities(5,3,5))if(x instanceof LivingEntity le&&!friendly(p,x))le.damage(8,p);}
 case THIEF->{for(Entity x:p.getNearbyEntities(4,2,4))if(x instanceof Player q&&!friendly(p,q)){int v=Math.min(20,q.getTotalExperience()/10);q.giveExp(-v);p.giveExp(v);}}
 default->{}}p.sendMessage("§a"+cap(e.id())+" spark activated.");}
 private void passives(){for(Player p:Bukkit.getOnlinePlayers())for(Effect e:s(p))switch(e){case SPEED->p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,40,1,true,false));case HASTE->p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,40,1,true,false));case STRENGTH->p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,40,0,true,false));case OCEAN->p.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING,40,0,true,false));case FEATHER->p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,40,0,true,false));case FROST->p.setFreezeTicks(0);default->{}}}
 @EventHandler public void join(PlayerJoinEvent e){}
 @EventHandler public void death(PlayerDeathEvent e){if(!getConfig().getBoolean("effects.natural-death-drops",true)&&e.getEntity().getKiller()==null)return;Effect[] z=s(e.getEntity());for(Effect x:z)if(x!=Effect.EMPTY)e.getDrops().add(item(x,false));z[0]=z[1]=Effect.EMPTY;save();}
 @EventHandler public void interact(PlayerInteractEvent e){if(!e.getAction().isRightClick()||!e.getPlayer().isSneaking())return;Effect x=read(e.getItem());if(x==Effect.EMPTY)return;e.setCancelled(true);give(e.getPlayer(),x,aug(e.getItem()));if(e.getItem()!=null)e.getItem().setAmount(e.getItem().getAmount()-1);}
 @EventHandler public void craft(PrepareItemCraftEvent e){
 if(e.getRecipe()==null)return; Effect x=read(e.getRecipe().getResult()); if(x==Effect.EMPTY)return;
 long count=getConfig().getLong("crafted."+x.id(),0);
 if(count>=4&&!getConfig().getBoolean("allow-infinite-effects",false)){e.getInventory().setResult(null);return;}
 e.getInventory().setResult(item(x,count==0));
}
@EventHandler public void craftComplete(CraftItemEvent e){
 Effect x=read(e.getRecipe()==null?null:e.getRecipe().getResult()); if(x==Effect.EMPTY)return;
 if(e.isShiftClick()){e.setCancelled(true);return;}
 Player p=(Player)e.getWhoClicked(); long count=getConfig().getLong("crafted."+x.id(),0);
 if(count>=4&&!getConfig().getBoolean("allow-infinite-effects",false)){e.setCancelled(true);return;}
 if(count==0){
   if(ritualActive){e.setCancelled(true);p.sendMessage("§cA ritual is already active.");return;}
   Location loc=e.getInventory().getLocation(); if(loc==null){e.setCancelled(true);return;}
   startRitual(p,x,loc); e.setCurrentItem(null);
 } else if(getConfig().getBoolean("regular-craft-broadcast",true)){
   Bukkit.broadcast(Component.text("§d"+p.getName()+" crafted a "+cap(x.id())+" infusion at "+p.getLocation().getBlockX()+", "+p.getLocation().getBlockY()+", "+p.getLocation().getBlockZ()+"."));
 }
 getConfig().set("crafted."+x.id(),count+1); saveConfig();
 Bukkit.getScheduler().runTask(this,()->registerRecipes());
}
private void startRitual(Player p,Effect e,Location loc){
 ritualActive=true;ritualEffect=e;ritualLocation=loc.clone();
 BossBar bar=Bukkit.createBossBar("🧪 "+cap(e.id())+" 🧪",BarColor.PURPLE,BarStyle.SOLID);bar.setProgress(1.0);for(Player q:Bukkit.getOnlinePlayers())bar.addPlayer(q);
 int seconds=e==Effect.ENDER?3600:600;
 ritualTask=new BukkitRunnable(){int ticks=seconds*20;public void run(){ticks--;bar.setProgress(Math.max(0,ticks/(double)(seconds*20)));if(ticks<=0){loc.getWorld().dropItem(loc.clone().add(.5,1,.5),item(e,true));Bukkit.broadcast(Component.text("§a"+cap(e.id())+" ritual complete."));bar.removeAll();ritualActive=false;ritualEffect=Effect.EMPTY;ritualLocation=null;cancel();}}}.runTaskTimer(this,1,1);
}
 private void drain(Player p,int n){Effect[] z=s(p);if(z[n]==Effect.EMPTY){p.sendMessage("§cNo effect in that slot.");return;}Effect x=z[n];z[n]=Effect.EMPTY;p.getInventory().addItem(item(x,false));p.sendMessage("§aDrained "+cap(x.id())+".");save();}
 @Override public boolean onCommand(CommandSender sender,Command c,String l,String[] a){if(!(sender instanceof Player p)){sender.sendMessage("Players only.");return true;}switch(l.toLowerCase()){case "lspark"->spark(p,0);case "rspark"->spark(p,1);case "ldrain"->drain(p,0);case "rdrain"->drain(p,1);case "swap"->{Effect[] z=s(p);Effect t=z[0];z[0]=z[1];z[1]=t;save();p.sendMessage("§aEffects swapped.");}case "controls"->p.sendMessage("§aUse /lspark and /rspark to activate abilities.");case "trust","untrust"->trustCmd(p,a,l.equalsIgnoreCase("trust"));case "cleareffects"->adminClear(p,a);case "cooldown"->adminCd(p,a);case "infuse"->infuse(p,a);}return true;}
 private void trustCmd(Player p,String[] a,boolean add){if(a.length<1){p.sendMessage("§c/"+(add?"trust":"untrust")+" <player>");return;}Player q=Bukkit.getPlayerExact(a[0]);if(q==null){p.sendMessage("§cPlayer not found.");return;}Set<UUID> set=trust.computeIfAbsent(p.getUniqueId(),x->new HashSet<>());if(add)set.add(q.getUniqueId());else set.remove(q.getUniqueId());p.sendMessage("§a"+(add?"Trusted ":"Untrusted ")+q.getName()+".");}
 private void adminClear(Player p,String[] a){if(!p.hasPermission("infuse.admin")||a.length<1){p.sendMessage("§cNo permission or player missing.");return;}Player q=Bukkit.getPlayerExact(a[0]);if(q!=null){slots.remove(q.getUniqueId());p.sendMessage("§aCleared "+q.getName()+".");}}
 private void adminCd(Player p,String[] a){if(!p.hasPermission("infuse.admin")||a.length<1){p.sendMessage("§cNo permission or player missing.");return;}Player q=Bukkit.getPlayerExact(a[0]);if(q!=null){cd.remove(q.getUniqueId());p.sendMessage("§aCooldowns reset.");}}
 private void infuse(Player p,String[] a){if(a.length==0){p.sendMessage("§d/infuse give <effect> [augmented]");return;}if(a[0].equalsIgnoreCase("reload")&&p.hasPermission("infuse.admin")){reloadConfig();p.sendMessage("§aReloaded.");return;}if(a[0].equalsIgnoreCase("give")&&p.hasPermission("infuse.admin")&&a.length>1){Effect x=Effect.of(a[1]);if(x!=Effect.EMPTY)give(p,x,a.length>2&&a[2].equalsIgnoreCase("augmented"));}}
 @Override public List<String> onTabComplete(CommandSender s,Command c,String l,String[] a){if(l.equalsIgnoreCase("infuse")&&a.length==1)return List.of("give","reload");if(l.equalsIgnoreCase("infuse")&&a.length==2)return Arrays.stream(Effect.values()).filter(x->x!=Effect.EMPTY).map(Effect::id).toList();return List.of();}
 private void registerRecipes(){
  for(Effect e:Effect.values()) if(e!=Effect.EMPTY){ Bukkit.removeRecipe(new NamespacedKey(this,"craft_"+e.id())); Bukkit.removeRecipe(new NamespacedKey(this,"aug_"+e.id())); }
  for(Effect e:Effect.values()) if(e!=Effect.EMPTY) addRecipe(e, getConfig().getLong("crafted."+e.id(),0)==0);
}
private void addRecipe(Effect e, boolean augmented){
  String[] shape; Map<Character,Material> m=new HashMap<>();
  switch(e){
   case EMERALD -> {shape=new String[]{"WSW","OEO","CTC"};m.put('W',Material.WILD_ARMOR_TRIM_SMITHING_TEMPLATE);m.put('S',Material.SNIFFER_EGG);m.put('O',Material.OMINOUS_BOTTLE);m.put('E',Material.EMERALD_BLOCK);m.put('C',Material.ENDER_CHEST);m.put('T',Material.ENCHANTING_TABLE);}
   case FEATHER -> {shape=new String[]{"BPB","FHF","BPB"};m.put('B',Material.BREEZE_ROD);m.put('P',Material.PHANTOM_MEMBRANE);m.put('F',Material.FEATHER);m.put('H',Material.HEAVY_CORE);}
   case FIRE -> {shape=new String[]{"URU","NPN","URU"};m.put('U',Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE);m.put('R',Material.RESPAWN_ANCHOR);m.put('N',Material.NETHERITE_INGOT);m.put('P',Material.MUSIC_DISC_PIGSTEP);}
   case ENDER -> {shape=new String[]{"RAR","AEA","RAR"};m.put('R',Material.DRAGON_HEAD);m.put('A',Material.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE);m.put('E',augmented?Material.DRAGON_EGG:Material.ENDER_EYE);}
   case FROST -> {shape=new String[]{"TPT","HDH","TPT"};m.put('T',Material.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE);m.put('P',Material.PEARLESCENT_FROGLIGHT);m.put('H',Material.GOAT_HORN);m.put('D',Material.MUSIC_DISC_CREATOR);}
   case HASTE -> {shape=new String[]{"DBD","GEG","DND"};m.put('D',Material.DIAMOND_BLOCK);m.put('B',Material.BEACON);m.put('G',Material.GOLD_BLOCK);m.put('E',Material.DEEPSLATE_EMERALD_ORE);m.put('N',Material.NETHERITE_PICKAXE);}
   case HEART -> {shape=new String[]{"PTP","EBE","PTP"};m.put('P',Material.POTION);m.put('T',Material.TOTEM_OF_UNDYING);m.put('E',Material.ENCHANTED_GOLDEN_APPLE);m.put('B',Material.BEETROOT);}
   case INVIS -> {shape=new String[]{"EME","SOS","ERE"};m.put('E',Material.ENDER_EYE);m.put('M',Material.MUSIC_DISC_5);m.put('S',Material.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE);m.put('O',Material.OMINOUS_TRIAL_KEY);m.put('R',Material.RECOVERY_COMPASS);}
   case OCEAN -> {shape=new String[]{"EHE","TCT","EHE"};m.put('E',Material.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE);m.put('H',Material.HEART_OF_THE_SEA);m.put('T',Material.TRIDENT);m.put('C',Material.CONDUIT);}
   case REGEN -> {shape=new String[]{"EHE","PAP","EMM"};m.put('E',Material.END_CRYSTAL);m.put('H',Material.HONEY_BLOCK);m.put('P',Material.PEARLESCENT_FROGLIGHT);m.put('A',Material.AXOLOTL_BUCKET);m.put('M',Material.MUSIC_DISC_CREATOR_MUSIC_BOX);}
   case SPEED -> {shape=new String[]{"RSR","DHE","RNR"};m.put('R',Material.RABBIT_FOOT);m.put('S',Material.SADDLE);m.put('D',Material.DUNE_ARMOR_TRIM_SMITHING_TEMPLATE);m.put('H',Material.DIAMOND_HORSE_ARMOR);m.put('E',Material.EYE_ARMOR_TRIM_SMITHING_TEMPLATE);m.put('N',Material.NETHERITE_BOOTS);}
   case STRENGTH -> {shape=new String[]{"PRP","SWA","PRP"};m.put('P',Material.PLAYER_HEAD);m.put('R',Material.RIB_ARMOR_TRIM_SMITHING_TEMPLATE);m.put('S',Material.NETHERITE_SWORD);m.put('W',Material.WITHER_ROSE);m.put('A',Material.NETHERITE_AXE);}
   case THUNDER -> {shape=new String[]{"BLB","TCT","BLB"};m.put('B',Material.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE);m.put('L',Material.LODESTONE);m.put('T',Material.TRIDENT);m.put('C',Material.CREEPER_HEAD);}
   case APOPHIS -> {shape=new String[]{"BLB","LCL","BLB"};m.put('B',Material.RIB_ARMOR_TRIM_SMITHING_TEMPLATE);m.put('L',Material.NETHERITE_INGOT);m.put('C',Material.NETHER_STAR);}
   case THIEF -> {shape=new String[]{"BLB","CDC","ZXZ"};m.put('B',Material.ENCHANTED_GOLDEN_APPLE);m.put('L',Material.ENDER_EYE);m.put('C',Material.PLAYER_HEAD);m.put('D',Material.SHEARS);m.put('Z',Material.RABBIT_FOOT);m.put('X',Material.SCULK_CATALYST);}
   default -> {return;}
  }
  NamespacedKey k=new NamespacedKey(this,(augmented?"aug_":"craft_")+e.id());
  ShapedRecipe r=new ShapedRecipe(k,item(e,augmented));r.shape(shape);for(var x:m.entrySet())r.setIngredient(x.getKey(),x.getValue());getServer().addRecipe(r);
}

 private void load(){var sec=getConfig().getConfigurationSection("players");if(sec==null)return;for(String k:sec.getKeys(false))try{UUID id=UUID.fromString(k);Effect[] z=slots.computeIfAbsent(id,x->new Effect[]{Effect.EMPTY,Effect.EMPTY});z[0]=Effect.of(getConfig().getString("players."+k+".slot1","empty"));z[1]=Effect.of(getConfig().getString("players."+k+".slot2","empty"));}catch(Exception ignored){}}
 private void save(){getConfig().set("players",null);for(var e:slots.entrySet()){getConfig().set("players."+e.getKey()+".slot1",e.getValue()[0].id());getConfig().set("players."+e.getKey()+".slot2",e.getValue()[1].id());}saveConfig();}
}