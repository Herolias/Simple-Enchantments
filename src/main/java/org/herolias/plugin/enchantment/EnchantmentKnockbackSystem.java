package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.knockback.KnockbackComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * ECS system that enhances knockback based on the Knockback enchantment level.
 * Uses hybrid approach: enhances existing knockback or creates new horizontal
 * knockback.
 */
public class EnchantmentKnockbackSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final EnchantmentManager enchantmentManager;

    private static final double BASE_HORIZONTAL_KNOCKBACK = 0.7;
    private static final double VERTICAL_LIFT = 0.3;
    private static final float DEFAULT_KNOCKBACK_DURATION = 0.0f;

    private final Set<Dependency<EntityStore>> dependencies = Set.of(
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
        return com.hypixel.hytale.component.Archetype.empty();
    }

    @Override
    public void handle(int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Damage damage) {

        if (damage.getAmount() <= 0)
            return;

        // Use centralized damage context extraction
        EnchantmentManager.DamageContext ctx = enchantmentManager.getDamageContext(damage, commandBuffer);
        if (!ctx.hasAttacker())
            return;

        Entity attackerEntity = EntityUtils.getEntity(ctx.attackerRef(), commandBuffer);
        if (attackerEntity == null)
            return;

        ItemStack weapon = enchantmentManager.getWeaponFromEntity(attackerEntity);
        if (weapon == null)
            return;

        int knockbackLevel = enchantmentManager.getEnchantmentLevel(weapon, EnchantmentType.KNOCKBACK);
        if (knockbackLevel <= 0)
            return;

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

        // Get or create knockback component
        KnockbackComponent knockbackComponent = damage.getIfPresentMetaObject(Damage.KNOCKBACK_COMPONENT);

        if (knockbackComponent == null) {
            knockbackComponent = new KnockbackComponent();
            double horizontalStrength = BASE_HORIZONTAL_KNOCKBACK * knockbackLevel;
            Vector3d velocity = new Vector3d(
                    dirX * horizontalStrength,
                    VERTICAL_LIFT * knockbackLevel,
                    dirZ * horizontalStrength);
            knockbackComponent.setVelocity(velocity);
            knockbackComponent.setVelocityType(ChangeVelocityType.Add);
            knockbackComponent.setDuration(DEFAULT_KNOCKBACK_DURATION);
            damage.putMetaObject(Damage.KNOCKBACK_COMPONENT, knockbackComponent);
        } else {
            // Enhance existing knockback with additional velocity (horizontal + vertical
            // lift)
            double horizontalStrength = BASE_HORIZONTAL_KNOCKBACK * knockbackLevel;
            Vector3d currentVelocity = knockbackComponent.getVelocity();
            currentVelocity.x += dirX * horizontalStrength;
            currentVelocity.y += VERTICAL_LIFT * knockbackLevel;
            currentVelocity.z += dirZ * horizontalStrength;
        }

        // Apply multiplier to scale existing knockback velocity
        double multiplierPerLevel = EnchantmentType.KNOCKBACK.getEffectMultiplier();
        double knockbackMultiplier = 1.0 + (knockbackLevel * multiplierPerLevel);
        knockbackComponent.addModifier(knockbackMultiplier);

        com.hypixel.hytale.server.core.universe.PlayerRef playerRef = store.getComponent(ctx.attackerRef(),
                com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
        EnchantmentEventHelper.fireActivated(playerRef, weapon, EnchantmentType.KNOCKBACK, knockbackLevel);
    }
}
