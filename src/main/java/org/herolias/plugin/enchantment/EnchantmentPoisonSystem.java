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
 * ECS system that applies the Poison status effect when attacking with a
 * Poison-enchanted weapon.
 * 
 * Applicable to: Melee and Ranged weapons
 */
public class EnchantmentPoisonSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String POISON_EFFECT_ID = "PoisonEnchantment";

    private final EnchantmentManager enchantmentManager;

    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency(Order.AFTER, DamageSystems.ApplyDamage.class));

    public EnchantmentPoisonSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentPoisonSystem initialized");
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

        if (damage.getCause() != null && "Poison".equalsIgnoreCase(damage.getCause().getId()))
            return;

        Boolean isReflection = damage.getIfPresentMetaObject(EnchantmentReflectionSystem.IS_REFLECTION);
        if (isReflection != null && isReflection)
            return;

        EnchantmentManager.DamageContext ctx = enchantmentManager.getDamageContext(damage, commandBuffer);

        int poisonLevel = 0;
        int lootingLevel = 0;

        // 1. Check projectile data first (Ranged)
        if (ctx.hasProjectile()) {
            ProjectileEnchantmentData data = enchantmentManager.getProjectileEnchantmentData(ctx.projectileRef(),
                    commandBuffer);
            if (data != null) {
                lootingLevel = data.getLootingLevel();
                // HACK: As we didn't add poison explicitly to ProjectileEnchantmentData, we
                // just read from the shooter's weapon directly
            }
        }

        // 2. Check attacker's weapon
        if (ctx.hasAttacker()) {
            Entity attackerEntity = EntityUtils.getEntity(ctx.attackerRef(), commandBuffer);
            ItemStack weapon = enchantmentManager.getWeaponFromEntity(attackerEntity);

            if (weapon != null) {
                poisonLevel = enchantmentManager.getEnchantmentLevel(weapon, EnchantmentType.POISON);
                lootingLevel = Math.max(lootingLevel, enchantmentManager.getEnchantmentLevel(weapon, EnchantmentType.LOOTING));
            }
        }

        if (poisonLevel <= 0)
            return;

        Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
        if (targetRef == null || !targetRef.isValid())
            return;

        if (!enchantmentManager.applyStatusEffect(targetRef, POISON_EFFECT_ID, store, commandBuffer)) {
            LOGGER.atWarning().log("Poison effect not found in asset map");
            return;
        }

        if (ctx.hasAttacker()) {
            Entity shooterEntity = EntityUtils.getEntity(ctx.attackerRef(), commandBuffer);
            ItemStack weapon = enchantmentManager.getWeaponFromEntity(shooterEntity);
            if (weapon != null) {
                com.hypixel.hytale.server.core.universe.PlayerRef playerRef = store.getComponent(ctx.attackerRef(),
                        com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                EnchantmentEventHelper.fireActivated(playerRef, weapon, EnchantmentType.POISON, poisonLevel);
            }
        }

        // Store enchantment data on the victim so we can attribute drops if they die
        // from poison
        com.hypixel.hytale.server.core.entity.UUIDComponent targetUuid = commandBuffer.getComponent(targetRef,
                com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
        if (targetUuid != null) {
            enchantmentManager.updateDoTEnchantments(targetUuid.getUuid(), 0, lootingLevel);
        }
    }
}
