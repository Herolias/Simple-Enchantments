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
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * ECS system that applies the Burn status effect when attacking with a Burn-enchanted weapon.
 * 
 * Effect: Applies the in-game "Burn" status effect to the target (fire visuals, fire damage over time)
 * Applicable to: Melee weapons
 */
public class EnchantmentBurnSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String BURN_EFFECT_ID = "BurnEnchantment";
    
    private final EnchantmentManager enchantmentManager;
    
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
        new SystemDependency(Order.AFTER, DamageSystems.ApplyDamage.class)
    );

    public EnchantmentBurnSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentBurnSystem initialized");
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

        // Check if this is reflected damage - if so, don't apply burn/weapon effects
        Boolean isReflection = damage.getIfPresentMetaObject(EnchantmentReflectionSystem.IS_REFLECTION);
        if (isReflection != null && isReflection) return;
        
        // Use centralized damage context extraction
        EnchantmentManager.DamageContext ctx = enchantmentManager.getDamageContext(damage, commandBuffer);
        
        int burnLevel = 0;
        int lootingLevel = 0;

        // 1. Check projectile data first (if applicable)
        if (ctx.hasProjectile()) {
            ProjectileEnchantmentData data = enchantmentManager.getProjectileEnchantmentData(ctx.projectileRef(), commandBuffer);
            if (data != null) {
                burnLevel = data.getBurnLevel();
                lootingLevel = data.getLootingLevel();
            }
        }

        // 2. Check attacker's weapon (Melee or fallback for Ranged)
        if (burnLevel <= 0 && ctx.hasAttacker()) {
            Entity attackerEntity = EntityUtils.getEntity(ctx.attackerRef(), commandBuffer);
            ItemStack weapon = enchantmentManager.getWeaponFromEntity(attackerEntity);
            
            if (weapon != null) {
                burnLevel = enchantmentManager.getEnchantmentLevel(weapon, EnchantmentType.BURN);
                lootingLevel = enchantmentManager.getEnchantmentLevel(weapon, EnchantmentType.LOOTING);
            }
        }
        
        if (burnLevel <= 0) return;
        
        Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
        if (targetRef == null || !targetRef.isValid()) return;
        
        // Use centralized status effect application
        if (!enchantmentManager.applyStatusEffect(targetRef, BURN_EFFECT_ID, store, commandBuffer)) {
            LOGGER.atWarning().log("Burn effect not found in asset map");
            return;
        }
        
        if (ctx.hasAttacker()) {
             Entity shooterEntity = EntityUtils.getEntity(ctx.attackerRef(), commandBuffer);
             ItemStack weapon = enchantmentManager.getWeaponFromEntity(shooterEntity);
             if (weapon != null) {
                  com.hypixel.hytale.server.core.universe.PlayerRef playerRef = store.getComponent(ctx.attackerRef(), com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                  EnchantmentEventHelper.fireActivated(playerRef, weapon, EnchantmentType.BURN, burnLevel);
             }
        }
        
        // Store enchantment data on the victim so we can attribute drops if they die from burn
        com.hypixel.hytale.server.core.entity.UUIDComponent targetUuid = commandBuffer.getComponent(targetRef, com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
        if (targetUuid != null) {
            enchantmentManager.storeBurnEnchantments(targetUuid.getUuid(), burnLevel, lootingLevel);
        }
    }
}
