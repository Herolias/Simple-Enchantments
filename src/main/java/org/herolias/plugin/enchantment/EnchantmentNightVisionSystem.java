package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ColorLight;
import com.hypixel.hytale.protocol.ComponentUpdateType;
import com.hypixel.hytale.protocol.DynamicLightUpdate;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.herolias.plugin.util.InventoryAccess;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Night Vision: sends a per-player DynamicLight update to players wearing an
 * enchanted helmet.
 * <p>
 * Instead of adding a real DynamicLight component (which broadcasts to all
 * players), a ComponentUpdate with the light data is queued directly to the
 * wearing player's own EntityViewer, making the light visible only to them.
 * The helmet level comes from {@link EquipmentLevelCache}; the active state is
 * kept per player UUID together with the entity reference it was sent for, so
 * a new entity (world change) gets the light again and nothing is kept for
 * players that left ({@link #cleanupPlayer}).
 */
public class EnchantmentNightVisionSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Light config: full radius, minimal colour.
    private static final byte LIGHT_RADIUS = -1;
    private static final byte LIGHT_RED = (byte) 1;
    private static final byte LIGHT_GREEN = (byte) 1;
    private static final byte LIGHT_BLUE = (byte) 1;

    @Nonnull
    private static final Query<EntityStore> QUERY = Query.and(
            Player.getComponentType(),
            UUIDComponent.getComponentType(),
            EntityTrackerSystems.EntityViewer.getComponentType());

    private final EnchantmentManager enchantmentManager;

    /** Players whose viewer currently has the light, with the entity it was sent for. */
    private final Map<UUID, Ref<EntityStore>> activeNightVision = new ConcurrentHashMap<>();

    public EnchantmentNightVisionSystem(@Nonnull EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentNightVisionSystem initialized (per-player mode)");
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
        EntityTrackerSystems.EntityViewer viewer = archetypeChunk.getComponent(index,
                EntityTrackerSystems.EntityViewer.getComponentType());
        if (uuidComponent == null || viewer == null) {
            return;
        }
        UUID playerId = uuidComponent.getUuid();
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);

        int level = EquipmentLevelCache.get(playerId, store, ref, enchantmentManager).nightVision();
        boolean hasNightVision = level > 0;

        Ref<EntityStore> activeFor = activeNightVision.get(playerId);
        boolean wasActive = activeFor != null && activeFor.equals(ref);

        try {
            if (hasNightVision && !wasActive) {
                DynamicLightUpdate update = new DynamicLightUpdate();
                update.dynamicLight = new ColorLight(LIGHT_RADIUS, LIGHT_RED, LIGHT_GREEN, LIGHT_BLUE);
                viewer.queueUpdate(ref, update);
                activeNightVision.put(playerId, ref);

                ItemContainer armor = InventoryAccess.getArmor(store, ref);
                ItemStack helmet = armor != null ? armor.getItemStack((short) ItemArmorSlot.Head.getValue()) : null;
                if (!ItemStack.isEmpty(helmet)) {
                    PlayerRef playerRef = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
                    EnchantmentEventHelper.fireActivated(playerRef, helmet, EnchantmentType.NIGHT_VISION, level);
                }
            } else if (!hasNightVision && wasActive) {
                viewer.queueRemove(ref, ComponentUpdateType.DynamicLight);
                activeNightVision.remove(playerId);
            }
            // Nothing to do while the state is unchanged: no per-tick packets.
        } catch (RuntimeException e) {
            LOGGER.atWarning().atMostEvery(30, TimeUnit.SECONDS).withCause(e)
                    .log("Failed to update the Night Vision light for %s", playerId);
        }
    }

    /** Forgets the light state of a player who disconnected or whose entity was removed. */
    public void cleanupPlayer(@Nonnull UUID playerId) {
        activeNightVision.remove(playerId);
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return false;
    }
}
