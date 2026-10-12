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
import com.hypixel.hytale.protocol.EntityStatOp;
import com.hypixel.hytale.protocol.EntityStatUpdate;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.protocol.ValueType;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.livingentity.LivingEntityEffectSystem;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsSystems;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.interaction.system.InteractionSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.floats.FloatList;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * ECS ticking system that boosts instant healing for players wearing a
 * chestplate with the Second Stomach enchantment.
 * <p>
 * <b>How consumable healing is detected (no reflection, no per-player state).</b>
 * Every stat change is recorded in the {@link EntityStatMap}'s per-tick update
 * log together with a {@code predictable} flag. That flag is set precisely by
 * the two paths a consumable heals through:
 * <ul>
 *   <li>{@code ChangeStatInteraction} (item interactions) →
 *       {@code Predictable.SELF}</li>
 *   <li>{@code ActiveEntityEffect} stat changes (food/potion effects) →
 *       {@code Predictable.ALL}</li>
 * </ul>
 * whereas natural regeneration ({@code EntityStatsSystems.Regenerate}), damage
 * and this mod's own heals (Regeneration, Life Leech, Absorption, this system)
 * use {@code Predictable.NONE}. Summing the positive, predictable health deltas
 * of the current tick therefore yields exactly the consumable healing.
 * <p>
 * Regen-over-time consumables ({@code Food_Health_Regen_*}, i.e. effects with a
 * {@code DamageCalculatorCooldown} &gt; 0) are not "instant" healing. Their
 * ticks are recognised by value: the effect passes its per-cycle amount to
 * {@code addStatValue}, which is what {@code EntityStatUpdate.value} holds, so
 * any predictable {@code Add} whose value equals the per-cycle amount of an
 * active periodic health effect is skipped.
 * <p>
 * Ordering: runs after effects and interactions have ticked and, being a
 * {@link EntityStatsSystems.StatModifyingSystem}, before the update log is
 * consumed and cleared - the same shape as {@link EnchantmentThriftSystem}.
 */
