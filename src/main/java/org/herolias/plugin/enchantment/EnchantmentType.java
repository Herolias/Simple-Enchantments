package org.herolias.plugin.enchantment;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

/**
 * Defines an enchantment in the SimpleEnchanting system.
 * <p>
 * Converted from enum to class to allow dynamic registration by addon mods.
 * All 27 built-in enchantments are preserved as public static final constants
 * for full backward compatibility.
 * <p>
 * Addon mods register new enchantments via the API; Simple Enchantments handles
 * scrolls, config, commands, and UI automatically.
 */
public final class EnchantmentType {

    // ============================== Fields ==============================

    private final String id;
    private final String displayName;
    private final String description;
    private final int maxLevel;
    private final Set<ItemCategory> applicableCategories;
    private final boolean requiresDurability;
    private final boolean isLegendary;
    private final String ownerModId; // null = built-in
    private final double defaultMultiplierPerLevel;
    private final String bonusDescriptionTemplate; // e.g. "Melee damage increased by {amount}%"
    private final String scrollBaseName; // override for special naming, null = auto-generated

    // Addon enchantment configuration (mutable, set by builder after construction)
    private java.util.List<org.herolias.plugin.api.ScrollDefinition> scrollDefinitions = java.util.List.of();
    private String craftingCategory; // e.g. "Enchanting_Melee"

    // ============================== Built-in Enchantments ==============================

    public static final EnchantmentType SHARPNESS = builtin("sharpness", "Sharpness",
            "Increases melee damage", 3, false, false, 0.10,
            "Melee damage increased by {amount}%",
            ItemCategory.MELEE_WEAPON);

    public static final EnchantmentType LIFE_LEECH = builtin("life_leech", "Life Leech",
            "Heals for a portion of damage dealt", 1, false, true, 0.10,
            "Heals for {amount}% of damage dealt",
            ItemCategory.MELEE_WEAPON);

    public static final EnchantmentType DURABILITY = builtin("durability", "Durability",
            "Reduces durability loss", 3, true, false, 0.25,
            "Durability loss reduced by {amount}%",
            ItemCategory.MELEE_WEAPON, ItemCategory.RANGED_WEAPON, ItemCategory.TOOL, ItemCategory.PICKAXE,
            ItemCategory.SHOVEL, ItemCategory.AXE, ItemCategory.ARMOR,
            ItemCategory.STAFF, ItemCategory.STAFF_MANA, ItemCategory.STAFF_ESSENCE);

    public static final EnchantmentType STURDY = builtin("sturdy", "Sturdy",
            "Prevents repair durability penalty", 1, true, true, 0.0,
            "Prevents durability loss on repair",
            ItemCategory.MELEE_WEAPON, ItemCategory.RANGED_WEAPON, ItemCategory.TOOL, ItemCategory.PICKAXE,
            ItemCategory.SHOVEL, ItemCategory.AXE, ItemCategory.ARMOR,
            ItemCategory.STAFF, ItemCategory.STAFF_MANA, ItemCategory.STAFF_ESSENCE);

    public static final EnchantmentType DEXTERITY = builtin("dexterity", "Dexterity",
            "Reduces stamina costs", 3, false, false, 0.20,
            "Stamina costs reduced by {amount}%",
            ItemCategory.MELEE_WEAPON, ItemCategory.SHIELD,
            ItemCategory.STAFF, ItemCategory.STAFF_MANA, ItemCategory.STAFF_ESSENCE);

    public static final EnchantmentType PROTECTION = builtin("protection", "Protection",
            "Increases physical damage resistance", 3, false, false, 0.04,
            "Physical damage reduced by {amount}%",
            ItemCategory.ARMOR);

    public static final EnchantmentType EFFICIENCY = builtin("efficiency", "Efficiency",
            "Increases mining speed", 3, false, false, 0.20,
            "Mining speed increased by {amount}%",
            ItemCategory.PICKAXE, ItemCategory.AXE, ItemCategory.SHOVEL);

    public static final EnchantmentType FORTUNE = builtin("fortune", "Fortune",
            "Chance for extra ore/crystal drops", 3, false, false, 0.25,
            "Extra drop chance increased by {amount}%",
            ItemCategory.PICKAXE);

    public static final EnchantmentType SMELTING = builtin("smelting", "Smelting",
            "Automatically smelts mined blocks", 1, false, true, 0.0,
            "Automatically smelts mined blocks",
            ItemCategory.PICKAXE);

    public static final EnchantmentType STRENGTH = builtin("strength", "Strength",
            "Increases projectile damage and range", 3, false, false, 0.10,
            "Projectile damage increased by {amount}%",
            ItemCategory.RANGED_WEAPON);

    public static final EnchantmentType EAGLES_EYE = builtin("eagles_eye", "Eagle's Eye",
            "Increases damage with distance", 3, false, false, 0.005,
            "Damage increases with distance up to {amount}%",
            ItemCategory.RANGED_WEAPON);

