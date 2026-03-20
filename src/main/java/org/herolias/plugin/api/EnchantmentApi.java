package org.herolias.plugin.api;

import com.hypixel.hytale.server.core.entity.entities.Player;
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
     * @param item          The item to enchant
     * @param enchantmentId The ID of the enchantment (e.g., "sharpness")
     * @param level         The level to apply
     * @return A new ItemStack with the enchantment applied, or the original item if
     *         application failed
     */
    @Nonnull
    ItemStack addEnchantment(@Nonnull ItemStack item, @Nonnull String enchantmentId, int level);

    /**
     * Removes an enchantment from an item.
     * 
     * @param item          The item to modify
     * @param enchantmentId The ID of the enchantment to remove
     * @return A new ItemStack with the enchantment removed
     */
    @Nonnull
    ItemStack removeEnchantment(@Nonnull ItemStack item, @Nonnull String enchantmentId);

    /**
     * Gets the level of a specific enchantment on an item.
     * 
     * @param item          The item to check
     * @param enchantmentId The ID of the enchantment
     * @return The level of the enchantment, or 0 if not present
     */
    int getEnchantmentLevel(@Nullable ItemStack item, @Nonnull String enchantmentId);

    /**
     * Checks if an item has a specific enchantment.
     * 
     * @param item          The item to check
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
     * @param itemId     The item ID (e.g. "super_sword")
     * @param categoryId The category ID (e.g. "MELEE_WEAPON")
     * @throws IllegalArgumentException if the category does not exist.
     */
    void registerItemToCategory(@Nonnull String itemId, @Nonnull String categoryId);

    /**
     * Registers a custom item category based on an item family.
     * Use the returned ItemCategory to specify which items an enchantment applies
     * to.
     *
     * @param categoryId Unique ID for the new or existing category (e.g.
     *                   "MY_MOD_WEAPON")
     * @param family     The item tag family (e.g. "katana")
     * @return The registered ItemCategory
     */
    @Nonnull
    org.herolias.plugin.enchantment.ItemCategory registerCategoryByFamily(@Nonnull String categoryId,
            @Nonnull String family);

    /**
     * Registers a custom item category and explicitly assigns an array of item IDs
     * to it.
     * Use the returned ItemCategory to specify which items an enchantment applies
     * to.
     *
     * @param categoryId Unique ID for the new or existing category (e.g.
     *                   "MY_MOD_TOOLS")
     * @param itemIds    The item IDs to associate with this category
     * @return The registered ItemCategory
     */
    @Nonnull
    org.herolias.plugin.enchantment.ItemCategory registerCategoryByItems(@Nonnull String categoryId,
            @Nonnull String... itemIds);

    /**
     * Retrieves a registered item category by its ID.
     *
     * @param categoryId The category ID
     * @return The ItemCategory, or null if not found
     */
    @Nullable
    org.herolias.plugin.enchantment.ItemCategory getCategory(@Nonnull String categoryId);

    // ==================== Enchantment Registration ====================

    /**
     * Starts building a new addon enchantment.
     * <p>
     * Example:
     * 
     * <pre>{@code
     * EnchantmentType lightning = api.registerEnchantment("my_mod:lightning", "Lightning Strike")
     *         .description("Chance to strike enemies with lightning")
     *         .maxLevel(3)
     *         .multiplierPerLevel(0.15)
     *         .bonusDescription("Lightning strike chance: {amount}%")
     *         .appliesTo(ItemCategory.MELEE_WEAPON)
     *         .build();
     * }</pre>
     *
     * @param id          Namespaced enchantment ID (e.g. "my_mod:lightning"). Must
     *                    contain ':'
     * @param displayName Human-readable name (e.g. "Lightning Strike")
     * @return A builder to configure and register the enchantment
     */
    @Nonnull
    EnchantmentBuilder registerEnchantment(@Nonnull String id, @Nonnull String displayName);

    /**
     * Gets a registered enchantment by its ID.
     *
     * @param id The enchantment ID (e.g. "sharpness" or "my_mod:lightning")
     * @return The enchantment type, or null if not registered
     */
    @Nullable
    org.herolias.plugin.enchantment.EnchantmentType getRegisteredEnchantment(@Nonnull String id);

    /**
     * Checks if an enchantment ID is registered.
     *
     * @param id The enchantment ID
     * @return True if registered
     */
    boolean isEnchantmentRegistered(@Nonnull String id);

    /**
     * Declares two enchantments as conflicting (cannot be on the same item).
     *
     * @param enchantmentId1 First enchantment ID
     * @param enchantmentId2 Second enchantment ID
     */
    void addConflict(@Nonnull String enchantmentId1, @Nonnull String enchantmentId2);

    /**
     * Registers a new crafting category (tab) in the Enchanting Table.
     * <p>
     * Built-in categories are: "Enchanting_Melee", "Enchanting_Ranged",
     * "Enchanting_Armor", "Enchanting_Shield", "Enchanting_Staff",
     * "Enchanting_Tools".
     * <p>
     * Use this to add a new tab for your mod's scrolls when they don't fit
     * into the existing categories.
     *
     * @param categoryId  Unique ID for the category (e.g. "Enchanting_Magic")
     * @param displayName Displayed name in the Enchanting Table UI
     * @param iconPath    Path to the tab icon (relative to mod assets).
     *                    Use null to use the default enchanting icon.
     */
    void registerCraftingCategory(@Nonnull String categoryId, @Nonnull String displayName,
            @Nullable String iconPath);

    /**
     * Gets all enchantments on a player's equipped items
     * (armor slots, utility/off-hand, and main-hand).
     * <p>
     * If the same enchantment appears on multiple items, the highest level is used.
     *
     * @param player The player to inspect
     * @return A map of enchantment IDs to their levels across all equipped slots
     */
    @Nonnull
    Map<String, Integer> equippedItemEnchantments(@Nonnull Player player);

}
