package org.herolias.plugin.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data class for the plugin configuration.
 * <p>
 * Enchantment multipliers are stored in a unified map keyed by enchantment ID.
 * Legacy per-field config values are auto-migrated on load.
 */
public class EnchantingConfig {
    public double configVersion = 2.0; // Bumped for enchantment multipliers migration
    public int maxEnchantmentsPerItem = 5;
    public boolean showEnchantmentBanner = true;
    public boolean hasAutoDisabledBanner = false;
    public boolean enableEnchantmentGlow = true;
    public boolean allowSameScrollUpgrades = true;

    // ===== Unified enchantment multipliers (keyed by enchantment ID) =====
    // Replaces all individual per-enchantment fields
    // (sharpnessDamageMultiplierPerLevel, etc.)
    public Map<String, Double> enchantmentMultipliers = new LinkedHashMap<>();

    // ===== Legacy per-enchantment fields (kept for auto-migration from old
    // configs) =====
    // These are read once during migration, then written into
    // enchantmentMultipliers.
    // After migration, these fields are ignored. GSON will simply skip them if
    // absent.
    public Double sharpnessDamageMultiplierPerLevel;
    public Double lifeLeechPercentage;
    public Double durabilityReductionPerLevel;
    public Double dexterityStaminaReductionPerLevel;
    public Double protectionDamageReductionPerLevel;
    public Double efficiencyMiningSpeedPerLevel;
    public Double fortuneRollChancePerLevel;
    public Double strengthDamageMultiplierPerLevel;
    public Double strengthRangeMultiplierPerLevel;
    public Double eaglesEyeDistanceBonusPerLevel;
    public Double lootingChanceMultiplierPerLevel;
    public Double lootingQuantityMultiplierPerLevel;
    public Double featherFallingReductionPerLevel;
    public Double waterBreathingReductionPerLevel;
    public Double knockbackStrengthPerLevel;
    public Double reflectionDamagePercentagePerLevel;
    public Double absorptionHealPercentagePerLevel;
    public Double fastSwimSpeedBonusPerLevel;
    public Double rangedProtectionDamageReductionPerLevel;
    public Double frenzyChargeSpeedMultiplierPerLevel;
    public Double riposteDamageMultiplierPerLevel;
    public Double coupDeGraceDamageMultiplierPerLevel;
    public Double thriftRestoreAmountPerLevel;
    public Double elementalHeartSaveChancePerLevel;

    // ===== Effect Duration Settings =====
    public double burnDuration = 3.0; // In seconds
    public double freezeDuration = 5.0; // In seconds
    public double poisonDuration = 4.0; // In seconds

    // ===== Other settings =====
    public boolean disableEnchantmentCrafting = false;
    public boolean returnEnchantmentOnCleanse = false;
    public boolean salvagerYieldsScroll = true;

    public Map<String, Boolean> disabledEnchantments = new LinkedHashMap<>();

    public Map<String, List<ConfigIngredient>> scrollRecipes = new LinkedHashMap<>();

    public List<ConfigIngredient> enchantingTableRecipe;
    public int enchantingTableCraftingTier = 2;

    public Map<String, List<ConfigIngredient>> enchantingTableUpgrades;

    public EnchantingConfig() {
        // Default constructor for GSON
    }

    /**
     * Creates a fresh configuration instance with all default values populated.
     */
    public static EnchantingConfig createDefault() {
        EnchantingConfig config = new EnchantingConfig();
        config.initializeDefaultMultipliers();
        config.initializeDefaultRecipes();
        config.initializeDefaultDisabledEnchantments();
        if (config.notifiedPlayers == null)
            config.notifiedPlayers = new ArrayList<>();
        return config;
    }