    public static final EnchantmentType LOOTING = builtin("looting", "Looting",
            "Increases enemy drops and rare drop chance", 3, false, false, 0.25,
            "Drop rates increased by {amount}%",
            ItemCategory.MELEE_WEAPON, ItemCategory.RANGED_WEAPON,
            ItemCategory.STAFF, ItemCategory.STAFF_MANA, ItemCategory.STAFF_ESSENCE);

    public static final EnchantmentType FEATHER_FALLING = builtin("feather_falling", "Feather Falling",
            "Reduces fall damage", 3, false, false, 0.20,
            "Fall damage reduced by {amount}%",
            ItemCategory.BOOTS);

    public static final EnchantmentType WATERBREATHING = builtin("waterbreathing", "Waterbreathing",
            "Breathe underwater longer", 3, false, false, 0.20,
            "Oxygen drain reduced by {amount}%",
            ItemCategory.HELMET);

    public static final EnchantmentType BURN = builtin("burn", "Burn",
            "Sets target on fire", 1, false, true, 0.0,
            "Sets target on fire",
            ItemCategory.MELEE_WEAPON, ItemCategory.RANGED_WEAPON);

    public static final EnchantmentType FREEZE = builtin("freeze", "Freeze",
            "Slows targets on hit", 1, false, true, 0.0,
            "Slows target on hit",
            ItemCategory.RANGED_WEAPON, ItemCategory.MELEE_WEAPON);

    public static final EnchantmentType ETERNAL_SHOT = builtin("eternal_shot", "Eternal Shot",
            "Shoot without consuming arrows", 1, false, false, 0.0,
            "Does not consume arrows",
            ItemCategory.RANGED_WEAPON);

    public static final EnchantmentType PICK_PERFECT = builtinCustomScroll("pick_perfect", "Pick Perfect",
            "Drops the block itself when mined", 1, false, true, 0.0,
            "Drops the block itself when mined", "Scroll_Silktouch",
            ItemCategory.PICKAXE, ItemCategory.AXE, ItemCategory.SHOVEL);

    public static final EnchantmentType THRIFT = builtin("thrift", "Thrift",
            "Restores mana on hit", 3, false, false, 0.20,
            "Restores {amount}% of mana on hit",
            ItemCategory.STAFF_MANA);

    public static final EnchantmentType ELEMENTAL_HEART = builtinCustomScroll("elemental_heart", "Elemental Heart",
            "Chance to save essence", 1, false, true, 1.0,
            "Does not consume essence when casting", "Scroll_ElementalHeart",
            ItemCategory.STAFF_ESSENCE);

    public static final EnchantmentType KNOCKBACK = builtin("knockback", "Knockback",
            "Knocks targets back", 3, false, false, 0.6,
            "Knocks targets back",
            ItemCategory.MELEE_WEAPON);

    public static final EnchantmentType REFLECTION = builtin("reflection", "Reflection",
            "Reflects damage when blocking", 3, false, false, 0.10,
            "Reflects {amount}% of damage",
            ItemCategory.SHIELD);

    public static final EnchantmentType ABSORPTION = builtin("absorption", "Absorption",
            "Heals directly from blocked damage", 3, false, false, 0.10,
            "Heals for {amount}% of blocked damage",
            ItemCategory.SHIELD);

    public static final EnchantmentType FAST_SWIM = builtinCustomScroll("fast_swim", "Swift Swim",
            "Increases swimming speed", 3, false, false, 0.25,
            "Swim speed increased by {amount}%", "Scroll_FastSwim",
            ItemCategory.GLOVES);

    public static final EnchantmentType NIGHT_VISION = builtin("night_vision", "Night Vision",
            "Increases visibility in dark environments", 1, false, true, 0.0,
            "Enhances vision in dark environments",
            ItemCategory.HELMET);

    public static final EnchantmentType RANGED_PROTECTION = builtin("ranged_protection", "Ranged Protection",
            "Reduces projectile and magic damage", 3, false, false, 0.04,
            "Projectile/magic damage reduced by {amount}%",
            ItemCategory.ARMOR);

    public static final EnchantmentType FRENZY = builtin("frenzy", "Frenzy",
            "Increases ability charge rate", 3, false, false, 0.15,
            "Ability charge speed increased by {amount}%",
            ItemCategory.MELEE_WEAPON, ItemCategory.RANGED_WEAPON,
            ItemCategory.STAFF, ItemCategory.STAFF_MANA, ItemCategory.STAFF_ESSENCE);

    public static final EnchantmentType RIPOSTE = builtin("riposte", "Riposte",
            "Increases counter attack damage", 3, false, false, 0.10,
            "Counter attack damage increased by {amount}%",
            ItemCategory.MELEE_WEAPON);

