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
 * Dexterity: refunds part of the stamina spent by abilities/interactions
 * performed with an enchanted melee weapon or shield.
 * <p>
 * Stamina drains from status effects and from interactions look identical in
 * the stat map (both are flagged predictable), so a refund is only made while
 * an item-use interaction chain is still running this tick, using the item
 * that chain holds. The chain walk and the enchantment read happen once per
 * tick in which stamina actually changed.
 */
public class EnchantmentAbilityStaminaSystem extends EntityTickingSystem<EntityStore>
        implements EntityStatsSystems.StatModifyingSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final float MIN_STAMINA_MULTIPLIER = 0.1f;

    private final EnchantmentManager enchantmentManager;
    private final Query<EntityStore> query = Query.and(AllLegacyLivingEntityTypesQuery.INSTANCE,
            EntityStatMap.getComponentType());
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<EntityStore, InteractionSystems.TickInteractionManagerSystem>(
                    Order.AFTER,
                    InteractionSystems.TickInteractionManagerSystem.class));

    public EnchantmentAbilityStaminaSystem(@Nonnull EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentAbilityStaminaSystem initialized");
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

        int staminaIndex = DefaultEntityStatTypes.getStamina();
        List<EntityStatUpdate> updates = statMap.getSelfUpdates().get(staminaIndex);
        if (updates == null || updates.isEmpty()) {
            return;
        }
        FloatList values = statMap.getSelfStatValues().get(staminaIndex);
        if (values == null || values.isEmpty()) {
            return;
        }

        float staminaSpent = EnchantmentThriftSystem.predictableDrain(updates, values);
        if (staminaSpent <= 0.0f) {
            return;
        }

        // Only a running interaction can be refunded; drains from effects are not.
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        ItemStack item = ActiveInteractionItems.heldItemOfActiveChain(ref, commandBuffer,
                ActiveInteractionItems.ITEM_USE_TYPES);
        if (item == null) {
            return;
        }
        if (!EnchantmentType.DEXTERITY.canApplyTo(enchantmentManager.categorizeItem(item))) {
            return;
        }
        int dexterityLevel = enchantmentManager.getEnchantmentLevel(item, EnchantmentType.DEXTERITY);
        if (dexterityLevel <= 0) {
            return;
        }

        float reduction = (float) EnchantmentType.DEXTERITY.getScaledMultiplier(dexterityLevel);
        reduction = Math.min(reduction, 1.0f - MIN_STAMINA_MULTIPLIER);
        if (reduction <= 0.0f) {
            return;
        }

        float refund = staminaSpent * reduction;
        if (refund <= 0.0f) {
            return;
        }
        statMap.addStatValue(staminaIndex, refund);

        if (archetypeChunk.getComponent(index, Player.getComponentType()) != null) {
            PlayerRef playerRef = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
            EnchantmentEventHelper.fireActivated(playerRef, item, EnchantmentType.DEXTERITY, dexterityLevel);
        }
    }
}
