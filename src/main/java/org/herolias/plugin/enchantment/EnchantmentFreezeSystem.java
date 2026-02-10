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
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * ECS system that applies the Slow status effect when hitting with a Freeze-enchanted weapon.
 * 
 * Effect: Applies the in-game "Freeze" status effect to the target (speed reduction)
 * Applicable to: Ranged weapons (bow, crossbow, slingshot) and melee weapons
 */
public class EnchantmentFreezeSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String FREEZE_EFFECT_ID = "Freeze_I";
    
    private final EnchantmentManager enchantmentManager;
    
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
        new SystemDependency(Order.AFTER, DamageSystems.ApplyDamage.class)
    );

    public EnchantmentFreezeSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentFreezeSystem initialized");
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
        
        if (damage.getAmount() <= 0 || damage.isCancelled()) return;
        
        // Use centralized damage context extraction
        EnchantmentManager.DamageContext ctx = enchantmentManager.getDamageContext(damage, commandBuffer);
        
        int freezeLevel = 0;
        
        // 1. Try to get from projectile data (ranged)
        if (ctx.hasProjectile()) {
            ProjectileEnchantmentData projectileData = enchantmentManager.getProjectileEnchantmentData(ctx.projectileRef(), commandBuffer);
            if (projectileData != null) {
                freezeLevel = projectileData.getFreezeLevel();
            }
        }
        
        // 2. Fallback / Melee: check shooter's weapon directly
        if (freezeLevel <= 0 && ctx.hasAttacker()) {
            Entity shooterEntity = EntityUtils.getEntity(ctx.attackerRef(), commandBuffer);
            if (shooterEntity instanceof LivingEntity shooter) {
                Inventory inventory = shooter.getInventory();
                if (inventory != null) {
                    ItemStack weapon = inventory.getItemInHand();
                    if (weapon != null && !weapon.isEmpty()) {
                        freezeLevel = enchantmentManager.getEnchantmentLevel(weapon, EnchantmentType.FREEZE);
                    }
                }
            }
        }
        
        if (freezeLevel <= 0) return;
        
        Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
        if (targetRef == null || !targetRef.isValid()) return;
        
        // Use centralized status effect application
        if (!enchantmentManager.applyStatusEffect(targetRef, FREEZE_EFFECT_ID, store, commandBuffer)) {
            LOGGER.atWarning().log("Freeze effect " + FREEZE_EFFECT_ID + " not found in asset map");
        }
    }
}
