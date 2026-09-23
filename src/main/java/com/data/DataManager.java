package com.infuse.data;

import com.infuse.InfusePlugin;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

public class DataManager {

    private final InfusePlugin plugin;
    private final File file;
    private YamlConfiguration yaml;

    private final Map<UUID, PlayerState> players = new HashMap<>();
    private final Map<String, Integer> crafted = new HashMap<>();
    private final List<RitualData> rituals = new ArrayList<>();

    public DataManager(InfusePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
        load();
    }

    public PlayerState state(UUID uuid) {
        return players.computeIfAbsent(uuid, k -> new PlayerState());
    }

    public int getCrafted(String effectKey) {
        return crafted.getOrDefault(effectKey.toLowerCase(), 0);
    }

    public void addCrafted(String effectKey) {
        String key = effectKey.toLowerCase();
        crafted.put(key, getCrafted(key) + 1);
        save();
    }

    public List<RitualData> getRituals() {
        return rituals;
    }

    public void addRitual(RitualData ritual) {
        rituals.add(ritual);
        save();
    }

    public void removeRitual(RitualData ritual) {
        rituals.remove(ritual);
        save();
    }

    @SuppressWarnings("unchecked")
    public void load() {
        if (!file.exists()) {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            yaml = new YamlConfiguration();
            return;
        }
        yaml = YamlConfiguration.loadConfiguration(file);
        players.clear();
        crafted.clear();
        rituals.clear();

        ConfigurationSection playersSec = yaml.getConfigurationSection("players");
        if (playersSec != null) {
            for (String uuidStr : playersSec.getKeys(false)) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(uuidStr);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                PlayerState state = new PlayerState();
                String base = "players." + uuidStr + ".";
                state.left = yaml.getString(base + "left");
                state.right = yaml.getString(base + "right");
                state.leftAug = yaml.getBoolean(base + "leftaug");
                state.rightAug = yaml.getBoolean(base + "rightaug");
                state.leftCd = yaml.getLong(base + "lcd");
                state.rightCd = yaml.getLong(base + "rcd");
                state.mode = yaml.getString(base + "mode", "keys");
                players.put(uuid, state);
            }
        }

        ConfigurationSection craftedSec = yaml.getConfigurationSection("effects");
        if (craftedSec != null) {
            for (String key : craftedSec.getKeys(false)) {
                crafted.put(key.toLowerCase(), craftedSec.getInt(key));
            }
        }

        List<Map<?, ?>> ritualList = yaml.getMapList("rituals");
        for (Map<?, ?> map : ritualList) {
            RitualData ritual = new RitualData();
            ritual.effectKey = String.valueOf(map.get("effect"));
            ritual.world = String.valueOf(map.get("world"));
            ritual.x = toDouble(map.get("x"));
            ritual.y = toDouble(map.get("y"));
            ritual.z = toDouble(map.get("z"));
            ritual.endTime = toLong(map.get("end"));
            rituals.add(ritual);
        }
    }

    public void save() {
        if (yaml == null) yaml = new YamlConfiguration();
        yaml.set("players", null);
        for (Map.Entry<UUID, PlayerState> entry : players.entrySet()) {
            PlayerState s = entry.getValue();
            String base = "players." + entry.getKey() + ".";
            if (s.left != null) yaml.set(base + "left", s.left);
            if (s.right != null) yaml.set(base + "right", s.right);
            if (s.leftAug) yaml.set(base + "leftaug", true);
            if (s.rightAug) yaml.set(base + "rightaug", true);
            if (s.leftCd > 0) yaml.set(base + "lcd", s.leftCd);
            if (s.rightCd > 0) yaml.set(base + "rcd", s.rightCd);
            if (!"keys".equals(s.mode)) yaml.set(base + "mode", s.mode);
        }
        yaml.set("effects", null);
        for (Map.Entry<String, Integer> entry : crafted.entrySet()) {
            yaml.set("effects." + entry.getKey(), entry.getValue());
        }
        List<Map<String, Object>> ritualList = new ArrayList<>();
        for (RitualData ritual : rituals) {
            Map<String, Object> map = new HashMap<>();
            map.put("effect", ritual.effectKey);
            map.put("world", ritual.world);
            map.put("x", ritual.x);
            map.put("y", ritual.y);
            map.put("z", ritual.z);
            map.put("end", ritual.endTime);
            ritualList.add(map);
        }
        yaml.set("rituals", ritualList);
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save data.yml: " + e.getMessage());
        }
    }

    private double toDouble(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        return 0;
    }

    private long toLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        return 0;
    }
}
