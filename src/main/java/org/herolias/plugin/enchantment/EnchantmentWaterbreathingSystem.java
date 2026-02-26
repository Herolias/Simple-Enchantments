package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ECS system that slows oxygen drain based on Waterbreathing enchantment on helmet.
 * 
 * Effect: Reduces oxygen drain by 20% per level (up to 60% at level 3)
 * Applicable to: Helmets only
 * 
 * Uses a time accumulator to batch oxygen updates and prevent UI flickering.
 */
public class EnchantmentWaterbreathingSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    
    // Helmet slot index in armor container (typically slot 0 for head)
    private static final short HELMET_SLOT = 0;
    
    // How much oxygen to restore per second based on enchantment level
    private static final float BASE_OXYGEN_RESTORE_PER_SECOND = 5.0f;
    
    // Minimum time between oxygen updates to prevent flickering (0.5 seconds)
    private static final float UPDATE_INTERVAL = 0.5f;
    
    private final EnchantmentManager enchantmentManager;
    
    // Track accumulated time per player to batch updates
    private final ConcurrentHashMap<UUID, Float> playerTimeAccumulators = new ConcurrentHashMap<>();
    
    @Nonnull
    private static final Query<EntityStore> QUERY = Query.and(
        EntityStatMap.getComponentType(),
        EntityModule.get().getPlayerComponentType()
    );

    public EnchantmentWaterbreathingSystem(EnchantmentManager enchantmentManager) {
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
        
        try {
            Entity entity = EntityUtils.getEntity(index, archetypeChunk);
            if (!(entity instanceof LivingEntity livingEntity)) {
                return;
            }
            
            com.hypixel.hytale.server.core.entity.UUIDComponent uuidComp = store.getComponent(archetypeChunk.getReferenceTo(index), com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
            if (uuidComp == null) return;
            UUID entityId = uuidComp.getUuid();
            
            Inventory inventory = livingEntity.getInventory();
            if (inventory == null) {
                return;
            }
            
            ItemContainer armorContainer = inventory.getArmor();
            if (armorContainer == null) {
                return;
            }
            
            // Get helmet from armor slot
            ItemStack helmet = armorContainer.getItemStack(HELMET_SLOT);
            if (helmet == null || helmet.isEmpty()) {
                playerTimeAccumulators.remove(entityId);
                return;
            }
            
            // Check for Waterbreathing enchantment
            int waterbreathingLevel = enchantmentManager.getEnchantmentLevel(helmet, EnchantmentType.WATERBREATHING);
            if (waterbreathingLevel <= 0) {
                playerTimeAccumulators.remove(entityId);
                return;
            }
            
            // Get oxygen stat
            EntityStatMap statMap = archetypeChunk.getComponent(index, EntityStatMap.getComponentType());
            if (statMap == null) {
                return;
            }
            
            EntityStatValue oxygenStat = statMap.get(DefaultEntityStatTypes.getOxygen());
            if (oxygenStat == null) {
                return;
            }
            
            // Only apply when oxygen is below max (meaning player is underwater and losing oxygen)
            float currentOxygen = oxygenStat.get();
            float maxOxygen = oxygenStat.getMax();
            
            if (currentOxygen >= maxOxygen) {
                playerTimeAccumulators.remove(entityId);
                return; // Not underwater or at full oxygen
            }
            
            // Accumulate time
            float accumulatedTime = playerTimeAccumulators.getOrDefault(entityId, 0f) + dt;
            
            // Only update every UPDATE_INTERVAL seconds to prevent flickering
            if (accumulatedTime < UPDATE_INTERVAL) {
                playerTimeAccumulators.put(entityId, accumulatedTime);
                return;
            }
            
            // Reset accumulator
            playerTimeAccumulators.put(entityId, 0f);
            
            // Calculate oxygen restoration based on enchantment level (20% per level)
            // Apply for the full accumulated time period
            float restorePercent = waterbreathingLevel * (float) EnchantmentType.WATERBREATHING.getEffectMultiplier();
            float oxygenToRestore = BASE_OXYGEN_RESTORE_PER_SECOND * restorePercent * accumulatedTime;
            
            // Apply oxygen restoration (capped at max)
            float newOxygen = Math.min(maxOxygen, currentOxygen + oxygenToRestore);
            if (newOxygen > currentOxygen) {
                statMap.addStatValue(DefaultEntityStatTypes.getOxygen(), oxygenToRestore);
                
                if (entity instanceof com.hypixel.hytale.server.core.entity.entities.Player) {
                    com.hypixel.hytale.server.core.universe.PlayerRef playerRef = store.getComponent(archetypeChunk.getReferenceTo(index), com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                    EnchantmentEventHelper.fireActivated(playerRef, helmet, EnchantmentType.WATERBREATHING, waterbreathingLevel);
                }
            }
            
        } catch (Exception e) {
            LOGGER.atWarning().log("Error in Waterbreathing system: " + e.getMessage());
        }
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return EntityTickingSystem.maybeUseParallel(archetypeChunkSize, taskCount);
    }
}
