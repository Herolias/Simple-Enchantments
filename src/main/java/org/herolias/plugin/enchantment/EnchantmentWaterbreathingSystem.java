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
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ECS system that increases max oxygen based on Waterbreathing enchantment on helmet.
 * 
 * Effect: Extends oxygen capacity, functionally reducing oxygen drain.
 * Applicable to: Helmets only
 * 
 * Uses native Hytale StaticModifiers for seamless integration.
 */
public class EnchantmentWaterbreathingSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    
    // Helmet slot index in armor container (typically slot 0 for head)
    private static final short HELMET_SLOT = 0;
    
    // The key used to register the modifier in the EntityStatMap
    private static final String MODIFIER_KEY = "Enchantment_Waterbreathing";
    
    private final EnchantmentManager enchantmentManager;
    
    // Track active level to prevent unnecessary modifier updates and to trigger events
    private final ConcurrentHashMap<UUID, Integer> activeEnchantmentLevels = new ConcurrentHashMap<>();
    
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
            int waterbreathingLevel = 0;
            if (helmet != null && !helmet.isEmpty()) {
                waterbreathingLevel = enchantmentManager.getEnchantmentLevel(helmet, EnchantmentType.WATERBREATHING);
            }
            
            Integer activeLevelObj = activeEnchantmentLevels.get(entityId);
            int activeLevel = activeLevelObj != null ? activeLevelObj : 0;
            
            if (waterbreathingLevel == activeLevel) {
                return; // Level hasn't changed, no need to update modifiers
            }
            
            EntityStatMap statMap = archetypeChunk.getComponent(index, EntityStatMap.getComponentType());
            if (statMap == null) {
                return;
            }
            
            if (waterbreathingLevel > 0) {
                // Base oxygen is 100. Let's add 250 * multiplier (e.g. 50 at level 1, 150 at level 3)
                float addedOxygen = 250.0f * (float) EnchantmentType.WATERBREATHING.getScaledMultiplier(waterbreathingLevel);
                
                StaticModifier modifier = new StaticModifier(Modifier.ModifierTarget.MAX, StaticModifier.CalculationType.ADDITIVE, addedOxygen);
                statMap.putModifier(EntityStatMap.Predictable.NONE, DefaultEntityStatTypes.getOxygen(), MODIFIER_KEY, modifier);
                
                activeEnchantmentLevels.put(entityId, waterbreathingLevel);
                
                // Only fire "activated" event when the level increases from 0 (equipped)
                if (activeLevel == 0 && entity instanceof com.hypixel.hytale.server.core.entity.entities.Player) {
                    com.hypixel.hytale.server.core.universe.PlayerRef playerRef = store.getComponent(archetypeChunk.getReferenceTo(index), com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                    EnchantmentEventHelper.fireActivated(playerRef, helmet, EnchantmentType.WATERBREATHING, waterbreathingLevel);
                }
            } else {
                // Remove modifier if unequipped
                statMap.removeModifier(EntityStatMap.Predictable.NONE, DefaultEntityStatTypes.getOxygen(), MODIFIER_KEY);
                activeEnchantmentLevels.remove(entityId);
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
