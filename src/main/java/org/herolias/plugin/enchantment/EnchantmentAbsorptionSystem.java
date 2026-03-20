package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * ECS system that applies Absorption enchantment.
 * Heals the blocker by a portion of blocked damage.
 */
public class EnchantmentAbsorptionSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final EnchantmentManager enchantmentManager;

    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency(Order.AFTER, DamageSystems.DamageStamina.class));

    public EnchantmentAbsorptionSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentAbsorptionSystem initialized");
    }

    @Override
    @Nonnull
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return com.hypixel.hytale.component.Archetype.empty();
    }

    @Override
    public void handle(int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Damage damage) {

        if (damage.isCancelled())
            return;

        // Check if the damage was blocked
        Boolean blocked = damage.getIfPresentMetaObject(Damage.BLOCKED);
        if (blocked == null || !blocked)
            return;

        // Get defender
        Entity defenderEntity = EntityUtils.getEntity(index, archetypeChunk);
        if (!(defenderEntity instanceof LivingEntity defender))
            return;

        // Use centralized blocker detection
        ItemStack blocker = enchantmentManager.getActiveBlocker(defender);
        if (blocker == null)
            return;

        int absorptionLevel = enchantmentManager.getEnchantmentLevel(blocker, EnchantmentType.ABSORPTION);
        if (absorptionLevel <= 0)
            return;

        // Calculate healing amount
        float originalAmount = damage.getInitialAmount();
        float healAmount = (float) (originalAmount * absorptionLevel
                * EnchantmentType.ABSORPTION.getEffectMultiplier());

        if (healAmount <= 0)
            return;

        // Retrieve EntityStatMap to heal the defender
        EntityStatMap statMap = archetypeChunk.getComponent(index, EntityStatMap.getComponentType());
        if (statMap == null) {
            // Fallback to command buffer if not in chunk (unlikely for index access but
            // safe)
            statMap = commandBuffer.getComponent(archetypeChunk.getReferenceTo(index),
                    EntityStatMap.getComponentType());
        }

        if (statMap != null) {
            statMap.addStatValue(DefaultEntityStatTypes.getHealth(), healAmount);
            com.hypixel.hytale.server.core.universe.PlayerRef playerRef = store.getComponent(
                    archetypeChunk.getReferenceTo(index),
                    com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
            EnchantmentEventHelper.fireActivated(playerRef, blocker, EnchantmentType.ABSORPTION, absorptionLevel);
            // Visual feedback could be added here (particles etc.)
        }
    }
}
