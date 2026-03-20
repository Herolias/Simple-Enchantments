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
 * ECS system that applies the Slow status effect when hitting with a
 * Freeze-enchanted weapon.
 * 
 * Effect: Applies the in-game "Freeze" status effect to the target (speed
 * reduction)
 * Applicable to: Ranged weapons (bow, crossbow, slingshot) and melee weapons
 */
public class EnchantmentFreezeSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String FREEZE_EFFECT_ID = "FreezeEnchantment";

    private final EnchantmentManager enchantmentManager;

    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency(Order.AFTER, DamageSystems.ApplyDamage.class));

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

        if (damage.getAmount() <= 0 || damage.isCancelled())
            return;

        // Use centralized damage context extraction
        EnchantmentManager.DamageContext ctx = enchantmentManager.getDamageContext(damage, commandBuffer);
        Boolean isReflection = damage.getIfPresentMetaObject(EnchantmentReflectionSystem.IS_REFLECTION);
        if (isReflection != null && isReflection)
            return;

        int freezeLevel = 0;

        // 1. Try to get from projectile data (ranged)
        if (ctx.hasProjectile()) {
            ProjectileEnchantmentData projectileData = enchantmentManager
                    .getProjectileEnchantmentData(ctx.projectileRef(), commandBuffer);
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

        if (freezeLevel <= 0)
            return;

        Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
        if (targetRef == null || !targetRef.isValid())
            return;

        // Use centralized status effect application
        // Check target's Environment Protection level to reduce freeze slow intensity
        int totalEnvProtection = 0;
        Entity targetEntity = EntityUtils.getEntity(index, archetypeChunk);
        if (targetEntity instanceof LivingEntity targetLiving) {
            Inventory targetInventory = targetLiving.getInventory();
            if (targetInventory != null) {
                com.hypixel.hytale.server.core.inventory.container.ItemContainer armorContainer = targetInventory
                        .getArmor();
                for (short i = 0; i < armorContainer.getCapacity(); i++) {
                    ItemStack armorPiece = armorContainer.getItemStack(i);
                    if (armorPiece != null && !armorPiece.isEmpty()) {
                        totalEnvProtection += enchantmentManager.getEnchantmentLevel(armorPiece,
                                EnchantmentType.ENVIRONMENTAL_PROTECTION);
                    }
                }
            }
        }

        // If the target has env protection, reduce the freeze slow intensity
        float originalSpeedMult = Float.MIN_VALUE; // sentinel: not modified
        com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect freezeEffect = null;
        if (totalEnvProtection > 0) {
            freezeEffect = com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect.getAssetMap()
                    .getAsset(FREEZE_EFFECT_ID);
            if (freezeEffect != null) {
                try {
                    java.lang.reflect.Field appEffectsField = com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect.class
                            .getDeclaredField("applicationEffects");
                    appEffectsField.setAccessible(true);
                    com.hypixel.hytale.server.core.asset.type.entityeffect.config.ApplicationEffects appEffects = (com.hypixel.hytale.server.core.asset.type.entityeffect.config.ApplicationEffects) appEffectsField
                            .get(freezeEffect);
                    if (appEffects != null) {
                        java.lang.reflect.Field speedMultField = com.hypixel.hytale.server.core.asset.type.entityeffect.config.ApplicationEffects.class
                                .getDeclaredField("horizontalSpeedMultiplier");
                        speedMultField.setAccessible(true);
                        originalSpeedMult = (float) speedMultField.get(appEffects);

                        // Calculate mitigated slow: reduce the slow amount by envProtLevel * multiplier
                        double envProtMultiplier = EnchantmentType.ENVIRONMENTAL_PROTECTION.getEffectMultiplier();
                        double mitigationFraction = Math.min(1.0, totalEnvProtection * envProtMultiplier);
                        // slowAmount = 1.0 - originalSpeedMult (e.g., 0.5 means 50% slow)
                        // Reduce the slow by the mitigation fraction
                        double slowAmount = 1.0 - originalSpeedMult;
                        double mitigatedSlow = slowAmount * (1.0 - mitigationFraction);
                        float newSpeedMult = (float) (1.0 - mitigatedSlow);

                        speedMultField.set(appEffects, newSpeedMult);
                    }
                } catch (Exception e) {
                    LOGGER.atWarning().log("Failed to modify freeze slow for EnvProtection: " + e.getMessage());
                }
            }
        }

        boolean applied = enchantmentManager.applyStatusEffect(targetRef, FREEZE_EFFECT_ID, store, commandBuffer);

        // Restore the original freeze speed multiplier so other targets aren't affected
        if (originalSpeedMult != Float.MIN_VALUE && freezeEffect != null) {
            try {
                java.lang.reflect.Field appEffectsField = com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect.class
                        .getDeclaredField("applicationEffects");
                appEffectsField.setAccessible(true);
                com.hypixel.hytale.server.core.asset.type.entityeffect.config.ApplicationEffects appEffects = (com.hypixel.hytale.server.core.asset.type.entityeffect.config.ApplicationEffects) appEffectsField
                        .get(freezeEffect);
                if (appEffects != null) {
                    java.lang.reflect.Field speedMultField = com.hypixel.hytale.server.core.asset.type.entityeffect.config.ApplicationEffects.class
                            .getDeclaredField("horizontalSpeedMultiplier");
                    speedMultField.setAccessible(true);
                    speedMultField.set(appEffects, originalSpeedMult);
                }
            } catch (Exception e) {
                LOGGER.atWarning().log("Failed to restore freeze slow: " + e.getMessage());
            }
        }

        if (!applied) {
            LOGGER.atWarning().log("Freeze effect " + FREEZE_EFFECT_ID + " not found in asset map");
        } else {
            if (ctx.hasAttacker()) {
                Entity shooterEntity = EntityUtils.getEntity(ctx.attackerRef(), commandBuffer);
                ItemStack weapon = enchantmentManager.getWeaponFromEntity(shooterEntity);
                if (weapon != null) {
                    com.hypixel.hytale.server.core.universe.PlayerRef playerRef = store.getComponent(ctx.attackerRef(),
                            com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                    EnchantmentEventHelper.fireActivated(playerRef, weapon, EnchantmentType.FREEZE, freezeLevel);
                }
            }
        }
    }
}
