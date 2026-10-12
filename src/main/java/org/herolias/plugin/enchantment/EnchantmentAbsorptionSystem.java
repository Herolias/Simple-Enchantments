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
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * ECS system that applies Absorption enchantment.
 * Heals the blocker by a portion of blocked damage.
 */
public class EnchantmentAbsorptionSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final EnchantmentManager enchantmentManager;

    /** The defender is healed through its stat map, so require it up front. */
    private static final Query<EntityStore> QUERY = Query.and(EntityStatMap.getComponentType());

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
        return QUERY;
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

        // The active-blocker lookup inspects the InteractionManager and still needs
        // the legacy entity handle.
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

        EntityStatMap statMap = archetypeChunk.getComponent(index, EntityStatMap.getComponentType());
        if (statMap == null)
            return;

        statMap.addStatValue(DefaultEntityStatTypes.getHealth(), healAmount);
        PlayerRef playerRef = store.getComponent(archetypeChunk.getReferenceTo(index), PlayerRef.getComponentType());
        EnchantmentEventHelper.fireActivated(playerRef, blocker, EnchantmentType.ABSORPTION, absorptionLevel);
    }
}
