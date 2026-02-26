package org.herolias.plugin.enchantment;

import java.util.Set;

/**
 * Defines all available enchantments in the SimpleEnchanting plugin.
 * 
 * The system is designed to be extensible - new enchantments can be added 
 * by adding new enum values with their properties.
 */
public enum EnchantmentType {
    
    /**
     * Sharpness - Increases melee damage by 10% per level
     * Applicable to: Swords, Axes (melee weapons)
     */
    SHARPNESS(
        "sharpness",
        "Sharpness",
        "Increases melee damage",
        3,  // max level
        false, // requiresDurability
        false, // isLegendary
        ItemCategory.MELEE_WEAPON
    ),

    /**
     * Life Leech - Heals the attacker for a portion of damage dealt
     * Applicable to: Melee weapons
     */
    LIFE_LEECH(
        "life_leech",
        "Life Leech",
        "Heals for a portion of damage dealt",
        1,  // max level
        false, // requiresDurability
        true, // isLegendary
        ItemCategory.MELEE_WEAPON   
    ),
    
    /**
     * Durability - Reduces durability loss by 25%/50%/75% per level
     * Applicable to: All equipment (weapons, tools, armor)
     */
    DURABILITY(
        "durability",
        "Durability",
        "Reduces durability loss",
        3,  // max level
        true, // requires durability
        false, // isLegendary
        ItemCategory.MELEE_WEAPON, ItemCategory.RANGED_WEAPON, ItemCategory.TOOL, ItemCategory.PICKAXE,
        ItemCategory.SHOVEL, ItemCategory.AXE, ItemCategory.ARMOR,
        ItemCategory.STAFF, ItemCategory.STAFF_MANA, ItemCategory.STAFF_ESSENCE
    ),

    /**
     * Sturdy - Prevents repair-kit max durability loss
     * Applicable to: All equipment (weapons, tools, armor)
     */
    STURDY(
        "sturdy",
        "Sturdy",
        "Prevents repair durability penalty",
        1,  // max level
        true, // requires durability
        true, // isLegendary
        ItemCategory.MELEE_WEAPON, ItemCategory.RANGED_WEAPON, ItemCategory.TOOL, ItemCategory.PICKAXE,
        ItemCategory.SHOVEL, ItemCategory.AXE, ItemCategory.ARMOR,
        ItemCategory.STAFF, ItemCategory.STAFF_MANA, ItemCategory.STAFF_ESSENCE
    ),

    /**
     * Dexterity - Reduces stamina costs for blocking and abilities
     * Applicable to: Shields and melee weapons
     */
    DEXTERITY(
        "dexterity",
        "Dexterity",
        "Reduces stamina costs",
        3,  // max level
        false, // requiresDurability
        false, // isLegendary
        ItemCategory.MELEE_WEAPON, ItemCategory.SHIELD,
        ItemCategory.STAFF, ItemCategory.STAFF_MANA, ItemCategory.STAFF_ESSENCE
    ),

    /**
     * Protection - Reduces incoming physical damage by 4% per level (per armor piece)
     * Applicable to: Armor
     */
    PROTECTION(
        "protection",
        "Protection",
        "Increases physical damage resistance",
        3,  // max level
        false, // requiresDurability
        false, // isLegendary
        ItemCategory.ARMOR
    ),

    /**
     * Efficiency - Increases mining speed by 20% per level
     * Applicable to: Pickaxes, Axes, Shovels
     */
    EFFICIENCY(
        "efficiency",
        "Efficiency",
        "Increases mining speed",
        3,  // max level
        false, // requiresDurability
        false, // isLegendary
        ItemCategory.PICKAXE, ItemCategory.AXE, ItemCategory.SHOVEL
    ),

    /**
     * Fortune - Adds a chance for extra ore/crystal drops
     * Applicable to: Pickaxes
     */
    FORTUNE(
        "fortune",
        "Fortune",
        "Chance for extra ore/crystal drops",
        3,  // max level
        false, // requiresDurability
        false, // isLegendary
        ItemCategory.PICKAXE
    ),

