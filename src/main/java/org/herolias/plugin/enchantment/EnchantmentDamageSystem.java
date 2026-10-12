package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.dependency.SystemGroupDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCalculatorSystems;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.DamageEntityInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.herolias.plugin.util.InventoryAccess;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * ECS system that hooks into damage calculations to apply enchantment bonuses.
 * 
 * Supports: Sharpness (+10% damage/level), Protection (reduces physical
 * damage),
 * Strength/Eagle's Eye (projectile damage), Life Leech (health on melee hit),
 * Frenzy (signature energy on hit).
 * <p>
 * Per hit, the attacker's weapon metadata is read exactly once
 * ({@link EnchantmentManager#getEnchantmentLevels}) and the defender's armor is
 * scanned once ({@link EnchantmentManager#calculateArmorProtection}).
 */
public class EnchantmentDamageSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final EnchantmentManager enchantmentManager;

    /** Only entities with stats can be damaged (same scope as {@code DamageSystems.ApplyDamage}). */
    private static final Query<EntityStore> QUERY = Query.and(EntityStatMap.getComponentType());

    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemGroupDependency<>(Order.AFTER, DamageModule.get().getFilterDamageGroup()),
            // SequenceModifier increments the per-swing hit counter that vanilla feeds
            // into EntityStatOnHit; running after it lets Frenzy use the same count.
            new SystemDependency(Order.AFTER, DamageCalculatorSystems.SequenceModifier.class),
            new SystemDependency(Order.BEFORE, DamageSystems.ApplyDamage.class));

    /** Positions in the single per-hit weapon level read. */
    private static final EnchantmentType[] WEAPON_TYPES = {
            EnchantmentType.SHARPNESS, EnchantmentType.LIFE_LEECH, EnchantmentType.FRENZY,
            EnchantmentType.STRENGTH, EnchantmentType.EAGLES_EYE };
    private static final int SHARPNESS = 0;
    private static final int LIFE_LEECH = 1;
    private static final int FRENZY = 2;
    private static final int STRENGTH = 3;
    private static final int EAGLES_EYE = 4;

    /** Positions in the per-piece armor level read used for event attribution. */
    private static final EnchantmentType[] PROTECTION_TYPES = {
            EnchantmentType.PROTECTION, EnchantmentType.RANGED_PROTECTION, EnchantmentType.ENVIRONMENTAL_PROTECTION };

    /**
     * Plain-field view of a {@link DamageEntityInteraction.EntityStatOnHit}. The
     * server exposes those fields only through {@code toPacket()}, so the packet
     * is built once per (static) interaction asset and cached by identity.
     */
    private record ChargeInfo(int statIndex, float amount, float[] multipliersPerEntitiesHit,
            float multiplierPerExtraEntityHit) {

        /** Same falloff vanilla applies in {@code EntityStatOnHit.processEntityStatsOnHit}. */
        float chainMultiplier(int hits) {
            float[] multipliers = multipliersPerEntitiesHit;
            if (multipliers == null || multipliers.length == 0) {
                return 1.0f;
            }
            return hits <= multipliers.length ? multipliers[hits - 1] : multiplierPerExtraEntityHit;
        }
    }

    private final Map<DamageEntityInteraction.EntityStatOnHit, ChargeInfo> chargeInfoCache = Collections
            .synchronizedMap(new WeakHashMap<>());

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
        return QUERY;
    }

    @Override
    public void handle(int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Damage damage) {

        if (damage.isCancelled() || damage.getAmount() <= 0)
            return;

        // Reflected damage is dealt by a shield, not a weapon: the "attacker" is the
        // reflection victim, so none of their weapon enchantments may apply. Their
        // armor (defender side) still counts.
        Boolean reflected = damage.getIfPresentMetaObject(EnchantmentReflectionSystem.IS_REFLECTION);
        boolean isReflection = reflected != null && reflected;

        // Use centralized damage context extraction
        EnchantmentManager.DamageContext ctx = enchantmentManager.getDamageContext(damage, commandBuffer);
        DamageCause damageCause = enchantmentManager.getDamageCause(damage.getDamageCauseIndex());

        boolean attackerSide = !isReflection && ctx.hasAttacker();
        ItemStack weapon = null;
        int[] weaponLevels = null;
        if (attackerSide) {
            weapon = enchantmentManager.getWeaponFromEntity(ctx.attackerRef(), commandBuffer);
            if (weapon != null) {
                weaponLevels = enchantmentManager.getEnchantmentLevels(weapon, WEAPON_TYPES);
            }
        }

        // Apply Sharpness (melee entity damage)
        if (weapon != null && !ctx.hasProjectile()) {
            applySharpness(ctx, store, damage, weapon, weaponLevels[SHARPNESS]);
        }

        // Apply Strength/Eagle's Eye whenever a projectile entity was involved
        if (attackerSide && ctx.hasProjectile()) {
            applyProjectileDamageModifiers(ctx, index, archetypeChunk, store, commandBuffer, damage, weapon,
                    weaponLevels);
        }

        // Apply Protection / Ranged Protection / Environmental Protection (defender)
        if (damageCause != null && !damageCause.doesBypassResistances()) {
            applyArmorProtection(index, archetypeChunk, store, commandBuffer, damage, damageCause);
        }

        // Apply Life Leech (melee only)
        if (weapon != null && !ctx.hasProjectile()) {
            applyLifeLeech(ctx, store, damage, commandBuffer, weapon, weaponLevels[LIFE_LEECH]);
        }

        // Apply Frenzy (ability charge)
        if (weapon != null) {
            applyFrenzy(ctx, store, damage, commandBuffer, weapon, weaponLevels[FRENZY]);
        }
    }

    @Nullable
    private static PlayerRef attackerPlayerRef(@Nonnull Store<EntityStore> store,
            @Nonnull EnchantmentManager.DamageContext ctx) {
        return store.getComponent(ctx.attackerRef(), PlayerRef.getComponentType());
    }

    private void applySharpness(@Nonnull EnchantmentManager.DamageContext ctx,
            @Nonnull Store<EntityStore> store,
            @Nonnull Damage damage,
            @Nonnull ItemStack weapon,
            int sharpnessLevel) {
        if (sharpnessLevel <= 0)
            return;
        double multiplier = 1.0 + sharpnessLevel * EnchantmentType.SHARPNESS.getEffectMultiplier();
        if (multiplier == 1.0)
            return;
        damage.setAmount((float) (damage.getAmount() * multiplier));
        EnchantmentEventHelper.fireActivated(attackerPlayerRef(store, ctx), weapon, EnchantmentType.SHARPNESS,
                sharpnessLevel);
    }

    private void applyArmorProtection(int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Damage damage,
            @Nonnull DamageCause damageCause) {
        boolean physical = enchantmentManager.isPhysicalDamage(damageCause);
        boolean ranged = enchantmentManager.isRangedDamage(damageCause);
        boolean environmental = enchantmentManager.isEnvironmentalDamage(damageCause);
        if (!physical && !ranged && !environmental)
            return;

        Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
        ItemContainer armor = InventoryAccess.getArmor(commandBuffer, targetRef);
        if (armor == null)
            return;

        // One pass over the armor for all three protection multipliers
        EnchantmentManager.ArmorProtection protection = enchantmentManager.calculateArmorProtection(armor);
        boolean applyPhysical = physical && protection.physical() < 1.0;
        boolean applyRanged = ranged && protection.ranged() < 1.0;
        boolean applyEnvironmental = environmental && protection.environmental() < 1.0;
        if (!applyPhysical && !applyRanged && !applyEnvironmental)
            return;

        // Same order (and float rounding) as the former per-type applications
        if (applyPhysical)
            damage.setAmount((float) (damage.getAmount() * protection.physical()));
        if (applyRanged)
            damage.setAmount((float) (damage.getAmount() * protection.ranged()));
        if (applyEnvironmental)
            damage.setAmount((float) (damage.getAmount() * protection.environmental()));

        // Event attribution: first armor piece carrying each applied protection type.
        // One metadata read per piece.
        ItemStack[] firstPiece = new ItemStack[PROTECTION_TYPES.length];
        int[] firstLevel = new int[PROTECTION_TYPES.length];
        boolean[] wanted = { applyPhysical, applyRanged, applyEnvironmental };
        int remaining = (applyPhysical ? 1 : 0) + (applyRanged ? 1 : 0) + (applyEnvironmental ? 1 : 0);
        for (short i = 0; i < armor.getCapacity() && remaining > 0; i++) {
            ItemStack armorPiece = armor.getItemStack(i);
            if (armorPiece == null || armorPiece.isEmpty())
                continue;
            int[] levels = enchantmentManager.getEnchantmentLevels(armorPiece, PROTECTION_TYPES);
            for (int t = 0; t < PROTECTION_TYPES.length; t++) {
                if (wanted[t] && firstPiece[t] == null && levels[t] > 0) {
                    firstPiece[t] = armorPiece;
                    firstLevel[t] = levels[t];
                    remaining--;
                }
            }
        }

        PlayerRef playerRef = store.getComponent(targetRef, PlayerRef.getComponentType());
        for (int t = 0; t < PROTECTION_TYPES.length; t++) {
            if (firstPiece[t] != null) {
                EnchantmentEventHelper.fireActivated(playerRef, firstPiece[t], PROTECTION_TYPES[t], firstLevel[t]);
            }
        }
    }

    private void applyLifeLeech(@Nonnull EnchantmentManager.DamageContext ctx,
            @Nonnull Store<EntityStore> store,
            @Nonnull Damage damage,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull ItemStack weapon,
            int lifeLeechLevel) {
        if (damage.getAmount() <= 0)
            return;
        if (lifeLeechLevel <= 0)
            return;

        ItemCategory category = enchantmentManager.categorizeItem(weapon);
        if (category != ItemCategory.MELEE_WEAPON)
            return;

        float healAmount = (float) (damage.getAmount()
                * (lifeLeechLevel * EnchantmentType.LIFE_LEECH.getEffectMultiplier()));
        if (healAmount <= 0.0f)
            return;

        EntityStatMap statMap = commandBuffer.getComponent(ctx.attackerRef(), EntityStatMap.getComponentType());
        if (statMap == null)
            return;

        statMap.addStatValue(DefaultEntityStatTypes.getHealth(), healAmount);
        EnchantmentEventHelper.fireActivated(attackerPlayerRef(store, ctx), weapon, EnchantmentType.LIFE_LEECH,
                lifeLeechLevel);
    }

    private void applyFrenzy(@Nonnull EnchantmentManager.DamageContext ctx,
            @Nonnull Store<EntityStore> store,
            @Nonnull Damage damage,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull ItemStack weapon,
            int frenzyLevel) {
        if (damage.getAmount() <= 0)
            return;
        // Frenzy applies to all weapons (Melee, Ranged, Staves)
        if (frenzyLevel <= 0)
            return;

        // Calculate bonus charge amount
        float chargeAmount = 0.0f;
        boolean foundBase = false;

        // Base charge from the DamageSequence (native Hytale charge logic), including
        // the per-swing multi-target falloff vanilla applies in SequenceModifier.
        DamageCalculatorSystems.DamageSequence seq = damage
                .getIfPresentMetaObject(DamageCalculatorSystems.DAMAGE_SEQUENCE);
        if (seq != null) {
            DamageEntityInteraction.EntityStatOnHit[] stats = seq.getEntityStatOnHit();
            if (stats != null) {
                int hits = Math.max(1, seq.getSequentialHits());
                int signatureEnergy = DefaultEntityStatTypes.getSignatureEnergy();
                for (DamageEntityInteraction.EntityStatOnHit stat : stats) {
                    ChargeInfo info = chargeInfo(stat);
                    if (info.statIndex() != signatureEnergy)
                        continue;
                    chargeAmount += info.amount() * info.chainMultiplier(hits);
                    foundBase = true; // Found at least one source of charge
                }
            }
        }

        if (foundBase) {
            // If we found a base charge, increase it by the percentage (e.g. +10% per
            // level)
            chargeAmount = (float) (chargeAmount * enchantmentManager.calculateFrenzySpeedMultiplier(frenzyLevel));
        } else {
            // Fallback: 1% of damage * level (equivalent to +10% of an assumed 10% base
            // charge)
            // This ensures the enchantment works on non-charging attacks effectively adding
            // a charge mechanic.
            chargeAmount = (float) (damage.getAmount() * 0.01 * frenzyLevel);
        }

        if (chargeAmount <= 0.0f)
            return;

        EntityStatMap statMap = commandBuffer.getComponent(ctx.attackerRef(), EntityStatMap.getComponentType());
        if (statMap == null)
            return;

        statMap.addStatValue(DefaultEntityStatTypes.getSignatureEnergy(), chargeAmount);
        EnchantmentEventHelper.fireActivated(attackerPlayerRef(store, ctx), weapon, EnchantmentType.FRENZY,
                frenzyLevel);
    }

    @Nonnull
    private ChargeInfo chargeInfo(@Nonnull DamageEntityInteraction.EntityStatOnHit stat) {
        return chargeInfoCache.computeIfAbsent(stat, s -> {
            com.hypixel.hytale.protocol.EntityStatOnHit packet = s.toPacket();
            return new ChargeInfo(packet.entityStatIndex, packet.amount, packet.multipliersPerEntitiesHit,
                    packet.multiplierPerExtraEntityHit);
        });
    }

    private void applyProjectileDamageModifiers(@Nonnull EnchantmentManager.DamageContext ctx,
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Damage damage,
            @Nullable ItemStack weapon,
            @Nullable int[] weaponLevels) {
        // Levels snapshotted on the projectile when it was fired take precedence;
        // fall back to the ranged weapon still in the shooter's hand.
        int strengthLevel = 0;
        int eaglesEyeLevel = 0;
        ProjectileEnchantmentData projectileData = enchantmentManager.getProjectileEnchantmentData(ctx.projectileRef(),
                commandBuffer);
        if (projectileData != null && projectileData.hasAny()) {
            strengthLevel = projectileData.getStrengthLevel();
            eaglesEyeLevel = projectileData.getEaglesEyeLevel();
        } else if (weapon != null && weaponLevels != null
                && enchantmentManager.categorizeItem(weapon) == ItemCategory.RANGED_WEAPON) {
            // Strength / Eagle's Eye are ranged-weapon-only enchantments, so there is
            // nothing to read from staffs.
            strengthLevel = weaponLevels[STRENGTH];
            eaglesEyeLevel = weaponLevels[EAGLES_EYE];
        }
        if (strengthLevel <= 0 && eaglesEyeLevel <= 0)
            return;

        TransformComponent shooterTransform = commandBuffer.getComponent(ctx.attackerRef(),
                TransformComponent.getComponentType());
        TransformComponent targetTransform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        double distance = 0.0;
        if (shooterTransform != null && targetTransform != null) {
            distance = shooterTransform.getPosition().distance(targetTransform.getPosition());
        }

        double multiplier = enchantmentManager.calculateProjectileDamageMultiplier(strengthLevel, eaglesEyeLevel,
                distance);
        if (multiplier == 1.0)
            return;

        damage.setAmount((float) (damage.getAmount() * multiplier));

        if (weapon == null)
            return; // The event needs the item that carries the enchantment

        EnchantmentType activeType = strengthLevel > 0 ? EnchantmentType.STRENGTH : EnchantmentType.EAGLES_EYE;
        int activeLevel = strengthLevel > 0 ? strengthLevel : eaglesEyeLevel;
        EnchantmentEventHelper.fireActivated(attackerPlayerRef(store, ctx), weapon, activeType, activeLevel);
    }
}
