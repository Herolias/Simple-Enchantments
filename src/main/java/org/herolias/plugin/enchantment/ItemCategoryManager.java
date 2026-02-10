package org.herolias.plugin.enchantment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;
import org.herolias.plugin.SimpleEnchanting;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages item categories and their mappings.
 * Allows dynamic registration of categories and configuration-based overriding.
 */
public class ItemCategoryManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final ItemCategoryManager INSTANCE = new ItemCategoryManager();

    private final Map<String, ItemCategory> categoriesById = new ConcurrentHashMap<>();
    private final Map<String, ItemCategory> itemCategoryMap = new ConcurrentHashMap<>();
    private final Map<String, ItemCategory> apiItemCategoryMap = new ConcurrentHashMap<>();

    private ItemCategoryManager() {
        // Register default categories
        registerCategory(ItemCategory.MELEE_WEAPON);
        registerCategory(ItemCategory.RANGED_WEAPON);
        registerCategory(ItemCategory.TOOL);
        registerCategory(ItemCategory.PICKAXE);
        registerCategory(ItemCategory.SHOVEL);
        registerCategory(ItemCategory.AXE);
        registerCategory(ItemCategory.SHIELD);
        registerCategory(ItemCategory.BOOTS);
        registerCategory(ItemCategory.HELMET);
        registerCategory(ItemCategory.ARMOR);
        registerCategory(ItemCategory.ARMOR);
        registerCategory(ItemCategory.GLOVES);
        registerCategory(ItemCategory.STAFF);
        registerCategory(ItemCategory.STAFF_MANA);
        registerCategory(ItemCategory.STAFF_ESSENCE);
        registerCategory(ItemCategory.UNKNOWN);

        // Initialize Default Family Mappings
        registerFamilyMapping("sword", ItemCategory.MELEE_WEAPON);
        registerFamilyMapping("mace", ItemCategory.MELEE_WEAPON);
        registerFamilyMapping("dagger", ItemCategory.MELEE_WEAPON);
        registerFamilyMapping("spear", ItemCategory.MELEE_WEAPON);
        registerFamilyMapping("scythe", ItemCategory.MELEE_WEAPON);
        registerFamilyMapping("longsword", ItemCategory.MELEE_WEAPON);
        registerFamilyMapping("battleaxe", ItemCategory.MELEE_WEAPON);
        registerFamilyMapping("club", ItemCategory.MELEE_WEAPON);
        
        registerFamilyMapping("bow", ItemCategory.RANGED_WEAPON);
        registerFamilyMapping("crossbow", ItemCategory.RANGED_WEAPON);
        
        registerFamilyMapping("pickaxe", ItemCategory.PICKAXE);
        registerFamilyMapping("hatchet", ItemCategory.AXE);
        registerFamilyMapping("shovel", ItemCategory.SHOVEL);
        registerFamilyMapping("hoe", ItemCategory.TOOL);
        
        registerFamilyMapping("shield", ItemCategory.SHIELD);
        registerFamilyMapping("helmet", ItemCategory.HELMET);
        registerFamilyMapping("chestplate", ItemCategory.ARMOR);
        registerFamilyMapping("gloves", ItemCategory.GLOVES);
        registerFamilyMapping("boots", ItemCategory.BOOTS);
    }

    public static ItemCategoryManager getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a new item category.
     */
    public void registerCategory(ItemCategory category) {
        categoriesById.put(category.getId(), category);
    }

    /**
     * Gets a category by its ID.
     */
    public ItemCategory getCategoryById(String id) {
        return categoriesById.get(id);
    }

    /**
     * Registers a specific item string ID to a category.
     */
    public void registerItem(String itemId, ItemCategory category) {
        itemCategoryMap.put(itemId.toLowerCase(), category);
    }

    /**
     * Registers an item to a category via API.
     * Updates the cache immediately to ensure priority.
     */
    public void registerApiItem(String itemId, ItemCategory category) {
        if (itemId == null || category == null) return;
        String lower = itemId.toLowerCase();
        apiItemCategoryMap.put(lower, category);
        categoryCache.put(lower, category); // Force update cache to reflect API priority
    }

    /**
     * Categorizes an item ID.
     * 1. Check strict mapping.
     * 2. Fallback to heuristic/regex matching.
     */
    /**
     * Categorizes an item based on its ItemStack metadata (Tags) or ID.
     * 1. Check strict mapping (Config).
     * 2. Check Item Tags (Family).
     * 3. Fallback to heuristics.
     */
    public ItemCategory categorizeItem(com.hypixel.hytale.server.core.inventory.ItemStack itemStack) {
        if (itemStack == null || itemStack.isEmpty()) {
            return ItemCategory.UNKNOWN;
        }

    // Use the pre-calculated cache via the String overload
        return categorizeItem(itemStack.getItemId());
    }

    /**
     * Categorizes an item using the Item asset directly.
     * Useful during asset loading or when the Item object is available.
     * This will populate the cache if not present.
     */
    public ItemCategory categorizeItem(String itemId, com.hypixel.hytale.server.core.asset.type.item.config.Item item) {
        if (itemId == null) return ItemCategory.UNKNOWN;
        String lowerItemId = itemId.toLowerCase();
        
        // 1. Check Cache first
        if (categoryCache.containsKey(lowerItemId)) {
            return categoryCache.get(lowerItemId);
        }
        
        // 2. Check Blacklist
        if (isBlacklisted(lowerItemId)) {
            categoryCache.put(lowerItemId, ItemCategory.UNKNOWN);
            return ItemCategory.UNKNOWN;
        }

        // 3. Check keys registered via API (Highest Priority)
        if (apiItemCategoryMap.containsKey(lowerItemId)) {
            ItemCategory cat = apiItemCategoryMap.get(lowerItemId);
            categoryCache.put(lowerItemId, cat);
            return cat;
        }

        // 4. Check strict mapping (Config)
        if (itemCategoryMap.containsKey(lowerItemId)) {
            ItemCategory cat = itemCategoryMap.get(lowerItemId);
            categoryCache.put(lowerItemId, cat);
            return cat;
        }
        
        ItemCategory category = ItemCategory.UNKNOWN;
        
        // 4. Check metadata families
        if (item != null && item.getData() != null) {
            java.util.Map<String, String[]> tags = item.getData().getRawTags();
            if (tags != null) {
                String[] families = tags.get("Family");
                if (families != null) {
                    for (String family : families) {
                        ItemCategory cat = getCategoryFromFamily(family);
                        if (cat != ItemCategory.UNKNOWN) {
                            category = cat;
                            break;
                        }
                    }
                }
            }
        }
        
        // 5. Fallback to Structural Components
        if (category == ItemCategory.UNKNOWN && item != null) {
            category = determineCategoryFromComponents(lowerItemId, item);
        }
        
        // Cache result (even if unknown, to save re-calculation?)
        // Caching unknown is useful to avoid re-checking.
        categoryCache.put(lowerItemId, category);
        
        return category;
    }

    public ItemCategory categorizeItem(String itemTypeId) {
        if (itemTypeId == null) {
            return ItemCategory.UNKNOWN;
        }

        String lowerItemId = itemTypeId.toLowerCase();

        // Check our comprehensive cache (includes Config, Families, Components, AND Blacklisted items as UNKNOWN)
        if (categoryCache.containsKey(lowerItemId)) {
            return categoryCache.get(lowerItemId);
        }

        return ItemCategory.UNKNOWN;
    }

    private ItemCategory getCategoryFromFamily(String family) {
        if (family == null) return ItemCategory.UNKNOWN;
        return familyCategoryMap.getOrDefault(family.toLowerCase(), ItemCategory.UNKNOWN);
    }



    // --- Configuration Loading ---

    public void loadConfiguration() {
        File configFile = new File(SimpleEnchanting.getInstance().getConfigManager().getConfigDirectory(), "simple_enchanting_custom_items.json");
        
        // Use SmartConfigManager
        CategoryConfig defaults = createDefault();
        CategoryConfig config = org.herolias.plugin.config.SmartConfigManager.loadAndMerge(configFile, CategoryConfig.class, defaults);
        
        if (config != null) {
            // Register grouped item mappings
            if (config.customItems != null) {
                for (Map.Entry<String, Map<String, String>> modEntry : config.customItems.entrySet()) {
                    String modName = modEntry.getKey(); // Purely visual/organizational
                    if (modEntry.getValue() != null) {
                        for (Map.Entry<String, String> itemEntry : modEntry.getValue().entrySet()) {
                            registerItemMapping(itemEntry.getKey(), itemEntry.getValue());
                        }
                    }
                }
            }
            
            // Register family mappings
            if (config.familyMappings != null) {
                for (Map.Entry<String, Map<String, String>> modEntry : config.familyMappings.entrySet()) {
                    String modName = modEntry.getKey(); // Purely visual
                    if (modEntry.getValue() != null) {
                        for (Map.Entry<String, String> entry : modEntry.getValue().entrySet()) {
                            String family = entry.getKey();
                            String catId = entry.getValue();
                            ItemCategory cat = getCategoryById(catId);
                            if (cat != null) {
                                registerFamilyMapping(family, cat);
                            } else {
                                LOGGER.atWarning().log("Unknown category '" + catId + "' for family '" + family + "'");
                            }
                        }
                    }
                }
            }
            
            // Load Blacklist
            if (config.blacklist != null) {
                for (String id : config.blacklist) {
                    blacklistedItems.add(id.toLowerCase());
                }
                LOGGER.atInfo().log("Loaded " + config.blacklist.size() + " blacklisted items.");
            }
        }
    }

    // Cache for Item ID -> Category, populated on asset load
    private final Map<String, ItemCategory> categoryCache = new ConcurrentHashMap<>();
    
    // Dynamic family mappings
    private final Map<String, ItemCategory> familyCategoryMap = new ConcurrentHashMap<>();

    public void registerFamilyMapping(String family, ItemCategory category) {
        familyCategoryMap.put(family.toLowerCase(), category);
    }
    
    /**
     * Event handler for LoadedAssetsEvent<String, Item, ...>
     * Populates the internal cache of item ID -> Category.
     */
    public void onItemsLoaded(com.hypixel.hytale.assetstore.event.LoadedAssetsEvent<String, com.hypixel.hytale.server.core.asset.type.item.config.Item, com.hypixel.hytale.assetstore.map.DefaultAssetMap<String, com.hypixel.hytale.server.core.asset.type.item.config.Item>> event) {
        LOGGER.atInfo().log("Populating ItemCategoryManager category cache...");
        int count = 0;
        
        for (Map.Entry<String, com.hypixel.hytale.server.core.asset.type.item.config.Item> entry : event.getLoadedAssets().entrySet()) {
            String itemId = entry.getKey();
            com.hypixel.hytale.server.core.asset.type.item.config.Item item = entry.getValue();
            
            // Use the new public method which handles caching etc.
            if (categorizeItem(itemId, item) != ItemCategory.UNKNOWN) {
                count++;
            }
        }
        LOGGER.atInfo().log("Cached categories for " + count + " items.");
    }

    private ItemCategory determineCategoryFromComponents(String itemId, com.hypixel.hytale.server.core.asset.type.item.config.Item item) {
        // ID-based heuristics
        if (itemId.contains("shield")) return ItemCategory.SHIELD;

        ItemCategory cat = checkArmor(item);
        if (cat != ItemCategory.UNKNOWN) return cat;

        cat = checkStaff(itemId); 
        if (cat != ItemCategory.UNKNOWN) return cat;

        cat = checkTool(item);
        if (cat != ItemCategory.UNKNOWN) return cat;

        cat = checkWeapon(item);
        if (cat != ItemCategory.UNKNOWN) return cat;
        
        return checkGenericTags(item);
    }

    private ItemCategory checkArmor(com.hypixel.hytale.server.core.asset.type.item.config.Item item) {
        if (item.getArmor() == null) return ItemCategory.UNKNOWN;
        
        Object slotObj = item.getArmor().getArmorSlot(); 
        if (slotObj != null) {
            String s = slotObj.toString().toLowerCase();
            if (s.contains("head")) return ItemCategory.HELMET;
            if (s.contains("chest")) return ItemCategory.ARMOR; 
            if (s.contains("hands")) return ItemCategory.GLOVES; 
            if (s.contains("legs")) return ItemCategory.BOOTS;
        }
        return ItemCategory.ARMOR;
    }

    private ItemCategory checkStaff(String itemId) {
        if (itemId.contains("staff") || itemId.contains("wand") || itemId.contains("broomstick")) {
             // Heuristic: "Ice", "Earth", "Nature", "Void", "Purple" usually imply Essence consumption
             // "Bone", "Wood", "Flame", "Red" usually imply Mana consumption
             if (itemId.contains("ice") || itemId.contains("flame")) {
                 return ItemCategory.STAFF_ESSENCE;
             }
             return ItemCategory.STAFF_MANA;
        }
        return ItemCategory.UNKNOWN;
    }

    private ItemCategory checkTool(com.hypixel.hytale.server.core.asset.type.item.config.Item item) {
        if (item.getTool() == null) return ItemCategory.UNKNOWN;
        
        if (item.getTool().getSpecs() != null) {
            double pickaxePower = 0.0;
            double axePower = 0.0;
            double shovelPower = 0.0;

            for (var spec : item.getTool().getSpecs()) {
                String gatherType = spec.getGatherType(); 
                double power = spec.getPower();

                if (power <= 0.05) continue;

                if (gatherType != null) {
                        if (gatherType.equals("Rocks") || gatherType.startsWith("Ore")) pickaxePower += power;
                        if (gatherType.equals("Woods")) axePower += power;
                        if (gatherType.equals("Soils")) shovelPower += power;
                }
            }
            
            if (pickaxePower > axePower && pickaxePower > shovelPower) return ItemCategory.PICKAXE;
            if (axePower > pickaxePower && axePower > shovelPower) return ItemCategory.AXE;
            if (shovelPower > pickaxePower && shovelPower > axePower) return ItemCategory.SHOVEL;

            if (pickaxePower > 0) return ItemCategory.PICKAXE;
            if (axePower > 0) return ItemCategory.AXE;
            if (shovelPower > 0) return ItemCategory.SHOVEL;
        }
        return ItemCategory.TOOL;
    }

    private ItemCategory checkWeapon(com.hypixel.hytale.server.core.asset.type.item.config.Item item) {
        if (item.getWeapon() == null) return ItemCategory.UNKNOWN;

        if (item.getCategories() != null) {
            for (String c : item.getCategories()) {
                if (c.toLowerCase().contains("ranged")) return ItemCategory.RANGED_WEAPON;
            }
        }
        return ItemCategory.MELEE_WEAPON;
    }
    
    private ItemCategory checkGenericTags(com.hypixel.hytale.server.core.asset.type.item.config.Item item) {
        if (item.getData() != null && item.getData().getRawTags() != null) {
             String[] types = item.getData().getRawTags().get("Type");
             if (types != null) {
                 for (String type : types) {
                     if ("Tool".equalsIgnoreCase(type)) return ItemCategory.TOOL;
                     if ("Armor".equalsIgnoreCase(type)) return ItemCategory.ARMOR; 
                     if ("Weapon".equalsIgnoreCase(type)) return ItemCategory.MELEE_WEAPON; 
                 }
             }
        }
        return ItemCategory.UNKNOWN;
    }

    private void registerItemMapping(String itemId, String catId) {
        ItemCategory cat = getCategoryById(catId);
        if (cat != null) {
            registerItem(itemId, cat);
        } else {
            LOGGER.atWarning().log("Unknown category '" + catId + "' for item '" + itemId + "'");
        }
    }



    // Helper DTO for JSON - Made public for SmartConfigManager
    public static class CategoryConfig {
        @com.google.gson.annotations.SerializedName("custom_items")
        public Map<String, Map<String, String>> customItems; // ModName -> { ItemID -> CategoryID }
        
        @com.google.gson.annotations.SerializedName("family_mappings")
        public Map<String, Map<String, String>> familyMappings; // ModName -> { FamilyName -> CategoryID }
        
        @com.google.gson.annotations.SerializedName("blacklist")
        public java.util.List<String> blacklist; // List of Item IDs or Families to exclude entirely
    }

    /**
     * Creates default configuration with prepopulated examples and standard blacklist.
     */
    public static CategoryConfig createDefault() {
        CategoryConfig defaultConfig = new CategoryConfig();
        
        // Add some example data so user sees how to use it
        defaultConfig.customItems = new java.util.LinkedHashMap<>();
        
        java.util.Map<String, String> tensaZangetsu = new java.util.LinkedHashMap<>();
        tensaZangetsu.put("Tensa_Zangetsu", "MELEE_WEAPON");
        tensaZangetsu.put("ZangetsuShikai", "MELEE_WEAPON");
        defaultConfig.customItems.put("Jaykov's Tensa Zangetsu", tensaZangetsu);

        defaultConfig.familyMappings = new java.util.LinkedHashMap<>();
        
        java.util.Map<String, String> kebsKatanas = new java.util.LinkedHashMap<>();
        kebsKatanas.put("Katana", "MELEE_WEAPON");
        defaultConfig.familyMappings.put("Keb's Katanas", kebsKatanas);
        java.util.Map<String, String> draysBetterSpellBooks = new java.util.LinkedHashMap<>();
        draysBetterSpellBooks.put("Spellbook", "STAFF_MANA");
        defaultConfig.familyMappings.put("Drays Better Spell Books", draysBetterSpellBooks);
        
        // Standard Blacklist (Projectiles, Throwables, Totems, Guns, Magical Items)
        defaultConfig.blacklist = java.util.Arrays.asList(
            // Arrows
            "Weapon_Arrow_Clearshot", "Weapon_Arrow_Crude", "Weapon_Arrow_Deadeye", "Weapon_Arrow_Iron", "Weapon_Arrow_Trueshot",
            // Bombs
            "Weapon_Bomb", "Weapon_Bomb_Continuous", "Weapon_Bomb_Fire", "Weapon_Bomb_Large_Fire", "Weapon_Bomb_Popberry", "Weapon_Bomb_Potion_Poison", "Weapon_Bomb_Stun",
            // Darts
            "Weapon_Dart_Tribal",
            // Wands
            // "Weapon_Wand_Root", "Weapon_Wand_Stoneskin", "Weapon_Wand_Tribal", "Weapon_Wand_Wood", "Weapon_Wand_Wood_Rotten",
            // Deployables
            "Weapon_Deployable_Healing_Totem", "Weapon_Deployable_Slowness_Totem", "Weapon_Deployable_Turret",
            // Spears (Thrown/Melee hybrid, often issues with enchants)
            "Weapon_Spear_Adamantite", "Weapon_Spear_Adamantite_Saurian", "Weapon_Spear_Bone", "Weapon_Spear_Bronze", "Weapon_Spear_Cobalt", "Weapon_Spear_Copper",
            "Weapon_Spear_Crude", "Weapon_Spear_Double_Incandescent", "Weapon_Spear_Fishbone", "Weapon_Spear_Iron", "Weapon_Spear_Leaf", "Weapon_Spear_Mithril",
            "Weapon_Spear_Onyxium", "Weapon_Spear_Scrap", "Weapon_Spear_Stone_Trork", "Weapon_Spear_Thorium", "Weapon_Spear_Tribal",
            // Guns
            "Weapon_Gun", "Weapon_Gun_Blunderbuss", "Weapon_Gun_Blunderbuss_Rusty",
            // Blowguns
            "Weapon_Blowgun_Tribal",
            // Spellbooks
            "Weapon_Spellbook_Demon", "Weapon_Spellbook_Fire", "Weapon_Spellbook_Frost", "Weapon_Spellbook_Grimoire_Brown", "Weapon_Spellbook_Grimoire_Purple", "Weapon_Spellbook_Rekindle_Embers", "*Tool_Watering_Can_State_Filled_Water"
        );
        return defaultConfig;
    }


    // Blacklist cache
    private final java.util.Set<String> blacklistedItems = ConcurrentHashMap.newKeySet();

    /**
     * Checks if an item is blacklisted from being categorized (and thus enchanted).
     */
    public boolean isBlacklisted(String itemId) {
        return itemId != null && blacklistedItems.contains(itemId.toLowerCase());
    }
}
