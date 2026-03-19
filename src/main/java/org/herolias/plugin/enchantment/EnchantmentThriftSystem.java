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
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.AllLegacyLivingEntityTypesQuery;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsSystems;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.interaction.system.InteractionSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.floats.FloatList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

/**
 * Applies Thrift mana restoration to mana costs.
 * Uses predictable mana stat updates to refund a portion of mana spent
 * when the player is wielding a Thrift-enchanted staff.
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

    public EnchantmentThriftSystem(EnchantmentManager enchantmentManager) {
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
        // We can use the same group as damage/stats to ensure order
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

        // Monitor MANA (ID: Mana)
        int manaIndex = DefaultEntityStatTypes.getMana();
        List<EntityStatUpdate> updates = statMap.getSelfUpdates().get(manaIndex);
        if (updates == null || updates.isEmpty()) {
            return;
        }

        FloatList values = statMap.getSelfStatValues().get(manaIndex);
        if (values == null || values.isEmpty()) {
            return;
        }

        Entity entity = EntityUtils.getEntity(index, archetypeChunk);
        if (!(entity instanceof LivingEntity living)) {
            return;
        }

        Inventory inventory = living.getInventory();
        if (inventory == null) {
            return;
        }

        // Check for Thrift Enchantment
        ItemStack weapon = inventory.getItemInHand();
        int thriftLevel = getThriftLevel(weapon);

        if (thriftLevel <= 0) {
            return;
        }

        // Calculate Refund Rate
        float multiplierIndex = (float) EnchantmentType.THRIFT.getEffectMultiplier();
        float refundPercentage = multiplierIndex * thriftLevel;

        // Safety cap at 100% refund (free spells)
        if (refundPercentage > 1.0f)
            refundPercentage = 1.0f;
        if (refundPercentage <= 0.0f)
            return;

        int maxPairs = Math.min(updates.size(), values.size() / 2);
        float manaSpent = 0.0f;

        for (int i = 0; i < maxPairs; i++) {
            EntityStatUpdate update = updates.get(i);
            if (update == null || !update.predictable) {
                if (update == null)
                    continue;
                if (!update.predictable)
                    continue;
            }
            float previous = values.getFloat(i * 2);
            float current = values.getFloat(i * 2 + 1);
            float delta = current - previous;
            if (delta < 0.0f) {
                manaSpent += -delta;
            }
        }

        if (manaSpent <= 0.0f) {
            return;
        }

        float refund = manaSpent * refundPercentage;
        if (refund <= 0.0f) {
            return;
        }

        // Apply Refund
        statMap.addStatValue(DefaultEntityStatTypes.getMana(), refund);

        if (entity instanceof com.hypixel.hytale.server.core.entity.entities.Player player) {
            com.hypixel.hytale.server.core.universe.PlayerRef playerRef = store.getComponent(
                    EntityUtils.getEntity(index, archetypeChunk).getReference(),
                    com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
            org.herolias.plugin.api.event.EnchantmentActivatedEvent ev = new org.herolias.plugin.api.event.EnchantmentActivatedEvent(
                    playerRef, weapon, EnchantmentType.THRIFT, thriftLevel);
            com.hypixel.hytale.server.core.HytaleServer.get().getEventBus()
                    .dispatchFor(org.herolias.plugin.api.event.EnchantmentActivatedEvent.class).dispatch(ev);
        }

    }

    private int getThriftLevel(@Nullable ItemStack item) {
        if (item == null || item.isEmpty()) {
            return 0;
        }
        return enchantmentManager.getEnchantmentLevel(item, EnchantmentType.THRIFT);
    }
}
