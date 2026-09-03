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
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.PlayerRef;
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

    /** Only entities with stats can be damaged (same scope as {@code DamageSystems.ApplyDamage}). */
    private static final Query<EntityStore> QUERY = Query.and(EntityStatMap.getComponentType());

    private final EnchantmentManager enchantmentManager;

    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency(Order.AFTER, DamageSystems.ApplyDamage.class));

    /**
     * Asset index of the {@code Poison} damage cause, i.e. the poison DoT's own
     * ticks. Resolved lazily; {@link Integer#MIN_VALUE} while unknown.
     */
    private volatile int poisonCauseIndex = Integer.MIN_VALUE;

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
        return QUERY;
    }

    @Override
    public void handle(int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Damage damage) {

        if (damage.getAmount() <= 0 || damage.isCancelled())
            return;

        // The poison DoT itself deals "Poison" damage; never re-apply poison from its own ticks.
        int poisonIndex = poisonCauseIndex();
        if (poisonIndex != Integer.MIN_VALUE && damage.getDamageCauseIndex() == poisonIndex)
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
                poisonLevel = data.getPoisonLevel();
            }
        }

        // 2. Check attacker's weapon - a single metadata read for both levels
        ItemStack weapon = ctx.hasAttacker()
                ? enchantmentManager.getWeaponFromEntity(ctx.attackerRef(), commandBuffer)
                : null;
        if (weapon != null) {
            int[] levels = enchantmentManager.getEnchantmentLevels(weapon, EnchantmentType.POISON,
                    EnchantmentType.LOOTING);
            if (poisonLevel <= 0) {
                poisonLevel = levels[0];
            }
            lootingLevel = Math.max(lootingLevel, levels[1]);
        }

        if (poisonLevel <= 0)
            return;

        Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
        if (targetRef == null || !targetRef.isValid())
            return;

        // applyStatusEffect already logs when the asset is missing.
        if (!enchantmentManager.applyStatusEffect(targetRef, POISON_EFFECT_ID, store, commandBuffer))
            return;

        if (weapon != null) {
            PlayerRef playerRef = store.getComponent(ctx.attackerRef(), PlayerRef.getComponentType());
            EnchantmentEventHelper.fireActivated(playerRef, weapon, EnchantmentType.POISON, poisonLevel);
        }

        // Remember the looting level on the victim so a death caused by the DoT
        // can still be attributed. Only written when there is something to keep.
        if (lootingLevel > 0) {
            UUIDComponent targetUuid = commandBuffer.getComponent(targetRef, UUIDComponent.getComponentType());
            if (targetUuid != null) {
                enchantmentManager.updateDoTEnchantments(targetUuid.getUuid(), 0, lootingLevel);
            }
        }
    }

    private int poisonCauseIndex() {
        int idx = poisonCauseIndex;
        if (idx == Integer.MIN_VALUE) {
            idx = DamageCause.getAssetMap().getIndexOrDefault("Poison", Integer.MIN_VALUE);
            poisonCauseIndex = idx;
        }
        return idx;
    }
}