    /**
     * Populates enchantmentMultipliers from the registry defaults.
     * Called for fresh installs or when new enchantments are added.
     */
    public void initializeDefaultMultipliers() {
        if (enchantmentMultipliers == null) {
            enchantmentMultipliers = new LinkedHashMap<>();
        }
        for (org.herolias.plugin.enchantment.EnchantmentType type : org.herolias.plugin.enchantment.EnchantmentType
                .values()) {
            enchantmentMultipliers.putIfAbsent(type.getId(), type.getDefaultMultiplierPerLevel());
            // Also register additional multiplier definitions (e.g. burn:duration,
            // looting:quantity)
            for (org.herolias.plugin.api.MultiplierDefinition def : type.getMultiplierDefinitions()) {
                enchantmentMultipliers.putIfAbsent(def.key(), def.defaultValue());
            }
        }
    }

    /**
     * Migrates legacy per-field config values into the unified
     * enchantmentMultipliers map.
     * Called after GSON deserialization to handle configs from v1.x.
     * Only migrates non-null legacy fields (i.e., values that were present in the
     * old config).
     */
    public void migrateFromLegacy() {
        if (enchantmentMultipliers == null) {
            enchantmentMultipliers = new LinkedHashMap<>();
        }

        // Only migrate if we have legacy fields but the map is empty (old config
        // format)
        boolean hasLegacyFields = sharpnessDamageMultiplierPerLevel != null
                || lifeLeechPercentage != null
                || durabilityReductionPerLevel != null;

        if (hasLegacyFields && enchantmentMultipliers.isEmpty()) {
            migrateLegacyField("sharpness", sharpnessDamageMultiplierPerLevel, 0.10);
            migrateLegacyField("life_leech", lifeLeechPercentage, 0.10);
            migrateLegacyField("durability", durabilityReductionPerLevel, 0.25);
            migrateLegacyField("dexterity", dexterityStaminaReductionPerLevel, 0.20);
            migrateLegacyField("protection", protectionDamageReductionPerLevel, 0.04);
            migrateLegacyField("efficiency", efficiencyMiningSpeedPerLevel, 0.20);
            migrateLegacyField("fortune", fortuneRollChancePerLevel, 0.25);
            migrateLegacyField("strength", strengthDamageMultiplierPerLevel, 0.10);
            migrateLegacyField("eagles_eye", eaglesEyeDistanceBonusPerLevel, 0.005);
            migrateLegacyField("looting", lootingChanceMultiplierPerLevel, 0.25);
            migrateLegacyField("feather_falling", featherFallingReductionPerLevel, 0.20);
            migrateLegacyField("waterbreathing", waterBreathingReductionPerLevel, 0.20);
            migrateLegacyField("knockback", knockbackStrengthPerLevel, 0.6);
            migrateLegacyField("reflection", reflectionDamagePercentagePerLevel, 0.10);
            migrateLegacyField("absorption", absorptionHealPercentagePerLevel, 0.10);
            migrateLegacyField("fast_swim", fastSwimSpeedBonusPerLevel, 0.25);
            migrateLegacyField("ranged_protection", rangedProtectionDamageReductionPerLevel, 0.04);
            migrateLegacyField("frenzy", frenzyChargeSpeedMultiplierPerLevel, 0.15);
            migrateLegacyField("riposte", riposteDamageMultiplierPerLevel, 0.10);
            migrateLegacyField("coup_de_grace", coupDeGraceDamageMultiplierPerLevel, 0.15);
            migrateLegacyField("thrift", thriftRestoreAmountPerLevel, 0.20);
            migrateLegacyField("elemental_heart", elementalHeartSaveChancePerLevel, 1.0);

            // Set zero-multiplier enchantments
            enchantmentMultipliers.putIfAbsent("eternal_shot", 0.0);
            enchantmentMultipliers.putIfAbsent("pick_perfect", 0.0);
            enchantmentMultipliers.putIfAbsent("smelting", 0.0);
            enchantmentMultipliers.putIfAbsent("sturdy", 0.0);
            enchantmentMultipliers.putIfAbsent("night_vision", 0.0);

            // Migrate legacy secondary multipliers into unified map
            if (lootingQuantityMultiplierPerLevel != null) {
                enchantmentMultipliers.put("looting:quantity", lootingQuantityMultiplierPerLevel);
            }
            enchantmentMultipliers.putIfAbsent("looting:quantity", 0.25);
            enchantmentMultipliers.putIfAbsent("burn:duration", burnDuration);
            enchantmentMultipliers.putIfAbsent("freeze:duration", freezeDuration);
            enchantmentMultipliers.putIfAbsent("poison:duration", poisonDuration);

            // Null out legacy fields after migration
            clearLegacyFields();

            // Update config version
            this.configVersion = 2.0;
        }

        // Always ensure all registered enchantments have an entry
        initializeDefaultMultipliers();
    }

