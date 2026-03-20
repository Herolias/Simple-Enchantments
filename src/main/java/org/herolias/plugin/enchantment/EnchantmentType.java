package org.herolias.plugin.enchantment;

import org.herolias.plugin.api.MultiplierDefinition;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

/**
 * Defines an enchantment in the SimpleEnchanting system.
 * <p>
 * Converted from enum to class to allow dynamic registration by addon mods.
 * All 31 built-in enchantments are preserved as public static final constants
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
    private final String ownerModName; // overrides ownerModId for display, null = built-in or unset
    private final double defaultMultiplierPerLevel;
    private final String bonusDescriptionTemplate; // e.g. "Melee damage increased by {amount}%"
    private final String scrollBaseName; // override for special naming, null = auto-generated

    // Addon enchantment configuration (mutable, set by builder after construction)
    private java.util.List<org.herolias.plugin.api.ScrollDefinition> scrollDefinitions = java.util.List.of();
    private String craftingCategory; // e.g. "Enchanting_Melee"
    private java.util.function.IntToDoubleFunction scaleFunction; // null = linear (level * multiplier)
    private String walkthroughText; // custom walkthrough text from addon mods, null = use lang key / bonus desc
    private java.util.List<MultiplierDefinition> multiplierDefinitions = java.util.List.of();

    // ============================== Built-in Enchantments
    // ==============================

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
            "Sets target on fire", 1, false, true, 5.0,
            "Sets target on fire",
            ItemCategory.MELEE_WEAPON, ItemCategory.RANGED_WEAPON);

    public static final EnchantmentType FREEZE = builtin("freeze", "Freeze",
            "Slows targets on hit", 1, false, true, 0.5,
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

    public static final EnchantmentType RIPOSTE = builtinCollab("riposte", "Riposte",
            "Increases counter attack damage", 3, false, false, 0.10,
            "Counter attack damage increased by {amount}%", "Perfect Parries",
            ItemCategory.MELEE_WEAPON);

    public static final EnchantmentType COUP_DE_GRACE = builtinCollab("coup_de_grace", "Coup de Grâce",
            "Increases damage to stunned enemies", 3, false, false, 0.15,
            "Damage to stunned enemies increased by {amount}%", "Perfect Parries",
            ItemCategory.MELEE_WEAPON);

    public static final EnchantmentType POISON = builtin("poison", "Poison",
            "Poisons targets on hit", 1, false, true, 3.0,
            "Poisons targets on hit",
            ItemCategory.MELEE_WEAPON, ItemCategory.RANGED_WEAPON);

    public static final EnchantmentType ENVIRONMENTAL_PROTECTION = builtin("environmental_protection",
            "Env. Protection",
            "Reduces environmental damage and alters status effects", 3, false, false, 0.04,
            "Environmental damage reduced by {amount}%",
            ItemCategory.ARMOR);

    // ============================== Static Initialization
    // ==============================

    static {
        // Register built-in conflict pairs
        EnchantmentRegistry registry = EnchantmentRegistry.getInstance();
        registry.addConflict("burn", "freeze");
        registry.addConflict("burn", "poison");
        registry.addConflict("pick_perfect", "fortune");
        registry.addConflict("pick_perfect", "smelting");
        registry.addConflict("reflection", "absorption");

        // Register MultiplierDefinitions for all built-in enchantments
        SHARPNESS.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("sharpness", 0.10, "config.multiplier.sharpness")));
        LIFE_LEECH.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("life_leech", 0.10, "config.multiplier.life_leech")));
        DURABILITY.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("durability", 0.25, "config.multiplier.durability")));
        DEXTERITY.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("dexterity", 0.20, "config.multiplier.dexterity")));
        PROTECTION.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("protection", 0.04, "config.multiplier.protection")));
        EFFICIENCY.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("efficiency", 0.20, "config.multiplier.efficiency")));
        FORTUNE.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("fortune", 0.25, "config.multiplier.fortune")));
        STRENGTH.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("strength", 0.10, "config.multiplier.strength")));
        EAGLES_EYE.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("eagles_eye", 0.005, "config.multiplier.eagles_eye")));
        LOOTING.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("looting", 0.25, "config.multiplier.looting"),
                new MultiplierDefinition("looting:quantity", 0.25, "config.multiplier.looting:quantity")));
        FEATHER_FALLING.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("feather_falling", 0.20, "config.multiplier.feather_falling")));
        WATERBREATHING.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("waterbreathing", 0.20, "config.multiplier.waterbreathing")));
        BURN.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("burn", 5.0, "config.multiplier.burn"),
                new MultiplierDefinition("burn:duration", 3.0, "config.multiplier.burn:duration")));
        FREEZE.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("freeze", 0.5, "config.multiplier.freeze"),
                new MultiplierDefinition("freeze:duration", 5.0, "config.multiplier.freeze:duration")));
        KNOCKBACK.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("knockback", 0.6, "config.multiplier.knockback")));
        REFLECTION.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("reflection", 0.10, "config.multiplier.reflection")));
        ABSORPTION.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("absorption", 0.10, "config.multiplier.absorption")));
        FAST_SWIM.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("fast_swim", 0.25, "config.multiplier.fast_swim")));
        RANGED_PROTECTION.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("ranged_protection", 0.04, "config.multiplier.ranged_protection")));
        FRENZY.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("frenzy", 0.15, "config.multiplier.frenzy")));
        RIPOSTE.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("riposte", 0.10, "config.multiplier.riposte")));
        COUP_DE_GRACE.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("coup_de_grace", 0.15, "config.multiplier.coup_de_grace")));
        THRIFT.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("thrift", 0.20, "config.multiplier.thrift")));
        ELEMENTAL_HEART.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("elemental_heart", 1.0, "config.multiplier.elemental_heart")));
        POISON.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("poison", 3.0, "config.multiplier.poison"),
                new MultiplierDefinition("poison:duration", 4.0, "config.multiplier.poison:duration")));
        ENVIRONMENTAL_PROTECTION.setMultiplierDefinitions(java.util.List.of(
                new MultiplierDefinition("environmental_protection", 0.04,
                        "config.multiplier.environmental_protection")));
    }

    // ============================== Constructors ==============================

    /**
     * Internal constructor for built-in enchantments.
     */
    private EnchantmentType(String id, String displayName, String description,
            int maxLevel, boolean requiresDurability, boolean isLegendary,
            double defaultMultiplierPerLevel, String bonusDescriptionTemplate,
            String scrollBaseName, String ownerModName, ItemCategory... categories) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.maxLevel = maxLevel;
        this.requiresDurability = requiresDurability;
        this.isLegendary = isLegendary;
        this.applicableCategories = Set.of(categories);
        this.ownerModId = null; // built-in
        this.ownerModName = ownerModName; // built-in or collab
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
            String ownerModId, String ownerModName, Set<ItemCategory> applicableCategories) {
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
        this.ownerModName = ownerModName;
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
                requiresDurability, isLegendary, defaultMultiplier, bonusTemplate, null, null, categories);
        EnchantmentRegistry.getInstance().register(type);
        return type;
    }

    private static EnchantmentType builtinCustomScroll(String id, String displayName, String description,
            int maxLevel, boolean requiresDurability, boolean isLegendary,
            double defaultMultiplier, String bonusTemplate,
            String scrollBaseName, ItemCategory... categories) {
        EnchantmentType type = new EnchantmentType(id, displayName, description, maxLevel,
                requiresDurability, isLegendary, defaultMultiplier, bonusTemplate, scrollBaseName, null, categories);
        EnchantmentRegistry.getInstance().register(type);
        return type;
    }

    private static EnchantmentType builtinCollab(String id, String displayName, String description,
            int maxLevel, boolean requiresDurability, boolean isLegendary,
            double defaultMultiplier, String bonusTemplate,
            String collabModName, ItemCategory... categories) {
        EnchantmentType type = new EnchantmentType(id, displayName, description, maxLevel,
                requiresDurability, isLegendary, defaultMultiplier, bonusTemplate, null, collabModName, categories);
        EnchantmentRegistry.getInstance().register(type);
        return type;
    }

    // ============================== Accessors ==============================

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

    public boolean requiresDurability() {
        return requiresDurability;
    }

    public boolean isLegendary() {
        return isLegendary;
    }

    public Set<ItemCategory> getApplicableCategories() {
        return applicableCategories;
    }

    @Nullable
    public String getOwnerModId() {
        return ownerModId;
    }

    @Nullable
    public String getOwnerModName() {
        return ownerModName;
    }

    public double getDefaultMultiplierPerLevel() {
        return defaultMultiplierPerLevel;
    }

    public String getBonusDescriptionTemplate() {
        return bonusDescriptionTemplate;
    }

    /** Returns true if this is a built-in Simple Enchantments enchantment. */
    public boolean isBuiltIn() {
        return ownerModId == null;
    }

    /**
     * Backward-compatible replacement for the old enum {@code name()} method.
     * Returns the ID in uppercase (e.g. "SHARPNESS", "LIFE_LEECH").
     */
    public String name() {
        return id.toUpperCase();
    }

    /**
     * Gets the scroll definitions for addon enchantments. Empty list for built-in.
     */
    public java.util.List<org.herolias.plugin.api.ScrollDefinition> getScrollDefinitions() {
        return scrollDefinitions;
    }

    /** Sets scroll definitions. Called by EnchantmentBuilder. */
    public void setScrollDefinitions(java.util.List<org.herolias.plugin.api.ScrollDefinition> definitions) {
        this.scrollDefinitions = definitions != null ? java.util.List.copyOf(definitions) : java.util.List.of();
    }

    /** Gets the crafting category for the Enchanting Table. */
    @Nullable
    public String getCraftingCategory() {
        return craftingCategory;
    }

    /** Sets the crafting category. Called by EnchantmentBuilder. */
    public void setCraftingCategory(String category) {
        this.craftingCategory = category;
    }

    /** Gets the custom scale function, or null if linear scaling. */
    @Nullable
    public java.util.function.IntToDoubleFunction getScaleFunction() {
        return scaleFunction;
    }

    /** Sets the custom scale function. Called by EnchantmentBuilder. */
    public void setScaleFunction(java.util.function.IntToDoubleFunction scaleFunction) {
        this.scaleFunction = scaleFunction;
    }

    /** Gets the custom walkthrough text, or null if using default fallback. */
    @Nullable
    public String getWalkthroughText() {
        return walkthroughText;
    }

    /** Sets custom walkthrough text. Called by EnchantmentBuilder. */
    public void setWalkthroughText(String walkthroughText) {
        this.walkthroughText = walkthroughText;
    }

    /**
     * Gets the multiplier definitions for this enchantment (primary + additional).
     */
    @Nonnull
    public java.util.List<MultiplierDefinition> getMultiplierDefinitions() {
        return multiplierDefinitions;
    }

    /** Sets multiplier definitions. Called by EnchantmentBuilder or static init. */
    public void setMultiplierDefinitions(java.util.List<MultiplierDefinition> definitions) {
        this.multiplierDefinitions = definitions != null ? java.util.List.copyOf(definitions) : java.util.List.of();
    }

    /**
     * Gets the value of a specific multiplier from the active configuration.
     * Falls back to the default value from the MultiplierDefinition if not
     * configured.
     *
     * @param key the multiplier key (e.g. "burn:duration")
     * @return the configured value, or the definition's default, or 0.0 if not
     *         found
     */
    public double getMultiplierValue(String key) {
        try {
            org.herolias.plugin.config.EnchantingConfig config = org.herolias.plugin.SimpleEnchanting.getInstance()
                    .getConfigManager().getConfig();
            if (config.enchantmentMultipliers.containsKey(key)) {
                return config.enchantmentMultipliers.get(key);
            }
        } catch (Exception e) {
            // Plugin not initialized yet
        }
        // Fall back to definition default
        for (MultiplierDefinition def : multiplierDefinitions) {
            if (def.key().equals(key)) {
                return def.defaultValue();
            }
        }
        return 0.0;
    }

    /**
     * Computes the total scaled multiplier for a given level.
     * <p>
     * If a custom scale function was set via the builder, it is used.
     * Otherwise falls back to linear scaling:
     * {@code level * getEffectMultiplier()}.
     * <p>
     * Addon mods should call this method when computing effect values to
     * respect the server admin's configured multiplier and the chosen scale curve.
     *
     * @param level the enchantment level (1-based)
     * @return the total multiplier for this level
     */
    public double getScaledMultiplier(int level) {
        if (scaleFunction != null) {
            return scaleFunction.applyAsDouble(level);
        }
        return level * getEffectMultiplier();
    }

    // ============================== Backward Compat Static Methods
    // ==============================

    /**
     * Gets the enchantment by its ID. Drop-in replacement for old enum method.
     */
    @Nullable
    public static EnchantmentType fromId(String id) {
        if (id == null)
            return null;
        return EnchantmentRegistry.getInstance().getById(id);
    }

    /**
     * Gets the enchantment by its display name (case-insensitive).
     */
    @Nullable
    public static EnchantmentType findByDisplayName(String displayName) {
        if (displayName == null)
            return null;
        return EnchantmentRegistry.getInstance().getByDisplayName(displayName);
    }

    /**
     * Returns all registered enchantments, analogous to the old enum values().
     */
    public static EnchantmentType[] values() {
        return EnchantmentRegistry.getInstance().values();
    }

    // ============================== Conflict Checking
    // ==============================

    /**
     * Checks if this enchantment conflicts with another.
     */
    public boolean conflictsWith(EnchantmentType other) {
        if (this.equals(other))
            return true;
        return EnchantmentRegistry.getInstance().areConflicting(this.id, other.id);
    }

    // ============================== Config Integration
    // ==============================

    /**
     * Gets the effect multiplier per level from the active configuration.
     * Falls back to the default if not configured.
     */
    public double getEffectMultiplier() {
        try {
            org.herolias.plugin.config.EnchantingConfig config = org.herolias.plugin.SimpleEnchanting.getInstance()
                    .getConfigManager().getConfig();
            return config.enchantmentMultipliers.getOrDefault(this.id, this.defaultMultiplierPerLevel);
        } catch (Exception e) {
            return this.defaultMultiplierPerLevel;
        }
    }

    // ============================== Category Checking
    // ==============================

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

    // ============================== Translation Keys
    // ==============================

    public String getNameKey() {
        return formatTranslationKey("name");
    }

    public String getDescriptionKey() {
        return formatTranslationKey("description");
    }

    public String getBonusTranslationKey() {
        return formatTranslationKey("bonus");
    }

    public String getWalkthroughKey() {
        return formatTranslationKey("walkthrough");
    }

    private String formatTranslationKey(String suffix) {
        if (id.contains(":")) {
            String[] parts = id.split(":", 2);
            return parts[0] + ":enchantment." + parts[1] + "." + suffix;
        }
        return "enchantment." + id + "." + suffix;
    }

    // ============================== Display Formatting
    // ==============================

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
        if (level <= 0)
            return "";
        double mult = getScaledMultiplier(level);
        float percentage = (float) (mult * 100);

        // Special handling for Eagle's Eye which scales differently
        float displayAmount = percentage;
        if (this.equals(EAGLES_EYE)) {
            displayAmount = percentage * 50;
        }
        // For freeze, show the slow percentage instead of the raw multiplier
        if (this.equals(FREEZE)) {
            displayAmount = (float) ((1.0 - getEffectMultiplier()) * 100);
        }
        // For burn and poison, show the raw DPS value, not percentage
        if (this.equals(BURN) || this.equals(POISON)) {
            displayAmount = (float) getEffectMultiplier();
        }

        String template = bonusDescriptionTemplate;
        return resolve(langCode, clientLangCode, template, displayAmount);
    }

    private String resolve(String langCode, String clientLangCode, String defaultPattern, Float amount) {
        String key = getBonusTranslationKey();
        String template = null;
        try {
            org.herolias.plugin.SimpleEnchanting plugin = org.herolias.plugin.SimpleEnchanting.getInstance();
            if (plugin != null) {
                template = plugin.getLanguageManager().getRawMessage(key, langCode, clientLangCode);
            }
        } catch (Exception ignored) {
        }

        if (template == null || template.equals(key))
            template = defaultPattern;

        if (amount != null) {
            template = template.replace("{amount}", String.valueOf(amount));
        }

        // Replace {duration} with the duration multiplier value
        if (template.contains("{duration}")) {
            double duration = getMultiplierValue(this.id + ":duration");
            template = template.replace("{duration}", String.valueOf((float) duration));
        }

        return template;
    }

    // ============================== Walkthrough ==============================

    /**
     * Gets the localized walkthrough description with dynamic config values.
     */
    public String getWalkthroughDescription(String langCode, String clientLangCode) {
        String template = null;

        // Priority 1: Custom walkthrough text set by addon mod via .walkthrough()
        if (walkthroughText != null) {
            template = walkthroughText;
        }

        // Priority 2: Localized walkthrough key from lang files
        if (template == null) {
            String key = getWalkthroughKey();
            try {
                org.herolias.plugin.SimpleEnchanting plugin = org.herolias.plugin.SimpleEnchanting.getInstance();
                if (plugin != null) {
                    String resolved = plugin.getLanguageManager().getRawMessage(key, langCode, clientLangCode);
                    if (resolved != null && !resolved.equals(key)) {
                        template = resolved;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // Priority 3: Localized description key, then hardcoded description
        if (template == null) {
            String descKey = getDescriptionKey();
            try {
                org.herolias.plugin.SimpleEnchanting plugin = org.herolias.plugin.SimpleEnchanting.getInstance();
                if (plugin != null) {
                    String resolved = plugin.getLanguageManager().getRawMessage(descKey, langCode, clientLangCode);
                    template = (resolved != null && !resolved.equals(descKey)) ? resolved : getDescription();
                }
            } catch (Exception ignored) {
                template = getDescription();
            }
        }

        // Replace {amount} with the per-level config value
        double mult = getScaledMultiplier(1);
        float percentage = (float) (mult * 100);
        if (template.contains("{amount}")) {
            // For freeze, show the slow percentage (1 - speedMultiplier) * 100
            if (this.equals(FREEZE)) {
                float slowPercent = (float) ((1.0 - getEffectMultiplier()) * 100);
                template = template.replace("{amount}", String.valueOf(slowPercent));
            } else if (this.equals(BURN) || this.equals(POISON)) {
                // For burn and poison, show the raw DPS value, not percentage
                float dps = (float) getEffectMultiplier();
                template = template.replace("{amount}", String.valueOf(dps));
            } else {
                template = template.replace("{amount}", String.valueOf(percentage));
            }
        }

        // Replace {duration} with the duration multiplier value
        if (template.contains("{duration}")) {
            double duration = getMultiplierValue(this.id + ":duration");
            template = template.replace("{duration}", String.valueOf((float) duration));
        }

        return template;
    }

    // ============================== Scroll Naming ==============================

    /**
     * Gets the scroll base name for this enchantment (e.g., "Scroll_Sharpness").
     * Some built-in enchantments have custom overrides (e.g., pick_perfect ->
     * Scroll_Silktouch).
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
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
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
