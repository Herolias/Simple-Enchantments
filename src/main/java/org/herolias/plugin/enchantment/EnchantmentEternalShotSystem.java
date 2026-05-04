package org.herolias.plugin.enchantment;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import com.hypixel.hytale.server.core.inventory.transaction.SlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Eternal Shot Enchantment System (Rewritten for new arrow consumption model)
 *
 * With the updated Hytale arrow system, arrows are consumed when the bow is
 * drawn and refunded if no shot is fired. This system works by:
 *
 * 1. Tracking ammo consumption: when a player holding an Eternal Shot ranged
 *    weapon has ammo removed from their inventory, the consumed ammo type is
 *    recorded.
 * 2. Refunding on projectile spawn: {@link EnchantmentProjectileSpeedSystem}
 *    calls {@link #getAndClearConsumedAmmo} when a projectile with Eternal Shot
 *    is spawned, and adds the tracked ammo back to the player's inventory.
 * 3. Cancel handling: if the player cancels the draw, vanilla refunds the
 *    arrow automatically and the tracking record is cleared — no mod
 *    intervention needed.
 *
 * This replaces the previous 950+ line system that tried to intercept and
 * reverse inventory changes after the fact.
 */
public class EnchantmentEternalShotSystem extends AbstractRefundSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final EnchantmentManager enchantmentManager;

    private static final long TRACKING_EXPIRY_MS = 30_000;

    private static class ConsumedAmmoRecord {
        final ItemStack ammo;
        int count;
        long timestamp;

        ConsumedAmmoRecord(ItemStack ammo, int count, long timestamp) {
            this.ammo = ammo;
            this.count = count;
            this.timestamp = timestamp;
        }
    }

    private final Map<UUID, ConsumedAmmoRecord> consumedAmmo = new ConcurrentHashMap<>();
    /** Tracks how many refund-additions to ignore per player (our own refunds, not vanilla cancel). */
    private final Map<UUID, Integer> pendingRefundSkips = new ConcurrentHashMap<>();

    public EnchantmentEternalShotSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentEternalShotSystem initialized");
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull InventoryChangeEvent event) {
        LivingEntity entity = (LivingEntity) EntityUtils.getEntity(index, chunk);
        if (!(entity instanceof Player player))
            return;

        UUID playerUuid = getPlayerUuid(player);
        if (playerUuid == null)
            return;

        cleanupOldDropRecords(playerUuid);
        cleanupExpiredRecords();

        Transaction transaction = event.getTransaction();
        ItemContainer container = event.getItemContainer();
        processTransaction(player, playerUuid, container, transaction);
    }

    private void processTransaction(Player player, UUID playerUuid, ItemContainer container, Transaction transaction) {
        if (transaction instanceof ItemStackTransaction ist) {
            for (ItemStackSlotTransaction slotTx : ist.getSlotTransactions()) {
                processSlot(player, playerUuid, container, slotTx);
            }
        } else if (transaction instanceof SlotTransaction st) {
            processSlot(player, playerUuid, container, st);
        }
    }

    private void processSlot(Player player, UUID playerUuid, ItemContainer container, SlotTransaction slotTx) {
        if (!slotTx.succeeded())
            return;

        ItemStack before = slotTx.getSlotBefore();
        ItemStack after = slotTx.getSlotAfter();
        int beforeQty = (before == null || before.isEmpty()) ? 0 : before.getQuantity();
        int afterQty = (after == null || after.isEmpty()) ? 0 : after.getQuantity();

        if (beforeQty > afterQty && before != null) {
            handleAmmoRemoval(player, playerUuid, before, slotTx.getSlot());
        } else if (afterQty > beforeQty && after != null) {
            handleAmmoAddition(playerUuid, after);
        }
    }

    private void handleAmmoRemoval(Player player, UUID playerUuid, ItemStack ammo, short slot) {
        if (wasRecentlyDropped(playerUuid, slot))
            return;

        Inventory inventory = player.getInventory();
        if (inventory == null)
            return;

        ItemStack weapon = inventory.getItemInHand();
        if (weapon == null || weapon.isEmpty())
            return;

        if (enchantmentManager.categorizeItem(weapon) != ItemCategory.RANGED_WEAPON)
            return;

        if (enchantmentManager.getEnchantmentLevel(weapon, EnchantmentType.ETERNAL_SHOT) <= 0)
            return;

        // Accumulate count for batch reloads (e.g. crossbow loads 6 arrows)
        ConsumedAmmoRecord existing = consumedAmmo.get(playerUuid);
        if (existing != null && existing.ammo.getItemId().equals(ammo.getItemId())) {
            existing.count++;
            existing.timestamp = System.currentTimeMillis();
        } else {
            consumedAmmo.put(playerUuid, new ConsumedAmmoRecord(ammo.withQuantity(1), 1, System.currentTimeMillis()));
        }
    }

    /**
     * When ammo is added back (vanilla cancel refund), decrement the tracking
     * count so the projectile spawn system won't over-refund.
     * Skips additions caused by our own refunds (marked via {@link #markPendingRefund}).
     */
    private void handleAmmoAddition(UUID playerUuid, ItemStack addedAmmo) {
        // Check if this addition is from our own refund — skip it
        Integer skips = pendingRefundSkips.get(playerUuid);
        if (skips != null && skips > 0) {
            int remaining = skips - 1;
            if (remaining <= 0) {
                pendingRefundSkips.remove(playerUuid);
            } else {
                pendingRefundSkips.put(playerUuid, remaining);
            }
            return;
        }

        ConsumedAmmoRecord record = consumedAmmo.get(playerUuid);
        if (record != null && record.ammo.getItemId().equals(addedAmmo.getItemId())) {
            record.count--;
            if (record.count <= 0) {
                consumedAmmo.remove(playerUuid);
            }
        }
    }

    /**
     * Called by {@link EnchantmentProjectileSpeedSystem} before refunding ammo
     * to prevent the resulting inventory addition from being treated as a
     * vanilla cancel refund.
     */
    public void markPendingRefund(@Nonnull UUID playerUuid) {
        pendingRefundSkips.merge(playerUuid, 1, Integer::sum);
    }

    /**
     * Called by {@link EnchantmentProjectileSpeedSystem} when an Eternal Shot
     * projectile is spawned. Returns one unit of tracked consumed ammo.
     * Decrements the count; only removes the record when fully consumed.
     */
    @Nullable
    public ItemStack getAndClearConsumedAmmo(@Nonnull UUID playerUuid) {
        ConsumedAmmoRecord record = consumedAmmo.get(playerUuid);
        if (record == null)
            return null;
        if (System.currentTimeMillis() - record.timestamp > TRACKING_EXPIRY_MS) {
            consumedAmmo.remove(playerUuid);
            return null;
        }
        record.count--;
        if (record.count <= 0) {
            consumedAmmo.remove(playerUuid);
        }
        return record.ammo;
    }

    /**
     * Reads the loaded ammo item ID from the weapon's metadata.
     * Used as a fallback when no tracked consumption record is available.
     * Works for weapons that use {@link org.herolias.plugin.interaction.ConsumeAmmoInteraction}
     * (e.g. crossbows), which stores the consumed ammo ID as "LoadedAmmoId" metadata.
     */
    @Nullable
    public ItemStack findAmmoFromWeapon(@Nonnull LivingEntity entity) {
        Inventory inventory = entity.getInventory();
        if (inventory == null)
            return null;

        ItemStack weapon = inventory.getItemInHand();
        if (weapon == null || weapon.isEmpty())
            return null;

        String ammoId = weapon.getFromMetadataOrNull("LoadedAmmoId",
                com.hypixel.hytale.codec.Codec.STRING);
        if (ammoId == null)
            return null;

        return new ItemStack(ammoId, 1);
    }

    @Nullable
    private UUID getPlayerUuid(@Nonnull Player player) {
        if (player.getWorld() == null || player.getReference() == null)
            return null;

        com.hypixel.hytale.server.core.entity.UUIDComponent uuidComp = player.getWorld().getEntityStore().getStore()
                .getComponent(player.getReference(),
                        com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
        return uuidComp != null ? uuidComp.getUuid() : null;
    }

    private void cleanupExpiredRecords() {
        long now = System.currentTimeMillis();
        consumedAmmo.entrySet().removeIf(e -> now - e.getValue().timestamp > TRACKING_EXPIRY_MS);
    }
}
