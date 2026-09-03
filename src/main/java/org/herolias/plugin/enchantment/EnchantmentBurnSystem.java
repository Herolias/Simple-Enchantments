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
 * ECS system that applies the Burn status effect when attacking with a
 * Burn-enchanted weapon.
 * 
 * Effect: Applies the in-game "Burn" status effect to the target (fire visuals,
 * fire damage over time)
 * Applicable to: Melee weapons
 */
public class EnchantmentBurnSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String BURN_EFFECT_ID = "BurnEnchantment";

    /** Only entities with stats can be damaged (same scope as {@code DamageSystems.ApplyDamage}). */
    private static final Query<EntityStore> QUERY = Query.and(EntityStatMap.getComponentType());

    private final EnchantmentManager enchantmentManager;

    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency(Order.AFTER, DamageSystems.ApplyDamage.class));

    /**
     * Asset index of the {@code Fire} damage cause, i.e. the Burn DoT's own ticks.
     * Resolved lazily on the first hit; {@link Integer#MIN_VALUE} while unknown.
     */
    private volatile int fireCauseIndex = Integer.MIN_VALUE;

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

        // The burn DoT itself deals "Fire" damage; never re-apply burn from its own ticks.
        int fireIndex = fireCauseIndex();
        if (fireIndex != Integer.MIN_VALUE && damage.getDamageCauseIndex() == fireIndex)
            return;

        // Check if this is reflected damage - if so, don't apply burn/weapon effects
        Boolean isReflection = damage.getIfPresentMetaObject(EnchantmentReflectionSystem.IS_REFLECTION);
        if (isReflection != null && isReflection)
            return;

        // Use centralized damage context extraction
        EnchantmentManager.DamageContext ctx = enchantmentManager.getDamageContext(damage, commandBuffer);

        int burnLevel = 0;
        int lootingLevel = 0;

        // 1. Check projectile data first (if applicable)
        if (ctx.hasProjectile()) {
            ProjectileEnchantmentData data = enchantmentManager.getProjectileEnchantmentData(ctx.projectileRef(),
                    commandBuffer);
            if (data != null) {
                burnLevel = data.getBurnLevel();
                lootingLevel = data.getLootingLevel();
            }
        }

        // 2. Check attacker's weapon (Melee or fallback for Ranged) - one metadata read
        ItemStack weapon = ctx.hasAttacker()
                ? enchantmentManager.getWeaponFromEntity(ctx.attackerRef(), commandBuffer)
                : null;
        if (burnLevel <= 0 && weapon != null) {
            int[] levels = enchantmentManager.getEnchantmentLevels(weapon, EnchantmentType.BURN,
                    EnchantmentType.LOOTING);
            burnLevel = levels[0];
            lootingLevel = levels[1];
        }

        if (burnLevel <= 0)
            return;

        Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
        if (targetRef == null || !targetRef.isValid())
            return;

        // applyStatusEffect already logs when the asset is missing.
        if (!enchantmentManager.applyStatusEffect(targetRef, BURN_EFFECT_ID, store, commandBuffer))
            return;

        if (weapon != null) {
            PlayerRef playerRef = store.getComponent(ctx.attackerRef(), PlayerRef.getComponentType());
            EnchantmentEventHelper.fireActivated(playerRef, weapon, EnchantmentType.BURN, burnLevel);
        }

        // Remember the burn/looting levels on the victim so a death caused by the
        // DoT can still be attributed (drop cooking / looting). Entries carry a
        // creation timestamp so the manager can expire them.
        UUIDComponent targetUuid = commandBuffer.getComponent(targetRef, UUIDComponent.getComponentType());
        if (targetUuid != null) {
            enchantmentManager.updateDoTEnchantments(targetUuid.getUuid(), burnLevel, lootingLevel);
        }
    }

    private int fireCauseIndex() {
        int idx = fireCauseIndex;
        if (idx == Integer.MIN_VALUE) {
            idx = DamageCause.getAssetMap().getIndexOrDefault("Fire", Integer.MIN_VALUE);
            fireCauseIndex = idx;
        }
        return idx;
    }
}
