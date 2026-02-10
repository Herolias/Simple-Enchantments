package org.herolias.plugin.enchantment;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.entity.LivingEntityInventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import com.hypixel.hytale.server.core.inventory.transaction.SlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import org.herolias.plugin.util.ProcessingGuard;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Eternal Shot Enchantment System
 * 
 * Intercepts arrow/ammo consumption when shooting bows/crossbows and restores
 * the ammo if the weapon has the Eternal Shot enchantment.
 * 
 * Also prevents arrow duplication when swapping away from loaded crossbows
 * (both vanilla and modded) by tracking recent refunds and cancelling
 * swap-from ammo returns.
 * 
 * Tracks manual drops to avoid incorrectly refunding dropped arrows.
 */
public class EnchantmentEternalShotSystem extends AbstractRefundSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final EnchantmentManager enchantmentManager;
    private final ProcessingGuard guard = new ProcessingGuard();

    /**
     * Tracks recent Eternal Shot refunds per player to detect and cancel
     * swap-from arrow duplication on modded crossbows.
     * Key: Player UUID, Value: RefundRecord with timestamp and item info
     */
    private final Map<UUID, RefundRecord> recentRefunds = new ConcurrentHashMap<>();
    private static final long REFUND_TRACKING_WINDOW_MS = 2000; // 2 second window for swap-from detection

    private record RefundRecord(String itemId, long timestamp, short slot) {}

    public EnchantmentEternalShotSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentEternalShotSystem initialized");
    }

    public void onInventoryChange(@Nonnull LivingEntityInventoryChangeEvent event) {
        if (guard.isProcessing()) return;
        
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof Player player)) return;
        
        Transaction transaction = event.getTransaction();
        ItemContainer container = event.getItemContainer();
        
        cleanupOldDropRecords(player);
        cleanupOldRefundRecords();
        
        if (transaction instanceof ItemStackTransaction itemStackTransaction) {
            for (ItemStackSlotTransaction slotTx : itemStackTransaction.getSlotTransactions()) {
                processSlotTransaction(player, container, slotTx);
            }
        } else if (transaction instanceof SlotTransaction slotTransaction) {
            processSlotTransaction(player, container, slotTransaction);
        }
    }
    
    private void processSlotTransaction(Player player, ItemContainer container, SlotTransaction slotTransaction) {
        if (!slotTransaction.succeeded()) return;
        
        ItemStack slotBefore = slotTransaction.getSlotBefore();
        ItemStack slotAfter = slotTransaction.getSlotAfter();
        
        int beforeQty = (slotBefore == null || slotBefore.isEmpty()) ? 0 : slotBefore.getQuantity();
        int afterQty = (slotAfter == null || slotAfter.isEmpty()) ? 0 : slotAfter.getQuantity();
        short slot = slotTransaction.getSlot();

        if (beforeQty > afterQty) {
            // Ammo was CONSUMED (quantity decreased) - handle refund for Eternal Shot
            processAmmoConsumption(player, container, slotTransaction, slotBefore, slotAfter, beforeQty, afterQty, slot);
        } else if (afterQty > beforeQty) {
            // Ammo was ADDED (quantity increased) - check for swap-from duplication
            processAmmoAddition(player, container, slotTransaction, slotBefore, slotAfter, beforeQty, afterQty, slot);
        }
    }

    /**
     * Handles ammo consumption: if the player holds a ranged weapon with Eternal Shot,
     * refund the consumed ammo and record the refund for duplication prevention.
     */
    private void processAmmoConsumption(Player player, ItemContainer container, SlotTransaction slotTransaction,
                                         ItemStack slotBefore, ItemStack slotAfter,
                                         int beforeQty, int afterQty, short slot) {
        if (slotBefore == null || slotBefore.isEmpty()) return;
        if (!isAmmoItem(slotBefore)) return;
        
        if (wasRecentlyDropped(player, slot)) return;  // Manual drop, don't refund
        
        Inventory inventory = player.getInventory();
        if (inventory == null) return;
        
        ItemStack weapon = inventory.getItemInHand();
        if (weapon == null || weapon.isEmpty()) return;
        
        ItemCategory category = enchantmentManager.categorizeItem(weapon);
        if (category != ItemCategory.RANGED_WEAPON) return;
        
        int level = enchantmentManager.getEnchantmentLevel(weapon, EnchantmentType.ETERNAL_SHOT);
        if (level <= 0) return;

        // Refund the consumed ammo
        guard.runGuarded(() -> container.replaceItemStackInSlot(slot, slotAfter, slotBefore));

        // Track this refund for crossbow swap-from duplication prevention.
        // When a crossbow is loaded, ammo is consumed and we refund it. If the player
        // then swaps away, the crossbow's swap-from interaction tries to return the ammo
        // again (since the crossbow thinks it still has loaded ammo). We need to eat
        // those returned arrows to prevent duplication.
        if (enchantmentManager.isCrossbow(weapon)) {
            recentRefunds.put(player.getUuid(),
                new RefundRecord(slotBefore.getItemId(), System.currentTimeMillis(), slot));
        }
    }

    /**
     * Handles ammo being added back to inventory: detects swap-from duplication
     * on modded crossbows and cancels the addition by consuming the returned arrows.
     * 
     * This fixes the duplication exploit where:
     * 1. Player loads a modded crossbow (ammo consumed -> refunded by Eternal Shot)
     * 2. Player swaps away from crossbow (swap-from returns ammo -> duplication!)
     */
    private void processAmmoAddition(Player player, ItemContainer container, SlotTransaction slotTransaction,
                                      ItemStack slotBefore, ItemStack slotAfter,
                                      int beforeQty, int afterQty, short slot) {
        if (slotAfter == null || slotAfter.isEmpty()) return;
        if (!isAmmoItem(slotAfter)) return;

        UUID playerUuid = player.getUuid();
        RefundRecord record = recentRefunds.get(playerUuid);
        if (record == null) return;

        long elapsed = System.currentTimeMillis() - record.timestamp;
        if (elapsed > REFUND_TRACKING_WINDOW_MS) {
            recentRefunds.remove(playerUuid);
            return;
        }

        // Check if the added ammo matches what we recently refunded
        if (!record.itemId.equals(slotAfter.getItemId())) return;

        // The player is NOT currently holding the crossbow (they swapped away).
        // Verify the player is no longer holding a crossbow with Eternal Shot,
        // meaning this addition is from a swap-from interaction, not normal gameplay.
        Inventory inventory = player.getInventory();
        if (inventory == null) return;

        ItemStack currentWeapon = inventory.getItemInHand();
        boolean stillHoldingEternalCrossbow = currentWeapon != null && !currentWeapon.isEmpty()
            && enchantmentManager.isCrossbow(currentWeapon)
            && enchantmentManager.getEnchantmentLevel(currentWeapon, EnchantmentType.ETERNAL_SHOT) > 0;

        if (stillHoldingEternalCrossbow) {
            // Player is still holding the crossbow - this is a normal reload, not a swap-from
            return;
        }

        // This is a swap-from returning arrows that we already refunded -> cancel the duplication
        int addedQty = afterQty - beforeQty;
        LOGGER.atFine().log("Cancelling swap-from arrow duplication for " + player.getUuid() + 
            " (" + addedQty + "x " + slotAfter.getItemId() + ")");

        // Restore the slot to its state before the arrows were added back
        guard.runGuarded(() -> container.replaceItemStackInSlot(slot, slotAfter, slotBefore));

        // Clear the refund record since we handled it
        recentRefunds.remove(playerUuid);
    }

    /**
     * Checks if an item is ammunition (arrows, bolts, or other projectile ammo).
     * Broadened to support modded projectile types beyond just "arrow" and "bolt".
     */
    private boolean isAmmoItem(@Nonnull ItemStack itemStack) {
        String itemId = itemStack.getItemId();
        if (itemId == null) return false;

        String lower = itemId.toLowerCase();

        // Direct name matching for common ammo naming conventions
        if (lower.contains("arrow") || lower.contains("bolt") || lower.contains("ammo") || lower.contains("ammunition")) {
            return true;
        }

        // Check item tags for ammunition classification
        try {
            Item item = itemStack.getItem();
            if (item != null && item.getData() != null) {
                String[] typeValues = item.getData().getRawTags().get("Type");
                if (typeValues != null) {
                    for (String tag : typeValues) {
                        String tagLower = tag.toLowerCase();
                        if (tagLower.contains("arrow") || tagLower.contains("bolt") || tagLower.contains("ammo") || tagLower.contains("ammunition") || tagLower.contains("projectile")) {
                            return true;
                        }
                    }
                }
                String[] familyValues = item.getData().getRawTags().get("Family");
                if (familyValues != null) {
                    for (String tag : familyValues) {
                        String tagLower = tag.toLowerCase();
                        if (tagLower.contains("arrow") || tagLower.contains("ammo") || tagLower.contains("ammunition") || tagLower.contains("projectile")) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Fallback to name-based check only
        }

        return false;
    }

    /**
     * Removes expired refund records.
     */
    private void cleanupOldRefundRecords() {
        long now = System.currentTimeMillis();
        recentRefunds.entrySet().removeIf(entry -> 
            now - entry.getValue().timestamp > REFUND_TRACKING_WINDOW_MS);
    }
}
