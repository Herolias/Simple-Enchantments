package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.ecs.DamageBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * ECS system that applies Efficiency enchantment to block damage events.
 */
public class EnchantmentBlockDamageSystem extends EntityEventSystem<EntityStore, DamageBlockEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final EnchantmentManager enchantmentManager;

    public EnchantmentBlockDamageSystem(EnchantmentManager enchantmentManager) {
        super(DamageBlockEvent.class);
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentBlockDamageSystem initialized");
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull DamageBlockEvent event) {
        ItemStack tool = event.getItemInHand();
        if (tool == null || tool.isEmpty()) {
            return;
        }


        // Fast path: Only tools can have Efficiency (valid for block breaking)
        if (!enchantmentManager.isTool(tool)) {
            return;
        }

        if (!enchantmentManager.hasEnchantment(tool, EnchantmentType.EFFICIENCY)) {
            return;
        }

        double multiplier = enchantmentManager.calculateMiningSpeedMultiplier(tool);
        if (multiplier <= 1.0) {
            return;
        }

        float currentDamage = event.getDamage();
        if (currentDamage <= 0.0f) {
            return;
        }

        float newDamage = (float) (currentDamage * multiplier);
        event.setDamage(newDamage);
    }
}
