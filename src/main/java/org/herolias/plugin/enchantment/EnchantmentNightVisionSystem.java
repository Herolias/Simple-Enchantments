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
 * kept per player UUID together with the entity reference and radius it was
 * sent for, so a new entity (world change) or a changed radius setting gets the
 * light again and nothing is kept for players that left ({@link #cleanupPlayer}).
 * <p>
 * The light also lights particles, more strongly the larger its radius, so the
 * radius is configurable ({@code night_vision:radius}, 1-255, default 255).
 */
public class EnchantmentNightVisionSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Light config: configurable radius, minimal colour.
    private static final String RADIUS_KEY = "night_vision:radius";
    private static final int MIN_RADIUS = 1;
    private static final int MAX_RADIUS = 255;
    private static final byte LIGHT_RED = (byte) 1;
    private static final byte LIGHT_GREEN = (byte) 1;
    private static final byte LIGHT_BLUE = (byte) 1;

    @Nonnull
    private static final Query<EntityStore> QUERY = Query.and(
            Player.getComponentType(),
            UUIDComponent.getComponentType(),
            EntityTrackerSystems.EntityViewer.getComponentType());

    private final EnchantmentManager enchantmentManager;

    /** The entity and radius a player's light was last sent for. */
    private record ActiveLight(@Nonnull Ref<EntityStore> ref, byte radius) {
    }

    /** Players whose viewer currently has the light. */
    private final Map<UUID, ActiveLight> activeNightVision = new ConcurrentHashMap<>();

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

        ActiveLight active = activeNightVision.get(playerId);
        boolean wasActive = active != null && active.ref().equals(ref);

        try {
            if (hasNightVision) {
                byte radius = configuredRadius();
                if (wasActive && active.radius() == radius) {
                    return; // Nothing to do while the state is unchanged: no per-tick packets.
                }
                DynamicLightUpdate update = new DynamicLightUpdate();
                update.dynamicLight = new ColorLight(radius, LIGHT_RED, LIGHT_GREEN, LIGHT_BLUE);
                viewer.queueUpdate(ref, update);
                activeNightVision.put(playerId, new ActiveLight(ref, radius));
                if (wasActive) {
                    return; // Only the radius setting changed
                }

                ItemContainer armor = InventoryAccess.getArmor(store, ref);
                ItemStack helmet = armor != null ? armor.getItemStack((short) ItemArmorSlot.Head.getValue()) : null;
                if (!ItemStack.isEmpty(helmet)) {
                    PlayerRef playerRef = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
                    EnchantmentEventHelper.fireActivated(playerRef, helmet, EnchantmentType.NIGHT_VISION, level);
                }
            } else if (wasActive) {
                viewer.queueRemove(ref, ComponentUpdateType.DynamicLight);
                activeNightVision.remove(playerId);
            }
        } catch (RuntimeException e) {
            LOGGER.atWarning().atMostEvery(30, TimeUnit.SECONDS).withCause(e)
                    .log("Failed to update the Night Vision light for %s", playerId);
        }
    }

    /**
     * The configured light radius as the protocol's byte. 255 is sent as -1, which is
     * the value this light used before the radius became configurable.
     */
    private static byte configuredRadius() {
        long radius = Math.round(EnchantmentType.NIGHT_VISION.getMultiplierValue(RADIUS_KEY));
        return (byte) Math.clamp(radius, MIN_RADIUS, MAX_RADIUS);
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
