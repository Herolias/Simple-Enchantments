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
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.entity.knockback.KnockbackComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * ECS system that enhances knockback based on the Knockback enchantment level.
 * Uses hybrid approach: enhances existing knockback or creates new horizontal
 * knockback.
 * <p>
 * When the hit carries no knockback of its own, a {@link KnockbackComponent}
 * is attached to the target exactly like
 * {@code DamageEntityInteraction} does (reuse the component already on the
 * entity, otherwise {@code commandBuffer.putComponent}), so that
 * {@code KnockbackSystems.ApplyKnockback}/{@code ApplyPlayerKnockback} - which
 * query for that component - actually consume it. The component is also
 * published on the damage under {@link Damage#KNOCKBACK_COMPONENT} so later
 * knockback-modifying systems see it.
 */
public class EnchantmentKnockbackSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final EnchantmentManager enchantmentManager;

    private static final double BASE_HORIZONTAL_KNOCKBACK = 0.7;
    private static final double VERTICAL_LIFT = 0.3;
    private static final float DEFAULT_KNOCKBACK_DURATION = 0.0f;

    /** Only entities with stats can be damaged (same scope as {@code DamageSystems.ApplyDamage}). */
    private static final Query<EntityStore> QUERY = Query.and(EntityStatMap.getComponentType());

    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemGroupDependency<>(Order.AFTER, DamageModule.get().getFilterDamageGroup()),
            new SystemDependency(Order.BEFORE, DamageSystems.ApplyDamage.class));

    public EnchantmentKnockbackSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
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
        if (damage.isCancelled() || damage.getAmount() <= 0) {
            return;
        }

        // Reflected damage is dealt by the defender's shield, not by a weapon swing:
        // the "attacker" here is the reflection victim, so their Knockback must not apply.
        Boolean isReflection = damage.getIfPresentMetaObject(EnchantmentReflectionSystem.IS_REFLECTION);
        if (isReflection != null && isReflection)
            return;

        // Use centralized damage context extraction
        EnchantmentManager.DamageContext ctx = enchantmentManager.getDamageContext(damage, commandBuffer);
        if (!ctx.hasAttacker())
            return;

        ItemStack weapon = enchantmentManager.getWeaponFromEntity(ctx.attackerRef(), commandBuffer);

        // The active-blocker lookup inspects the InteractionManager and still needs
        // the legacy entity handle; nothing else here does.
        ItemStack shield = null;
        Entity attackerEntity = EntityUtils.getEntity(ctx.attackerRef(), commandBuffer);
        if (attackerEntity instanceof LivingEntity living) {
            shield = enchantmentManager.getActiveBlocker(living);
        }

        int weaponKb = weapon != null ? enchantmentManager.getEnchantmentLevel(weapon, EnchantmentType.KNOCKBACK) : 0;
        // The blocker may be the held weapon itself (parrying weapons): one read per item.
        int shieldKb = shield == null ? 0
                : shield == weapon ? weaponKb
                : enchantmentManager.getEnchantmentLevel(shield, EnchantmentType.KNOCKBACK);

        int knockbackLevel = Math.max(weaponKb, shieldKb);
        if (knockbackLevel <= 0)
            return;

        ItemStack sourceItem = (shieldKb >= weaponKb && shield != null) ? shield : weapon;

        // Calculate horizontal direction
        TransformComponent attackerTransform = commandBuffer.getComponent(ctx.attackerRef(),
                TransformComponent.getComponentType());
        TransformComponent targetTransform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());

        if (attackerTransform == null || targetTransform == null)
            return;

        Vector3d attackerPos = attackerTransform.getPosition();
        Vector3d targetPos = targetTransform.getPosition();

        double dx = targetPos.x - attackerPos.x;
        double dz = targetPos.z - attackerPos.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        if (horizontalDistance < 0.01)
            return;

        double dirX = dx / horizontalDistance;
        double dirZ = dz / horizontalDistance;
        double horizontalStrength = BASE_HORIZONTAL_KNOCKBACK * knockbackLevel;

        // Get or create knockback component
        KnockbackComponent knockbackComponent = damage.getIfPresentMetaObject(Damage.KNOCKBACK_COMPONENT);

        if (knockbackComponent == null) {
            Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
            // Mirror DamageEntityInteraction: reuse the component already on the target,
            // otherwise attach a fresh one so the knockback systems pick it up.
            knockbackComponent = commandBuffer.getComponent(targetRef, KnockbackComponent.getComponentType());
            if (knockbackComponent == null) {
                knockbackComponent = new KnockbackComponent();
                commandBuffer.putComponent(targetRef, KnockbackComponent.getComponentType(), knockbackComponent);
            }
            knockbackComponent.setVelocity(new Vector3d(
                    dirX * horizontalStrength,
                    VERTICAL_LIFT * knockbackLevel,
                    dirZ * horizontalStrength));
            knockbackComponent.setVelocityType(ChangeVelocityType.Add);
            knockbackComponent.setDuration(DEFAULT_KNOCKBACK_DURATION);
            damage.putMetaObject(Damage.KNOCKBACK_COMPONENT, knockbackComponent);
        } else {
            // Enhance existing knockback with additional velocity (horizontal + vertical
            // lift)
            Vector3d currentVelocity = knockbackComponent.getVelocity();
            currentVelocity.x += dirX * horizontalStrength;
            currentVelocity.y += VERTICAL_LIFT * knockbackLevel;
            currentVelocity.z += dirZ * horizontalStrength;
        }

        // Apply multiplier to scale existing knockback velocity
        double multiplierPerLevel = EnchantmentType.KNOCKBACK.getEffectMultiplier();
        double knockbackMultiplier = 1.0 + (knockbackLevel * multiplierPerLevel);
        knockbackComponent.addModifier(knockbackMultiplier);

        PlayerRef playerRef = store.getComponent(ctx.attackerRef(), PlayerRef.getComponentType());
        EnchantmentEventHelper.fireActivated(playerRef, sourceItem, EnchantmentType.KNOCKBACK, knockbackLevel);
    }
}