    private void migrateLegacyField(String enchantmentId, Double legacyValue, double defaultValue) {
        enchantmentMultipliers.put(enchantmentId, legacyValue != null ? legacyValue : defaultValue);
    }

    private void clearLegacyFields() {
        sharpnessDamageMultiplierPerLevel = null;
        lifeLeechPercentage = null;
        durabilityReductionPerLevel = null;
        dexterityStaminaReductionPerLevel = null;
        protectionDamageReductionPerLevel = null;
        efficiencyMiningSpeedPerLevel = null;
        fortuneRollChancePerLevel = null;
        strengthDamageMultiplierPerLevel = null;
        strengthRangeMultiplierPerLevel = null;
        eaglesEyeDistanceBonusPerLevel = null;
        lootingChanceMultiplierPerLevel = null;
        lootingQuantityMultiplierPerLevel = null;
        featherFallingReductionPerLevel = null;
        waterBreathingReductionPerLevel = null;
        knockbackStrengthPerLevel = null;
        reflectionDamagePercentagePerLevel = null;
        absorptionHealPercentagePerLevel = null;
        fastSwimSpeedBonusPerLevel = null;
        rangedProtectionDamageReductionPerLevel = null;
        frenzyChargeSpeedMultiplierPerLevel = null;
        riposteDamageMultiplierPerLevel = null;
        coupDeGraceDamageMultiplierPerLevel = null;
        thriftRestoreAmountPerLevel = null;
        elementalHeartSaveChancePerLevel = null;
    }

    private void initializeDefaultDisabledEnchantments() {
        if (disabledEnchantments == null) {
            disabledEnchantments = new LinkedHashMap<>();
        }
        if (notifiedPlayers == null) {
            notifiedPlayers = new ArrayList<>();
        }
        for (org.herolias.plugin.enchantment.EnchantmentType type : org.herolias.plugin.enchantment.EnchantmentType
                .values()) {
            if (type == org.herolias.plugin.enchantment.EnchantmentType.THRIFT
                    || type == org.herolias.plugin.enchantment.EnchantmentType.NIGHT_VISION) {
                disabledEnchantments.put(type.getId(), true);
            } else {
                disabledEnchantments.put(type.getId(), false);
            }
        }
    }

