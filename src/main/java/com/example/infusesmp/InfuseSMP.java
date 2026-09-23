package com.infuse;

import com.infuse.commands.CommandHandler;
import com.infuse.craft.CraftManager;
import com.infuse.craft.RitualBossBar;
import com.infuse.data.DataManager;
import com.infuse.data.RitualData;
import com.infuse.effect.EffectManager;
import com.infuse.listeners.InfuseListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public final class InfusePlugin extends JavaPlugin {

    private DataManager dataManager;
    private EffectManager effectManager;
    private RitualBossBar bossBar;
    private CraftManager craftManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.dataManager = new DataManager(this);
        this.effectManager = new EffectManager(this, dataManager);
        this.bossBar = new RitualBossBar(this);
        this.craftManager = new CraftManager(this, dataManager, effectManager, bossBar);

        getServer().getPluginManager().registerEvents(new InfuseListener(this, dataManager, effectManager), this);
        getServer().getPluginManager().registerEvents(craftManager, this);
        getServer().getPluginManager().registerEvents(bossBar, this);

        CommandHandler handler = new CommandHandler(this, dataManager, effectManager, craftManager);
        String[] commands = {"infuse", "lspark", "rspark", "ldrain", "rdrain", "swap", "controls", "cleareffects", "cooldown"};
        for (String name : commands) {
            if (getCommand(name) != null) {
                getCommand(name).setExecutor(handler);
            } else {
                getLogger().warning("Command not found in plugin.yml: " + name);
            }
        }

        // Resume rituals interrupted by a restart.
        for (RitualData ritual : java.util.List.copyOf(dataManager.getRituals())) {
            craftManager.scheduleRitual(ritual);
            bossBar.start(ritual);
        }

        // Autosave every 5 minutes.
        new BukkitRunnable() {
            @Override
            public void run() {
                dataManager.save();
            }
        }.runTaskTimer(this, 6000L, 6000L);

        getLogger().info("InfuseSMP enabled - 11 effects loaded.");
    }

    @Override
    public void onDisable() {
        if (dataManager != null) {
            dataManager.save();
        }
    }

    public DataManager getDataManager() {
        return dataManager;
    }

    public EffectManager getEffectManager() {
        return effectManager;
    }

    public RitualBossBar getBossBar() {
        return bossBar;
    }

    public CraftManager getCraftManager() {
        return craftManager;
    }
}
