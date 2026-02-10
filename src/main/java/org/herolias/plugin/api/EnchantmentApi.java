package org.herolias.plugin.api;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Public API for the SimpleEnchanting plugin.
 * Allows other mods to interact with the enchantment system.
 */
public interface EnchantmentApi {
    
    /**
     * Adds an enchantment to an item.
     * 
     * @param item The item to enchant
     * @param enchantmentId The ID of the enchantment (e.g., "sharpness")
     * @param level The level to apply
     * @return A new ItemStack with the enchantment applied, or the original item if application failed
     */
    @Nonnull
    ItemStack addEnchantment(@Nonnull ItemStack item, @Nonnull String enchantmentId, int level);

    /**
     * Removes an enchantment from an item.
     * 
     * @param item The item to modify
     * @param enchantmentId The ID of the enchantment to remove
     * @return A new ItemStack with the enchantment removed
     */
    @Nonnull
    ItemStack removeEnchantment(@Nonnull ItemStack item, @Nonnull String enchantmentId);

    /**
     * Gets the level of a specific enchantment on an item.
     * 
     * @param item The item to check
     * @param enchantmentId The ID of the enchantment
     * @return The level of the enchantment, or 0 if not present
     */
    int getEnchantmentLevel(@Nullable ItemStack item, @Nonnull String enchantmentId);

    /**
     * Checks if an item has a specific enchantment.
     * 
     * @param item The item to check
     * @param enchantmentId The ID of the enchantment
     * @return True if the item has the enchantment, false otherwise
     */
    boolean hasEnchantment(@Nullable ItemStack item, @Nonnull String enchantmentId);

    /**
     * Gets all enchantments currently on an item.
     * 
     * @param item The item to check
     * @return A map of enchantment IDs to their levels
     */
    @Nonnull
    Map<String, Integer> getEnchantments(@Nullable ItemStack item);

    /** 
     * Registers an item to a specific category.
     * Use this to make items enchantable that aren't automatically detected.
     * This has higher priority than configuration files.
     *
     * @param itemId The item ID (e.g. "super_sword")
     * @param categoryId The category ID (e.g. "MELEE_WEAPON")
     * @throws IllegalArgumentException if the category does not exist.
     */
    void registerItemToCategory(@Nonnull String itemId, @Nonnull String categoryId);

}

