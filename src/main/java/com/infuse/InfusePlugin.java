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
 private NamespacedKey effectKey,augKey; private final Map<UUID,Effect[]> slots=new HashMap<>(); private final Map<UUID,long[]> cd=new HashMap<>(); private final Map<UUID,Set<UUID>> trust=new HashMap<>();
 enum Effect {EMPTY,EMERALD,ENDER,FEATHER,FIRE,FROST,HASTE,HEART,INVIS,OCEAN,REGEN,SPEED,STRENGTH,THUNDER,APOPHIS,THIEF;
  static Effect of(String s){try{return valueOf(s.toUpperCase(Locale.ROOT));}catch(Exception e){return EMPTY;}} String id(){return name().toLowerCase(Locale.ROOT);}
 }
 @Override public void onEnable(){saveDefaultConfig();load();registerRecipes();effectKey=new NamespacedKey(this,"effect");augKey=new NamespacedKey(this,"augmented");getServer().getPluginManager().registerEvents(this,this);String[] cs={"infuse","lspark","rspark","ldrain","rdrain","swap","controls","cleareffects","cooldown"};for(String c:cs){PluginCommand x=getCommand(c);if(x!=null){x.setExecutor(this);x.setTabCompleter(this);}}getLogger().info("InfuseSMP enabled on Paper 1.21.11");new BukkitRunnable(){public void run(){passives();}}.runTaskTimer(this,20,20);}
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
 @EventHandler public void craft(PrepareItemCraftEvent e){if(e.getRecipe()==null)return;Effect x=read(e.getRecipe().getResult());if(x==Effect.EMPTY)return;long count=getConfig().getLong("crafted."+x.id(),0);if(count>=4&&!getConfig().getBoolean("allow-infinite-effects",false))e.getInventory().setResult(null);else if(count==0)e.getInventory().setResult(item(x,true));}
 private void drain(Player p,int n){Effect[] z=s(p);if(z[n]==Effect.EMPTY){p.sendMessage("§cNo effect in that slot.");return;}Effect x=z[n];z[n]=Effect.EMPTY;p.getInventory().addItem(item(x,false));p.sendMessage("§aDrained "+cap(x.id())+".");save();}
 @Override public boolean onCommand(CommandSender sender,Command c,String l,String[] a){if(!(sender instanceof Player p)){sender.sendMessage("Players only.");return true;}switch(l.toLowerCase()){case "lspark"->spark(p,0);case "rspark"->spark(p,1);case "ldrain"->drain(p,0);case "rdrain"->drain(p,1);case "swap"->{Effect[] z=s(p);Effect t=z[0];z[0]=z[1];z[1]=t;save();p.sendMessage("§aEffects swapped.");}case "controls"->p.sendMessage("§aUse /lspark and /rspark to activate abilities.");case "trust","untrust"->trustCmd(p,a,l.equalsIgnoreCase("trust"));case "cleareffects"->adminClear(p,a);case "cooldown"->adminCd(p,a);case "infuse"->infuse(p,a);}return true;}
 private void trustCmd(Player p,String[] a,boolean add){if(a.length<1){p.sendMessage("§c/"+(add?"trust":"untrust")+" <player>");return;}Player q=Bukkit.getPlayerExact(a[0]);if(q==null){p.sendMessage("§cPlayer not found.");return;}Set<UUID> set=trust.computeIfAbsent(p.getUniqueId(),x->new HashSet<>());if(add)set.add(q.getUniqueId());else set.remove(q.getUniqueId());p.sendMessage("§a"+(add?"Trusted ":"Untrusted ")+q.getName()+".");}
 private void adminClear(Player p,String[] a){if(!p.hasPermission("infuse.admin")||a.length<1){p.sendMessage("§cNo permission or player missing.");return;}Player q=Bukkit.getPlayerExact(a[0]);if(q!=null){slots.remove(q.getUniqueId());p.sendMessage("§aCleared "+q.getName()+".");}}
 private void adminCd(Player p,String[] a){if(!p.hasPermission("infuse.admin")||a.length<1){p.sendMessage("§cNo permission or player missing.");return;}Player q=Bukkit.getPlayerExact(a[0]);if(q!=null){cd.remove(q.getUniqueId());p.sendMessage("§aCooldowns reset.");}}
 private void infuse(Player p,String[] a){if(a.length==0){p.sendMessage("§d/infuse give <effect> [augmented]");return;}if(a[0].equalsIgnoreCase("reload")&&p.hasPermission("infuse.admin")){reloadConfig();p.sendMessage("§aReloaded.");return;}if(a[0].equalsIgnoreCase("give")&&p.hasPermission("infuse.admin")&&a.length>1){Effect x=Effect.of(a[1]);if(x!=Effect.EMPTY)give(p,x,a.length>2&&a[2].equalsIgnoreCase("augmented"));}}
 @Override public List<String> onTabComplete(CommandSender s,Command c,String l,String[] a){if(l.equalsIgnoreCase("infuse")&&a.length==1)return List.of("give","reload");if(l.equalsIgnoreCase("infuse")&&a.length==2)return Arrays.stream(Effect.values()).filter(x->x!=Effect.EMPTY).map(Effect::id).toList();return List.of();}
 private void registerRecipes(){for(Effect e:Effect.values())if(e!=Effect.EMPTY){NamespacedKey k=new NamespacedKey(this,"craft_"+e.id());ShapedRecipe r=new ShapedRecipe(k,item(e,false));r.shape("ABA","CDC","AEA");r.setIngredient('A',Material.IRON_INGOT);r.setIngredient('B',Material.DIAMOND);r.setIngredient('C',Material.GOLD_INGOT);r.setIngredient('D',Material.NETHER_STAR);r.setIngredient('E',switch(e){case EMERALD->Material.EMERALD_BLOCK;case ENDER->Material.ENDER_PEARL;case FEATHER->Material.FEATHER;case FIRE->Material.BLAZE_ROD;case FROST->Material.BLUE_ICE;case HASTE->Material.GOLDEN_PICKAXE;case HEART->Material.GOLDEN_APPLE;case INVIS->Material.PHANTOM_MEMBRANE;case OCEAN->Material.HEART_OF_THE_SEA;case REGEN->Material.GHAST_TEAR;case SPEED->Material.RABBIT_FOOT;case STRENGTH->Material.BLAZE_POWDER;case THUNDER->Material.LIGHTNING_ROD;case APOPHIS->Material.NETHER_STAR;case THIEF->Material.SHEARS;default->Material.AIR;});getServer().addRecipe(r);}}
 private void load(){var sec=getConfig().getConfigurationSection("players");if(sec==null)return;for(String k:sec.getKeys(false))try{UUID id=UUID.fromString(k);Effect[] z=slots.computeIfAbsent(id,x->new Effect[]{Effect.EMPTY,Effect.EMPTY});z[0]=Effect.of(getConfig().getString("players."+k+".slot1","empty"));z[1]=Effect.of(getConfig().getString("players."+k+".slot2","empty"));}catch(Exception ignored){}}
 private void save(){getConfig().set("players",null);for(var e:slots.entrySet()){getConfig().set("players."+e.getKey()+".slot1",e.getValue()[0].id());getConfig().set("players."+e.getKey()+".slot2",e.getValue()[1].id());}saveConfig();}
}