package org.herolias.plugin.listener;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.ecs.*;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.enchantment.EnchantmentManager;
import org.herolias.plugin.enchantment.EnchantmentType;
import org.herolias.plugin.enchantment.ItemCategory;

/**
 * Handles player interactions with the Enchanting Table block.
 * 
 * Uses UseBlockEvent from Hytale's ECS event system for block interactions.
 * 
 * When a player interacts with the Enchanting Table:
 * 1. Opens the enchanting UI
 * 2. Allows placing items and essences
 * 3. Applies enchantments based on the essence type used
 * 
 * Essence Types and their corresponding enchantments:
 * - Fire Essence: Fire-based enchantments (future)
 * - Void Essence: Sharpness/damage enchantments
 * - Ice Essence: Slow/cold-based enchantments (future)
 */
public class EnchantingTableListener {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    
    // Block ID for the enchanting table (using Alchemy Table appearance)
    public static final String ENCHANTING_TABLE_BLOCK_ID = "Enchanting_Table";
    
    // Essence item IDs (these should match Hytale's actual item IDs)
    public static final String FIRE_ESSENCE_ID = "Essence_Fire";
    public static final String VOID_ESSENCE_ID = "Essence_Void";
    public static final String ICE_ESSENCE_ID = "Essence_Ice";
    
    private final EnchantmentManager enchantmentManager;

    public EnchantingTableListener(SimpleEnchanting plugin) {
        this.enchantmentManager = plugin.getEnchantmentManager();
        LOGGER.atInfo().log("EnchantingTableListener initialized");
    }

    /**
     * Handler for UseBlockEvent - triggers when player uses the enchanting table.
     * 
     * TODO: Register this handler with Hytale's event system.
     * The exact registration method depends on the plugin API.
     * 
     * @param event The UseBlockEvent from Hytale's ECS system
     */
    public void onUseBlock(UseBlockEvent event) {
        // TODO: Check if the block is our enchanting table
        // String blockId = event.getBlock().getId(); // or similar
        // if (!ENCHANTING_TABLE_BLOCK_ID.equals(blockId)) return;
        
        LOGGER.atInfo().log("Enchanting Table used!");
        // TODO: Open enchanting UI for the player
    }

    /**
     * Processes an enchanting request from a player.
     * 
     * @param item The item to enchant
     * @param essenceId The essence being used
     * @return true if enchanting was successful
     */
    public ItemStack processEnchantment(ItemStack item, String essenceId) {
        if (item == null || item.isEmpty()) {
            LOGGER.atWarning().log("Cannot enchant null or empty item");
            return null;
        }
        
        ItemCategory category = enchantmentManager.categorizeItem(item);
        
        if (category == ItemCategory.UNKNOWN) {
            LOGGER.atWarning().log("Cannot enchant item type: " + item.getItemId());
            return null;
        }
        
        // Determine which enchantment to apply based on essence
        EnchantmentType enchantment = getEnchantmentForEssence(essenceId, category);
        
        if (enchantment == null) {
            LOGGER.atWarning().log("No valid enchantment for essence " + essenceId + " on " + category);
            return null;
        }
        
        // Apply the enchantment (level 1 for now) using metadata system
        org.herolias.plugin.enchantment.EnchantmentApplicationResult result = enchantmentManager.applyEnchantmentToItem(item, enchantment, 1);
        
        if (result.success()) {
            LOGGER.atInfo().log("Successfully applied " + enchantment.getDisplayName() + " to item");
            return result.item();
        } else {
            LOGGER.atInfo().log("Failed to apply enchantment: " + result.message());
            return null;
        }
    }

    /**
     * Determines which enchantment to apply based on the essence used and item category.
     * 
     * Current mappings:
     * - Void Essence + Melee Weapon = Sharpness
     * - (More mappings to be added as enchantments are implemented)
     */
    private EnchantmentType getEnchantmentForEssence(String essenceId, ItemCategory category) {
        if (essenceId == null) {
            return null;
        }
        
        // Void Essence grants damage-type enchantments
        if (essenceId.equalsIgnoreCase(VOID_ESSENCE_ID) || essenceId.toLowerCase().contains("void")) {
            if (category == ItemCategory.MELEE_WEAPON || category == ItemCategory.AXE) {
                return EnchantmentType.SHARPNESS;
            }
        }
        
        // Fire Essence - for future fire-based enchantments
        if (essenceId.equalsIgnoreCase(FIRE_ESSENCE_ID) || essenceId.toLowerCase().contains("fire")) {
            // Future: Fire Aspect for weapons, Fire Protection for armor
            return null;
        }
        
        // Ice Essence - for future cold-based enchantments
        if (essenceId.equalsIgnoreCase(ICE_ESSENCE_ID) || essenceId.toLowerCase().contains("ice")) {
            // Future: Frost Walker for boots, Freezing for weapons
            return null;
        }
        
        return null;
    }

    /**
     * Checks if an item can be enchanted at the enchanting table.
     */
    public boolean canEnchant(String itemTypeId) {
        ItemCategory category = enchantmentManager.categorizeItem(itemTypeId);
        return category.isEnchantable();
    }
}
