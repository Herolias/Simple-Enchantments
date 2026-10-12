package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.SlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles the Durability enchantment logic by listening to inventory changes.
 *
 * Since the core game logic applies fixed durability loss based on Item config,
 * we intercept the inventory change event, detect if durability was lost,
 * and if the item has the Durability enchantment, we "refund" a portion of the
 * loss.
 *
 * <p>
 * Event systems run on the owning world thread, so no re-dispatch is needed.
 * Our own corrections raise a durability <em>increase</em> (or a max-durability
 * increase for Sturdy), which every branch below ignores, so no recursion guard
 * is required either.
 * </p>
 */
public class EnchantmentDurabilitySystem extends EntityEventSystem<EntityStore, InventoryChangeEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final EnchantmentManager enchantmentManager;

    public EnchantmentDurabilitySystem(EnchantmentManager enchantmentManager) {
        super(InventoryChangeEvent.class);
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentDurabilitySystem initialized");
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        // Only player inventories are relevant.
        return Player.getComponentType();
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull InventoryChangeEvent event) {
        Transaction transaction = event.getTransaction();
        if (!(transaction instanceof SlotTransaction slotTransaction))
            return;
        if (!slotTransaction.succeeded())
            return;

        ItemStack before = slotTransaction.getSlotBefore();
        ItemStack after = slotTransaction.getSlotAfter();

        if (before == null || after == null || before.isEmpty() || after.isEmpty())
            return;
        if (!Objects.equals(before.getItemId(), after.getItemId()))
            return;

        ItemContainer container = event.getItemContainer();
        short slot = slotTransaction.getSlot();

        // One metadata read for both enchantments this system cares about.
        int[] levels = enchantmentManager.getEnchantmentLevels(before, EnchantmentType.STURDY,
                EnchantmentType.DURABILITY);
        int sturdyLevel = levels[0];
        int durabilityLevel = levels[1];
        if (sturdyLevel <= 0 && durabilityLevel <= 0)
            return;

        // Handle Sturdy enchantment (prevents max durability loss from repair kits)
        double beforeMax = before.getMaxDurability();
        double afterMax = after.getMaxDurability();
        if (afterMax + 0.001 < beforeMax && sturdyLevel > 0) {
            ItemStack live = liveStackMatching(container, slot, after);
            if (live == null)
                return;
            ItemStack correctedStack = live.withRestoredDurability(beforeMax);
            if (container.replaceItemStackInSlot(slot, live, correctedStack).succeeded()) {
                EnchantmentEventHelper.fireActivated(playerRef(index, archetypeChunk), before,
                        EnchantmentType.STURDY, sturdyLevel);
            }
            return;
        }

        // Check if durability decreased
        if (after.getDurability() >= before.getDurability())
            return;

        double loss = before.getDurability() - after.getDurability();
        if (loss < 0.001)
            return;

        if (durabilityLevel <= 0)
            return;

        double multiplier = enchantmentManager.calculateDurabilityMultiplier(before);
        if (multiplier >= 1.0)
            return;

        // Use a chance-based system to prevent the durability loss entirely
        double chanceToPrevent = 1.0 - multiplier;
        if (ThreadLocalRandom.current().nextDouble() >= chanceToPrevent)
            return;

        // Two losses can land in one tick: the transaction's "after" may already be
        // stale. Re-read the live slot and refund on top of whatever is there now.
        ItemStack live = liveStackMatching(container, slot, after);
        if (live == null)
            return;
        ItemStack correctedStack = live.withIncreasedDurability(loss);
        if (container.replaceItemStackInSlot(slot, live, correctedStack).succeeded()) {
            EnchantmentEventHelper.fireActivated(playerRef(index, archetypeChunk), before,
                    EnchantmentType.DURABILITY, durabilityLevel);
        }
    }

    /**
     * Returns the stack currently in {@code slot} if it is still the same item
     * (id and metadata) the transaction reported, otherwise null.
     */
    @Nullable
    private static ItemStack liveStackMatching(@Nonnull ItemContainer container, short slot,
            @Nonnull ItemStack expected) {
        ItemStack live = container.getItemStack(slot);
        if (live == null || live.isEmpty() || !live.isEquivalentType(expected)) {
            return null;
        }
        return live;
    }

    @Nullable
    private static PlayerRef playerRef(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk) {
        return archetypeChunk.getComponent(index, PlayerRef.getComponentType());
    }
}
