package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * ECS system that reduces fall damage based on Feather Falling enchantment on boots.
 * 
 * Effect: Reduces fall damage by 20% per level (up to 60% at level 3)
 * Applicable to: Boots only
 */
public class EnchantmentFeatherFallingSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    
    // Boots slot index in armor container (typically slot 3 for feet)
    private static final short BOOTS_SLOT = 3;
    
    private final EnchantmentManager enchantmentManager;
    
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
        new SystemDependency(Order.BEFORE, DamageSystems.ApplyDamage.class)
    );

    public EnchantmentFeatherFallingSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentFeatherFallingSystem initialized");
    }

    @Override
    @Nonnull
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return com.hypixel.hytale.component.Archetype.empty();
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull Damage damage) {
        
        // Skip if damage is already zero
        if (damage.getAmount() <= 0) {
            return;
        }
        
        // Only process fall damage
        DamageCause damageCause = DamageCause.getAssetMap().getAsset(damage.getDamageCauseIndex());
        if (damageCause == null || !enchantmentManager.isFallDamage(damageCause)) {
            return;
        }
        
        try {
            Entity targetEntity = EntityUtils.getEntity(index, archetypeChunk);
            if (!(targetEntity instanceof LivingEntity targetLiving)) {
                return;
            }
            
            Inventory inventory = targetLiving.getInventory();
            if (inventory == null) {
                return;
            }
            
            ItemContainer armorContainer = inventory.getArmor();
            if (armorContainer == null) {
                return;
            }
            
            // Get boots from armor slot
            ItemStack boots = armorContainer.getItemStack(BOOTS_SLOT);
            if (boots == null || boots.isEmpty()) {
                return;
            }
            
            // Check for Feather Falling enchantment
            int featherFallingLevel = enchantmentManager.getEnchantmentLevel(boots, EnchantmentType.FEATHER_FALLING);
            if (featherFallingLevel <= 0) {
                return;
            }
            
            // Calculate damage reduction (20% per level)
            double reductionPercent = featherFallingLevel * EnchantmentType.FEATHER_FALLING.getEffectMultiplier();
            double multiplier = 1.0 - reductionPercent;
            
            float originalDamage = damage.getAmount();
            float reducedDamage = (float) (originalDamage * multiplier);
            damage.setAmount(reducedDamage);
            
            if (targetEntity instanceof com.hypixel.hytale.server.core.entity.entities.Player) {
                com.hypixel.hytale.server.core.universe.PlayerRef playerRef = store.getComponent(targetEntity.getReference(), com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                EnchantmentEventHelper.fireActivated(playerRef, boots, EnchantmentType.FEATHER_FALLING, featherFallingLevel);
            }
            
            LOGGER.atFine().log("Feather Falling " + featherFallingLevel + " reduced fall damage: " 
                + originalDamage + " -> " + reducedDamage);
            
        } catch (Exception e) {
            LOGGER.atWarning().log("Error in Feather Falling system: " + e.getMessage());
        }
    }
}
