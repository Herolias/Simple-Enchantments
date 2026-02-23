package org.herolias.plugin.enchantment;

import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.range.FloatRange;
import com.hypixel.hytale.protocol.ValueType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemAppearanceCondition;
import org.herolias.plugin.SimpleEnchanting;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Injects ItemAppearanceConditions for enchantment glow at runtime.
 * 
 * This replaces the previous approach of file-based item overrides,
 * which caused compatibility issues with other mods that also modified items.
 * 
 * By injecting at runtime via LoadedAssetsEvent, we:
 * - Preserve all vanilla item properties
 * - Merge with any other mod's changes (non-destructive)
 * - Automatically apply to all enchantable items
 */
public class EnchantmentGlowInjector {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    
    // Stat keys that the condition is mapped to (must match EntityStatType assets)
    private static final String STAT_GLOW_PRIMARY = "EnchantmentGlow_Primary";
    private static final String STAT_GLOW_PRIMARY_SINGLE = "EnchantmentGlow_Primary_Single";
    private static final String STAT_GLOW_HEAD = "EnchantmentGlow_Head";
    private static final String STAT_GLOW_CHEST = "EnchantmentGlow_Chest";
    private static final String STAT_GLOW_HANDS = "EnchantmentGlow_Hands";
    private static final String STAT_GLOW_LEGS = "EnchantmentGlow_Legs";
    private static final String STAT_GLOW_SHIELD = "EnchantmentGlow_Shield";
    
    // The VFX to apply when condition is met
    private static final String MODEL_VFX_ID = "Enchantment_Glow";
    private static final String MODEL_VFX_ID_SMALL = "Enchantment_Glow_small";
    
    // Keywords for identifying items that should use the small glow effect
    private static final java.util.Set<String> SMALL_WEAPON_KEYWORDS = java.util.Set.of(
        "dagger", "mace", "shortbow", "short_bow", "shovel", "battleaxe", "longsword",
        "staff", "spellbook"
    );
    
    // Reflection field for accessing protected itemAppearanceConditions
    private static Field itemAppearanceConditionsField;
    private static Field conditionField;
    private static Field conditionValueTypeField;
    private static Field modelVFXIdField;

    static {
        try {
            itemAppearanceConditionsField = Item.class.getDeclaredField("itemAppearanceConditions");
            itemAppearanceConditionsField.setAccessible(true);
            
            conditionField = ItemAppearanceCondition.class.getDeclaredField("condition");
            conditionField.setAccessible(true);
            
            conditionValueTypeField = ItemAppearanceCondition.class.getDeclaredField("conditionValueType");
            conditionValueTypeField.setAccessible(true);
            
            modelVFXIdField = ItemAppearanceCondition.class.getDeclaredField("modelVFXId");
            modelVFXIdField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            LOGGER.atSevere().log("Failed to find required fields for EnchantmentGlowInjector: " + e.getMessage());
        }
    }

    /**
     * Registers the event listener for item loading.
     */
    public static void registerEventListener(@Nonnull SimpleEnchanting plugin) {
        plugin.getEventRegistry().register(
            LoadedAssetsEvent.class, 
            Item.class, 
            EnchantmentGlowInjector::onItemsLoaded
        );
        LOGGER.atInfo().log("EnchantmentGlowInjector registered");
    }

    private static void onItemsLoaded(LoadedAssetsEvent<String, Item, DefaultAssetMap<String, Item>> event) {
        if (itemAppearanceConditionsField == null || conditionField == null) {
            LOGGER.atWarning().log("EnchantmentGlowInjector skipped - reflection fields not available");
            return;
        }

        int weaponCount = 0;
        int toolCount = 0;
        int armorCount = 0;
        
        ItemCategoryManager categoryManager = ItemCategoryManager.getInstance();

        for (Map.Entry<String, Item> entry : event.getLoadedAssets().entrySet()) {
            String itemId = entry.getKey();
            Item item = entry.getValue();

            try {
                ItemCategory category = categoryManager.categorizeItem(itemId, item);
                
                if (category == ItemCategory.UNKNOWN) continue;

                // Determine appropriate VFX (normal or small)
                String vfxId = getGlowVfxFor(item, itemId);

                // Check what type of item this is and inject appropriate glow conditions
                if (category.isShield()) {
                    injectGlowCondition(item, STAT_GLOW_SHIELD, MODEL_VFX_ID_SMALL, 1.0f);
                } else if (category.isWeapon()) {
                    injectGlowCondition(item, STAT_GLOW_PRIMARY, vfxId, 1.0f);
                    injectGlowCondition(item, STAT_GLOW_PRIMARY, "Enchantment_Glow_Single", 2.0f);
                    weaponCount++;
                } else if (category.isTool()) {
                    injectGlowCondition(item, STAT_GLOW_PRIMARY, vfxId, 1.0f);
                    injectGlowCondition(item, STAT_GLOW_PRIMARY, "Enchantment_Glow_Single", 2.0f);
                    toolCount++;
                } else if (category.isArmor()) {
                    // Armor uses slot-specific glow stats
                    String statKey = getArmorGlowStat(itemId);
                    if (statKey != null) {
                        injectGlowCondition(item, statKey, vfxId, 1.0f);
                        armorCount++;
                    }
                }
            } catch (Exception e) {
                LOGGER.atWarning().log("Failed to inject glow for item " + itemId + ": " + e.getMessage());
            }
        }

        LOGGER.atInfo().log("EnchantmentGlowInjector: Injected glow conditions for " 
            + weaponCount + " weapons, " 
            + toolCount + " tools, " 
            + armorCount + " armor pieces");
    }
    
