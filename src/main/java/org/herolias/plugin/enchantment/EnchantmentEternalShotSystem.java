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
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Eternal Shot Enchantment System
 *
 * Intercepts arrow/ammo consumption when shooting bows/crossbows and restores
 * the ammo if the weapon has the Eternal Shot enchantment.
 *
 * Also prevents arrow duplication when swapping away from loaded crossbows.
 * Uses a "retroactive removal" strategy:
 *   1. Let the vanilla swap-from refund through, but mark it as "pending verification"
 *   2. When the slot tracker confirms the player switched away from an
 *      Eternal Shot crossbow, retroactively remove the arrows from the exact
 *      slot(s) and container(s) where they were added
 *   3. If no slot switch is detected (it was a legitimate pickup), do nothing
 */
public class EnchantmentEternalShotSystem extends AbstractRefundSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final EnchantmentManager enchantmentManager;
    private final ProcessingGuard guard = new ProcessingGuard();

    /**
     * Max time (ms) between an arrow addition and the slot tracker detecting
     * the slot change. The slot tracker polls every 50ms, so 300ms is generous.
     */
    private static final long SWAP_VERIFY_WINDOW_MS = 300;

    /**
     * Tracks the ammo type and total quantity refunded by Eternal Shot when
     * loading a crossbow. Accumulates across multiple consumption events.
     */
    private static class LoadedAmmoRecord {
        final String ammoId;
        int totalQtyRefunded;

        LoadedAmmoRecord(String ammoId, int qty) {
            this.ammoId = ammoId;
            this.totalQtyRefunded = qty;
        }
    }

    /**
     * Records a single pending arrow addition that needs to be verified
     * against a slot switch. Stores the exact container and slot so we
     * can remove from the correct location.
     */
    private static class PendingAddition {
        final long timestamp;
        final ItemContainer container;
        final short slot;
        final int addedQty;

        PendingAddition(long timestamp, ItemContainer container, short slot, int addedQty) {
            this.timestamp = timestamp;
            this.container = container;
            this.slot = slot;
            this.addedQty = addedQty;
        }
    }

    private final Map<UUID, LoadedAmmoRecord> loadedCrossbowAmmo = new ConcurrentHashMap<>();
    private final Map<UUID, List<PendingAddition>> pendingSwapVerifications = new ConcurrentHashMap<>();

    public EnchantmentEternalShotSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentEternalShotSystem initialized");
    }

    public void onInventoryChange(@Nonnull LivingEntityInventoryChangeEvent event) {
        if (guard.isProcessing()) return;

        LivingEntity entity = event.getEntity();
        if (!(entity instanceof Player player)) return;

        if (player.getWorld() == null || player.getReference() == null) return;
        com.hypixel.hytale.server.core.entity.UUIDComponent uComp = player.getWorld().getEntityStore().getStore().getComponent(player.getReference(), com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
        if (uComp != null) {
            cleanupOldDropRecords(uComp.getUuid());
        }

        Transaction transaction = event.getTransaction();
        ItemContainer container = event.getItemContainer();

        if (transaction instanceof ItemStackTransaction itemStackTransaction) {
            for (ItemStackSlotTransaction slotTx : itemStackTransaction.getSlotTransactions()) {
                processSlotTransaction(player, container, slotTx, itemStackTransaction);
            }
        } else if (transaction instanceof SlotTransaction slotTransaction) {
            processSlotTransaction(player, container, slotTransaction, null);
        }
    }

    /**
     * Called by {@link EnchantmentSlotTracker} when a player's active hotbar slot changes.
     * If the PREVIOUS slot held an Eternal Shot crossbow and we recently let arrow
     * additions through, this confirms they were swap-from refunds → remove them.
     *
     * @param player       the player who switched slots
     * @param previousItem the item in the PREVIOUS slot (before the switch)
     */
    public void onSlotChanged(Player player, @Nullable ItemStack previousItem) {
        if (player.getWorld() == null || player.getReference() == null) return;
        com.hypixel.hytale.server.core.entity.UUIDComponent uComp = player.getWorld().getEntityStore().getStore().getComponent(player.getReference(), com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
        if (uComp == null) return;
        UUID uuid = uComp.getUuid();

        // Only relevant if we're tracking a loaded crossbow for this player
        LoadedAmmoRecord record = loadedCrossbowAmmo.get(uuid);
        if (record == null) return;

        // Check if the PREVIOUS item was an Eternal Shot crossbow
        boolean wasCrossbow = previousItem != null && !previousItem.isEmpty()
                && enchantmentManager.isCrossbow(previousItem)
                && enchantmentManager.getEnchantmentLevel(previousItem, EnchantmentType.ETERNAL_SHOT) > 0;

        if (!wasCrossbow) {
            // Previous item wasn't an Eternal Shot crossbow → stale record
            loadedCrossbowAmmo.remove(uuid);
            pendingSwapVerifications.remove(uuid);
            return;
        }

        // Previous item WAS an Eternal Shot crossbow.
        // Check if we recently let arrow additions through (pending verification).
        List<PendingAddition> pendings = pendingSwapVerifications.remove(uuid);
        if (pendings != null && !pendings.isEmpty()) {
            long now = System.currentTimeMillis();
            int totalRemoved = 0;
            for (PendingAddition pending : pendings) {
                if ((now - pending.timestamp) < SWAP_VERIFY_WINDOW_MS) {
                    // Remove from the exact container and slot where the arrows were added
                    if (removeFromExactSlot(pending.container, record.ammoId, pending.slot, pending.addedQty)) {
                        totalRemoved += pending.addedQty;
                    }
                }
            }
        } else {
            // No pending verification → crossbow was fired before switching
        }

        // Clear the loaded record for this cycle
        loadedCrossbowAmmo.remove(uuid);
    }

    /**
     * Removes a specific number of arrows from the exact container and slot
     * where the vanilla refund deposited them.
     */
    private boolean removeFromExactSlot(ItemContainer container, String ammoId, short slot, int qtyToRemove) {
        if (container == null || slot < 0 || slot >= container.getCapacity()) return false;

        ItemStack stack = container.getItemStack(slot);
        if (stack == null || stack.isEmpty() || !ammoId.equals(stack.getItemId())) return false;

        int newQty = stack.getQuantity() - qtyToRemove;
        if (newQty > 0) {
            ItemStack reduced = stack.withQuantity(newQty);
            guard.runGuarded(() -> container.replaceItemStackInSlot(slot, stack, reduced));
        } else {
            guard.runGuarded(() -> container.replaceItemStackInSlot(slot, stack, ItemStack.EMPTY));
        }
        return true;
    }

    private void processSlotTransaction(Player player, ItemContainer container, SlotTransaction slotTransaction, @Nullable ItemStackTransaction parentTransaction) {
        if (!slotTransaction.succeeded()) return;

        ItemStack slotBefore = slotTransaction.getSlotBefore();
        ItemStack slotAfter = slotTransaction.getSlotAfter();

        int beforeQty = (slotBefore == null || slotBefore.isEmpty()) ? 0 : slotBefore.getQuantity();
        int afterQty = (slotAfter == null || slotAfter.isEmpty()) ? 0 : slotAfter.getQuantity();
        short slot = slotTransaction.getSlot();

        if (beforeQty > afterQty) {
            processAmmoConsumption(player, container, slotTransaction, slotBefore, slotAfter, beforeQty, afterQty, slot);
        } else if (afterQty > beforeQty) {
            processAmmoAddition(player, container, slotTransaction, slotBefore, slotAfter, beforeQty, afterQty, slot, parentTransaction);
        }
    }

    /**
     * Handles ammo consumption: if the player holds a ranged weapon with Eternal Shot,
     * refund the consumed ammo and accumulate the refund record.
     */
    private void processAmmoConsumption(Player player, ItemContainer container, SlotTransaction slotTransaction,
                                         ItemStack slotBefore, ItemStack slotAfter,
                                         int beforeQty, int afterQty, short slot) {
        if (slotBefore == null || slotBefore.isEmpty()) return;
        if (!isAmmoItem(slotBefore)) return;

        if (player.getWorld() == null || player.getReference() == null) return;
        com.hypixel.hytale.server.core.entity.UUIDComponent uuidComp = player.getWorld().getEntityStore().getStore().getComponent(player.getReference(), com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
        if (uuidComp == null) return;
        UUID playerUuid = uuidComp.getUuid();

        if (wasRecentlyDropped(playerUuid, slot)) return;

        Inventory inventory = player.getInventory();
        if (inventory == null) return;

        ItemStack weapon = inventory.getItemInHand();
        if (weapon == null || weapon.isEmpty()) return;

        ItemCategory category = enchantmentManager.categorizeItem(weapon);
        if (category != ItemCategory.RANGED_WEAPON) return;

        int level = enchantmentManager.getEnchantmentLevel(weapon, EnchantmentType.ETERNAL_SHOT);
        if (level <= 0) return;

        guard.runGuarded(() -> container.replaceItemStackInSlot(slot, slotAfter, slotBefore));

        // Track this refund for crossbow swap-from duplication prevention.
        if (enchantmentManager.isCrossbow(weapon)) {
            int consumed = beforeQty - afterQty;
            if (player.getWorld() == null || player.getReference() == null) return;
            com.hypixel.hytale.server.core.entity.UUIDComponent uuidComp2 = player.getWorld().getEntityStore().getStore().getComponent(player.getReference(), com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
            if (uuidComp2 != null) {
                UUID uuid = uuidComp2.getUuid();

                // Accumulate refund quantity (crossbow may load multiple arrows)
                LoadedAmmoRecord existing = loadedCrossbowAmmo.get(uuid);
                if (existing != null && existing.ammoId.equals(slotBefore.getItemId())) {
                    existing.totalQtyRefunded += consumed;
                } else {
                    loadedCrossbowAmmo.put(uuid, new LoadedAmmoRecord(slotBefore.getItemId(), consumed));
                }

                // Clear any stale pending verifications from a previous cycle
                pendingSwapVerifications.remove(uuid);
            }
        }
    }

    /**
     * Handles ammo being added to inventory. If we have a loaded crossbow record
     * and the arrow type matches, mark it as pending verification with the exact
     * slot and container info for precise retroactive removal.
     */
    private void processAmmoAddition(Player player, ItemContainer container, SlotTransaction slotTransaction,
                                      ItemStack slotBefore, ItemStack slotAfter,
                                      int beforeQty, int afterQty, short slot,
                                      @Nullable ItemStackTransaction parentTransaction) {
        if (slotAfter == null || slotAfter.isEmpty()) return;
        if (!isAmmoItem(slotAfter)) return;

        if (player.getWorld() == null || player.getReference() == null) return;
        com.hypixel.hytale.server.core.entity.UUIDComponent uuidComp = player.getWorld().getEntityStore().getStore().getComponent(player.getReference(), com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
        if (uuidComp == null) return;
        UUID playerUuid = uuidComp.getUuid();
        
        LoadedAmmoRecord record = loadedCrossbowAmmo.get(playerUuid);

        // If we haven't recorded a refund for this player, ignore.
        if (record == null) return;

        // Check if the added ammo matches what we refunded
        if (!record.ammoId.equals(slotAfter.getItemId())) return;

        int addedQty = afterQty - beforeQty;

        // Record the exact container and slot for precise retroactive removal
        pendingSwapVerifications
            .computeIfAbsent(playerUuid, k -> new ArrayList<>())
            .add(new PendingAddition(System.currentTimeMillis(), container, slot, addedQty));
    }

    /**
     * Checks if an item is ammunition.
     */
    private boolean isAmmoItem(@Nonnull ItemStack itemStack) {
        String itemId = itemStack.getItemId();
        if (itemId == null) return false;

        String lower = itemId.toLowerCase();

        if (lower.contains("arrow") || lower.contains("bolt") || lower.contains("ammo") || lower.contains("ammunition")) {
            return true;
        }

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
            // Fallback
        }

        return false;
    }
}