    public void initializeDefaultRecipes() {
        if (scrollRecipes == null) {
            scrollRecipes = new LinkedHashMap<>();
        }

        addScrollRecipe("Scroll_Burn_I", 4, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Fire_Essence", 20,
                "Ingredient_Crystal_Red", 20, "Plant_Flower_Flax_Orange", 3);
        addScrollRecipe("Scroll_Cleansing", 2, "Ingredient_Fabric_Scrap_Linen", 5, "Ingredient_Void_Essence", 50);
        addScrollRecipe("Scroll_Dexterity_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence", 5,
                "Ingredient_Crystal_Yellow", 5);
        addScrollRecipe("Scroll_Dexterity_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence",
                10, "Ingredient_Crystal_Yellow", 10);
        addScrollRecipe("Scroll_Dexterity_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence",
                15, "Ingredient_Crystal_Yellow", 15);
        addScrollRecipe("Scroll_Durability_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence",
                10, "Ingredient_Bar_Cobalt", 5, "Ingredient_Bar_Thorium", 5);
        addScrollRecipe("Scroll_Durability_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence",
                15, "Ingredient_Bar_Cobalt", 10, "Ingredient_Bar_Thorium", 10);
        addScrollRecipe("Scroll_Durability_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence",
                20, "Ingredient_Bar_Cobalt", 15, "Ingredient_Bar_Thorium", 15);
        addScrollRecipe("Scroll_Eagles_Eye_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Void_Essence",
                10, "Ingredient_Feathers_Light", 5, "Plant_Petals_Storm", 3);
        addScrollRecipe("Scroll_Eagles_Eye_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Void_Essence",
                10, "Ingredient_Feathers_Light", 7, "Plant_Petals_Storm", 5);
        addScrollRecipe("Scroll_Eagles_Eye_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Void_Essence",
                15, "Ingredient_Feathers_Light", 12, "Plant_Petals_Storm", 7);
        addScrollRecipe("Scroll_Efficiency_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Crystal_Red",
                10, "Ingredient_Bar_Gold", 5, "Ingredient_Bar_Cobalt", 5);
        addScrollRecipe("Scroll_Efficiency_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Crystal_Red",
                15, "Ingredient_Bar_Gold", 10, "Ingredient_Bar_Cobalt", 10);
        addScrollRecipe("Scroll_Efficiency_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Crystal_Red",
                20, "Ingredient_Bar_Gold", 15, "Ingredient_Bar_Cobalt", 15);
        addScrollRecipe("Scroll_Eternal_Shot_I", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Void_Essence",
                30, "Weapon_Arrow_Crude", 100, "Ingredient_Crystal_Purple", 15);
        addScrollRecipe("Scroll_Feather_Falling_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5,
                "Ingredient_Life_Essence", 10, "Ingredient_Feathers_Light", 10, "Plant_Petals_Storm", 5);
        addScrollRecipe("Scroll_Feather_Falling_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5,
                "Ingredient_Life_Essence", 15, "Ingredient_Feathers_Light", 20, "Plant_Petals_Storm", 5);
        addScrollRecipe("Scroll_Feather_Falling_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5,
                "Ingredient_Life_Essence", 20, "Ingredient_Feathers_Light", 30, "Plant_Petals_Storm", 5);
        addScrollRecipe("Scroll_Fortune_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Bar_Gold", 5,
                "Ingredient_Crystal_Yellow", 10, "Rock_Gem_Emerald", 1);
        addScrollRecipe("Scroll_Fortune_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Bar_Gold", 10,
                "Ingredient_Crystal_Yellow", 15, "Rock_Gem_Emerald", 2);
        addScrollRecipe("Scroll_Fortune_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Bar_Gold", 20,
                "Ingredient_Crystal_Yellow", 15, "Rock_Gem_Emerald", 3);
        addScrollRecipe("Scroll_Freeze_I", 4, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Ice_Essence", 20,
                "Ingredient_Crystal_Cyan", 20, "Plant_Flower_Bushy_White", 3);
        addScrollRecipe("Scroll_Knockback_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Crop_Stamina1", 3,
                "Ingredient_Crystal_Yellow", 5, "Ingredient_Bar_Adamantite", 5);
        addScrollRecipe("Scroll_Knockback_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Crop_Stamina1", 5,
                "Ingredient_Crystal_Yellow", 10, "Ingredient_Bar_Adamantite", 10);
        addScrollRecipe("Scroll_Knockback_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Crop_Stamina1", 7,
                "Ingredient_Crystal_Yellow", 15, "Ingredient_Bar_Adamantite", 15);
        addScrollRecipe("Scroll_Life_Leech_I", 4, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Crop_Health1", 3,
                "Ingredient_Crystal_Red", 20, "Ingredient_Life_Essence", 25);
        addScrollRecipe("Scroll_Looting_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Bar_Gold", 5,
                "Ingredient_Crystal_Green", 10, "Rock_Gem_Emerald", 1);
        addScrollRecipe("Scroll_Looting_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Bar_Gold", 10,
                "Ingredient_Crystal_Green", 15, "Rock_Gem_Emerald", 2);
        addScrollRecipe("Scroll_Looting_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Bar_Gold", 20,
                "Ingredient_Crystal_Green", 15, "Rock_Gem_Emerald", 3);
        addScrollRecipe("Scroll_Thrift_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Crop_Mana2", 5,
                "Ingredient_Crystal_Blue", 10, "Plant_Petals_Azure", 10);
        addScrollRecipe("Scroll_Thrift_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Crop_Mana2", 10,
                "Ingredient_Crystal_Blue", 15, "Plant_Petals_Azure", 15);
        addScrollRecipe("Scroll_Thrift_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Crop_Mana2", 15,
                "Ingredient_Crystal_Blue", 20, "Plant_Petals_Azure", 20);
        addScrollRecipe("Scroll_Protection_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence",
                15, "Ingredient_Crystal_Cyan", 10, "Plant_Petals_Azure", 10);
        addScrollRecipe("Scroll_Protection_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence",
                30, "Ingredient_Crystal_Cyan", 15, "Plant_Petals_Azure", 10);
        addScrollRecipe("Scroll_Protection_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence",
                40, "Ingredient_Crystal_Cyan", 25, "Plant_Petals_Azure", 10);
        addScrollRecipe("Scroll_Reflection_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Cactus_Flower", 5,
                "Ingredient_Crystal_Red", 10, "Plant_Petals_Storm", 5);
        addScrollRecipe("Scroll_Reflection_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Cactus_Flower", 10,
                "Ingredient_Crystal_Red", 15, "Plant_Petals_Storm", 10);
        addScrollRecipe("Scroll_Reflection_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Cactus_Flower", 15,
                "Ingredient_Crystal_Red", 20, "Plant_Petals_Storm", 15);
        addScrollRecipe("Scroll_Sharpness_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Void_Essence", 5,
                "Ingredient_Crystal_Blue", 5, "Ingredient_Bar_Silver", 5);
        addScrollRecipe("Scroll_Sharpness_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Void_Essence",
                10, "Ingredient_Crystal_Blue", 10, "Ingredient_Bar_Silver", 10);
        addScrollRecipe("Scroll_Sharpness_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Void_Essence",
                15, "Ingredient_Crystal_Blue", 12, "Ingredient_Bar_Silver", 20);
        addScrollRecipe("Scroll_Silktouch_I", 4, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Petals_Azure", 10,
                "Plant_Flower_Tall_Red", 5, "Rock_Gem_Sapphire", 1);
        addScrollRecipe("Scroll_Smelting_I", 4, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Fire_Essence", 30,
                "Wood_Fire_Trunk", 30);
        addScrollRecipe("Scroll_ElementalHeart_I", 4, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Voidheart",
                1, "Ingredient_Ice_Essence", 15, "Ingredient_Fire_Essence", 15);
        addScrollRecipe("Scroll_Strength_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Void_Essence", 10,
                "Plant_Crop_Stamina2", 1, "Ingredient_Life_Essence", 15);
        addScrollRecipe("Scroll_Strength_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Void_Essence",
                20, "Plant_Crop_Stamina2", 1, "Ingredient_Life_Essence", 25);
        addScrollRecipe("Scroll_Strength_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Void_Essence",
                25, "Plant_Crop_Stamina2", 2, "Ingredient_Life_Essence", 30);
        addScrollRecipe("Scroll_Sturdy_I", 4, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Voidheart", 1,
                "Ingredient_Life_Essence_Concentrated", 1);
        addScrollRecipe("Scroll_Waterbreathing_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5,
                "Ingredient_Water_Essence", 1, "Deco_Coral_Shell", 5, "Ingredient_Crystal_Blue", 15);
        addScrollRecipe("Scroll_Waterbreathing_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5,
                "Ingredient_Water_Essence", 2, "Deco_Coral_Shell", 7, "Ingredient_Crystal_Blue", 20);
        addScrollRecipe("Scroll_Waterbreathing_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5,
                "Ingredient_Water_Essence", 3, "Deco_Coral_Shell", 10, "Ingredient_Crystal_Blue", 30);

        addScrollRecipe("Scroll_Absorption_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence",
                10, "Ingredient_Crystal_Green", 15, "Plant_Crop_Health3", 3);
        addScrollRecipe("Scroll_Absorption_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence",
                20, "Ingredient_Crystal_Green", 20, "Plant_Crop_Health3", 5);
        addScrollRecipe("Scroll_Absorption_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence",
                30, "Ingredient_Crystal_Green", 25, "Plant_Crop_Health3", 7);

        addScrollRecipe("Scroll_FastSwim_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Water_Essence", 1,
                "Ingredient_Crystal_Blue", 10, "Plant_Petals_Blue", 10);
        addScrollRecipe("Scroll_FastSwim_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Water_Essence",
                2, "Ingredient_Crystal_Blue", 15, "Plant_Petals_Blue", 20);
        addScrollRecipe("Scroll_FastSwim_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Water_Essence",
                3, "Ingredient_Crystal_Blue", 20, "Plant_Petals_Blue", 30);

        addScrollRecipe("Scroll_Night_Vision_I", 4, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Fire_Essence",
                30, "Ingredient_Crystal_Yellow", 30, "Plant_Crop_Mushroom_Glowing_Orange", 20);
        addScrollRecipe("Scroll_Ranged_Protection_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5,
                "Ingredient_Ice_Essence", 15, "Ingredient_Fire_Essence", 15, "Plant_Petals_Azure", 10);
        addScrollRecipe("Scroll_Ranged_Protection_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5,
                "Ingredient_Ice_Essence", 20, "Ingredient_Fire_Essence", 20, "Plant_Petals_Azure", 20);
        addScrollRecipe("Scroll_Ranged_Protection_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5,
                "Ingredient_Ice_Essence", 25, "Ingredient_Fire_Essence", 25, "Plant_Petals_Azure", 30);

        addScrollRecipe("Scroll_Frenzy_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Petals_Blood", 15,
                "Ingredient_Crystal_Cyan", 10, "Plant_Crop_Stamina1", 5);
        addScrollRecipe("Scroll_Frenzy_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Petals_Blood", 20,
                "Ingredient_Crystal_Cyan", 20, "Plant_Crop_Stamina1", 7);
        addScrollRecipe("Scroll_Frenzy_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Petals_Blood", 25,
                "Ingredient_Crystal_Cyan", 30, "Plant_Crop_Stamina1", 10);

        addScrollRecipe("Scroll_Riposte_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Cactus_Flower", 5,
                "Ingredient_Crystal_Yellow", 15, "Ingredient_Bar_Iron", 10);
        addScrollRecipe("Scroll_Riposte_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Cactus_Flower", 10,
                "Ingredient_Crystal_Yellow", 20, "Ingredient_Bar_Iron", 20);
        addScrollRecipe("Scroll_Riposte_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Cactus_Flower", 15,
                "Ingredient_Crystal_Yellow", 25, "Ingredient_Bar_Iron", 30);

        addScrollRecipe("Scroll_Coup_De_Grace_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Crop_Stamina2", 3,
                "Ingredient_Crystal_Yellow", 15, "Ingredient_Void_Essence", 10);
        addScrollRecipe("Scroll_Coup_De_Grace_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Crop_Stamina2",
                5, "Ingredient_Crystal_Yellow", 20, "Ingredient_Void_Essence", 15);
        addScrollRecipe("Scroll_Coup_De_Grace_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Crop_Stamina2",
                7, "Ingredient_Crystal_Yellow", 25, "Ingredient_Void_Essence", 20);

        addScrollRecipe("Scroll_Poison_I", 4, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Sac_Venom", 25,
                "Ingredient_Crystal_Green", 30, "Plant_Crop_Mushroom_Cap_Green", 20);

        addScrollRecipe("Scroll_Environmental_Protection_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5,
                "Ingredient_Crystal_Blue", 10, "Ingredient_Fire_Essence", 5, "Plant_Crop_Mushroom_Boomshroom_Small", 5);
        addScrollRecipe("Scroll_Environmental_Protection_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5,
                "Ingredient_Crystal_Blue", 15, "Ingredient_Fire_Essence", 10, "Plant_Crop_Mushroom_Boomshroom_Small",
                10);
        addScrollRecipe("Scroll_Environmental_Protection_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5,
                "Ingredient_Crystal_Blue", 25, "Ingredient_Fire_Essence", 20, "Plant_Crop_Mushroom_Boomshroom_Small",
                15);

        if (enchantingTableRecipe == null) {
            enchantingTableRecipe = new ArrayList<>();
            addTableRecipe("Ingredient_Bar_Gold", 5);
            addTableRecipe("Ingredient_Bar_Copper", 30);
            addTableRecipe("Ingredient_Void_Essence", 15);
            addTableRecipe("Wood_Azure_Trunk", 10);
        }

        if (enchantingTableUpgrades == null) {
            enchantingTableUpgrades = new LinkedHashMap<>();
            addTableUpgrade("Upgrade_1", "Ingredient_Bar_Gold", 15, "Ingredient_Life_Essence", 30,
                    "Ingredient_Fire_Essence", 10, "Ingredient_Ice_Essence", 10);
            addTableUpgrade("Upgrade_2", "Ingredient_Bar_Adamantite", 10, "Ingredient_Life_Essence", 35,
                    "Rock_Gem_Sapphire", 1, "Rock_Gem_Emerald", 1);
            addTableUpgrade("Upgrade_3", "Rock_Gem_Ruby", 1, "Ingredient_Voidheart", 1, "Ingredient_Life_Essence", 40,
                    "Ingredient_Void_Essence", 20);
        }
    }