    /**
     * Smelting - Automatically smelts mined blocks
     * Applicable to: Pickaxes
     */
    SMELTING(
        "smelting",
        "Smelting",
        "Automatically smelts mined blocks",
        1,  // max level
        false, // requiresDurability
        true, // isLegendary
        ItemCategory.PICKAXE
    ),

    /**
     * Strength - Increases projectile damage and range
     * Applicable to: Ranged weapons
     */
    STRENGTH(
        "strength",
        "Strength",
        "Increases projectile damage and range",
        3,  // max level
        false, // requiresDurability
        false, // isLegendary
        ItemCategory.RANGED_WEAPON
    ),

    /**
     * Eagle's Eye - Increases projectile damage based on distance
     * Applicable to: Ranged weapons
     */
    EAGLES_EYE(
        "eagles_eye",
        "Eagle's Eye",
        "Increases damage with distance",
        3,  // max level
        false, // requiresDurability
        false, // isLegendary
        ItemCategory.RANGED_WEAPON
    ),

    /**
     * Looting - Increases enemy drops and rare drop chance
     * Applicable to: Melee and ranged weapons
     */
    LOOTING(
        "looting",
        "Looting",
        "Increases enemy drops and rare drop chance",
        3,  // max level
        false, // requiresDurability
        false, // isLegendary
        ItemCategory.MELEE_WEAPON, ItemCategory.RANGED_WEAPON,
        ItemCategory.STAFF, ItemCategory.STAFF_MANA, ItemCategory.STAFF_ESSENCE
    ),
    
    /**
     * Feather Falling - Reduces fall damage by 20% per level
     * Applicable to: Boots only
     */
    FEATHER_FALLING(
        "feather_falling",
        "Feather Falling",
        "Reduces fall damage",
        3,  // max level
        false, // requiresDurability
        false, // isLegendary
        ItemCategory.BOOTS
    ),

    /**
     * Waterbreathing - Reduces oxygen drain underwater by 20% per level
     * Applicable to: Helmets only
     */
    WATERBREATHING(
        "waterbreathing",
        "Waterbreathing",
        "Breathe underwater longer",
        3,  // max level
        false, // requiresDurability
        false, // isLegendary
        ItemCategory.HELMET
    ),

    /**
     * Burn - Sets targets on fire, dealing 3 fire damage per second for 3 seconds
     * Applicable to: Melee weapons
     */
    BURN(
        "burn",
        "Burn",
        "Sets target on fire",
        1,  // max level (single level)
        false, // requiresDurability
        true, // isLegendary
        ItemCategory.MELEE_WEAPON, ItemCategory.RANGED_WEAPON
    ),

    /**
     * Freeze - Immobilizes targets with the Freeze status effect
     * Applicable to: Ranged and Melee weapons
     */
    FREEZE(
        "freeze",
        "Freeze",
        "Slows targets on hit",
        1,  // max level (single level)
        false, // requiresDurability
        true, // isLegendary
        ItemCategory.RANGED_WEAPON, ItemCategory.MELEE_WEAPON
    ),

    /**
     * Eternal Shot - Allows shooting arrows without consuming them (like Minecraft Infinity)
     * Applicable to: Bows and Crossbows (ranged weapons)
     */
    ETERNAL_SHOT(
        "eternal_shot",
        "Eternal Shot",
        "Shoot without consuming arrows",
        1,  // max level (single level)
        false, // requiresDurability
        false, // isLegendary
        ItemCategory.RANGED_WEAPON
    ),
    /**
     * Pick Perfect - Drops the block itself when mined
     * Applicable to: Pickaxes, Axes, Shovels
     */
    PICK_PERFECT(
        "pick_perfect",
        "Pick Perfect",
        "Drops the block itself when mined",
        1,  // max level
        false, // requiresDurability
        true, // isLegendary
        ItemCategory.PICKAXE, ItemCategory.AXE, ItemCategory.SHOVEL
    ),