public class EnchantmentSecondStomachSystem extends EntityTickingSystem<EntityStore>
        implements EntityStatsSystems.StatModifyingSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final short CHEST_SLOT = (short) ItemArmorSlot.Chest.getValue();

    private static final float EPSILON = 0.001f;

    @Nonnull
    private static final Query<EntityStore> QUERY = Query.and(
            Player.getComponentType(),
            EntityStatMap.getComponentType(),
            InventoryComponent.Armor.getComponentType());

    private final EnchantmentManager enchantmentManager;

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency(Order.AFTER, LivingEntityEffectSystem.class),
            new SystemDependency(Order.AFTER, InteractionSystems.TickInteractionManagerSystem.class));

    public EnchantmentSecondStomachSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentSecondStomachSystem initialized");
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    @Override
    @Nonnull
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    @Nullable
    public SystemGroup<EntityStore> getGroup() {
        // Same group as LivingEntityEffectSystem and EnchantmentThriftSystem
        return DamageModule.get().getGatherDamageGroup();
    }

    @Override
    public void tick(float dt, int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            EntityStatMap statMap = archetypeChunk.getComponent(index, EntityStatMap.getComponentType());
            if (statMap == null) {
                return;
            }

            int healthIndex = DefaultEntityStatTypes.getHealth();
            List<EntityStatUpdate> updates = statMap.getSelfUpdates().get(healthIndex);
            if (updates == null || updates.isEmpty()) {
                return;
            }
            FloatList values = statMap.getSelfStatValues().get(healthIndex);
            if (values == null || values.isEmpty()) {
                return;
            }
            EntityStatValue healthStat = statMap.get(healthIndex);
            if (healthStat == null) {
                return;
            }

            // Pass 1 (no metadata reads): sum this tick's consumable health gains.
            float instantHealGain = 0.0f;
            FloatList periodicAmounts = null;
            boolean periodicResolved = false;
            int pairs = Math.min(updates.size(), values.size() / 2);
            for (int i = 0; i < pairs; i++) {
                EntityStatUpdate update = updates.get(i);
                if (update == null || !update.predictable || !changesValue(update.op)) {
                    continue;
                }
                float delta = values.getFloat(i * 2 + 1) - values.getFloat(i * 2);
                if (delta <= EPSILON) {
                    continue;
                }
                if (update.op == EntityStatOp.Add) {
                    if (!periodicResolved) {
                        periodicAmounts = periodicHealAmounts(index, archetypeChunk, healthIndex, healthStat);
                        periodicResolved = true;
                    }
                    if (periodicAmounts != null && periodicAmounts.contains(update.value)) {
                        continue; // regen-over-time tick, not instant healing
                    }
                }
                instantHealGain += delta;
            }

            if (instantHealGain <= EPSILON) {
                return;
            }

            // Pass 2: something was consumed - now read the armor.
            InventoryComponent.Armor armorComponent = archetypeChunk.getComponent(index,
                    InventoryComponent.Armor.getComponentType());
            ItemContainer armorContainer = armorComponent != null ? armorComponent.getInventory() : null;
            if (armorContainer == null || armorContainer.getCapacity() <= CHEST_SLOT) {
                return;
            }

            ItemStack chestplate = armorContainer.getItemStack(CHEST_SLOT);
            if (chestplate == null || chestplate.isEmpty()) {
                return;
            }

            int level = enchantmentManager.getEnchantmentLevel(chestplate, EnchantmentType.SECOND_STOMACH);
            if (level <= 0) {
                return;
            }

            double multiplier = level * EnchantmentType.SECOND_STOMACH.getEffectMultiplier();
            float bonusHeal = (float) (instantHealGain * multiplier);
            if (bonusHeal <= 0.0f) {
                return;
            }

            // Non-predictable, so it is never mistaken for consumable healing.
            statMap.addStatValue(healthIndex, bonusHeal);

            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            EnchantmentEventHelper.fireActivated(playerRef, chestplate, EnchantmentType.SECOND_STOMACH, level);

        } catch (Exception e) {
            LOGGER.atWarning().atMostEvery(30, TimeUnit.SECONDS).withCause(e)
                    .log("Error in Second Stomach system");
        }
    }

    /** Ops that move the stat value itself (as opposed to init/modifier bookkeeping). */
    private static boolean changesValue(@Nullable EntityStatOp op) {
        if (op == null) {
            return false;
        }
        switch (op) {
            case Add:
            case Set:
            case Min:
            case Max:
            case Minimize:
            case Maximize:
            case Reset:
                return true;
            default:
                return false;
        }
    }

    /**
     * Per-cycle health amounts of the entity's active regen-over-time effects,
     * computed with the same expression {@code EntityStatMap.processStatChanges}
     * uses so the values compare equal. Null when there are none.
     */
    @Nullable
    private static FloatList periodicHealAmounts(int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            int healthIndex,
            @Nonnull EntityStatValue healthStat) {
        EffectControllerComponent effectController = archetypeChunk.getComponent(index,
                EffectControllerComponent.getComponentType());
        if (effectController == null) {
            return null;
        }
        FloatList amounts = null;
        for (ActiveEntityEffect activeEffect : effectController.getActiveEffects().values()) {
            EntityEffect effect = EntityEffect.getAssetMap().getAsset(activeEffect.getEntityEffectIndex());
            if (effect == null || effect.getDamageCalculatorCooldown() <= 0.0f) {
                continue; // one-shot effects heal instantly
            }
            Int2FloatMap stats = effect.getEntityStats();
            if (stats == null || !stats.containsKey(healthIndex)) {
                continue;
            }
            float amount = stats.get(healthIndex);
            if (effect.getValueType() == ValueType.Percent) {
                amount = amount * (healthStat.getMax() - healthStat.getMin()) / 100.0f;
            }
            if (amount <= 0.0f) {
                continue;
            }
            if (amounts == null) {
                amounts = new FloatArrayList(2);
            }
            amounts.add(amount);
        }
        return amounts;
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return false;
    }
}