    public static final EnchantmentType COUP_DE_GRACE = builtin("coup_de_grace", "Coup de Grâce",
            "Increases damage to stunned enemies", 3, false, false, 0.15,
            "Damage to stunned enemies increased by {amount}%",
            ItemCategory.MELEE_WEAPON);

    // ============================== Static Initialization ==============================

    static {
        // Register built-in conflict pairs
        EnchantmentRegistry registry = EnchantmentRegistry.getInstance();
        registry.addConflict("burn", "freeze");
        registry.addConflict("pick_perfect", "fortune");
        registry.addConflict("pick_perfect", "smelting");
        registry.addConflict("reflection", "absorption");
    }

    // ============================== Constructors ==============================

    /**
     * Internal constructor for built-in enchantments.
     */
    private EnchantmentType(String id, String displayName, String description,
                            int maxLevel, boolean requiresDurability, boolean isLegendary,
                            double defaultMultiplierPerLevel, String bonusDescriptionTemplate,
                            String scrollBaseName, ItemCategory... categories) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.maxLevel = maxLevel;
        this.requiresDurability = requiresDurability;
        this.isLegendary = isLegendary;
        this.applicableCategories = Set.of(categories);
        this.ownerModId = null; // built-in
        this.defaultMultiplierPerLevel = defaultMultiplierPerLevel;
        this.bonusDescriptionTemplate = bonusDescriptionTemplate;
        this.scrollBaseName = scrollBaseName;
    }

    /**
     * Public constructor for addon enchantments.
     * Use EnchantmentBuilder via the API for a fluent interface.
     *
     * @param id Must be namespaced for addons (e.g. "my_mod:lightning")
     */
    public EnchantmentType(String id, String displayName, String description,
                           int maxLevel, boolean requiresDurability, boolean isLegendary,
                           double defaultMultiplierPerLevel, String bonusDescriptionTemplate,
                           String ownerModId, Set<ItemCategory> applicableCategories) {
        if (ownerModId != null && !id.contains(":")) {
            throw new IllegalArgumentException(
                    "Addon enchantment IDs must be namespaced (e.g. 'my_mod:lightning'), got: '" + id + "'");
        }
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.maxLevel = maxLevel;
        this.requiresDurability = requiresDurability;
        this.isLegendary = isLegendary;
        this.applicableCategories = Set.copyOf(applicableCategories);
        this.ownerModId = ownerModId;
        this.defaultMultiplierPerLevel = defaultMultiplierPerLevel;
        this.bonusDescriptionTemplate = bonusDescriptionTemplate;
        this.scrollBaseName = null; // auto-generated for addons
    }

    // ============================== Factory Methods ==============================

    private static EnchantmentType builtin(String id, String displayName, String description,
                                           int maxLevel, boolean requiresDurability, boolean isLegendary,
                                           double defaultMultiplier, String bonusTemplate,
                                           ItemCategory... categories) {
        EnchantmentType type = new EnchantmentType(id, displayName, description, maxLevel,
                requiresDurability, isLegendary, defaultMultiplier, bonusTemplate, null, categories);
        EnchantmentRegistry.getInstance().register(type);
        return type;
    }

    private static EnchantmentType builtinCustomScroll(String id, String displayName, String description,
                                                       int maxLevel, boolean requiresDurability, boolean isLegendary,
                                                       double defaultMultiplier, String bonusTemplate,
                                                       String scrollBaseName, ItemCategory... categories) {
        EnchantmentType type = new EnchantmentType(id, displayName, description, maxLevel,
                requiresDurability, isLegendary, defaultMultiplier, bonusTemplate, scrollBaseName, categories);
        EnchantmentRegistry.getInstance().register(type);
        return type;
    }

    // ============================== Accessors ==============================

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public int getMaxLevel() { return maxLevel; }
    public boolean requiresDurability() { return requiresDurability; }
    public boolean isLegendary() { return isLegendary; }
    public Set<ItemCategory> getApplicableCategories() { return applicableCategories; }
    @Nullable public String getOwnerModId() { return ownerModId; }
    public double getDefaultMultiplierPerLevel() { return defaultMultiplierPerLevel; }
    public String getBonusDescriptionTemplate() { return bonusDescriptionTemplate; }

    /** Returns true if this is a built-in Simple Enchantments enchantment. */
    public boolean isBuiltIn() { return ownerModId == null; }

    /**
     * Backward-compatible replacement for the old enum {@code name()} method.
     * Returns the ID in uppercase (e.g. "SHARPNESS", "LIFE_LEECH").
     */
    public String name() { return id.toUpperCase(); }

    /** Gets the scroll definitions for addon enchantments. Empty list for built-in. */
    public java.util.List<org.herolias.plugin.api.ScrollDefinition> getScrollDefinitions() { return scrollDefinitions; }

    /** Sets scroll definitions. Called by EnchantmentBuilder. */
    public void setScrollDefinitions(java.util.List<org.herolias.plugin.api.ScrollDefinition> definitions) {
        this.scrollDefinitions = definitions != null ? java.util.List.copyOf(definitions) : java.util.List.of();
    }

    /** Gets the crafting category for the Enchanting Table. */
    @Nullable public String getCraftingCategory() { return craftingCategory; }

    /** Sets the crafting category. Called by EnchantmentBuilder. */
    public void setCraftingCategory(String category) { this.craftingCategory = category; }

    // ============================== Backward Compat Static Methods ==============================

    /**
     * Gets the enchantment by its ID. Drop-in replacement for old enum method.
     */
    @Nullable
    public static EnchantmentType fromId(String id) {
        if (id == null) return null;
        return EnchantmentRegistry.getInstance().getById(id);
    }

    /**
     * Gets the enchantment by its display name (case-insensitive).
     */
    @Nullable
    public static EnchantmentType findByDisplayName(String displayName) {
        if (displayName == null) return null;
        return EnchantmentRegistry.getInstance().getByDisplayName(displayName);
    }

    /**
     * Returns all registered enchantments, analogous to the old enum values().
     */
    public static EnchantmentType[] values() {
        return EnchantmentRegistry.getInstance().values();
    }

    // ============================== Conflict Checking ==============================

    /**
     * Checks if this enchantment conflicts with another.
     */
    public boolean conflictsWith(EnchantmentType other) {
        if (this.equals(other)) return true;
        return EnchantmentRegistry.getInstance().areConflicting(this.id, other.id);
    }

    // ============================== Config Integration ==============================

    /**
     * Gets the effect multiplier per level from the active configuration.
     * Falls back to the default if not configured.
     */
    public double getEffectMultiplier() {
        try {
            org.herolias.plugin.config.EnchantingConfig config = 
                org.herolias.plugin.SimpleEnchanting.getInstance().getConfigManager().getConfig();
            return config.enchantmentMultipliers.getOrDefault(this.id, this.defaultMultiplierPerLevel);
        } catch (Exception e) {
            return this.defaultMultiplierPerLevel;
        }
    }

    // ============================== Category Checking ==============================

    /**
     * Checks if this enchantment can be applied to items of the given category.
     */
    public boolean canApplyTo(ItemCategory category) {
        if (applicableCategories.contains(category)) {
            return true;
        }
        // Allow HELMET, BOOTS, GLOVES to accept enchantments targeting ARMOR
        if ((category == ItemCategory.HELMET || category == ItemCategory.BOOTS || category == ItemCategory.GLOVES) 
                && applicableCategories.contains(ItemCategory.ARMOR)) {
            return true;
        }
        return false;
    }

    // ============================== Translation Keys ==============================

    public String getNameKey() { return "enchantment." + id + ".name"; }
    public String getDescriptionKey() { return "enchantment." + id + ".description"; }
    public String getBonusTranslationKey() { return "enchantment." + id + ".bonus"; }
    public String getWalkthroughKey() { return "enchantment." + id + ".walkthrough"; }

    // ============================== Display Formatting ==============================

    /**
     * Gets a formatted display string with level (e.g., "Sharpness I")
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

        // Special handling for Eagle's Eye which scales differently
        float displayAmount = percentage;
        if (this.equals(EAGLES_EYE)) {
            displayAmount = percentage * 50;
        }

        String template = bonusDescriptionTemplate;
        return resolve(langCode, clientLangCode, template, 
                template.contains("{amount}") ? displayAmount : null);
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

    // ============================== Walkthrough ==============================

    /**
     * Gets the localized walkthrough description with dynamic config values.
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

    // ============================== Scroll Naming ==============================

    /**
     * Gets the scroll base name for this enchantment (e.g., "Scroll_Sharpness").
     * Some built-in enchantments have custom overrides (e.g., pick_perfect -> Scroll_Silktouch).
     */
    @Nonnull
    public String getScrollBaseName() {
        if (scrollBaseName != null) {
            return scrollBaseName;
        }
        // Standard behavior: Scroll_PascalCase
        String[] parts = id.split(":");
        String rawId = parts.length > 1 ? parts[1] : parts[0]; // strip namespace for addons
        String[] idParts = rawId.split("_");
        StringBuilder sb = new StringBuilder("Scroll");
        for (String part : idParts) {
            sb.append("_");
            sb.append(Character.toUpperCase(part.charAt(0)));
            sb.append(part.substring(1));
        }
        return sb.toString();
    }

    // ============================== Utility ==============================

    /**
     * Converts an integer to Roman numerals (for enchantment levels).
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

    // ============================== Object Identity ==============================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EnchantmentType that = (EnchantmentType) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "EnchantmentType{" + id + "}";
    }
}
