package org.herolias.plugin.enchantment;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.event.events.entity.LivingEntityInventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.SlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import org.bson.BsonDocument;
import org.herolias.plugin.util.ProcessingGuard;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Preserves enchantments across item state changes.
 *
 * <p>Some server interactions (e.g. {@code RefillContainerInteraction}) replace
 * an item with a <b>new</b> {@link ItemStack} when its state changes (empty → filled).
 * This discards all metadata, including enchantments.</p>
 *
 * <p>Since we cannot modify the server code, this system intercepts inventory
 * change events and re-applies enchantments when it detects a state transition:</p>
 * <ol>
 *   <li><b>Phase 1 — Cache:</b> When an enchanted item is removed from a slot,
 *       its enchantment metadata is cached briefly.</li>
 *   <li><b>Phase 2 — Restore:</b> When a new item (without enchantments) appears
 *       in the same slot and is a <em>state variant</em> of the removed item,
 *       the cached enchantments are re-applied.</li>
 * </ol>
 *
 * <p>State variants are items linked via the {@code stateToBlock} / {@code blockToState}
 * maps in their {@link Item} definition (e.g. empty Watering Can → filled Watering Can).</p>
 */
public class EnchantmentStateTransferSystem {

    /** Maximum age for cached enchantment data before it is discarded (ms). */
    private static final long CACHE_EXPIRY_MS = 2000;

    /** Lazy cleanup interval: purge stale entries every N events. */
    private static final int CLEANUP_INTERVAL = 50;

    private final EnchantmentManager enchantmentManager;
    private final ProcessingGuard guard = new ProcessingGuard();

    /**
     * Cache of recently removed enchantment data, keyed by (containerId + slot).
     * Short-lived: entries older than {@link #CACHE_EXPIRY_MS} are purged lazily.
     */
    private final ConcurrentHashMap<String, CachedEnchantment> cache = new ConcurrentHashMap<>();
    private int eventCounter = 0;

    public EnchantmentStateTransferSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
    }

    /**
     * Handles inventory change events. Registered as a global listener.
     */
    public void onInventoryChange(@Nonnull LivingEntityInventoryChangeEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof com.hypixel.hytale.server.core.entity.entities.Player player)) return;

        if (player.getWorld() != null && !player.getWorld().isInThread()) {
            player.getWorld().execute(() -> onInventoryChange(event));
            return;
        }

        if (guard.isProcessing()) return;

        Transaction transaction = event.getTransaction();
        if (!(transaction instanceof SlotTransaction slotTransaction)) return;
        if (!slotTransaction.succeeded()) return;

        ItemStack before = slotTransaction.getSlotBefore();
        ItemStack after = slotTransaction.getSlotAfter();
        short slot = slotTransaction.getSlot();
        ItemContainer container = event.getItemContainer();

        // Lazy cleanup of stale cache entries
        if (++eventCounter % CLEANUP_INTERVAL == 0) {
            purgeStaleEntries();
        }

        String cacheKey = getCacheKey(container, slot);

        // ── Phase 1: Cache enchantments from removed items ──
        if (hasItem(before) && !hasItem(after)) {
            BsonDocument enchBson = getEnchantmentBson(before);
            if (enchBson != null && !enchBson.isEmpty()) {
                cache.put(cacheKey, new CachedEnchantment(
                    before.getItemId(),
                    enchBson,
                    System.currentTimeMillis()
                ));
            }
            return;
        }

        // ── Phase 2: Restore enchantments to newly added items ──
        if (!hasItem(before) && hasItem(after)) {
            CachedEnchantment cached = cache.remove(cacheKey);
            if (cached == null) return;

            // Check expiry
            if (System.currentTimeMillis() - cached.timestamp > CACHE_EXPIRY_MS) return;

            // Skip if the new item already has enchantments
            BsonDocument afterBson = getEnchantmentBson(after);
            if (afterBson != null && !afterBson.isEmpty()) return;

            // Verify items are state-related
            if (!areStateVariants(cached.itemId, after.getItemId())) return;

            // Re-apply enchantments
            guard.runGuarded(() -> {
                ItemStack restoredItem = after.withMetadata(EnchantmentData.METADATA_KEY, cached.enchantmentBson);
                container.replaceItemStackInSlot(slot, after, restoredItem);
            });
            return;
        }

        // ── Direct replacement (SET with both before and after present) ──
        // Some interactions may use setItemStackForSlot directly, producing a single
        // event with both slotBefore and slotAfter populated.
        if (hasItem(before) && hasItem(after)
                && !before.getItemId().equals(after.getItemId())) {
            BsonDocument beforeBson = getEnchantmentBson(before);
            BsonDocument afterBson = getEnchantmentBson(after);

            if (beforeBson != null && !beforeBson.isEmpty()
                    && (afterBson == null || afterBson.isEmpty())
                    && areStateVariants(before.getItemId(), after.getItemId())) {

                guard.runGuarded(() -> {
                    ItemStack restoredItem = after.withMetadata(EnchantmentData.METADATA_KEY, beforeBson);
                    container.replaceItemStackInSlot(slot, after, restoredItem);
                });
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────────────────

    private static boolean hasItem(ItemStack item) {
        return item != null && !item.isEmpty();
    }

    /**
     * Reads the enchantment BSON document from an item's metadata, or null.
     */
    private static BsonDocument getEnchantmentBson(ItemStack item) {
        if (item == null || item.isEmpty()) return null;
        try {
            return item.getFromMetadataOrNull(
                EnchantmentData.METADATA_KEY,
                com.hypixel.hytale.codec.Codec.BSON_DOCUMENT
            );
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Checks whether two item IDs are state variants of each other.
     * Uses the Item's {@code getStateForItem()} / {@code blockToState} map.
     *
     * <p>Example: For Watering Can, the parent item "Tool_Watering_Can" has
     * a state "Filled_Water" that maps to the state variant item ID.
     * The reverse map (blockToState) maps the variant ID back to the state name.
     * {@code getStateForItem(otherItemId)} checks if otherItemId is a value
     * in this item's blockToState map.</p>
     */
    private static boolean areStateVariants(String oldItemId, String newItemId) {
        if (oldItemId == null || newItemId == null) return false;
        if (oldItemId.equals(newItemId)) return false;

        // Direction 1: Does oldItem's blockToState contain newItemId?
        Item oldItem = Item.getAssetMap().getAsset(oldItemId);
        if (oldItem != null) {
            String stateName = oldItem.getStateForItem(newItemId);
            if (stateName != null) return true;
        }

        // Direction 2: Does newItem's blockToState contain oldItemId?
        Item newItem = Item.getAssetMap().getAsset(newItemId);
        if (newItem != null) {
            String stateName = newItem.getStateForItem(oldItemId);
            if (stateName != null) return true;
        }

        return false;
    }

    /**
     * Creates a unique cache key for a (container, slot) pair.
     */
    private static String getCacheKey(ItemContainer container, short slot) {
        return System.identityHashCode(container) + ":" + slot;
    }

    /**
     * Removes cache entries older than {@link #CACHE_EXPIRY_MS}.
     */
    private void purgeStaleEntries() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, CachedEnchantment>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().timestamp > CACHE_EXPIRY_MS) {
                it.remove();
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  Inner record
    // ────────────────────────────────────────────────────────────────────

    /** Short-lived cache entry for enchantment data removed from a slot. */
    private record CachedEnchantment(String itemId, BsonDocument enchantmentBson, long timestamp) {}
}
