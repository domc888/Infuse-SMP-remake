package com.infuse;

public enum EffectType {
    SPEED("speed", "&bSpeed"),
    FIRE("fire", "&cFire"),
    INVISIBILITY("invisibility", "&fInvisibility"),
    THUNDER("thunder", "&eThunder"),
    REGENERATION("regeneration", "&dRegeneration"),
    FROST("frost", "&3Frost"),
    FEATHER("feather", "&7Feather"),
    HASTE("haste", "&6Haste"),
    STRENGTH("strength", "&4Strength"),
    HEART("heart", "&cHeart"),
    ENDER("ender", "&5Ender");

    private final String key;
    private final String display;

    EffectType(String key, String display) {
        this.key = key;
        this.display = display;
    }

    public String getKey() {
        return key;
    }

    public String getDisplay() {
        return display;
    }

    public static EffectType fromKey(String key) {
        if (key == null) return null;
        for (EffectType type : values()) {
            if (type.key.equalsIgnoreCase(key)) return type;
        }
        return null;
    }
}
