package org.herolias.plugin.enchantment;

/**
 * Immutable data class holding enchantment levels for projectiles.
 * 
 * When a projectile is fired from an enchanted weapon, the enchantment levels
 * are captured and stored with the projectile so they can be applied on hit.
 * 
 * Use the Builder for cleaner construction:
 * <pre>
 * ProjectileEnchantmentData data = ProjectileEnchantmentData.builder()
 *     .strength(2)
 *     .looting(1)
 *     .burn(1)
 *     .build();
 * </pre>
 */
public class ProjectileEnchantmentData {
    private final int strengthLevel;
    private final int eaglesEyeLevel;
    private final int lootingLevel;
    private final int freezeLevel;
    private final int burnLevel;
    private final int eternalShotLevel;

    // Legacy constructors for backwards compatibility
    public ProjectileEnchantmentData(int strengthLevel, int eaglesEyeLevel, int lootingLevel) {
        this(strengthLevel, eaglesEyeLevel, lootingLevel, 0, 0, 0);
    }

    public ProjectileEnchantmentData(int strengthLevel, int eaglesEyeLevel, int lootingLevel, int freezeLevel) {
        this(strengthLevel, eaglesEyeLevel, lootingLevel, freezeLevel, 0, 0);
    }

    public ProjectileEnchantmentData(int strengthLevel, int eaglesEyeLevel, int lootingLevel, int freezeLevel, int burnLevel) {
        this(strengthLevel, eaglesEyeLevel, lootingLevel, freezeLevel, burnLevel, 0);
    }

    public ProjectileEnchantmentData(int strengthLevel, int eaglesEyeLevel, int lootingLevel, int freezeLevel, int burnLevel, int eternalShotLevel) {
        this.strengthLevel = strengthLevel;
        this.eaglesEyeLevel = eaglesEyeLevel;
        this.lootingLevel = lootingLevel;
        this.freezeLevel = freezeLevel;
        this.burnLevel = burnLevel;
        this.eternalShotLevel = eternalShotLevel;
    }

    // Builder pattern for cleaner construction
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int strengthLevel;
        private int eaglesEyeLevel;
        private int lootingLevel;
        private int freezeLevel;
        private int burnLevel;
        private int eternalShotLevel;

        public Builder strength(int level) { this.strengthLevel = level; return this; }
        public Builder eaglesEye(int level) { this.eaglesEyeLevel = level; return this; }
        public Builder looting(int level) { this.lootingLevel = level; return this; }
        public Builder freeze(int level) { this.freezeLevel = level; return this; }
        public Builder burn(int level) { this.burnLevel = level; return this; }
        public Builder eternalShot(int level) { this.eternalShotLevel = level; return this; }

        public ProjectileEnchantmentData build() {
            return new ProjectileEnchantmentData(strengthLevel, eaglesEyeLevel, lootingLevel, freezeLevel, burnLevel, eternalShotLevel);
        }
    }

    public int getStrengthLevel() {
        return strengthLevel;
    }

    public int getEaglesEyeLevel() {
        return eaglesEyeLevel;
    }

    public int getLootingLevel() {
        return lootingLevel;
    }
    
    public int getFreezeLevel() {
        return freezeLevel;
    }
    
    public int getBurnLevel() {
        return burnLevel;
    }

    public int getEternalShotLevel() {
        return eternalShotLevel;
    }

    /**
     * Gets the level of a specific enchantment type.
     * Provides type-safe access to enchantment levels.
     * 
     * @param type The enchantment type to check
     * @return The level, or 0 if not applicable
     */
    public int getLevel(EnchantmentType type) {
        return switch (type) {
            case STRENGTH -> strengthLevel;
            case EAGLES_EYE -> eaglesEyeLevel;
            case LOOTING -> lootingLevel;
            case FREEZE -> freezeLevel;
            case BURN -> burnLevel;
            case ETERNAL_SHOT -> eternalShotLevel;
            default -> 0;
        };
    }

    public boolean hasAny() {
        return strengthLevel > 0 || eaglesEyeLevel > 0 || lootingLevel > 0 || freezeLevel > 0 || burnLevel > 0 || eternalShotLevel > 0;
    }
}
