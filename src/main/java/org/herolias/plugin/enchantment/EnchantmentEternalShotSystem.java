package org.herolias.plugin.enchantment;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.InventoryChangeEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import com.hypixel.hytale.server.core.inventory.transaction.SlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MoveTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MoveType;
import com.hypixel.hytale.server.core.inventory.transaction.ListTransaction;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
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
 * 1. Let the vanilla swap-from refund through, but mark it as "pending
 * verification"
 * 2. When the slot tracker confirms the player switched away from an
 * Eternal Shot crossbow, retroactively remove the arrows from the exact
 * slot(s) and container(s) where they were added
 * 3. If no slot switch is detected (it was a legitimate pickup), do nothing
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
    private static final long PRELOAD_KICKSTART_WINDOW_MS = 1500;
    private static final long IN_PLACE_ROLLBACK_WINDOW_MS = 800;

    /**
     * Tracks the ammo type and total quantity refunded by Eternal Shot when
     * loading a crossbow. Accumulates across multiple consumption events.
     */
    private static class LoadedAmmoRecord {
        final String ammoId;
        final String weaponId;
        long lastRefundTimestamp;

        LoadedAmmoRecord(String ammoId, String weaponId, long lastRefundTimestamp) {
            this.ammoId = ammoId;
            this.weaponId = weaponId;
            this.lastRefundTimestamp = lastRefundTimestamp;
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

    /**
     * Some reloadable modded weapons fire an initial single-ammo consume before the
     * actual multi-ammo reload transaction. Eternal Shot refunds that first remove,
     * then refunds the real reload as well, creating a +1 dupe. We keep the first
     * refund as "suspect" for a short window and retroactively remove it if a
     * follow-up ammo consume arrives for the same weapon/ammo pair.
     */
    private static class SuspectPreloadRefund {
        final long timestamp;
        final String weaponId;
        final String ammoId;
        final ItemContainer container;
        final short slot;
        final int qty;

        SuspectPreloadRefund(long timestamp, String weaponId, String ammoId, ItemContainer container, short slot,
                int qty) {
            this.timestamp = timestamp;
            this.weaponId = weaponId;
            this.ammoId = ammoId;
            this.container = container;
            this.slot = slot;
            this.qty = qty;
        }
    }

    private static class SuspectReconciliationResult {
        final ItemStack adjustedSlotBefore;

        SuspectReconciliationResult(ItemStack adjustedSlotBefore) {
            this.adjustedSlotBefore = adjustedSlotBefore;
        }
    }

    /**
     * When a loaded Eternal Shot weapon leaves the active slot, the vanilla unload
     * refund can be emitted either in the same inventory change or a short moment
     * later. Keep a tiny confirmation window so that delayed add-backs are still
     * recognized as unload rollbacks instead of legitimate pickups.
     */
    private static class ConfirmedUnloadRecord {
        final String ammoId;
        final long timestamp;
        int unresolvedPendingQty;

        ConfirmedUnloadRecord(String ammoId, long timestamp) {
            this.ammoId = ammoId;
            this.timestamp = timestamp;
            this.unresolvedPendingQty = 0;
        }
    }

    private final Map<UUID, LoadedAmmoRecord> loadedCrossbowAmmo = new ConcurrentHashMap<>();
    private final Map<UUID, List<PendingAddition>> pendingSwapVerifications = new ConcurrentHashMap<>();
    private final Map<UUID, SuspectPreloadRefund> suspectPreloadRefunds = new ConcurrentHashMap<>();
    private final Map<UUID, ConfirmedUnloadRecord> confirmedUnloads = new ConcurrentHashMap<>();

    public EnchantmentEternalShotSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentEternalShotSystem initialized");
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk, @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull InventoryChangeEvent event) {
        LivingEntity entity = (LivingEntity) EntityUtils.getEntity(index, archetypeChunk);
        if (!(entity instanceof Player player))
            return;

        if (player.getWorld() != null && !player.getWorld().isInThread()) {
            player.getWorld().execute(() -> handle(index, archetypeChunk, store, commandBuffer, event));
            return;
        }

        if (guard.isProcessing())
            return;

        if (player.getWorld() == null || player.getReference() == null)
            return;
        com.hypixel.hytale.server.core.entity.UUIDComponent uComp = player.getWorld().getEntityStore().getStore()
                .getComponent(player.getReference(),
                        com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
        if (uComp != null) {
            cleanupOldDropRecords(uComp.getUuid());
            cleanupOldSuspectRefund(uComp.getUuid());
            cleanupOldConfirmedUnload(uComp.getUuid());
        }

        Transaction transaction = event.getTransaction();
        ItemContainer container = event.getItemContainer();

        if (transaction instanceof MoveTransaction<?> moveTx) {
            processMoveTransaction(player, container, moveTx);
            return;
        }

        if (transaction instanceof ListTransaction<?> lt && !lt.getList().isEmpty()
                && lt.getList().stream().allMatch(t -> t instanceof MoveTransaction)) {
            for (Object inner : lt.getList()) {
                processMoveTransaction(player, container, (MoveTransaction<?>) inner);
            }
            return;
        }

        // Detect crossbow being drag-dropped out of the active hotbar slot via non-move
        // operations
        // (e.g. shift-click produces ItemStackTransaction, direct slot ops produce
        // SlotTransaction).
        if (transaction instanceof ItemStackTransaction itemStackTransaction) {
            checkForCrossbowDraggedFromHotbar(player, container, itemStackTransaction);
            for (ItemStackSlotTransaction slotTx : itemStackTransaction.getSlotTransactions()) {
                processSlotTransaction(player, container, slotTx, itemStackTransaction);
            }
        } else if (transaction instanceof SlotTransaction slotTransaction) {
            checkForCrossbowDraggedFromHotbar(player, container, slotTransaction);
            processSlotTransaction(player, container, slotTransaction, null);
        }
    }

    private void processMoveTransaction(Player player, ItemContainer container, MoveTransaction<?> moveTx) {
        if (!moveTx.succeeded())
            return;

        if (moveTx.getMoveType() == MoveType.MOVE_FROM_SELF) {
            checkForCrossbowDraggedFromHotbar(player, container, moveTx.getRemoveTransaction());
            rollbackEmbeddedSwapRefund(player, container, moveTx);
            return;
        }

        processCurrentContainerTransaction(player, container, moveTx.getAddTransaction());
    }

    private void processCurrentContainerTransaction(Player player, ItemContainer container,
            @Nullable Transaction transaction) {
        if (transaction == null || !transaction.succeeded())
            return;

        if (transaction instanceof ItemStackTransaction itemStackTransaction) {
            for (ItemStackSlotTransaction slotTx : itemStackTransaction.getSlotTransactions()) {
                processSlotTransaction(player, container, slotTx, itemStackTransaction);
            }
            return;
        }

        if (transaction instanceof SlotTransaction slotTransaction) {
            processSlotTransaction(player, container, slotTransaction, null);
        }
    }

    private void rollbackEmbeddedSwapRefund(Player player, ItemContainer container, MoveTransaction<?> moveTx) {
        if (!(moveTx.getAddTransaction() instanceof SlotTransaction addTransaction))
            return;

        Inventory inventory = player.getInventory();
        if (inventory == null)
            return;

        SlotTransaction removeTransaction = moveTx.getRemoveTransaction();
        ItemContainer hotbar = inventory.getHotbar();
        int activeSlot = inventory.getActiveHotbarSlot();
        if (hotbar == null || container != hotbar || activeSlot < 0
                || !removeTransaction.wasSlotModified((short) activeSlot)) {
            return;
        }

        ItemStack movedOut = removeTransaction.getSlotBefore();
        if (movedOut == null || movedOut.isEmpty())
            return;
        if (!shouldTrackLoadedAmmo(movedOut))
            return;
        if (enchantmentManager.getEnchantmentLevel(movedOut, EnchantmentType.ETERNAL_SHOT) <= 0)
            return;

        ItemStack swappedInBefore = addTransaction.getSlotBefore();
        ItemStack slotAfter = removeTransaction.getSlotAfter();
        if (swappedInBefore == null || swappedInBefore.isEmpty() || slotAfter == null || slotAfter.isEmpty())
            return;
        if (!sameItemId(swappedInBefore, slotAfter) || !isAmmoItem(slotAfter))
            return;

        UUID playerUuid = getPlayerUuid(player);
        if (playerUuid == null)
            return;

        ConfirmedUnloadRecord unload = confirmedUnloads.get(playerUuid);
        if (unload == null || !unload.ammoId.equals(slotAfter.getItemId()))
            return;

        int refundedQty = slotAfter.getQuantity() - swappedInBefore.getQuantity();
        int qtyToRollback = refundedQty + Math.max(0, unload.unresolvedPendingQty);
        if (qtyToRollback <= 0)
            return;

        if (removeFromExactSlot(container, unload.ammoId, removeTransaction.getSlot(), qtyToRollback)) {
            unload.unresolvedPendingQty = 0;
            confirmedUnloads.remove(playerUuid);
        }
    }

    /**
     * Checks whether this inventory change moved/replaced an Eternal Shot crossbow
     * out of the player's active hotbar slot (e.g. by drag-and-drop).
     * If so, runs the same cleanup that {@link #onSlotChanged} would.
     */
    private void checkForCrossbowDraggedFromHotbar(Player player, ItemContainer container, SlotTransaction tx) {
        Inventory inv = player.getInventory();
        if (inv == null)
            return;

        // Only care about changes to the hotbar container
        ItemContainer hotbar = inv.getHotbar();
        if (hotbar == null || container != hotbar)
            return;

        int activeSlot = inv.getActiveHotbarSlot();
        if (activeSlot < 0)
            return;

        // Is the transaction touching the active hotbar slot?
        if (!tx.wasSlotModified((short) activeSlot))
            return;

        ItemStack before = tx.getSlotBefore();
        if (before == null || before.isEmpty())
            return;

        // Was the item there a reloadable Eternal Shot weapon?
        if (!shouldTrackLoadedAmmo(before))
            return;
        if (enchantmentManager.getEnchantmentLevel(before, EnchantmentType.ETERNAL_SHOT) <= 0)
            return;

        // Yes — treat this exactly like a hotbar slot switch away from the crossbow.
        onSlotChanged(player, before);
    }

    /**
     * Overload for ItemStackTransaction (which wraps multiple slot transactions).
     * Checks if any of the inner slot transactions match the active hotbar slot.
     */
    private void checkForCrossbowDraggedFromHotbar(Player player, ItemContainer container, ItemStackTransaction tx) {
        for (ItemStackSlotTransaction slotTx : tx.getSlotTransactions()) {
            checkForCrossbowDraggedFromHotbar(player, container, (SlotTransaction) slotTx);
        }
    }

    /**
     * Called by {@link EnchantmentSlotTracker} when a player's active hotbar slot
     * changes.
     * If the PREVIOUS slot held an Eternal Shot crossbow and we recently let arrow
     * additions through, this confirms they were swap-from refunds → remove them.
     *
     * @param player       the player who switched slots
     * @param previousItem the item in the PREVIOUS slot (before the switch)
     */
    public void onSlotChanged(Player player, @Nullable ItemStack previousItem) {
        if (player.getWorld() == null || player.getReference() == null)
            return;
        com.hypixel.hytale.server.core.entity.UUIDComponent uComp = player.getWorld().getEntityStore().getStore()
                .getComponent(player.getReference(),
                        com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
        if (uComp == null)
            return;
        UUID uuid = uComp.getUuid();

        // Only relevant if we're tracking a loaded crossbow for this player
        LoadedAmmoRecord record = loadedCrossbowAmmo.get(uuid);
        if (record == null)
            return;

        // Check if the PREVIOUS item was a reloadable Eternal Shot weapon
        boolean wasReloadableWeapon = previousItem != null && !previousItem.isEmpty()
                && shouldTrackLoadedAmmo(previousItem)
                && enchantmentManager.getEnchantmentLevel(previousItem, EnchantmentType.ETERNAL_SHOT) > 0;

        if (!wasReloadableWeapon) {
            // Previous item wasn't a reloadable Eternal Shot weapon → stale record
            loadedCrossbowAmmo.remove(uuid);
            pendingSwapVerifications.remove(uuid);
            suspectPreloadRefunds.remove(uuid);
            return;
        }

        // Previous item WAS an Eternal Shot crossbow.
        ConfirmedUnloadRecord confirmedUnload = new ConfirmedUnloadRecord(
                record.ammoId,
                System.currentTimeMillis());
        confirmedUnloads.put(uuid, confirmedUnload);

        // Check if we recently let arrow additions through (pending verification).
        List<PendingAddition> pendings = pendingSwapVerifications.remove(uuid);
        if (pendings != null && !pendings.isEmpty()) {
            long now = System.currentTimeMillis();
            for (PendingAddition pending : pendings) {
                if ((now - pending.timestamp) < SWAP_VERIFY_WINDOW_MS) {
                    // Remove from the exact container and slot where the arrows were added
                    boolean removed = removeFromExactSlot(pending.container, record.ammoId, pending.slot,
                            pending.addedQty);
                    if (!removed) {
                        confirmedUnload.unresolvedPendingQty += pending.addedQty;
                    }
                }
            }
        }

        // Clear the loaded record for this cycle
        loadedCrossbowAmmo.remove(uuid);
        suspectPreloadRefunds.remove(uuid);
    }

    /**
     * Removes a specific number of arrows from the exact container and slot
     * where the vanilla refund deposited them.
     */
    private boolean removeFromExactSlot(ItemContainer container, String ammoId, short slot, int qtyToRemove) {
        if (container == null || slot < 0 || slot >= container.getCapacity())
            return false;

        ItemStack stack = container.getItemStack(slot);
        if (stack == null || stack.isEmpty() || !ammoId.equals(stack.getItemId())) {
            return false;
        }

        int newQty = stack.getQuantity() - qtyToRemove;
        if (newQty > 0) {
            ItemStack reduced = stack.withQuantity(newQty);
            guard.runGuarded(() -> container.replaceItemStackInSlot(slot, stack, reduced));
        } else {
            guard.runGuarded(() -> container.replaceItemStackInSlot(slot, stack, ItemStack.EMPTY));
        }
        return true;
    }

    private void processSlotTransaction(Player player, ItemContainer container, SlotTransaction slotTransaction,
            @Nullable ItemStackTransaction parentTransaction) {
        if (!slotTransaction.succeeded())
            return;

        ItemStack slotBefore = slotTransaction.getSlotBefore();
        ItemStack slotAfter = slotTransaction.getSlotAfter();

        int beforeQty = (slotBefore == null || slotBefore.isEmpty()) ? 0 : slotBefore.getQuantity();
        int afterQty = (slotAfter == null || slotAfter.isEmpty()) ? 0 : slotAfter.getQuantity();
        short slot = slotTransaction.getSlot();

        boolean sameStackType = sameItemId(slotBefore, slotAfter);
        boolean pureRemoval = beforeQty > afterQty && (afterQty == 0 || sameStackType);
        boolean pureAddition = afterQty > beforeQty && (beforeQty == 0 || sameStackType);

        if (pureRemoval) {
            processAmmoConsumption(player, container, slotTransaction, slotBefore, slotAfter, beforeQty, afterQty, slot,
                    parentTransaction);
        } else if (pureAddition) {
            processAmmoAddition(player, container, slotTransaction, slotBefore, slotAfter, beforeQty, afterQty, slot,
                    parentTransaction);
        }
    }

    private boolean sameItemId(@Nullable ItemStack first, @Nullable ItemStack second) {
        if (first == null || first.isEmpty() || second == null || second.isEmpty()) {
            return false;
        }
        String firstId = first.getItemId();
        String secondId = second.getItemId();
        return firstId != null && firstId.equals(secondId);
    }

    @Nullable
    private UUID getPlayerUuid(@Nonnull Player player) {
        if (player.getWorld() == null || player.getReference() == null)
            return null;

        com.hypixel.hytale.server.core.entity.UUIDComponent uuidComp = player.getWorld().getEntityStore().getStore()
                .getComponent(
                        player.getReference(),
                        com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
        return uuidComp != null ? uuidComp.getUuid() : null;
    }

    /**
     * Handles ammo consumption: if the player holds a ranged weapon with Eternal
     * Shot,
     * refund the consumed ammo and accumulate the refund record.
     */
    private void processAmmoConsumption(Player player, ItemContainer container, SlotTransaction slotTransaction,
            ItemStack slotBefore, ItemStack slotAfter,
            int beforeQty, int afterQty, short slot,
            @Nullable ItemStackTransaction parentTransaction) {
        if (slotBefore == null || slotBefore.isEmpty())
            return;
        if (!isAmmoItem(slotBefore))
            return;

        if (parentTransaction == null || parentTransaction.getQuery() == null || !parentTransaction.isAllOrNothing()) {
            return;
        }

        if (player.getWorld() == null || player.getReference() == null)
            return;
        com.hypixel.hytale.server.core.entity.UUIDComponent uuidComp = player.getWorld().getEntityStore().getStore()
                .getComponent(player.getReference(),
                        com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
        if (uuidComp == null)
            return;
        UUID playerUuid = uuidComp.getUuid();

        if (wasRecentlyDropped(playerUuid, slot)) {
            return;
        }

        Inventory inventory = player.getInventory();
        if (inventory == null)
            return;

        ItemStack weapon = inventory.getItemInHand();
        if (weapon == null || weapon.isEmpty())
            return;

        ItemCategory category = enchantmentManager.categorizeItem(weapon);
        if (category != ItemCategory.RANGED_WEAPON)
            return;

        int level = enchantmentManager.getEnchantmentLevel(weapon, EnchantmentType.ETERNAL_SHOT);
        if (level <= 0)
            return;

        String trackingReason = getLoadedAmmoTrackingReason(weapon);
        SuspectReconciliationResult suspectResult = reconcileSuspectPreloadRefund(
                playerUuid,
                weapon,
                slotBefore,
                container,
                slot,
                beforeQty,
                afterQty,
                trackingReason);
        ItemStack refundSlotBefore = suspectResult.adjustedSlotBefore;
        int refundedQty = (refundSlotBefore == null || refundSlotBefore.isEmpty()) ? 0
                : refundSlotBefore.getQuantity() - afterQty;

        guard.runGuarded(() -> container.replaceItemStackInSlot(slot, slotAfter, refundSlotBefore));

        if (player.getWorld() != null && player.getReference() != null) {
            com.hypixel.hytale.server.core.universe.PlayerRef playerRef = player.getWorld().getEntityStore().getStore()
                    .getComponent(player.getReference(),
                            com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
            EnchantmentEventHelper.fireActivated(playerRef, weapon, EnchantmentType.ETERNAL_SHOT, level);
        }

        // Only weapons that keep a loaded round/projectile need the later swap/unload
        // cleanup.
        if (trackingReason != null) {
            int consumed = refundedQty;
            if (player.getWorld() == null || player.getReference() == null)
                return;
            com.hypixel.hytale.server.core.entity.UUIDComponent uuidComp2 = player.getWorld().getEntityStore()
                    .getStore().getComponent(player.getReference(),
                            com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
            if (uuidComp2 != null) {
                UUID uuid = uuidComp2.getUuid();

                // Accumulate refund quantity (crossbow may load multiple arrows)
                if (consumed > 0) {
                    LoadedAmmoRecord existing = loadedCrossbowAmmo.get(uuid);
                    long now = System.currentTimeMillis();
                    if (existing != null
                            && existing.ammoId.equals(slotBefore.getItemId())
                            && existing.weaponId.equals(weapon.getItemId())) {
                        existing.lastRefundTimestamp = now;
                    } else {
                        loadedCrossbowAmmo.put(uuid,
                                new LoadedAmmoRecord(slotBefore.getItemId(), weapon.getItemId(), now));
                    }
                }

                // Clear any stale pending verifications from a previous cycle
                pendingSwapVerifications.remove(uuid);
            }
        }

        maybeRecordSuspectPreloadRefund(playerUuid, weapon, slotBefore, container, slot, beforeQty - afterQty,
                trackingReason);
    }

    /**
     * Determines whether a weapon can hold/refund loaded ammo separately from the
     * current inventory stack. Those weapons need the extra anti-duplication
     * bookkeeping when Eternal Shot refunds their load cost.
     */
    private boolean shouldTrackLoadedAmmo(@Nullable ItemStack weapon) {
        return getLoadedAmmoTrackingReason(weapon) != null;
    }

    @Nullable
    private String getLoadedAmmoTrackingReason(@Nullable ItemStack weapon) {
        if (weapon == null || weapon.isEmpty())
            return null;

        if (enchantmentManager.isCrossbow(weapon)) {
            return "crossbow-id";
        }

        if (weapon.getFromMetadataOrNull("LoadedAmmoId", Codec.STRING) != null) {
            return "loaded-ammo-metadata";
        }

        try {
            Item item = weapon.getItem();
            if (item == null) {
                return null;
            }

            com.hypixel.hytale.server.core.asset.type.item.config.ItemWeapon itemWeapon = item.getWeapon();
            if (itemWeapon != null) {
                int ammoStatId = DefaultEntityStatTypes.getAmmo();
                if (ammoStatId != Integer.MIN_VALUE) {
                    if (itemWeapon.getStatModifiers() != null
                            && itemWeapon.getStatModifiers().containsKey(ammoStatId)) {
                        return "weapon-ammo-stat-modifier";
                    }

                    int[] entityStatsToClear = itemWeapon.getEntityStatsToClear();
                    if (entityStatsToClear != null) {
                        for (int statId : entityStatsToClear) {
                            if (statId == ammoStatId) {
                                return "weapon-clears-ammo-stat";
                            }
                        }
                    }
                }
            }

            if (item.getInteractions() != null) {
                for (String rootInteraction : item.getInteractions().values()) {
                    if (rootInteraction != null && rootInteraction.toLowerCase().contains("reload")) {
                        return "reload-root-interaction:" + rootInteraction;
                    }
                }
            }
        } catch (Exception e) {
            // If asset inspection fails, default to "not reload-tracked" rather than
            // treating every ranged weapon like a crossbow and risking duplication.
        }

        return null;
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
        if (slotAfter == null || slotAfter.isEmpty())
            return;
        if (!isAmmoItem(slotAfter))
            return;

        if (player.getWorld() == null || player.getReference() == null)
            return;
        com.hypixel.hytale.server.core.entity.UUIDComponent uuidComp = player.getWorld().getEntityStore().getStore()
                .getComponent(player.getReference(),
                        com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
        if (uuidComp == null)
            return;
        UUID playerUuid = uuidComp.getUuid();

        int addedQty = afterQty - beforeQty;
        if (consumeConfirmedUnloadRefund(playerUuid, container, slotAfter, slot, addedQty)) {
            return;
        }

        LoadedAmmoRecord record = loadedCrossbowAmmo.get(playerUuid);

        // If we haven't recorded a refund for this player, ignore.
        if (record == null)
            return;

        // Check if the added ammo matches what we refunded
        if (!record.ammoId.equals(slotAfter.getItemId()))
            return;

        if (consumeSuspectPreloadRefundOnAdd(playerUuid, player, slotAfter, addedQty)) {
            return;
        }

        if (shouldRemoveInPlaceRollback(playerUuid, player, record, slotAfter)) {
            removeFromExactSlot(container, record.ammoId, slot, addedQty);
            return;
        }

        // Record the exact container and slot for precise retroactive removal
        pendingSwapVerifications
                .computeIfAbsent(playerUuid, k -> new ArrayList<>())
                .add(new PendingAddition(System.currentTimeMillis(), container, slot, addedQty));
    }

    private void cleanupOldSuspectRefund(@Nonnull UUID playerUuid) {
        SuspectPreloadRefund suspect = suspectPreloadRefunds.get(playerUuid);
        if (suspect == null)
            return;
        if ((System.currentTimeMillis() - suspect.timestamp) > PRELOAD_KICKSTART_WINDOW_MS) {
            suspectPreloadRefunds.remove(playerUuid);
        }
    }

    private void cleanupOldConfirmedUnload(@Nonnull UUID playerUuid) {
        ConfirmedUnloadRecord unload = confirmedUnloads.get(playerUuid);
        if (unload == null)
            return;
        if ((System.currentTimeMillis() - unload.timestamp) > SWAP_VERIFY_WINDOW_MS) {
            confirmedUnloads.remove(playerUuid);
        }
    }

    @Nonnull
    private SuspectReconciliationResult reconcileSuspectPreloadRefund(@Nonnull UUID playerUuid,
            @Nullable ItemStack weapon,
            @Nullable ItemStack ammo,
            @Nonnull ItemContainer currentContainer,
            short currentSlot,
            int beforeQty,
            int afterQty,
            @Nullable String trackingReason) {
        SuspectPreloadRefund suspect = suspectPreloadRefunds.get(playerUuid);
        if (suspect == null)
            return new SuspectReconciliationResult(ammo);

        long ageMs = System.currentTimeMillis() - suspect.timestamp;
        if (ageMs > PRELOAD_KICKSTART_WINDOW_MS) {
            suspectPreloadRefunds.remove(playerUuid);
            return new SuspectReconciliationResult(ammo);
        }

        String weaponId = weapon != null ? weapon.getItemId() : null;
        String ammoId = ammo != null ? ammo.getItemId() : null;
        if (weaponId == null || ammoId == null) {
            return new SuspectReconciliationResult(ammo);
        }

        if (!weaponId.equals(suspect.weaponId) || !ammoId.equals(suspect.ammoId)) {
            return new SuspectReconciliationResult(ammo);
        }

        if (!isAmmoStatTrackedWeapon(trackingReason)) {
            return new SuspectReconciliationResult(ammo);
        }

        if (suspect.container == currentContainer && suspect.slot == currentSlot) {
            int adjustedQty = Math.max(afterQty, beforeQty - suspect.qty);
            ItemStack adjustedBefore = adjustedQty > 0 ? ammo.withQuantity(adjustedQty) : ItemStack.EMPTY;
            suspectPreloadRefunds.remove(playerUuid);
            return new SuspectReconciliationResult(adjustedBefore);
        }

        removeFromExactSlot(suspect.container, suspect.ammoId, suspect.slot, suspect.qty);
        suspectPreloadRefunds.remove(playerUuid);
        return new SuspectReconciliationResult(ammo);
    }

    private void maybeRecordSuspectPreloadRefund(@Nonnull UUID playerUuid,
            @Nullable ItemStack weapon,
            @Nullable ItemStack ammo,
            @Nonnull ItemContainer container,
            short slot,
            int consumed,
            @Nullable String trackingReason) {
        if (!isAmmoStatTrackedWeapon(trackingReason) || consumed != 1 || weapon == null || ammo == null) {
            return;
        }

        suspectPreloadRefunds.put(playerUuid, new SuspectPreloadRefund(
                System.currentTimeMillis(),
                weapon.getItemId(),
                ammo.getItemId(),
                container,
                slot,
                consumed));
    }

    private boolean consumeSuspectPreloadRefundOnAdd(@Nonnull UUID playerUuid,
            @Nonnull Player player,
            @Nonnull ItemStack addedAmmo,
            int addedQty) {
        if (addedQty <= 0) {
            return false;
        }

        SuspectPreloadRefund suspect = suspectPreloadRefunds.get(playerUuid);
        if (suspect == null) {
            return false;
        }

        long ageMs = System.currentTimeMillis() - suspect.timestamp;
        if (ageMs > PRELOAD_KICKSTART_WINDOW_MS) {
            suspectPreloadRefunds.remove(playerUuid);
            return false;
        }

        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return false;
        }

        ItemStack heldWeapon = inventory.getItemInHand();
        if (heldWeapon == null || heldWeapon.isEmpty()) {
            return false;
        }

        if (!suspect.weaponId.equals(heldWeapon.getItemId())) {
            return false;
        }

        if (!suspect.ammoId.equals(addedAmmo.getItemId())) {
            return false;
        }

        if (addedQty < suspect.qty) {
            return false;
        }

        boolean removed = removeFromExactSlot(suspect.container, suspect.ammoId, suspect.slot, suspect.qty);
        suspectPreloadRefunds.remove(playerUuid);
        return removed;
    }

    private boolean consumeConfirmedUnloadRefund(@Nonnull UUID playerUuid,
            @Nonnull ItemContainer container,
            @Nonnull ItemStack addedAmmo,
            short slot,
            int addedQty) {
        if (addedQty <= 0) {
            return false;
        }

        ConfirmedUnloadRecord unload = confirmedUnloads.get(playerUuid);
        if (unload == null) {
            return false;
        }

        long ageMs = System.currentTimeMillis() - unload.timestamp;
        if (ageMs > SWAP_VERIFY_WINDOW_MS) {
            confirmedUnloads.remove(playerUuid);
            return false;
        }

        if (!unload.ammoId.equals(addedAmmo.getItemId())) {
            return false;
        }

        boolean removed = removeFromExactSlot(container, unload.ammoId, slot, addedQty);
        if (removed && unload.unresolvedPendingQty > 0) {
            unload.unresolvedPendingQty = Math.max(0, unload.unresolvedPendingQty - addedQty);
        }
        if (removed && unload.unresolvedPendingQty <= 0) {
            confirmedUnloads.remove(playerUuid);
        }
        return removed;
    }

    private boolean isAmmoStatTrackedWeapon(@Nullable String trackingReason) {
        return "weapon-ammo-stat-modifier".equals(trackingReason)
                || "weapon-clears-ammo-stat".equals(trackingReason);
    }

    private boolean shouldRemoveInPlaceRollback(@Nonnull UUID playerUuid,
            @Nonnull Player player,
            @Nonnull LoadedAmmoRecord record,
            @Nonnull ItemStack addedAmmo) {
        // During the normal two-step reload flow we keep a suspect pre-load refund
        // alive until the follow-up consume resolves it. Do not treat the matching
        // add-back during that phase as a rollback, or one ammo is lost every reload.
        if (suspectPreloadRefunds.containsKey(playerUuid)) {
            return false;
        }

        long ageMs = System.currentTimeMillis() - record.lastRefundTimestamp;
        if (ageMs > IN_PLACE_ROLLBACK_WINDOW_MS) {
            return false;
        }

        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return false;
        }

        ItemStack heldWeapon = inventory.getItemInHand();
        if (heldWeapon == null || heldWeapon.isEmpty()) {
            return false;
        }

        if (!record.weaponId.equals(heldWeapon.getItemId())) {
            return false;
        }

        if (enchantmentManager.getEnchantmentLevel(heldWeapon, EnchantmentType.ETERNAL_SHOT) <= 0) {
            return false;
        }

        if (!shouldTrackLoadedAmmo(heldWeapon)) {
            return false;
        }

        return record.ammoId.equals(addedAmmo.getItemId());
    }

    /**
     * Checks if an item is ammunition.
     */
    private boolean isAmmoItem(@Nonnull ItemStack itemStack) {
        String itemId = itemStack.getItemId();
        if (itemId == null)
            return false;

        String lower = itemId.toLowerCase();

        if (lower.contains("arrow") || lower.contains("bolt") || lower.contains("ammo")
                || lower.contains("ammunition")) {
            return true;
        }

        try {
            Item item = itemStack.getItem();
            if (item != null && item.getData() != null) {
                String[] typeValues = item.getData().getRawTags().get("Type");
                if (typeValues != null) {
                    for (String tag : typeValues) {
                        String tagLower = tag.toLowerCase();
                        if (tagLower.contains("arrow") || tagLower.contains("bolt") || tagLower.contains("ammo")
                                || tagLower.contains("ammunition") || tagLower.contains("projectile")) {
                            return true;
                        }
                    }
                }
                String[] familyValues = item.getData().getRawTags().get("Family");
                if (familyValues != null) {
                    for (String tag : familyValues) {
                        String tagLower = tag.toLowerCase();
                        if (tagLower.contains("arrow") || tagLower.contains("ammo") || tagLower.contains("ammunition")
                                || tagLower.contains("projectile")) {
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
