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
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.herolias.plugin.util.InventoryAccess;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ECS system that applies the Slow status effect when hitting with a
 * Freeze-enchanted weapon.
 * 
 * Effect: Applies the in-game "Freeze" status effect to the target (speed
 * reduction)
 * Applicable to: Ranged weapons (bow, crossbow, slingshot) and melee weapons
 * <p>
 * <b>Environmental Protection mitigation.</b> A target wearing armor with
 * Environmental Protection is slowed less. Instead of mutating the shared
 * {@code FreezeEnchantment} asset per hit, one pre-built variant asset per
 * mitigation level is shipped:
 * <pre>
 *   Server/Entity/Effects/Status/FreezeEnchantment_EnvProt_1.json  (HorizontalSpeedMultiplier 0.52)
 *   Server/Entity/Effects/Status/FreezeEnchantment_EnvProt_2.json  (HorizontalSpeedMultiplier 0.54)
 *   Server/Entity/Effects/Status/FreezeEnchantment_EnvProt_3.json  (HorizontalSpeedMultiplier 0.56)
 * </pre>
 * The values follow the formula previously computed at runtime:
 * {@code slow = 1 - baseMultiplier} (base 0.5 → slow 0.5),
 * {@code mitigation = min(1, level * ENVIRONMENTAL_PROTECTION multiplier)} (default 0.04/level),
 * {@code newMultiplier = 1 - slow * (1 - mitigation)}. The JSON values assume the
 * default {@code environmental_protection} multiplier (0.04) and the default
 * freeze base slow (0.5); if an admin changes those in the config the variant
 * is still selected by level but keeps the default-multiplier values baked into
 * the JSON. The variants carry the same {@code Duration} field as the base
 * effect so the dynamic-effects code can patch them the same way.
 */
public class EnchantmentFreezeSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String FREEZE_EFFECT_ID = "FreezeEnchantment";

    /** Index = clamped total Environmental Protection level (0..3). */
    private static final String[] FREEZE_EFFECT_IDS_BY_ENV_PROT = {
            FREEZE_EFFECT_ID,
            "FreezeEnchantment_EnvProt_1",
            "FreezeEnchantment_EnvProt_2",
            "FreezeEnchantment_EnvProt_3"
    };
    private static final int MAX_ENV_PROT_VARIANT = FREEZE_EFFECT_IDS_BY_ENV_PROT.length - 1;

    /** Only entities with stats can be damaged (same scope as {@code DamageSystems.ApplyDamage}). */
    private static final Query<EntityStore> QUERY = Query.and(EntityStatMap.getComponentType());

    private final EnchantmentManager enchantmentManager;

    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency(Order.AFTER, DamageSystems.ApplyDamage.class));

    /** Logged once if a variant asset is missing and the base effect had to be used instead. */
    private final AtomicBoolean variantMissingLogged = new AtomicBoolean();

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

        Boolean isReflection = damage.getIfPresentMetaObject(EnchantmentReflectionSystem.IS_REFLECTION);
        if (isReflection != null && isReflection)
            return;

        // Use centralized damage context extraction
        EnchantmentManager.DamageContext ctx = enchantmentManager.getDamageContext(damage, commandBuffer);

        int freezeLevel = 0;

        // 1. Try to get from projectile data (ranged)
        if (ctx.hasProjectile()) {
            ProjectileEnchantmentData projectileData = enchantmentManager
                    .getProjectileEnchantmentData(ctx.projectileRef(), commandBuffer);
            if (projectileData != null) {
                freezeLevel = projectileData.getFreezeLevel();
            }
        }

        // 2. Fallback / Melee: check the attacker's held item directly
        ItemStack weapon = ctx.hasAttacker()
                ? enchantmentManager.getWeaponFromEntity(ctx.attackerRef(), commandBuffer)
                : null;
        if (freezeLevel <= 0 && weapon != null) {
            freezeLevel = enchantmentManager.getEnchantmentLevel(weapon, EnchantmentType.FREEZE);
        }

        if (freezeLevel <= 0)
            return;

        Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
        if (targetRef == null || !targetRef.isValid())
            return;

        // Pick the variant matching the target's total Environmental Protection level
        // (one metadata read per armor piece, no asset mutation).
        int totalEnvProtection = 0;
        ItemContainer armor = InventoryAccess.getArmor(commandBuffer, targetRef);
        if (armor != null) {
            totalEnvProtection = enchantmentManager.sumArmorEnchantmentLevels(armor,
                    EnchantmentType.ENVIRONMENTAL_PROTECTION)[0];
        }
        String effectId = selectEffectId(totalEnvProtection);

        // applyStatusEffect already logs when the asset is missing.
        if (!enchantmentManager.applyStatusEffect(targetRef, effectId, store, commandBuffer))
            return;

        if (weapon != null) {
            PlayerRef playerRef = store.getComponent(ctx.attackerRef(), PlayerRef.getComponentType());
            EnchantmentEventHelper.fireActivated(playerRef, weapon, EnchantmentType.FREEZE, freezeLevel);
        }
    }

    /**
     * Resolves the effect id for the given total Environmental Protection level,
     * falling back to the base effect if the variant asset is not loaded.
     */
    @Nonnull
    private String selectEffectId(int totalEnvProtection) {
        int variant = Math.min(Math.max(totalEnvProtection, 0), MAX_ENV_PROT_VARIANT);
        if (variant == 0) {
            return FREEZE_EFFECT_ID;
        }
        String variantId = FREEZE_EFFECT_IDS_BY_ENV_PROT[variant];
        if (EntityEffect.getAssetMap().getAsset(variantId) != null) {
            return variantId;
        }
        if (variantMissingLogged.compareAndSet(false, true)) {
            LOGGER.atWarning().log(
                    "Freeze effect variant '%s' is not loaded; Environmental Protection will not mitigate freeze",
                    variantId);
        }
        return FREEZE_EFFECT_ID;
    }
}
