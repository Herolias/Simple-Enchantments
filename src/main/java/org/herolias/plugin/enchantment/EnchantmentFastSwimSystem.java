package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public class EnchantmentFastSwimSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final EnchantmentManager enchantmentManager;
    private final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap playerLastLevels = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();

    // We keep MovementStatesComponent to check if in fluid
    private static final Query<EntityStore> QUERY = Query.and(
            MovementStatesComponent.getComponentType(),
            com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType() // Ensure we have PlayerRef for packet sending
    );

    public EnchantmentFastSwimSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        this.playerLastLevels.defaultReturnValue(0);
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
        
        Entity entity = EntityUtils.getEntity(index, archetypeChunk);
        
        // Currently only supporting Players for this method
        if (!(entity instanceof Player player)) return;

        // 1. Calculate current level
        int level = 0;
        Inventory inventory = player.getInventory();
        if (inventory != null) {
            ItemContainer armor = inventory.getArmor();
            if (armor != null) {
                ItemStack gloves = armor.getItemStack((short) ItemArmorSlot.Hands.getValue());
                if (gloves != null && !gloves.isEmpty()) {
                    level = enchantmentManager.getEnchantmentLevel(gloves, EnchantmentType.FAST_SWIM);
                }
            }
        }

        // 2. Optimization: Check if level changed
        com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId netIdComp = store.getComponent(archetypeChunk.getReferenceTo(index), com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId.getComponentType());
        if (netIdComp == null) return;
        int entityId = netIdComp.getId();
        int lastLevel = playerLastLevels.get(entityId);

        if (level == lastLevel) {
            return; // No change, do nothing
        }

        // 3. Level changed, send update packet
        PlayerRef playerRef = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
        if (playerRef == null) return;

        sendFluidUpdate(playerRef, level);
        
        // Update cache
        playerLastLevels.put(entityId, level);
        
        //LOGGER.atInfo().log("FastSwim Update [Player %s]: Level %d -> %d", player.getLegacyDisplayName(), lastLevel, level);
    }

    private void sendFluidUpdate(PlayerRef playerRef, int level) {
        // Prepare packet
        com.hypixel.hytale.protocol.packets.assets.UpdateFluidFX packet = new com.hypixel.hytale.protocol.packets.assets.UpdateFluidFX();
        packet.type = com.hypixel.hytale.protocol.UpdateType.AddOrUpdate;
        packet.fluidFX = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
        
        // Access server-side FluidFX assets
        var assetStore = com.hypixel.hytale.server.core.asset.type.fluidfx.config.FluidFX.getAssetStore();
        var assetMap = assetStore.getAssetMap();
        var loadedAssets = assetMap.getAssetMap();

        double multiplier = 1.0 + (level * EnchantmentType.FAST_SWIM.getEffectMultiplier());

        for (var entry : loadedAssets.entrySet()) {
            com.hypixel.hytale.server.core.asset.type.fluidfx.config.FluidFX serverFluid = entry.getValue();
            
            // Only care about fluids that function as actual fluids (have movement settings)
            if (serverFluid.getMovementSettings() == null) continue;

            // Get Protocol object
            com.hypixel.hytale.protocol.FluidFX protocolFluid = serverFluid.toPacket();
            
            // Clone it so we don't modify the server singleton
            com.hypixel.hytale.protocol.FluidFX modifiedFluid = protocolFluid.clone();
            
            // Apply multiplier to movement settings
            if (modifiedFluid.movementSettings != null) {
                // We modify the cloned settings
                modifiedFluid.movementSettings.swimUpSpeed *= multiplier;
                modifiedFluid.movementSettings.swimDownSpeed *= multiplier;
                modifiedFluid.movementSettings.horizontalSpeedMultiplier *= multiplier;
            }

            int index = assetMap.getIndex(entry.getKey());
            packet.fluidFX.put(index, modifiedFluid);
        }
        
        packet.maxId = assetMap.getNextIndex();

        // Send to player
        ((com.hypixel.hytale.server.core.receiver.IPacketReceiver) playerRef.getPacketHandler()).writeNoCache(packet);
    }
}
