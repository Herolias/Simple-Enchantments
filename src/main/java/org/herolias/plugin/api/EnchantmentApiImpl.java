package org.herolias.plugin.api;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ItemBase;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.enchantment.EnchantmentData;
import org.herolias.plugin.enchantment.EnchantmentManager;
import org.herolias.plugin.enchantment.EnchantmentTooltipManager;
import org.herolias.plugin.enchantment.EnchantmentType;
import org.herolias.plugin.enchantment.ItemCategory;
import org.herolias.plugin.enchantment.ItemCategoryManager;
import org.herolias.plugin.enchantment.VirtualItemRegistry;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
        var result = manager.applyEnchantmentToItem(item, type, level, true);
        
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
        return manager.updateItemVisuals(newItem);
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

    // ─────────────────────────────────────────────────────────────────────────
    //  CustomUI Tooltip Support
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Nonnull
    public ItemStack prepareItemForUI(@Nonnull ItemStack item) {
        if (item == null || item.isEmpty()) {
            return item;
        }

        EnchantmentData data = manager.getEnchantmentsFromItem(item);
        if (data.isEmpty()) {
            return item; // Not enchanted, no modification needed
        }

        // Generate a virtual ID for this enchanted item
        VirtualItemRegistry registry = getVirtualItemRegistry();
        if (registry == null) {
            LOGGER.atWarning().log("VirtualItemRegistry not available, returning original item");
            return item;
        }

        String virtualId = registry.generateVirtualId(item.getItemId(), data);
        
        // Create a new ItemStack with the virtual ID but same metadata
        return new ItemStack(virtualId, item.getQuantity(), item.getMetadata());
    }

    @Override
    public void sendVirtualItemDefinitions(@Nonnull PlayerRef playerRef, @Nonnull ItemStack... items) {
        if (playerRef == null || items == null || items.length == 0) {
            return;
        }

        VirtualItemRegistry registry = getVirtualItemRegistry();
        if (registry == null) {
            LOGGER.atWarning().log("VirtualItemRegistry not available, cannot send definitions");
            return;
        }

        String language = playerRef.getLanguage();
        Map<String, ItemBase> virtualItems = new LinkedHashMap<>();
        Map<String, String> translations = new LinkedHashMap<>();

        for (ItemStack item : items) {
            if (item == null || item.isEmpty()) continue;

            String itemId = item.getItemId();
            if (!VirtualItemRegistry.isVirtualId(itemId)) {
                continue; // Not a virtual item, skip
            }

            String baseItemId = VirtualItemRegistry.getBaseItemId(itemId);
            if (baseItemId == null) continue;

            // Get or create the virtual item base
            ItemBase virtualBase = registry.getOrCreateVirtualItemBase(baseItemId, itemId);
            if (virtualBase == null) continue;

            virtualItems.put(itemId, virtualBase);

            // Build the description translation
            String descKey = VirtualItemRegistry.getVirtualDescriptionKey(itemId);
            if (!translations.containsKey(descKey)) {
                // Get enchantment data from the item
                EnchantmentData enchData = manager.getEnchantmentsFromItem(item);
                String originalDesc = registry.getOriginalDescription(baseItemId, language);
                String enchantedDesc = registry.buildEnchantedDescription(originalDesc, enchData);
                translations.put(descKey, enchantedDesc);
            }
        }

        // Send the virtual item definitions and translations to the player
        if (!virtualItems.isEmpty() || !translations.isEmpty()) {
            EnchantmentTooltipManager tooltipManager = SimpleEnchanting.getInstance().getTooltipManager();
            if (tooltipManager != null) {
                tooltipManager.sendVirtualItemData(playerRef, virtualItems, translations);
            }
        }
    }

    @Override
    @Nonnull
    public ItemGridSlot createEnchantedItemSlot(@Nonnull PlayerRef playerRef, @Nonnull ItemStack item) {
        if (item == null || item.isEmpty()) {
            return new ItemGridSlot(ItemStack.EMPTY);
        }

        // Prepare the item (generates virtual ID if enchanted)
        ItemStack preparedItem = prepareItemForUI(item);

        // Send virtual item definitions to the player
        sendVirtualItemDefinitions(playerRef, preparedItem);

        // Create and return the ItemGridSlot
        return new ItemGridSlot(preparedItem);
    }

    /**
     * Gets the VirtualItemRegistry from the tooltip manager.
     */
    @Nullable
    private VirtualItemRegistry getVirtualItemRegistry() {
        EnchantmentTooltipManager tooltipManager = SimpleEnchanting.getInstance().getTooltipManager();
        return tooltipManager != null ? tooltipManager.getVirtualItemRegistry() : null;
    }
}
