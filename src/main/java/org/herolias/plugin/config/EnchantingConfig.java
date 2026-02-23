package org.herolias.plugin.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data class for the plugin configuration.
 */
public class EnchantingConfig {
    public double configVersion = 1.4; // Versioning for migration
    public int maxEnchantmentsPerItem = 5;
    public boolean showEnchantmentBanner = true; // Default to true so people see it without the lib
    public boolean hasAutoDisabledBanner = false; // Tracks if we've already auto-disabled it for the user
    public boolean enableEnchantmentGlow = true; // Renamed from showEnchantmentGlow to force enable on update
    
    // Enchantment specific settings
    public double sharpnessDamageMultiplierPerLevel = 0.10;
    public double lifeLeechPercentage = 0.10;
    public double durabilityReductionPerLevel = 0.25;
    public double dexterityStaminaReductionPerLevel = 0.20;
    public double protectionDamageReductionPerLevel = 0.04;
    public double efficiencyMiningSpeedPerLevel = 0.20;
    // public double efficiencySwingSpeedMultiplier = 0.10;
    public double fortuneRollChancePerLevel = 0.25;
    public double strengthDamageMultiplierPerLevel = 0.10;
    public double strengthRangeMultiplierPerLevel = 0.15;
    
    public boolean disableEnchantmentCrafting = false;
    public double eaglesEyeDistanceBonusPerLevel = 0.005;
    public double lootingChanceMultiplierPerLevel = 0.25;
    public double lootingQuantityMultiplierPerLevel = 0.25;
    public double featherFallingReductionPerLevel = 0.20;
    public double waterBreathingReductionPerLevel = 0.20;
    public double knockbackStrengthPerLevel = 0.6;
    public double reflectionDamagePercentagePerLevel = 0.10;
    public double absorptionHealPercentagePerLevel = 0.10;
    public double fastSwimSpeedBonusPerLevel = 0.25;
    public double rangedProtectionDamageReductionPerLevel = 0.04;
    public double frenzyChargeSpeedMultiplierPerLevel = 0.15;
    public double riposteDamageMultiplierPerLevel = 0.10;
    public double coupDeGraceDamageMultiplierPerLevel = 0.15;
    //public double burnDamagePerSecond = 3.0;

    // Staff Enchantments
    public double thriftRestoreAmountPerLevel = 0.20; // Percentage of spent mana refunded per level (0.10 = 10%)
    public double elementalHeartSaveChancePerLevel = 1.0; // Chance to save essence (1.0 = 100% at level 1)
    
    // Cleansing Scroll settings
    public boolean returnEnchantmentOnCleanse = false; // If true, gives back the scroll of the removed enchantment
    public boolean salvagerYieldsScroll = true; // If true, successfully salvaged items yield their highest rarity scroll
    
    public Map<String, Boolean> disabledEnchantments = new LinkedHashMap<>();
    
    public Map<String, List<ConfigIngredient>> scrollRecipes = new LinkedHashMap<>();
    
    public List<ConfigIngredient> enchantingTableRecipe;
    public int enchantingTableCraftingTier = 2; // Default tier for crafting the table
    
    public Map<String, List<ConfigIngredient>> enchantingTableUpgrades;
    
    public EnchantingConfig() {
        // Default constructor for GSON
    }

    /**
     * Creates a fresh configuration instance with all default values populated.
     */
    public static EnchantingConfig createDefault() {
        EnchantingConfig config = new EnchantingConfig();
        config.initializeDefaultRecipes();
        config.initializeDefaultDisabledEnchantments();
        if (config.notifiedPlayers == null) config.notifiedPlayers = new ArrayList<>();
        return config;
    }

