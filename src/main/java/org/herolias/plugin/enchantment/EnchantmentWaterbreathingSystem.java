package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.herolias.plugin.util.InventoryAccess;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Waterbreathing: raises max oxygen through a native StaticModifier while an
 * enchanted helmet is worn.
 * <p>
 * The helmet level comes from {@link EquipmentLevelCache}; the modifier is only
 * touched when the level changes, and the per-player level is forgotten on
 * disconnect/removal ({@link #cleanupPlayer}).
 */
public class EnchantmentWaterbreathingSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Key of the modifier in the EntityStatMap. */
    private static final String MODIFIER_KEY = "Enchantment_Waterbreathing";
    /** Base oxygen is 100; this much times the scaled multiplier is added. */
    private static final float ADDED_OXYGEN_BASE = 250.0f;

    @Nonnull
    private static final Query<EntityStore> QUERY = Query.and(
            EntityStatMap.getComponentType(),
            Player.getComponentType(),
            UUIDComponent.getComponentType());

    private final EnchantmentManager enchantmentManager;

    /** Level the modifier currently reflects, per player. */
    private final Map<UUID, Integer> activeEnchantmentLevels = new ConcurrentHashMap<>();

    public EnchantmentWaterbreathingSystem(@Nonnull EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentWaterbreathingSystem initialized");
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    @Override
    public void tick(float dt, int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        UUIDComponent uuidComponent = archetypeChunk.getComponent(index, UUIDComponent.getComponentType());
        EntityStatMap statMap = archetypeChunk.getComponent(index, EntityStatMap.getComponentType());
        if (uuidComponent == null || statMap == null) {
            return;
        }
        UUID playerId = uuidComponent.getUuid();
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);

        int level = EquipmentLevelCache.get(playerId, store, ref, enchantmentManager).waterbreathing();
        int activeLevel = activeEnchantmentLevels.getOrDefault(playerId, 0);
        if (level == activeLevel) {
            return;
        }

        try {
            if (level > 0) {
                float addedOxygen = ADDED_OXYGEN_BASE
                        * (float) EnchantmentType.WATERBREATHING.getScaledMultiplier(level);
                StaticModifier modifier = new StaticModifier(Modifier.ModifierTarget.MAX,
                        StaticModifier.CalculationType.ADDITIVE, addedOxygen);
                statMap.putModifier(EntityStatMap.Predictable.NONE, DefaultEntityStatTypes.getOxygen(), MODIFIER_KEY,
                        modifier);
                activeEnchantmentLevels.put(playerId, level);

                // Only fire the activation event when the helmet is put on (0 -> level).
                if (activeLevel == 0) {
                    ItemContainer armor = InventoryAccess.getArmor(store, ref);
                    ItemStack helmet = armor != null ? armor.getItemStack((short) ItemArmorSlot.Head.getValue()) : null;
                    if (!ItemStack.isEmpty(helmet)) {
                        PlayerRef playerRef = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
                        EnchantmentEventHelper.fireActivated(playerRef, helmet, EnchantmentType.WATERBREATHING, level);
                    }
                }
            } else {
                statMap.removeModifier(EntityStatMap.Predictable.NONE, DefaultEntityStatTypes.getOxygen(),
                        MODIFIER_KEY);
                activeEnchantmentLevels.remove(playerId);
            }
        } catch (RuntimeException e) {
            LOGGER.atWarning().atMostEvery(30, TimeUnit.SECONDS).withCause(e)
                    .log("Failed to update the Waterbreathing oxygen modifier for %s", playerId);
        }
    }

    /** Forgets the modifier state of a player who disconnected or whose entity was removed. */
    public void cleanupPlayer(@Nonnull UUID playerId) {
        activeEnchantmentLevels.remove(playerId);
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return EntityTickingSystem.maybeUseParallel(archetypeChunkSize, taskCount);
    }
}