    /**
     * Thrift - Restores mana on hit
     * Applicable to: Any item that consumes Mana or MagicCharges (e.g., Staves)
     */
    THRIFT(
        "thrift",
        "Thrift",
        "Restores mana on hit",
        3,  // max level
        false, // requiresDurability
        false, // isLegendary
        ItemCategory.STAFF_MANA
    ),

    /**
     * Elemental Heart - Chance to save essence properties when casting
     * Applicable to: Staffs
     */
    ELEMENTAL_HEART(
        "elemental_heart",
        "Elemental Heart",
        "Chance to save essence",
        1,  // max level
        false, // requiresDurability
        true, // isLegendary
        ItemCategory.STAFF_ESSENCE
    ),

    /**
     * Knockback - Knocks targets back
     * Applicable to: Melee weapons
     */
    KNOCKBACK(
        "knockback",
        "Knockback",
        "Knocks targets back",
        3,  // max level
        false, // requiresDurability
        false, // isLegendary
        ItemCategory.MELEE_WEAPON
    ),

    /**
     * Reflection - Reflects damage when blocking
     * Applicable to: Shields
     */
    REFLECTION(
        "reflection",
        "Reflection",
        "Reflects damage when blocking",
        3,  // max level
        false, // requiresDurability
        false, // isLegendary
        ItemCategory.SHIELD
    ),
    
    /**
     * Absorption - Heals the blocker by a part of the blocked damage
     * Applicable to: Shields
     */
    ABSORPTION(
        "absorption",
        "Absorption",
        "Heals directly from blocked damage",
        3,  // max level
        false, // requiresDurability
        false, // isLegendary
        ItemCategory.SHIELD
    ),

    /**
     * Fast Swim - Increases swim speed
     * Applicable to: Gloves
     */
    FAST_SWIM(
        "fast_swim",
        "Swift Swim",
        "Increases swimming speed",
        3,  // max level
        false, // requiresDurability
        false, // isLegendary
        ItemCategory.GLOVES
    ),

    /**
     * Night Vision - Increases sight in dark places (caves, nighttime, underwater)
     * Applicable to: Helmets only
     */
    NIGHT_VISION(
        "night_vision",
        "Night Vision",
        "Increases visibility in dark environments",
        1,  // max level
        false, // requiresDurability
        true, // isLegendary
        ItemCategory.HELMET
    ),

    /**
     * Ranged Protection - Reduces incoming projectile and magic damage by 4% per level (per armor piece)
     * Applicable to: Armor
     */
    RANGED_PROTECTION(
        "ranged_protection",
        "Ranged Protection",
        "Reduces projectile and magic damage",
        3,  // max level
        false, // requiresDurability
        false, // isLegendary
        ItemCategory.ARMOR
    ),

    /**
     * Frenzy - Increases ability charge rate
     * Applicable to: All weapons
     */
    FRENZY(
        "frenzy",
        "Frenzy",
        "Increases ability charge rate",
        3,  // max level
        false, // requiresDurability
        false, // isLegendary
        ItemCategory.MELEE_WEAPON, ItemCategory.RANGED_WEAPON, 
        ItemCategory.STAFF, ItemCategory.STAFF_MANA, ItemCategory.STAFF_ESSENCE
    ),

    /**
     * Riposte - Increases damage dealt by counter attacks
     * Applicable to: Melee weapons
     */
    RIPOSTE(
        "riposte",
        "Riposte",
        "Increases counter attack damage",
        3,  // max level
        false, // requiresDurability
        false, // isLegendary
        ItemCategory.MELEE_WEAPON
    ),

    /**
     * Coup de Grâce - Increases damage towards stunned/downed enemies
     * Applicable to: Melee weapons
     */
    COUP_DE_GRACE(
        "coup_de_grace",
        "Coup de Grâce",
        "Increases damage to stunned enemies",
        3,  // max level
        false, // requiresDurability
        false, // isLegendary
        ItemCategory.MELEE_WEAPON
    )
    ;

