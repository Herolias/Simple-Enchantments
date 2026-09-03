package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.transaction.ActionType;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.herolias.plugin.util.InventoryAccess;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Eternal Shot: ammunition consumed by an enchanted ranged weapon is given back
 * when the projectile is actually fired.
 * <p>
 * Vanilla removes the ammunition when the weapon is loaded (bow draw, crossbow
 * reload) through a {@code ModifyInventory} interaction and refunds it itself
 * when the shot is cancelled (swap away, released without firing). This system
 * therefore only has to
 * <ol>
 * <li>track how many units of which ammunition were removed while an Eternal
 * Shot ranged weapon is in hand ({@link #handle}),</li>
 * <li>hand one unit back per spawned projectile
 * ({@link #getAndClearConsumedAmmo}, called by
 * {@link EnchantmentProjectileSpeedSystem}), and</li>
 * <li>ignore additions that are vanilla's own cancel refund (they lower the
 * tracked count) or this plugin's refund (announced through
 * {@link #markPendingRefund}).</li>
 * </ol>
 * Only {@link ItemStackTransaction}s are considered: that is the shape of the
 * interaction-driven removal/addition. Manual drops are {@code SlotTransaction}s
 * and are additionally correlated through {@link AbstractRefundSystem}. A
 * record is bounded by the weapon's loaded-ammo capacity (the {@code Ammo}
 * stat's max), discarded when a reload starts with nothing loaded, and cleared
 * when the weapon leaves the hand ({@link #onSlotChanged}) or the player leaves
 * ({@link #cleanupPlayer}).
 */
public class EnchantmentEternalShotSystem extends AbstractRefundSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Raw tag ({@code Tag=Value}) carried by every vanilla arrow item. */
    private static final String ARROW_FAMILY_TAG = "Family=Arrow";

    private static final class ConsumedAmmoRecord {
        /** One unit of the consumed ammunition. */
        final ItemStack ammo;
        int count;

        ConsumedAmmoRecord(@Nonnull ItemStack ammo, int count) {
            this.ammo = ammo;
            this.count = count;
        }
    }

    private final EnchantmentManager enchantmentManager;
    private final Map<UUID, ConsumedAmmoRecord> consumedAmmo = new ConcurrentHashMap<>();
    /** Units this plugin is about to add back; matching additions are not vanilla refunds. */
    private final Map<UUID, Integer> pendingRefundUnits = new ConcurrentHashMap<>();

    public EnchantmentEternalShotSystem(@Nonnull EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentEternalShotSystem initialized");
    }

    @Override
    public void handle(int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull InventoryChangeEvent event) {
        Transaction transaction = event.getTransaction();
        if (!(transaction instanceof ItemStackTransaction itemStackTransaction) || !itemStackTransaction.succeeded()) {
            return;
        }
        ActionType action = itemStackTransaction.getAction();
        if (action != ActionType.REMOVE && action != ActionType.ADD) {
            return;
        }
        Player player = archetypeChunk.getComponent(index, Player.getComponentType());
        UUIDComponent uuidComponent = archetypeChunk.getComponent(index, UUIDComponent.getComponentType());
        if (player == null || uuidComponent == null) {
            return;
        }
        UUID playerUuid = uuidComponent.getUuid();
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);

        if (action == ActionType.REMOVE) {
            handleRemoval(ref, store, playerUuid, event, itemStackTransaction);
        } else {
            handleAddition(playerUuid, itemStackTransaction);
        }
    }

    private void handleRemoval(@Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID playerUuid,
            @Nonnull InventoryChangeEvent event,
            @Nonnull ItemStackTransaction transaction) {
        ItemStack weapon = InventoryAccess.getItemInHand(store, ref);
        if (!isEternalShotWeapon(weapon)) {
            return;
        }
        long tick = currentTick(store);
        for (ItemStackSlotTransaction slotTransaction : transaction.getSlotTransactions()) {
            if (!slotTransaction.succeeded()) {
                continue;
            }
            ItemStack before = slotTransaction.getSlotBefore();
            int removed = quantityOf(before) - quantityOf(slotTransaction.getSlotAfter());
            if (before == null || removed <= 0 || !isAmmunition(before, transaction.getQuery())) {
                continue;
            }
            if (consumeDropMarker(playerUuid, event, slotTransaction.getSlot(), before, tick)) {
                continue;
            }
            trackConsumed(ref, store, playerUuid, before, removed);
        }
    }

    private void trackConsumed(@Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID playerUuid,
            @Nonnull ItemStack ammo,
            int removed) {
        EntityStatValue ammoStat = ammoStat(store, ref);
        ConsumedAmmoRecord record = consumedAmmo.get(playerUuid);
        if (record != null && (!record.ammo.getItemId().equals(ammo.getItemId())
                || (ammoStat != null && ammoStat.get() <= 0.0f))) {
            // Different ammunition, or a reload starting with nothing loaded: the
            // previous record cannot describe ammunition that is still in the weapon.
            record = null;
        }
        if (record == null) {
            record = new ConsumedAmmoRecord(ammo.withQuantity(1), 0);
            consumedAmmo.put(playerUuid, record);
        }
        record.count += removed;

        // A weapon cannot hold more than its Ammo capacity; anything above it is
        // stale bookkeeping from an earlier load that never produced a shot.
        int capacity = ammoStat != null ? Math.round(ammoStat.getMax()) : 0;
        if (capacity > 0 && record.count > capacity) {
            record.count = Math.max(removed, capacity);
        }
    }

    /**
     * Additions of tracked ammunition are either this plugin's own refund
     * (skipped, see {@link #markPendingRefund}) or vanilla giving loaded ammo
     * back on cancel, which lowers the tracked count by the added amount.
     */
    private void handleAddition(@Nonnull UUID playerUuid, @Nonnull ItemStackTransaction transaction) {
        for (ItemStackSlotTransaction slotTransaction : transaction.getSlotTransactions()) {
            if (!slotTransaction.succeeded()) {
                continue;
            }
            ItemStack after = slotTransaction.getSlotAfter();
            int added = quantityOf(after) - quantityOf(slotTransaction.getSlotBefore());
            if (after == null || added <= 0 || !isAmmunition(after, transaction.getQuery())) {
                continue;
            }
            added -= consumePendingRefund(playerUuid, added);
            if (added <= 0) {
                continue;
            }
            ConsumedAmmoRecord record = consumedAmmo.get(playerUuid);
            if (record != null && record.ammo.getItemId().equals(after.getItemId())) {
                record.count -= added;
                if (record.count <= 0) {
                    consumedAmmo.remove(playerUuid);
                }
            }
        }
    }

    /** Consumes up to {@code units} pending refund units and returns how many were consumed. */
    private int consumePendingRefund(@Nonnull UUID playerUuid, int units) {
        Integer pending = pendingRefundUnits.get(playerUuid);
        if (pending == null || pending <= 0) {
            return 0;
        }
        int consumed = Math.min(pending, units);
        int remaining = pending - consumed;
        if (remaining <= 0) {
            pendingRefundUnits.remove(playerUuid);
        } else {
            pendingRefundUnits.put(playerUuid, remaining);
        }
        return consumed;
    }

    /**
     * Announces that this plugin is about to add {@code units} of ammunition to
     * the player's inventory, so the resulting addition is not mistaken for a
     * vanilla cancel refund.
     */
    public void markPendingRefund(@Nonnull UUID playerUuid, int units) {
        if (units > 0) {
            pendingRefundUnits.merge(playerUuid, units, Integer::sum);
        }
    }

    /**
     * Withdraws units announced with {@link #markPendingRefund} that never made
     * it into the inventory (dropped because it was full), so they cannot mask a
     * later vanilla refund.
     */
    public void cancelPendingRefund(@Nonnull UUID playerUuid, int units) {
        if (units > 0) {
            consumePendingRefund(playerUuid, units);
        }
    }

    /**
     * Called when an Eternal Shot projectile is spawned. Returns one unit of the
     * tracked ammunition, or null when nothing is tracked for the player.
     */
    @Nullable
    public ItemStack getAndClearConsumedAmmo(@Nonnull UUID playerUuid) {
        ConsumedAmmoRecord record = consumedAmmo.get(playerUuid);
        if (record == null) {
            return null;
        }
        record.count--;
        if (record.count <= 0) {
            consumedAmmo.remove(playerUuid);
        }
        return record.ammo;
    }

    /**
     * The weapon left the player's hand; vanilla refunds whatever was loaded, so
     * nothing tracked can still be fired.
     */
    public void onSlotChanged(@Nonnull UUID playerUuid) {
        consumedAmmo.remove(playerUuid);
    }

    @Override
    public void cleanupPlayer(@Nonnull UUID playerUuid) {
        super.cleanupPlayer(playerUuid);
        consumedAmmo.remove(playerUuid);
        pendingRefundUnits.remove(playerUuid);
    }

    private boolean isEternalShotWeapon(@Nullable ItemStack weapon) {
        if (weapon == null || weapon.isEmpty()) {
            return false;
        }
        if (enchantmentManager.categorizeItem(weapon) != ItemCategory.RANGED_WEAPON) {
            return false;
        }
        return enchantmentManager.getEnchantmentLevel(weapon, EnchantmentType.ETERNAL_SHOT) > 0;
    }

    /**
     * Ammunition is anything tagged {@code Family=Arrow}, or the exact item the
     * weapon's interaction asked to remove/add (the transaction query), which
     * covers ranged weapons whose ammunition carries no family tag.
     */
    private static boolean isAmmunition(@Nonnull ItemStack stack, @Nullable ItemStack query) {
        Item item = stack.getItem();
        if (item != null && item.getData() != null && item.getData().getRawTags().containsKey(ARROW_FAMILY_TAG)) {
            return true;
        }
        return query != null && !query.isEmpty() && query.getItemId().equals(stack.getItemId());
    }

    @Nullable
    private static EntityStatValue ammoStat(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        EntityStatMap statMap = accessor.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null) {
            return null;
        }
        int ammoIndex = DefaultEntityStatTypes.getAmmo();
        return ammoIndex == Integer.MIN_VALUE ? null : statMap.get(ammoIndex);
    }
}