    private void initializeDefaultDisabledEnchantments() {
        if (disabledEnchantments == null) {
            disabledEnchantments = new LinkedHashMap<>();
        }
        if (notifiedPlayers == null) {
            notifiedPlayers = new ArrayList<>();
        }
        for (org.herolias.plugin.enchantment.EnchantmentType type : org.herolias.plugin.enchantment.EnchantmentType.values()) {
            if (type == org.herolias.plugin.enchantment.EnchantmentType.THRIFT || type == org.herolias.plugin.enchantment.EnchantmentType.NIGHT_VISION) {
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

        addScrollRecipe("Scroll_Burn_I", 4, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Fire_Essence", 20, "Ingredient_Crystal_Red", 20, "Plant_Flower_Flax_Orange", 3);
        addScrollRecipe("Scroll_Cleansing", 2, "Ingredient_Fabric_Scrap_Linen", 5, "Ingredient_Void_Essence", 50);
        addScrollRecipe("Scroll_Dexterity_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence", 5, "Ingredient_Crystal_Yellow", 5);
        addScrollRecipe("Scroll_Dexterity_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence", 10, "Ingredient_Crystal_Yellow", 10);
        addScrollRecipe("Scroll_Dexterity_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence", 15, "Ingredient_Crystal_Yellow", 15);
        addScrollRecipe("Scroll_Durability_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence", 10, "Ingredient_Bar_Cobalt", 5, "Ingredient_Bar_Thorium", 5);
        addScrollRecipe("Scroll_Durability_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence", 15, "Ingredient_Bar_Cobalt", 10, "Ingredient_Bar_Thorium", 10);
        addScrollRecipe("Scroll_Durability_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence", 20, "Ingredient_Bar_Cobalt", 15, "Ingredient_Bar_Thorium", 15);
        addScrollRecipe("Scroll_Eagles_Eye_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Void_Essence", 10, "Ingredient_Feathers_Light", 5, "Plant_Petals_Storm", 3);
        addScrollRecipe("Scroll_Eagles_Eye_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Void_Essence", 10, "Ingredient_Feathers_Light", 7, "Plant_Petals_Storm", 5);
        addScrollRecipe("Scroll_Eagles_Eye_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Void_Essence", 15, "Ingredient_Feathers_Light", 12, "Plant_Petals_Storm",7);
        addScrollRecipe("Scroll_Efficiency_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Crystal_Red", 10, "Ingredient_Bar_Gold", 5, "Ingredient_Bar_Cobalt", 5);
        addScrollRecipe("Scroll_Efficiency_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Crystal_Red", 15, "Ingredient_Bar_Gold", 10, "Ingredient_Bar_Cobalt", 10);
        addScrollRecipe("Scroll_Efficiency_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Crystal_Red", 20, "Ingredient_Bar_Gold", 15, "Ingredient_Bar_Cobalt", 15);
        addScrollRecipe("Scroll_Eternal_Shot_I", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Void_Essence", 30, "Weapon_Arrow_Crude", 100, "Ingredient_Crystal_Purple", 15);
        addScrollRecipe("Scroll_Feather_Falling_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence", 10, "Ingredient_Feathers_Light", 10, "Plant_Petals_Storm", 5);
        addScrollRecipe("Scroll_Feather_Falling_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence", 15, "Ingredient_Feathers_Light", 20, "Plant_Petals_Storm", 5);
        addScrollRecipe("Scroll_Feather_Falling_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence", 20, "Ingredient_Feathers_Light", 30, "Plant_Petals_Storm", 5);
        addScrollRecipe("Scroll_Fortune_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Bar_Gold", 5, "Ingredient_Crystal_Yellow", 10, "Rock_Gem_Emerald", 1);
        addScrollRecipe("Scroll_Fortune_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Bar_Gold", 10, "Ingredient_Crystal_Yellow", 15, "Rock_Gem_Emerald", 2);
        addScrollRecipe("Scroll_Fortune_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Bar_Gold", 20, "Ingredient_Crystal_Yellow", 15, "Rock_Gem_Emerald", 3);
        addScrollRecipe("Scroll_Freeze_I", 4, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Ice_Essence", 20, "Ingredient_Crystal_Cyan", 20, "Plant_Flower_Bushy_White", 3);
        addScrollRecipe("Scroll_Knockback_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Crop_Stamina1", 3, "Ingredient_Crystal_Yellow", 5, "Ingredient_Bar_Adamantite", 5);
        addScrollRecipe("Scroll_Knockback_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Crop_Stamina1", 5, "Ingredient_Crystal_Yellow", 10, "Ingredient_Bar_Adamantite", 10);
        addScrollRecipe("Scroll_Knockback_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Crop_Stamina1", 7, "Ingredient_Crystal_Yellow", 15, "Ingredient_Bar_Adamantite", 15);
        addScrollRecipe("Scroll_Life_Leech_I", 4, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Crop_Health1", 3, "Ingredient_Crystal_Red", 20, "Ingredient_Life_Essence", 25);
        addScrollRecipe("Scroll_Looting_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Bar_Gold", 5, "Ingredient_Crystal_Green", 10, "Rock_Gem_Emerald", 1);
        addScrollRecipe("Scroll_Looting_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Bar_Gold", 10, "Ingredient_Crystal_Green", 15, "Rock_Gem_Emerald", 2);
        addScrollRecipe("Scroll_Looting_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Bar_Gold", 20, "Ingredient_Crystal_Green", 15, "Rock_Gem_Emerald", 3);
        addScrollRecipe("Scroll_Thrift_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Crop_Mana2", 5, "Ingredient_Crystal_Blue", 10, "Plant_Petals_Azure", 10);
        addScrollRecipe("Scroll_Thrift_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Crop_Mana2", 10, "Ingredient_Crystal_Blue", 15, "Plant_Petals_Azure", 15);
        addScrollRecipe("Scroll_Thrift_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Crop_Mana2", 15, "Ingredient_Crystal_Blue", 20, "Plant_Petals_Azure", 20);
        addScrollRecipe("Scroll_Protection_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence", 15, "Ingredient_Crystal_Cyan", 10, "Plant_Petals_Azure", 10);
        addScrollRecipe("Scroll_Protection_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence", 30, "Ingredient_Crystal_Cyan", 15, "Plant_Petals_Azure", 10);
        addScrollRecipe("Scroll_Protection_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence", 40, "Ingredient_Crystal_Cyan", 25, "Plant_Petals_Azure", 10);
        addScrollRecipe("Scroll_Reflection_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Cactus_Flower", 5, "Ingredient_Crystal_Red", 10, "Plant_Petals_Storm", 5);
        addScrollRecipe("Scroll_Reflection_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Cactus_Flower", 10, "Ingredient_Crystal_Red", 15, "Plant_Petals_Storm", 10);
        addScrollRecipe("Scroll_Reflection_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Cactus_Flower", 15, "Ingredient_Crystal_Red", 20, "Plant_Petals_Storm", 15);
        addScrollRecipe("Scroll_Sharpness_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Void_Essence", 5, "Ingredient_Crystal_Blue", 5, "Ingredient_Bar_Silver", 5);
        addScrollRecipe("Scroll_Sharpness_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Void_Essence", 10, "Ingredient_Crystal_Blue", 10, "Ingredient_Bar_Silver", 10);
        addScrollRecipe("Scroll_Sharpness_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Void_Essence", 15, "Ingredient_Crystal_Blue", 12, "Ingredient_Bar_Silver", 20);
        addScrollRecipe("Scroll_Silktouch_I", 4, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Petals_Azure", 10, "Plant_Flower_Tall_Red", 5, "Rock_Gem_Sapphire", 1);
        addScrollRecipe("Scroll_Smelting_I", 4, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Fire_Essence", 30, "Wood_Fire_Trunk", 30);
        addScrollRecipe("Scroll_ElementalHeart_I", 4, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Voidheart", 1, "Ingredient_Ice_Essence", 15, "Ingredient_Fire_Essence", 15);
        addScrollRecipe("Scroll_Strength_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Void_Essence", 10, "Plant_Crop_Stamina2", 1, "Ingredient_Life_Essence", 15);
        addScrollRecipe("Scroll_Strength_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Void_Essence", 20, "Plant_Crop_Stamina2", 1, "Ingredient_Life_Essence", 25);
        addScrollRecipe("Scroll_Strength_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Void_Essence", 25, "Plant_Crop_Stamina2", 2, "Ingredient_Life_Essence", 30);
        addScrollRecipe("Scroll_Sturdy_I", 4, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Voidheart", 1, "Ingredient_Life_Essence_Concentrated", 1);
        addScrollRecipe("Scroll_Waterbreathing_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Water_Essence", 1, "Deco_Coral_Shell", 5, "Ingredient_Crystal_Blue", 15);
        addScrollRecipe("Scroll_Waterbreathing_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Water_Essence", 2, "Deco_Coral_Shell", 7, "Ingredient_Crystal_Blue", 20);
        addScrollRecipe("Scroll_Waterbreathing_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Water_Essence", 3, "Deco_Coral_Shell", 10, "Ingredient_Crystal_Blue", 30);
        
        addScrollRecipe("Scroll_Absorption_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence", 10, "Ingredient_Crystal_Green", 15, "Plant_Crop_Health3", 3);
        addScrollRecipe("Scroll_Absorption_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence", 20, "Ingredient_Crystal_Green", 20, "Plant_Crop_Health3", 5);
        addScrollRecipe("Scroll_Absorption_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Life_Essence", 30, "Ingredient_Crystal_Green", 25, "Plant_Crop_Health3", 7);

        addScrollRecipe("Scroll_FastSwim_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Water_Essence", 1, "Ingredient_Crystal_Blue", 10, "Plant_Petals_Blue", 10);
        addScrollRecipe("Scroll_FastSwim_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Water_Essence", 2, "Ingredient_Crystal_Blue", 15, "Plant_Petals_Blue", 20);
        addScrollRecipe("Scroll_FastSwim_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Water_Essence", 3, "Ingredient_Crystal_Blue", 20, "Plant_Petals_Blue", 30);

        addScrollRecipe("Scroll_Night_Vision_I", 4, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Fire_Essence", 30, "Ingredient_Crystal_Yellow", 30, "Plant_Crop_Mushroom_Glowing_Orange", 20);
        addScrollRecipe("Scroll_Ranged_Protection_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5,  "Ingredient_Ice_Essence", 15, "Ingredient_Fire_Essence", 15, "Plant_Petals_Azure", 10);
        addScrollRecipe("Scroll_Ranged_Protection_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Ice_Essence", 20, "Ingredient_Fire_Essence", 20, "Plant_Petals_Azure", 20);
        addScrollRecipe("Scroll_Ranged_Protection_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Ingredient_Ice_Essence", 25, "Ingredient_Fire_Essence", 25, "Plant_Petals_Azure", 30);
        
        addScrollRecipe("Scroll_Frenzy_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Petals_Blood", 15, "Ingredient_Crystal_Cyan", 10, "Plant_Crop_Stamina1", 5);
        addScrollRecipe("Scroll_Frenzy_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Petals_Blood", 20, "Ingredient_Crystal_Cyan", 20, "Plant_Crop_Stamina1", 7);
        addScrollRecipe("Scroll_Frenzy_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Petals_Blood", 25, "Ingredient_Crystal_Cyan", 30, "Plant_Crop_Stamina1", 10);

        addScrollRecipe("Scroll_Riposte_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Cactus_Flower", 5, "Ingredient_Crystal_Yellow", 15, "Ingredient_Bar_Iron", 10);
        addScrollRecipe("Scroll_Riposte_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Cactus_Flower", 10, "Ingredient_Crystal_Yellow", 20, "Ingredient_Bar_Iron", 20);
        addScrollRecipe("Scroll_Riposte_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Cactus_Flower", 15, "Ingredient_Crystal_Yellow", 25, "Ingredient_Bar_Iron", 30);

        addScrollRecipe("Scroll_Coup_De_Grace_I", 1, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Crop_Stamina2", 3, "Ingredient_Crystal_Yellow", 15, "Ingredient_Void_Essence", 10);
        addScrollRecipe("Scroll_Coup_De_Grace_II", 2, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Crop_Stamina2", 5, "Ingredient_Crystal_Yellow", 20, "Ingredient_Void_Essence", 15);
        addScrollRecipe("Scroll_Coup_De_Grace_III", 3, "Ingredient_Fabric_Scrap_Cindercloth", 5, "Plant_Crop_Stamina2", 7, "Ingredient_Crystal_Yellow", 25, "Ingredient_Void_Essence", 20);

        if (enchantingTableRecipe == null) {
            enchantingTableRecipe = new ArrayList<>();
            addTableRecipe("Ingredient_Bar_Gold", 5);
            addTableRecipe("Ingredient_Bar_Copper", 30);
            addTableRecipe("Ingredient_Void_Essence", 15);
            addTableRecipe("Wood_Azure_Trunk", 10);
        }

        if (enchantingTableUpgrades == null) {
            enchantingTableUpgrades = new LinkedHashMap<>();
            
            // Upgrade 1 (Tier 1 -> 2)
            addTableUpgrade("Upgrade_1", "Ingredient_Bar_Gold", 15, "Ingredient_Life_Essence", 30, "Ingredient_Fire_Essence", 10, "Ingredient_Ice_Essence", 10);

            // Upgrade 2 (Tier 2 -> 3)
            addTableUpgrade("Upgrade_2", "Ingredient_Bar_Adamantite", 10, "Ingredient_Life_Essence", 35, "Rock_Gem_Sapphire", 1, "Rock_Gem_Emerald", 1);

            // Upgrade 3 (Tier 3 -> 4)
            addTableUpgrade("Upgrade_3", "Rock_Gem_Ruby", 1, "Ingredient_Voidheart", 1, "Ingredient_Life_Essence", 40, "Ingredient_Void_Essence", 20);
        }
        
        // All tiers are now applied in addScrollRecipe calls above
    }
    
    private void addScrollRecipe(String name, int defaultTier, Object... ingredients) {
        if (scrollRecipes.containsKey(name)) return;
        List<ConfigIngredient> list = new ArrayList<>();
        for (int i = 0; i < ingredients.length; i += 2) {
            String item = (String) ingredients[i];
            int amount = (Integer) ingredients[i + 1];
            list.add(new ConfigIngredient(item, amount));
        }
        // Add default tier
        list.add(new ConfigIngredient(defaultTier));
        scrollRecipes.put(name, list);
    }
    
    private void addTableRecipe(String item, int amount) {
        enchantingTableRecipe.add(new ConfigIngredient(item, amount));
    }

    private void addTableUpgrade(String name, Object... ingredients) {
        if (enchantingTableUpgrades.containsKey(name)) return;
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
        public Integer UnlocksAtTier; // Null if it's a regular ingredient
        
        public ConfigIngredient() {}
        
        public ConfigIngredient(String item, int amount) {
            this.item = item;
            this.amount = amount;
            this.UnlocksAtTier = null;
        }
        
        // Constructor for Tier definition
        public ConfigIngredient(int tier) {
            this.UnlocksAtTier = tier;
            this.amount = null; // Explicitly null
            this.item = null;   // Explicitly null
        }
    }
    


    // Track players who have seen the "Tooltips are here" welcome message
    // Moved to bottom to keep config readable
    // Track players who have seen the "Tooltips are here" welcome message
    // Moved to bottom to keep config readable
    public List<String> notifiedPlayers = new ArrayList<>();
    
    // If true, the welcome message will not be shown (used for fresh installs)
    public boolean skipWelcomeMessage = false;
}
