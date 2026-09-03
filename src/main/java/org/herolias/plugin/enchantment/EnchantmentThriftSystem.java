package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.EntityStatUpdate;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.AllLegacyLivingEntityTypesQuery;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsSystems;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.interaction.system.InteractionSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.floats.FloatList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

/**
 * Thrift: refunds part of the mana spent by casting with an enchanted staff.
 * <p>
 * Mana drains from status effects and from interactions look identical in the
 * stat map (both are flagged predictable), so a refund is only made while an
 * item-use interaction chain is still running this tick, and the staff is the
 * item that chain is using. The chain walk and the enchantment read happen once
 * per tick in which mana actually changed.
 */
public class EnchantmentThriftSystem extends EntityTickingSystem<EntityStore>
        implements EntityStatsSystems.StatModifyingSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final EnchantmentManager enchantmentManager;
    private final Query<EntityStore> query = Query.and(AllLegacyLivingEntityTypesQuery.INSTANCE,
            EntityStatMap.getComponentType());
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<EntityStore, InteractionSystems.TickInteractionManagerSystem>(
                    Order.AFTER,
                    InteractionSystems.TickInteractionManagerSystem.class));

    public EnchantmentThriftSystem(@Nonnull EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentThriftSystem initialized");
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    @Nonnull
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    @Nullable
    public SystemGroup<EntityStore> getGroup() {
        // Same group as the damage/stat systems so the refund lands in the same tick.
        return DamageModule.get().getGatherDamageGroup();
    }

    @Override
    public void tick(float dt,
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        EntityStatMap statMap = archetypeChunk.getComponent(index, EntityStatMap.getComponentType());
        if (statMap == null) {
            return;
        }

        int manaIndex = DefaultEntityStatTypes.getMana();
        List<EntityStatUpdate> updates = statMap.getSelfUpdates().get(manaIndex);
        if (updates == null || updates.isEmpty()) {
            return;
        }
        FloatList values = statMap.getSelfStatValues().get(manaIndex);
        if (values == null || values.isEmpty()) {
            return;
        }

        float manaSpent = predictableDrain(updates, values);
        if (manaSpent <= 0.0f) {
            return;
        }

        // Only a running cast can be refunded; drains from effects are not.
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        ItemStack weapon = ActiveInteractionItems.heldItemOfActiveChain(ref, commandBuffer,
                ActiveInteractionItems.ITEM_USE_TYPES);
        if (weapon == null) {
            return;
        }
        int thriftLevel = enchantmentManager.getEnchantmentLevel(weapon, EnchantmentType.THRIFT);
        if (thriftLevel <= 0) {
            return;
        }

        float refundPercentage = (float) EnchantmentType.THRIFT.getScaledMultiplier(thriftLevel);
        // Cap at 100% (free spells).
        refundPercentage = Math.min(refundPercentage, 1.0f);
        if (refundPercentage <= 0.0f) {
            return;
        }

        float refund = manaSpent * refundPercentage;
        if (refund <= 0.0f) {
            return;
        }
        statMap.addStatValue(manaIndex, refund);

        if (archetypeChunk.getComponent(index, Player.getComponentType()) != null) {
            PlayerRef playerRef = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
            EnchantmentEventHelper.fireActivated(playerRef, weapon, EnchantmentType.THRIFT, thriftLevel);
        }
    }

    /** Sum of the predictable decreases recorded for the stat this tick. */
    static float predictableDrain(@Nonnull List<EntityStatUpdate> updates, @Nonnull FloatList values) {
        int pairs = Math.min(updates.size(), values.size() / 2);
        float spent = 0.0f;
        for (int i = 0; i < pairs; i++) {
            EntityStatUpdate update = updates.get(i);
            if (update == null || !update.predictable) {
                continue;
            }
            float delta = values.getFloat(i * 2 + 1) - values.getFloat(i * 2);
            if (delta < 0.0f) {
                spent -= delta;
            }
        }
        return spent;
    }
}
