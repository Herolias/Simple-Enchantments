package org.herolias.plugin.api;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.universe.PlayerRef;
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

    // ─────────────────────────────────────────────────────────────────────────
    //  CustomUI Tooltip Support
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Prepares an ItemStack for display in a CustomUI with proper enchantment tooltips.
     * <p>
     * If the item has enchantments, returns a new ItemStack with a virtual item ID
     * that has a unique tooltip. If the item has no enchantments, returns the original.
     * <p>
     * <b>IMPORTANT:</b> After calling this, you MUST call {@link #sendVirtualItemDefinitions}
     * to send the virtual item definition to the player before rendering the UI.
     *
     * @param item The item to prepare (may have enchantments)
     * @return An ItemStack ready for UI display (may have a virtual ID)
     */
    @Nonnull
    ItemStack prepareItemForUI(@Nonnull ItemStack item);

    /**
     * Sends virtual item definitions and translations for prepared items to a player.
     * <p>
     * Call this BEFORE sending the CustomPage packet to ensure tooltips display correctly.
     * Only items that were prepared via {@link #prepareItemForUI} and have virtual IDs
     * need their definitions sent.
     *
     * @param playerRef The player to send definitions to
     * @param items     The items prepared via prepareItemForUI()
     */
    void sendVirtualItemDefinitions(@Nonnull PlayerRef playerRef, @Nonnull ItemStack... items);

    /**
     * Convenience method to create an ItemGridSlot ready for CustomUI display.
     * <p>
     * This method automatically handles virtual ID generation and sends the required
     * definitions to the player. Mod authors can use this directly in their
     * {@code UICommandBuilder.set()} calls for item display.
     * <p>
     * <b>This is the simplest integration option for third-party mods.</b>
     *
     * <pre>{@code
     * // Usage example in a CustomUI build() method:
     * EnchantmentApi api = EnchantmentApiProvider.getApi();
     * ItemStack enchantedItem = getItemFromDatabase();
     * ItemGridSlot slot = api.createEnchantedItemSlot(playerRef, enchantedItem);
     * cmd.set(selector + " #ItemIcon.Slots", new ItemGridSlot[]{slot});
     * }</pre>
     *
     * @param playerRef The player viewing the UI
     * @param item      The item to display (can be enchanted)
     * @return An ItemGridSlot configured to display the item with enchantment tooltips
     */
    @Nonnull
    ItemGridSlot createEnchantedItemSlot(@Nonnull PlayerRef playerRef, @Nonnull ItemStack item);
}