    private final String id;
    private final String displayName;
    private final String description;
    private final int maxLevel;
    private final Set<ItemCategory> applicableCategories;
    private final boolean requiresDurability;
    private final boolean isLegendary;

    private static final java.util.Map<String, EnchantmentType> BY_ID = new java.util.HashMap<>();
    private static final java.util.Map<String, EnchantmentType> BY_DISPLAY_NAME = new java.util.HashMap<>();

    static {
        for (EnchantmentType type : values()) {
            BY_ID.put(type.id, type);
            BY_DISPLAY_NAME.put(type.displayName.toLowerCase(), type);
        }
    }

    EnchantmentType(String id, String displayName, String description, 
            int maxLevel, boolean requiresDurability, boolean isLegendary, ItemCategory... categories) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.maxLevel = maxLevel;
        this.requiresDurability = requiresDurability;
        this.isLegendary = isLegendary;
        this.applicableCategories = Set.of(categories);
    }
    
    public boolean requiresDurability() {
        return requiresDurability;
    }

    public boolean isLegendary() {
        return isLegendary;
    }
    
    /**
     * Checks if this enchantment conflicts with another enchantment.
     */
    private static final Set<Set<EnchantmentType>> CONFLICT_PAIRS = Set.of(
        Set.of(BURN, FREEZE),
        Set.of(PICK_PERFECT, FORTUNE),
        Set.of(PICK_PERFECT, SMELTING),
        Set.of(REFLECTION, ABSORPTION)
    );

    public boolean conflictsWith(EnchantmentType other) {
        if (this == other) return true;
        return CONFLICT_PAIRS.contains(Set.of(this, other));
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public int getMaxLevel() {
        return maxLevel;
    }

    /**
     * Gets the effect multiplier per level from the active configuration.
     */
    public double getEffectMultiplier() {
        org.herolias.plugin.config.EnchantingConfig config = org.herolias.plugin.SimpleEnchanting.getInstance().getConfigManager().getConfig();
        return switch (this) {
            case SHARPNESS -> config.sharpnessDamageMultiplierPerLevel;
            case LIFE_LEECH -> config.lifeLeechPercentage;
            case DURABILITY -> config.durabilityReductionPerLevel;
            case DEXTERITY -> config.dexterityStaminaReductionPerLevel;
            case PROTECTION -> config.protectionDamageReductionPerLevel;
            case EFFICIENCY -> config.efficiencyMiningSpeedPerLevel;
            case FORTUNE -> config.fortuneRollChancePerLevel;
            case STRENGTH -> config.strengthDamageMultiplierPerLevel;
            case EAGLES_EYE -> config.eaglesEyeDistanceBonusPerLevel;
            case LOOTING -> config.lootingChanceMultiplierPerLevel;
            case FEATHER_FALLING -> config.featherFallingReductionPerLevel;
            case WATERBREATHING -> config.waterBreathingReductionPerLevel;
            case THRIFT -> config.thriftRestoreAmountPerLevel;
            case ELEMENTAL_HEART -> config.elementalHeartSaveChancePerLevel;
            case KNOCKBACK -> config.knockbackStrengthPerLevel;
            case REFLECTION -> config.reflectionDamagePercentagePerLevel;
            case ABSORPTION -> config.absorptionHealPercentagePerLevel;
            case FAST_SWIM -> config.fastSwimSpeedBonusPerLevel;
            case RANGED_PROTECTION -> config.rangedProtectionDamageReductionPerLevel;
            case FRENZY -> config.frenzyChargeSpeedMultiplierPerLevel;
            case RIPOSTE -> config.riposteDamageMultiplierPerLevel;
            case COUP_DE_GRACE -> config.coupDeGraceDamageMultiplierPerLevel;
            //case BURN -> config.burnDamagePerSecond;
            default -> 0.0;
        };
    }

    /**
     * Checks if this enchantment can be applied to items of the given category.
     */
    public boolean canApplyTo(ItemCategory category) {
        if (applicableCategories.contains(category)) {
            return true;
        }
        // Allow HELMET and BOOTS to accept enchantments targeting ARMOR
        if ((category == ItemCategory.HELMET || category == ItemCategory.BOOTS || category == ItemCategory.GLOVES) 
                && applicableCategories.contains(ItemCategory.ARMOR)) {
            return true;
        }
        return false;
    }
    
    /**
     * Gets the enchantment by its ID.
     */
    public static EnchantmentType fromId(String id) {
        if (id == null) return null;
        return BY_ID.get(id.toLowerCase());
    }

    /**
     * Gets the enchantment by its display name (case-insensitive).
     */
    public static EnchantmentType findByDisplayName(String displayName) {
        if (displayName == null) return null;
        return BY_DISPLAY_NAME.get(displayName.toLowerCase());
    }

    /**
     * Gets the translation key for the enchantment name.
     * Format: enchantment.[id].name
     */
    public String getNameKey() {
        return "enchantment." + id + ".name";
    }

    /**
     * Gets the translation key for the enchantment description.
     * Format: enchantment.[id].description
     */
    public String getDescriptionKey() {
        return "enchantment." + id + ".description";
    }

    /**
     * Gets a formatted display string with level (e.g., "Sharpness I")
     * Note: This returns a raw string. For UI components, use Message.translation(getNameKey()).
     */
    public String getFormattedName(int level) {
        return displayName + " " + toRoman(level);
    }
    
    /**
     * Gets formatted bonus text for the specific level (default English).
     */
    public String getBonusDescription(int level) {
        return getBonusDescription(level, "en-US", "en-US");
    }

    /**
     * Gets localized bonus text for the specific level.
     */
    public String getBonusDescription(int level, String langCode, String clientLangCode) {
         if (level <= 0) return "";
         double mult = getEffectMultiplier() * level;
         float percentage = (float) (mult * 100);

         return switch (this) {
             case SHARPNESS -> resolve(langCode, clientLangCode, "Melee damage increased by {amount}%", percentage);
             case LIFE_LEECH -> resolve(langCode, clientLangCode, "Heals for {amount}% of damage dealt", percentage);
             case DURABILITY -> resolve(langCode, clientLangCode, "Durability loss reduced by {amount}%", percentage);
             case STURDY -> resolve(langCode, clientLangCode, "Prevents durability loss on repair", null);
             case DEXTERITY -> resolve(langCode, clientLangCode, "Stamina costs reduced by {amount}%", percentage);
             case PROTECTION -> resolve(langCode, clientLangCode, "Physical damage reduced by {amount}%", percentage);
             case EFFICIENCY -> resolve(langCode, clientLangCode, "Mining speed increased by {amount}%", percentage);
             case FORTUNE -> resolve(langCode, clientLangCode, "Extra drop chance increased by {amount}%", percentage);
             case SMELTING -> resolve(langCode, clientLangCode, "Automatically smelts mined blocks", null);
             case STRENGTH -> resolve(langCode, clientLangCode, "Projectile damage increased by {amount}%", percentage);
             case EAGLES_EYE -> resolve(langCode, clientLangCode, "Damage increases with distance up to {amount}%", percentage * 50);
             case LOOTING -> resolve(langCode, clientLangCode, "Drop rates increased by {amount}%", percentage);
             case FEATHER_FALLING -> resolve(langCode, clientLangCode, "Fall damage reduced by {amount}%", percentage);
             case WATERBREATHING -> resolve(langCode, clientLangCode, "Oxygen drain reduced by {amount}%", percentage);
             case BURN -> resolve(langCode, clientLangCode, "Sets target on fire", null);
             case FREEZE -> resolve(langCode, clientLangCode, "Slows target on hit", null);
             case ETERNAL_SHOT -> resolve(langCode, clientLangCode, "Does not consume arrows", null);
             case PICK_PERFECT -> resolve(langCode, clientLangCode, "Drops the block itself when mined", null);
             case THRIFT -> resolve(langCode, clientLangCode, "Restores {amount}% of mana on hit", percentage);
             case ELEMENTAL_HEART -> resolve(langCode, clientLangCode, "Does not consume essence when casting", null); 
             case KNOCKBACK -> resolve(langCode, clientLangCode, "Knocks targets back", null);
             case REFLECTION -> resolve(langCode, clientLangCode, "Reflects {amount}% of damage", percentage);
             case ABSORPTION -> resolve(langCode, clientLangCode, "Heals for {amount}% of blocked damage", percentage);
             case FAST_SWIM -> resolve(langCode, clientLangCode, "Swim speed increased by {amount}%", percentage);
             case NIGHT_VISION -> resolve(langCode, clientLangCode, "Enhances vision in dark environments", null);
             case RANGED_PROTECTION -> resolve(langCode, clientLangCode, "Projectile/magic damage reduced by {amount}%", percentage);
             case FRENZY -> resolve(langCode, clientLangCode, "Ability charge speed increased by {amount}%", percentage);
             case RIPOSTE -> resolve(langCode, clientLangCode, "Counter attack damage increased by {amount}%", percentage);
             case COUP_DE_GRACE -> resolve(langCode, clientLangCode, "Damage to stunned enemies increased by {amount}%", percentage);
             default -> "";
         };
    }

    private String resolve(String langCode, String clientLangCode, String defaultPattern, Float amount) {
        String key = getBonusTranslationKey();
        String template = null;
        try {
            org.herolias.plugin.SimpleEnchanting plugin = org.herolias.plugin.SimpleEnchanting.getInstance();
            if (plugin != null) {
                template = plugin.getLanguageManager().getRawMessage(key, langCode, clientLangCode);
            }
        } catch (Exception ignored) {}
        
        if (template == null || template.equals(key)) template = defaultPattern;
        
        if (amount != null) {
            return template.replace("{amount}", String.valueOf(amount));
        }
        return template;
    }

    /**
     * Gets the translation key for the enchantment bonus.
     * Format: enchantment.[id].bonus
     */
    public String getBonusTranslationKey() {
        return "enchantment." + id + ".bonus";
    }

    /**
     * Gets the translation key for the enchantment walkthrough description.
     * Format: enchantment.[id].walkthrough
     */
    public String getWalkthroughKey() {
        return "enchantment." + id + ".walkthrough";
    }

    /**
     * Gets the localized walkthrough description with dynamic config values.
     * Replaces {amount} with the per-level effect multiplier percentage from config.
     * Falls back to the existing description key if no walkthrough key exists.
     */
    public String getWalkthroughDescription(String langCode, String clientLangCode) {
        String key = getWalkthroughKey();
        String template = null;
        try {
            org.herolias.plugin.SimpleEnchanting plugin = org.herolias.plugin.SimpleEnchanting.getInstance();
            if (plugin != null) {
                template = plugin.getLanguageManager().getRawMessage(key, langCode, clientLangCode);
            }
        } catch (Exception ignored) {}

        // Fallback to existing description if walkthrough key is missing
        if (template == null || template.equals(key)) {
            String descKey = getDescriptionKey();
            try {
                org.herolias.plugin.SimpleEnchanting plugin = org.herolias.plugin.SimpleEnchanting.getInstance();
                if (plugin != null) {
                    template = plugin.getLanguageManager().getRawMessage(descKey, langCode, clientLangCode);
                    if (template.equals(descKey)) {
                        template = getDescription();
                    }
                }
            } catch (Exception ignored) {
                template = getDescription();
            }
        }

        // Replace {amount} with the per-level config value
        double mult = getEffectMultiplier();
        float percentage = (float) (mult * 100);
        if (template.contains("{amount}")) {
            template = template.replace("{amount}", String.valueOf(percentage));
        }

        return template;
    }

    /**
     * Converts an integer to Roman numerals (for enchantment levels)
     */
    public static String toRoman(int number) {
        return switch (number) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> String.valueOf(number);
        };
    }
}
