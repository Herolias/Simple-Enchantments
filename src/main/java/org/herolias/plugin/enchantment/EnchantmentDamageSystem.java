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
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * ECS system that hooks into damage calculations to apply enchantment bonuses.
 * 
 * Supports: Sharpness (+10% damage/level), Protection (reduces physical damage),
 * Strength/Eagle's Eye (projectile damage), Life Leech (health on melee hit)
 */
public class EnchantmentDamageSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    
    private final EnchantmentManager enchantmentManager;

    private final Set<Dependency<EntityStore>> dependencies = Set.of(
        new SystemDependency(Order.BEFORE, DamageSystems.ApplyDamage.class)
    );

    public EnchantmentDamageSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentDamageSystem initialized");
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
        
        float currentDamage = damage.getAmount();
        if (currentDamage <= 0) return;
        
        // Use centralized damage context extraction
        EnchantmentManager.DamageContext ctx = enchantmentManager.getDamageContext(damage, commandBuffer);
        
        // Apply Sharpness (melee entity damage)
        if (ctx.hasAttacker() && !ctx.hasProjectile()) {
            Entity attackerEntity = EntityUtils.getEntity(ctx.attackerRef(), commandBuffer);
            ItemStack weapon = enchantmentManager.getWeaponFromEntity(attackerEntity);
            if (weapon != null) {
                double multiplier = enchantmentManager.calculateDamageMultiplier(weapon);
                if (multiplier != 1.0) {
                    damage.setAmount((float) (damage.getAmount() * multiplier));
                }
            }
        }

        // Apply Strength/Eagle's Eye for projectile damage
        DamageCause damageCause = DamageCause.getAssetMap().getAsset(damage.getDamageCauseIndex());
        if (damageCause != null && enchantmentManager.isProjectileDamage(damageCause) && ctx.hasAttacker()) {
            applyProjectileDamageModifiers(ctx, index, archetypeChunk, commandBuffer, damage);
        }

        // Apply Protection enchantment (defender)
        if (damageCause != null && !damageCause.doesBypassResistances() && enchantmentManager.isPhysicalDamage(damageCause)) {
            Entity targetEntity = EntityUtils.getEntity(index, archetypeChunk);
            if (targetEntity instanceof LivingEntity targetLiving) {
                Inventory targetInventory = targetLiving.getInventory();
                if (targetInventory != null) {
                    double protectionMultiplier = enchantmentManager.calculateProtectionMultiplier(targetInventory.getArmor());
                    if (protectionMultiplier < 1.0) {
                        damage.setAmount((float) (damage.getAmount() * protectionMultiplier));
                    }
                }
            }
        }

        // Apply Ranged Protection enchantment (defender - projectile/magic damage)
        if (damageCause != null && !damageCause.doesBypassResistances() && enchantmentManager.isRangedDamage(damageCause)) {
            Entity targetEntity = EntityUtils.getEntity(index, archetypeChunk);
            if (targetEntity instanceof LivingEntity targetLiving) {
                Inventory targetInventory = targetLiving.getInventory();
                if (targetInventory != null) {
                    double rangedProtectionMultiplier = enchantmentManager.calculateRangedProtectionMultiplier(targetInventory.getArmor());
                    if (rangedProtectionMultiplier < 1.0) {
                        damage.setAmount((float) (damage.getAmount() * rangedProtectionMultiplier));
                    }
                }
            }
        }

        // Apply Life Leech (melee only)
        applyLifeLeech(ctx, damage, commandBuffer);
    }

    private void applyLifeLeech(EnchantmentManager.DamageContext ctx, 
                                @Nonnull Damage damage, 
                                @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (damage.getAmount() <= 0) return;
        if (ctx.hasProjectile()) return;  // Melee only
        if (!ctx.hasAttacker()) return;

        Entity attackerEntity = EntityUtils.getEntity(ctx.attackerRef(), commandBuffer);
        ItemStack weapon = enchantmentManager.getWeaponFromEntity(attackerEntity);
        if (weapon == null) return;

        ItemCategory category = enchantmentManager.categorizeItem(weapon);
        if (category != ItemCategory.MELEE_WEAPON) return;

        int lifeLeechLevel = enchantmentManager.getEnchantmentLevel(weapon, EnchantmentType.LIFE_LEECH);
        if (lifeLeechLevel <= 0) return;

        float healAmount = (float) (damage.getAmount() * (lifeLeechLevel * EnchantmentType.LIFE_LEECH.getEffectMultiplier()));
        if (healAmount <= 0.0f) return;

        EntityStatMap statMap = commandBuffer.getComponent(ctx.attackerRef(), EntityStatMap.getComponentType());
        if (statMap != null) {
            statMap.addStatValue(DefaultEntityStatTypes.getHealth(), healAmount);
        }
    }

    private void applyProjectileDamageModifiers(EnchantmentManager.DamageContext ctx,
                                                int index,
                                                @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                                                @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                                @Nonnull Damage damage) {
        TransformComponent shooterTransform = commandBuffer.getComponent(ctx.attackerRef(), TransformComponent.getComponentType());
        TransformComponent targetTransform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        double distance = 0.0;
        if (shooterTransform != null && targetTransform != null) {
            distance = shooterTransform.getPosition().distanceTo(targetTransform.getPosition());
        }

        ProjectileEnchantmentData projectileData = ctx.hasProjectile()
            ? enchantmentManager.getProjectileEnchantmentData(ctx.projectileRef(), commandBuffer)
            : null;
            
        double multiplier = 1.0;
        if (projectileData != null && projectileData.hasAny()) {
            multiplier = enchantmentManager.calculateProjectileDamageMultiplier(
                projectileData.getStrengthLevel(),
                projectileData.getEaglesEyeLevel(),
                distance
            );
        } else if (ctx.hasAttacker()) {
            Entity shooterEntity = EntityUtils.getEntity(ctx.attackerRef(), commandBuffer);
            ItemStack weapon = enchantmentManager.getWeaponFromEntity(shooterEntity);
            
            if (weapon != null) {
                ItemCategory category = enchantmentManager.categorizeItem(weapon);
                if (category == ItemCategory.RANGED_WEAPON || category == ItemCategory.STAFF || 
                    category == ItemCategory.STAFF_MANA || category == ItemCategory.STAFF_ESSENCE) {
                    multiplier = enchantmentManager.calculateProjectileDamageMultiplier(weapon, distance);
                }
            }
        }
        
        if (multiplier != 1.0) {
            damage.setAmount((float) (damage.getAmount() * multiplier));
        }
    }
}
