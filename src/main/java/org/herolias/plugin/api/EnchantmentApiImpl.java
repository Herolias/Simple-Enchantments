package org.herolias.plugin.api;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.enchantment.EnchantmentData;
import org.herolias.plugin.enchantment.EnchantmentManager;
import org.herolias.plugin.enchantment.EnchantmentType;
import org.herolias.plugin.enchantment.ItemCategory;
import org.herolias.plugin.enchantment.ItemCategoryManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

public class EnchantmentApiImpl implements EnchantmentApi {

    private final EnchantmentManager manager;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public EnchantmentApiImpl(EnchantmentManager manager) {
        this.manager = manager;
    }

    @Override
    @Nonnull
    public ItemStack addEnchantment(@Nonnull ItemStack item, @Nonnull String enchantmentId, int level) {
        if (item == null || item.isEmpty()) {
            return item;
        }

        EnchantmentType type = EnchantmentType.fromId(enchantmentId);
        if (type == null) {
            throw new IllegalArgumentException("Unknown enchantment ID: '" + enchantmentId + "'");
        }

        // Delegate to manager's logic (handles checks, application, metadata update)
        // Note: manager.applyEnchantmentToItem returns a Result object with the new ItemStack
        var result = manager.applyEnchantmentToItem(null, item, type, level, true);
        
        if (result.success()) {
             return result.item();
        } else {
            // If failed (e.g. conflicts, max limit), return original item
            // Could log failure reason if needed: result.getMessage()
            return item;
        }
    }

    @Override
    @Nonnull
    public ItemStack removeEnchantment(@Nonnull ItemStack item, @Nonnull String enchantmentId) {
        if (item == null || item.isEmpty()) {
            return item;
        }

        EnchantmentType type = EnchantmentType.fromId(enchantmentId);
        if (type == null) {
            return item;
        }

        EnchantmentData data = manager.getEnchantmentsFromItem(item);
        if (!data.hasEnchantment(type)) {
            return item;
        }

        // Create writable copy/modify
        // Note: EnchantmentData returned from getEnchantmentsFromItem is usually a fresh object from BSON
        // but we should verify if we need to check if it's mutable. 
        // Based on analysis, getEnchantmentsFromItem creates a new EnchantmentData from BSON.
        
        data.removeEnchantment(type);
        
        // Write back to item metadata
        // IMPORTANT: ItemStacks are immutable-ish, need to return the new one with updated metadata
        // If data is empty, we MUST pass null to remove the key entirely to avoid "Enchantments": {} which corrupts the item on client
        org.bson.BsonDocument bson = data.isEmpty() ? null : data.toBson();
        ItemStack newItem = item.withMetadata(EnchantmentData.METADATA_KEY, bson);
        
        // Update visuals
        return newItem;
    }

    @Override
    public int getEnchantmentLevel(@Nullable ItemStack item, @Nonnull String enchantmentId) {
        if (item == null || item.isEmpty()) return 0;
        
        EnchantmentType type = EnchantmentType.fromId(enchantmentId);
        if (type == null) return 0;
        
        return manager.getEnchantmentLevel(item, type);
    }

    @Override
    public boolean hasEnchantment(@Nullable ItemStack item, @Nonnull String enchantmentId) {
        if (item == null || item.isEmpty()) return false;

        EnchantmentType type = EnchantmentType.fromId(enchantmentId);
        if (type == null) return false;

        return manager.hasEnchantment(item, type);
    }

    @Override
    @Nonnull
    public Map<String, Integer> getEnchantments(@Nullable ItemStack item) {
        Map<String, Integer> result = new HashMap<>();
        if (item == null || item.isEmpty()) return result;

        EnchantmentData data = manager.getEnchantmentsFromItem(item);
        for (Map.Entry<EnchantmentType, Integer> entry : data.getAllEnchantments().entrySet()) {
            result.put(entry.getKey().getId(), entry.getValue());
        }
        
        return result;
    }

