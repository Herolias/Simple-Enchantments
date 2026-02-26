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
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.AllLegacyLivingEntityTypesQuery;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsSystems;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.interaction.system.InteractionSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.floats.FloatList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

/**
 * Applies Dexterity stamina reduction to ability/interaction stamina costs.
 * Uses predictable stamina stat updates to refund a portion of stamina spent
 * when the player is wielding a Dexterity-enchanted melee weapon or shield.
 */
public class EnchantmentAbilityStaminaSystem extends EntityTickingSystem<EntityStore> implements EntityStatsSystems.StatModifyingSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final float MIN_STAMINA_MULTIPLIER = 0.1f;

    private final EnchantmentManager enchantmentManager;
    private final Query<EntityStore> query = Query.and(AllLegacyLivingEntityTypesQuery.INSTANCE, EntityStatMap.getComponentType());
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
        new SystemDependency<EntityStore, InteractionSystems.TickInteractionManagerSystem>(
            Order.AFTER,
            InteractionSystems.TickInteractionManagerSystem.class
        )
    );

    public EnchantmentAbilityStaminaSystem(EnchantmentManager enchantmentManager) {
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

        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        Entity entity = EntityUtils.getEntity(index, archetypeChunk);
        if (!(entity instanceof LivingEntity living)) {
            return;
        }

        Inventory inventory = living.getInventory();
        if (inventory == null) {
            return;
        }

        int dexterityLevel = getDexterityLevel(inventory, ref, commandBuffer);
        if (dexterityLevel <= 0) {
            return;
        }

        float reduction = (float) (dexterityLevel * EnchantmentType.DEXTERITY.getEffectMultiplier());
        if (reduction <= 0.0f) {
            return;
        }
        reduction = Math.min(reduction, 1.0f - MIN_STAMINA_MULTIPLIER);

        int maxPairs = Math.min(updates.size(), values.size() / 2);
        float staminaSpent = 0.0f;
        for (int i = 0; i < maxPairs; i++) {
            EntityStatUpdate update = updates.get(i);
            if (update == null || !update.predictable) {
                continue;
            }
            float previous = values.getFloat(i * 2);
            float current = values.getFloat(i * 2 + 1);
            float delta = current - previous;
            if (delta < 0.0f) {
                staminaSpent += -delta;
            }
        }

        if (staminaSpent <= 0.0f) {
            return;
        }

        float refund = staminaSpent * reduction;
        if (refund <= 0.0f) {
            return;
        }

        statMap.addStatValue(DefaultEntityStatTypes.getStamina(), refund);
        
        if (entity instanceof com.hypixel.hytale.server.core.entity.entities.Player) {
             com.hypixel.hytale.server.core.universe.PlayerRef playerRef = store.getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
             ItemStack activeItem = getDexterityItem(inventory, ref, commandBuffer);
             if (activeItem != null) {
             EnchantmentEventHelper.fireActivated(playerRef, activeItem, EnchantmentType.DEXTERITY, dexterityLevel);
             }
        }
        
        if (LOGGER.atFine().isEnabled()) {
            LOGGER.atFine().log("Dexterity refunded " + refund + " stamina.");
        }
    }

    private ItemStack getDexterityItem(@Nonnull Inventory inventory,
                                       @Nonnull Ref<EntityStore> ref,
                                       @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        ItemStack active = getDexterityItemFromActiveInteraction(ref, commandBuffer);
        if (active != null) return active;
        return inventory.getItemInHand();
    }
    
    private ItemStack getDexterityItemFromActiveInteraction(@Nonnull Ref<EntityStore> ref,
                                                            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        InteractionManager interactionManager = commandBuffer.getComponent(ref, InteractionModule.get().getInteractionManagerComponent());
        if (interactionManager == null) return null;

        long newestTimestamp = Long.MIN_VALUE;
        ItemStack bestItem = null;

        for (InteractionChain chain : interactionManager.getChains().values()) {
            if (chain == null || chain.getServerState() != InteractionState.NotFinished) continue;
            if (!isDexterityRelevantType(chain.getType())) continue;

            InteractionContext context = chain.getContext();
            if (context == null) continue;

            ItemStack heldItem = context.getHeldItem();
            if (heldItem == null || heldItem.isEmpty()) continue;

            if (chain.getTimestamp() > newestTimestamp) {
                newestTimestamp = chain.getTimestamp();
                bestItem = heldItem;
            }
        }

        return getDexterityLevel(bestItem) > 0 ? bestItem : null;
    }

    private int getDexterityLevel(@Nonnull Inventory inventory,
                                  @Nonnull Ref<EntityStore> ref,
                                  @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        int fromInteraction = getDexterityLevelFromActiveInteraction(ref, commandBuffer);
        if (fromInteraction > 0) {
            return fromInteraction;
        }
        return getDexterityLevel(inventory.getItemInHand());
    }

    private int getDexterityLevelFromActiveInteraction(@Nonnull Ref<EntityStore> ref,
                                                       @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        InteractionManager interactionManager = commandBuffer.getComponent(ref, InteractionModule.get().getInteractionManagerComponent());
        if (interactionManager == null) {
            return 0;
        }

        long newestTimestamp = Long.MIN_VALUE;
        ItemStack bestItem = null;

        // Find the most relevant active interaction
        for (InteractionChain chain : interactionManager.getChains().values()) {
            if (chain == null || chain.getServerState() != InteractionState.NotFinished) {
                continue;
            }
            
            if (!isDexterityRelevantType(chain.getType())) {
                continue;
            }

            InteractionContext context = chain.getContext();
            if (context == null) {
                continue;
            }

            ItemStack heldItem = context.getHeldItem();
            if (heldItem == null || heldItem.isEmpty()) {
                continue;
            }

            // Prefer the most recent interaction
            if (chain.getTimestamp() > newestTimestamp) {
                newestTimestamp = chain.getTimestamp();
                bestItem = heldItem;
            }
        }

        return getDexterityLevel(bestItem);
    }

    private boolean isDexterityRelevantType(@Nonnull InteractionType type) {
        return switch (type) {
            case Primary, Secondary, Ability1, Ability2, Ability3, Use, Pick -> true;
            default -> false;
        };
    }

    private int getDexterityLevel(@Nullable ItemStack item) {
        if (item == null || item.isEmpty()) {
            return 0;
        }
        ItemCategory category = enchantmentManager.categorizeItem(item);
        if (!EnchantmentType.DEXTERITY.canApplyTo(category)) {
            return 0;
        }
        return enchantmentManager.getEnchantmentLevel(item, EnchantmentType.DEXTERITY);
    }
}