    /**
     * Determines which glow VFX to use based on item type/category.
     */
    private static String getGlowVfxFor(Item item, String itemId) {
        String idLower = itemId.toLowerCase();
        
        // Check Categories first
        if (item.getCategories() != null) {
            for (String category : item.getCategories()) {
                String catLower = category.toLowerCase();
                for (String keyword : SMALL_WEAPON_KEYWORDS) {
                    if (catLower.contains(keyword)) {
                        return MODEL_VFX_ID_SMALL;
                    }
                }
            }
        }
        
        // Fallback checks on ID
        for (String keyword : SMALL_WEAPON_KEYWORDS) {
            if (idLower.contains(keyword)) {
                return MODEL_VFX_ID_SMALL;
            }
        }
        
        return MODEL_VFX_ID;
    }

    private static String getArmorGlowStat(String itemId) {
        String id = itemId.toLowerCase();
        if (id.contains("head") || id.contains("helmet") || id.contains("hood") || id.contains("cap")) {
            return STAT_GLOW_HEAD;
        } else if (id.contains("chest") || id.contains("torso") || id.contains("robe") || id.contains("tunic")) {
            return STAT_GLOW_CHEST;
        } else if (id.contains("hand") || id.contains("gauntlet") || id.contains("glove") || id.contains("bracer")) {
            return STAT_GLOW_HANDS;
        } else if (id.contains("leg") || id.contains("pants") || id.contains("greave") || id.contains("boots")) {
            return STAT_GLOW_LEGS;
        }
        // Default to primary for unrecognized armor
        return STAT_GLOW_PRIMARY;
    }

    private static void injectGlowCondition(Item item, String statKey, String vfxId, float targetValue) throws IllegalAccessException {
        // Get existing conditions map or create new one
        Map<String, ItemAppearanceCondition[]> conditions = 
            (Map<String, ItemAppearanceCondition[]>) itemAppearanceConditionsField.get(item);
        
        if (conditions == null) {
            conditions = new HashMap<>();
            itemAppearanceConditionsField.set(item, conditions);
        } else {
             // If map exists, we might need to APPEND to the array for this statKey if it exists
             // But first ensure we have a mutable map
             conditions = new HashMap<>(conditions);
             itemAppearanceConditionsField.set(item, conditions);
        }

        ItemAppearanceCondition glowCondition = createGlowCondition(vfxId, targetValue);
        if (glowCondition == null) return;

        if (conditions.containsKey(statKey)) {
            // Append to existing array
            ItemAppearanceCondition[] existing = conditions.get(statKey);
            ItemAppearanceCondition[] newArray = new ItemAppearanceCondition[existing.length + 1];
            System.arraycopy(existing, 0, newArray, 0, existing.length);
            newArray[existing.length] = glowCondition;
            conditions.put(statKey, newArray);
        } else {
            // New entry
            conditions.put(statKey, new ItemAppearanceCondition[]{ glowCondition });
        }
    }

    private static ItemAppearanceCondition createGlowCondition(String vfxId, float targetValue) {
        try {
            ItemAppearanceCondition condition = new ItemAppearanceCondition();
            
            // Set condition range: [X, X] means "when stat value equals X"
            FloatRange range = new FloatRange(targetValue, targetValue);
            conditionField.set(condition, range);
            
            // Set value type to Absolute
            conditionValueTypeField.set(condition, ValueType.Absolute);
            
            // Set the VFX to apply
            modelVFXIdField.set(condition, vfxId);
            
            return condition;
        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to create glow condition: " + e.getMessage());
            return null;
        }
    }
}