    private void addScrollRecipe(String name, int defaultTier, Object... ingredients) {
        if (scrollRecipes.containsKey(name))
            return;
        List<ConfigIngredient> list = new ArrayList<>();
        for (int i = 0; i < ingredients.length; i += 2) {
            String item = (String) ingredients[i];
            int amount = (Integer) ingredients[i + 1];
            list.add(new ConfigIngredient(item, amount));
        }
        list.add(new ConfigIngredient(defaultTier));
        scrollRecipes.put(name, list);
    }

    private void addTableRecipe(String item, int amount) {
        enchantingTableRecipe.add(new ConfigIngredient(item, amount));
    }

    private void addTableUpgrade(String name, Object... ingredients) {
        if (enchantingTableUpgrades.containsKey(name))
            return;
        List<ConfigIngredient> list = new ArrayList<>();
        for (int i = 0; i < ingredients.length; i += 2) {
            String item = (String) ingredients[i];
            int amount = (Integer) ingredients[i + 1];
            list.add(new ConfigIngredient(item, amount));
        }
        enchantingTableUpgrades.put(name, list);
    }

    public static class ConfigIngredient {
        public String item;
        public Integer amount;
        public Integer UnlocksAtTier;

        public ConfigIngredient() {
        }

        public ConfigIngredient(String item, int amount) {
            this.item = item;
            this.amount = amount;
            this.UnlocksAtTier = null;
        }

        public ConfigIngredient(int tier) {
            this.UnlocksAtTier = tier;
            this.amount = null;
            this.item = null;
        }
    }

    // Track players who have seen the "Tooltips are here" welcome message
    public List<String> notifiedPlayers = new ArrayList<>();

    public boolean hasSkippedTooltipAnnouncement = false;

    public boolean showWelcomeMessage = true;
}