    @Override
    public void registerItemToCategory(@Nonnull String itemId, @Nonnull String categoryId) {
        ItemCategory category = ItemCategoryManager.getInstance().getCategoryById(categoryId);
        if (category == null) {
            throw new IllegalArgumentException("Unknown category ID: '" + categoryId + "'");
        }
        ItemCategoryManager.getInstance().registerApiItem(itemId, category);
    }

    @Override
    @Nonnull
    public ItemCategory registerCategoryByFamily(@Nonnull String categoryId, @Nonnull String family) {
        ItemCategory category = ItemCategoryManager.getInstance().getCategoryById(categoryId);
        if (category == null) {
            category = new ItemCategory(categoryId);
            ItemCategoryManager.getInstance().registerCategory(category);
        }
        ItemCategoryManager.getInstance().registerFamilyMapping(family, category);
        return category;
    }

    @Override
    @Nonnull
    public ItemCategory registerCategoryByItems(@Nonnull String categoryId, @Nonnull String... itemIds) {
        ItemCategory category = ItemCategoryManager.getInstance().getCategoryById(categoryId);
        if (category == null) {
            category = new ItemCategory(categoryId);
            ItemCategoryManager.getInstance().registerCategory(category);
        }
        for (String itemId : itemIds) {
            ItemCategoryManager.getInstance().registerApiItem(itemId, category);
        }
        return category;
    }

    @Override
    @Nullable
    public ItemCategory getCategory(@Nonnull String categoryId) {
        return ItemCategoryManager.getInstance().getCategoryById(categoryId);
    }

    @Override
    @Nonnull
    public EnchantmentBuilder registerEnchantment(@Nonnull String id, @Nonnull String displayName) {
        return new EnchantmentBuilder(id, displayName);
    }

    @Override
    @javax.annotation.Nullable
    public EnchantmentType getRegisteredEnchantment(@Nonnull String id) {
        return EnchantmentType.fromId(id);
    }

    @Override
    public boolean isEnchantmentRegistered(@Nonnull String id) {
        return EnchantmentType.fromId(id) != null;
    }

    @Override
    public void addConflict(@Nonnull String enchantmentId1, @Nonnull String enchantmentId2) {
        org.herolias.plugin.enchantment.EnchantmentRegistry.getInstance().addConflict(enchantmentId1, enchantmentId2);
    }

    @Override
    public void registerCraftingCategory(@Nonnull String categoryId, @Nonnull String displayName,
                                          @javax.annotation.Nullable String iconPath) {
        CraftingCategoryDefinition.register(categoryId, displayName, iconPath);
        LOGGER.atInfo().log("Registered crafting category: " + categoryId + " (" + displayName + ")");
    }

    @Override
    @Nonnull
    public Map<String, Integer> equippedItemEnchantments(@Nonnull Player player) {
        Map<String, Integer> result = new HashMap<>();
        if (player == null) return result;

        Inventory inventory = player.getInventory();
        if (inventory == null) return result;

        // Main-hand
        collectEnchantments(inventory.getItemInHand(), result);

        // Utility / off-hand
        collectEnchantments(inventory.getUtilityItem(), result);

        // Armor slots (helmet=0, chestplate=1, leggings=2, boots=3)
        ItemContainer armorContainer = inventory.getArmor();
        if (armorContainer != null) {
            for (short slot = 0; slot < armorContainer.getCapacity(); slot++) {
                collectEnchantments(armorContainer.getItemStack(slot), result);
            }
        }

        return result;
    }

    /**
     * Helper: extracts enchantments from a single item and merges into the result map,
     * keeping the highest level when duplicates exist.
     */
    private void collectEnchantments(@Nullable ItemStack item, @Nonnull Map<String, Integer> result) {
        if (item == null || item.isEmpty()) return;

        EnchantmentData data = manager.getEnchantmentsFromItem(item);
        for (Map.Entry<EnchantmentType, Integer> entry : data.getAllEnchantments().entrySet()) {
            String id = entry.getKey().getId();
            int level = entry.getValue();
            result.merge(id, level, Math::max);
        }
    }

}
