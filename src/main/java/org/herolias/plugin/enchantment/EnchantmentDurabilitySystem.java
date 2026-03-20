package org.herolias.plugin.enchantment;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.inventory.InventoryChangeEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.Archetype;
import javax.annotation.Nonnull;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.transaction.SlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import org.herolias.plugin.util.ProcessingGuard;

import com.hypixel.hytale.server.core.entity.LivingEntity;
import java.util.Objects;

/**
 * Handles the Durability enchantment logic by listening to inventory changes.
 * 
 * Since the core game logic applies fixed durability loss based on Item config,
 * we intercept the inventory change event, detect if durability was lost,
 * and if the item has the Durability enchantment, we "refund" a portion of the
 * loss.
 */
public class EnchantmentDurabilitySystem extends EntityEventSystem<EntityStore, InventoryChangeEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final EnchantmentManager enchantmentManager;
    private final ProcessingGuard guard = new ProcessingGuard();

    public EnchantmentDurabilitySystem(EnchantmentManager enchantmentManager) {
        super(InventoryChangeEvent.class);
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentDurabilitySystem initialized");
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk, @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull InventoryChangeEvent event) {
        LivingEntity entity = (LivingEntity) EntityUtils.getEntity(index, archetypeChunk);
        if (!(entity instanceof com.hypixel.hytale.server.core.entity.entities.Player player))
            return;

        if (player.getWorld() != null && !player.getWorld().isInThread()) {
            player.getWorld().execute(() -> handle(index, archetypeChunk, store, commandBuffer, event));
            return;
        }

        if (guard.isProcessing())
            return;

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

        // Handle Sturdy enchantment (prevents max durability loss from repair kits)
        double beforeMax = before.getMaxDurability();
        double afterMax = after.getMaxDurability();
        if (afterMax + 0.001 < beforeMax && enchantmentManager.hasEnchantment(before, EnchantmentType.STURDY)) {
            guard.runGuarded(() -> {
                ItemStack correctedStack = after.withRestoredDurability(beforeMax);
                event.getItemContainer().replaceItemStackInSlot(slotTransaction.getSlot(), after, correctedStack);

                LivingEntity targetEntity = (LivingEntity) EntityUtils.getEntity(index, archetypeChunk);
                if (targetEntity instanceof com.hypixel.hytale.server.core.entity.entities.Player p) {
                    if (p.getWorld() != null && p.getReference() != null) {
                        com.hypixel.hytale.server.core.universe.PlayerRef playerRef = p.getWorld().getEntityStore()
                                .getStore().getComponent(p.getReference(),
                                        com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                        EnchantmentEventHelper.fireActivated(playerRef, before, EnchantmentType.STURDY,
                                enchantmentManager.getEnchantmentLevel(before, EnchantmentType.STURDY));
                    }
                }
            });
            return;
        }

        // Check if durability decreased
        if (after.getDurability() >= before.getDurability())
            return;

        double loss = before.getDurability() - after.getDurability();
        if (loss < 0.001)
            return;

        if (!enchantmentManager.hasEnchantment(before, EnchantmentType.DURABILITY))
            return;

        double multiplier = enchantmentManager.calculateDurabilityMultiplier(before);
        if (multiplier >= 1.0)
            return;

        // Use a chance-based system to prevent the durability loss entirely
        double chanceToPrevent = 1.0 - multiplier;
        if (Math.random() < chanceToPrevent) {
            guard.runGuarded(() -> {
                ItemStack correctedStack = after.withIncreasedDurability(loss);
                event.getItemContainer().replaceItemStackInSlot(slotTransaction.getSlot(), after, correctedStack);

                LivingEntity targetEntity = (LivingEntity) EntityUtils.getEntity(index, archetypeChunk);
                if (targetEntity instanceof com.hypixel.hytale.server.core.entity.entities.Player p) {
                    if (p.getWorld() != null && p.getReference() != null) {
                        com.hypixel.hytale.server.core.universe.PlayerRef playerRef = p.getWorld().getEntityStore()
                                .getStore().getComponent(p.getReference(),
                                        com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                        EnchantmentEventHelper.fireActivated(playerRef, before, EnchantmentType.DURABILITY,
                                enchantmentManager.getEnchantmentLevel(before, EnchantmentType.DURABILITY));
                    }
                }
            });
        }
    }
}
