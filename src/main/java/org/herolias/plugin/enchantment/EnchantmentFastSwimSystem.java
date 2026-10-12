package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.protocol.UpdateType;
import com.hypixel.hytale.protocol.packets.assets.UpdateFluidFX;
import com.hypixel.hytale.server.core.asset.type.fluidfx.config.FluidFX;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.receiver.IPacketReceiver;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.herolias.plugin.util.InventoryAccess;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fast Swim: sends the wearer a per-player fluid definition with faster
 * movement while the enchanted gloves are worn. The glove level comes from
 * {@link EquipmentLevelCache}, so no item metadata is parsed per tick.
 */
public class EnchantmentFastSwimSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final Query<EntityStore> QUERY = Query.and(
            MovementStatesComponent.getComponentType(),
            PlayerRef.getComponentType(),
            UUIDComponent.getComponentType());

    private final EnchantmentManager enchantmentManager;
    private final Map<UUID, Integer> playerLastLevels = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> playerLastFluidState = new ConcurrentHashMap<>();

    public EnchantmentFastSwimSystem(@Nonnull EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentFastSwimSystem initialized");
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
        PlayerRef playerRef = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
        UUIDComponent uuidComponent = archetypeChunk.getComponent(index, UUIDComponent.getComponentType());
        if (playerRef == null || uuidComponent == null) {
            return;
        }
        UUID playerId = uuidComponent.getUuid();
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);

        int level = EquipmentLevelCache.get(playerId, store, ref, enchantmentManager).fastSwim();

        MovementStatesComponent statesComponent = archetypeChunk.getComponent(index,
                MovementStatesComponent.getComponentType());
        boolean inFluid = statesComponent != null && statesComponent.getMovementStates() != null
                && statesComponent.getMovementStates().inFluid;

        int lastLevel = playerLastLevels.getOrDefault(playerId, 0);
        boolean lastInFluid = playerLastFluidState.getOrDefault(playerId, false);

        if (level != lastLevel) {
            sendFluidUpdate(playerRef, level);
            playerLastLevels.put(playerId, level);
        }

        // Fire the activation event when an enchanted player enters a fluid.
        if (level > 0 && inFluid && !lastInFluid) {
            ItemContainer armor = InventoryAccess.getArmor(store, ref);
            ItemStack gloves = armor != null ? armor.getItemStack((short) ItemArmorSlot.Hands.getValue()) : null;
            if (!ItemStack.isEmpty(gloves)) {
                EnchantmentEventHelper.fireActivated(playerRef, gloves, EnchantmentType.FAST_SWIM, level);
            }
        }

        if (inFluid != lastInFluid) {
            playerLastFluidState.put(playerId, inFluid);
        }
    }

    private void sendFluidUpdate(@Nonnull PlayerRef playerRef, int level) {
        UpdateFluidFX packet = new UpdateFluidFX();
        packet.type = UpdateType.AddOrUpdate;
        packet.fluidFX = new Int2ObjectOpenHashMap<>();

        var assetMap = FluidFX.getAssetStore().getAssetMap();
        double multiplier = 1.0 + EnchantmentType.FAST_SWIM.getScaledMultiplier(level);

        for (var entry : assetMap.getAssetMap().entrySet()) {
            FluidFX serverFluid = entry.getValue();
            // Only fluids that can be moved through carry movement settings.
            if (serverFluid.getMovementSettings() == null) {
                continue;
            }
            // Clone the protocol object so the server singleton is left untouched.
            com.hypixel.hytale.protocol.FluidFX modifiedFluid = serverFluid.toPacket().clone();
            if (modifiedFluid.movementSettings != null) {
                modifiedFluid.movementSettings.swimUpSpeed *= multiplier;
                modifiedFluid.movementSettings.swimDownSpeed *= multiplier;
                modifiedFluid.movementSettings.horizontalSpeedMultiplier *= multiplier;
            }
            packet.fluidFX.put(assetMap.getIndex(entry.getKey()), modifiedFluid);
        }
        packet.maxId = assetMap.getNextIndex();

        ((IPacketReceiver) playerRef.getPacketHandler()).writeNoCache(packet);
    }

    /** Removes tracking data for a player who has disconnected or whose entity was removed. */
    public void cleanupPlayer(@Nonnull UUID playerId) {
        playerLastLevels.remove(playerId);
        playerLastFluidState.remove(playerId);
    }
}
