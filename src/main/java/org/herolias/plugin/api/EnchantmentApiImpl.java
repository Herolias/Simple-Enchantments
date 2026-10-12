package org.herolias.plugin.api;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.herolias.plugin.enchantment.EnchantmentApplicationResult;
import org.herolias.plugin.enchantment.EnchantmentData;
import org.herolias.plugin.enchantment.EnchantmentManager;
import org.herolias.plugin.enchantment.EnchantmentType;
import org.herolias.plugin.enchantment.ItemCategory;
import org.herolias.plugin.enchantment.ItemCategoryManager;
import org.herolias.plugin.enchantment.NativeTooltipManager;
import org.herolias.plugin.util.InventoryAccess;

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
        return addEnchantment(item, enchantmentId, level, false);
    }

    @Override
    @Nonnull
    public ItemStack addEnchantmentUnsafe(@Nonnull ItemStack item, @Nonnull String enchantmentId, int level) {
        return addEnchantment(item, enchantmentId, level, true);
    }

    @Nonnull
    private ItemStack addEnchantment(@Nonnull ItemStack item, @Nonnull String enchantmentId, int level,
            boolean unsafe) {
        if (enchantmentId == null) {
            throw new IllegalArgumentException("Enchantment ID must not be null");
        }
        EnchantmentType type = EnchantmentType.fromId(enchantmentId);
        if (type == null) {
            throw new IllegalArgumentException("Unknown enchantment ID: '" + enchantmentId + "'");
        }
        if (level < 1) {
            throw new IllegalArgumentException(
                    "Enchantment level must be at least 1 (got " + level + " for '" + enchantmentId + "')");
        }
        if (item == null || item.isEmpty()) {
            return item;
        }

        // Delegate to the manager (handles category/conflict/limit checks, metadata and
        // tooltips). Safe mode clamps to the enchantment's max level.
        EnchantmentApplicationResult result = manager.applyEnchantmentToItem(null, item, type, level, unsafe);
        if (!result.success()) {
            // If failed (e.g. conflicts, max limit), return original item
            return item;
        }

        // The API has no inventory commit step, so listeners are notified right away
        // (documented on EnchantmentApi#addEnchantment).
        manager.fireItemEnchanted(null, result);
        return result.item();
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
        data.removeEnchantment(type);

        // Write back to item metadata
        org.bson.BsonDocument bson = data.isEmpty() ? null : data.toBson();
        ItemStack newItem = NativeTooltipManager.withEnchantments(item, bson, manager);

        // Update visuals
        return newItem;
    }

    @Override
    public int getEnchantmentLevel(@Nullable ItemStack item, @Nonnull String enchantmentId) {
        if (item == null || item.isEmpty())
            return 0;

        EnchantmentType type = EnchantmentType.fromId(enchantmentId);
        if (type == null)
            return 0;

        return manager.getEnchantmentLevel(item, type);
    }

    @Override
    public boolean hasEnchantment(@Nullable ItemStack item, @Nonnull String enchantmentId) {
        if (item == null || item.isEmpty())
            return false;

        EnchantmentType type = EnchantmentType.fromId(enchantmentId);
        if (type == null)
            return false;

        return manager.hasEnchantment(item, type);
    }

    @Override
    @Nonnull
    public Map<String, Integer> getEnchantments(@Nullable ItemStack item) {
        Map<String, Integer> result = new HashMap<>();
        if (item == null || item.isEmpty())
            return result;

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
        LOGGER.atInfo().log("Registered crafting category: %s (%s)", categoryId, displayName);
    }

    @Override
    @Nonnull
    public Map<String, Integer> equippedItemEnchantments(@Nonnull Player player) {
        Map<String, Integer> result = new HashMap<>();
        if (player == null)
            return result;

        Ref<EntityStore> ref = InventoryAccess.refOf(player);
        if (ref == null)
            return result;
        Store<EntityStore> store = ref.getStore();

        // Main-hand
        collectEnchantments(InventoryAccess.getItemInHand(store, ref), result);

        // Utility / off-hand
        collectEnchantments(InventoryAccess.getUtilityItem(store, ref), result);

        // Armor slots (helmet=0, chestplate=1, gloves=2, legs=3)
        ItemContainer armorContainer = InventoryAccess.getArmor(store, ref);
        if (armorContainer != null) {
            for (short slot = 0; slot < armorContainer.getCapacity(); slot++) {
                collectEnchantments(armorContainer.getItemStack(slot), result);
            }
        }

        return result;
    }

    /**
     * Helper: extracts enchantments from a single item and merges into the result
     * map,
     * keeping the highest level when duplicates exist.
     */
    private void collectEnchantments(@Nullable ItemStack item, @Nonnull Map<String, Integer> result) {
        if (item == null || item.isEmpty())
            return;

        EnchantmentData data = manager.getEnchantmentsFromItem(item);
        for (Map.Entry<EnchantmentType, Integer> entry : data.getAllEnchantments().entrySet()) {
            String id = entry.getKey().getId();
            int level = entry.getValue();
            result.merge(id, level, Math::max);
        }
    }

}
