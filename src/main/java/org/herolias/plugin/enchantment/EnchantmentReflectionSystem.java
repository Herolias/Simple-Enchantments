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
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * ECS system that applies Reflection enchantment.
 * Reflects a portion of blocked damage back to the attacker.
 */
public class EnchantmentReflectionSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final EnchantmentManager enchantmentManager;

    private final Set<Dependency<EntityStore>> dependencies = Set.of(
        new SystemDependency(Order.AFTER, DamageSystems.DamageStamina.class)
    );

    public EnchantmentReflectionSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentReflectionSystem initialized");
    }

    public static final com.hypixel.hytale.server.core.meta.MetaKey<Boolean> IS_REFLECTION = Damage.META_REGISTRY.registerMetaObject(data -> Boolean.FALSE);

    @Override
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
        
        if (damage.isCancelled()) return;

        // Check if the damage was blocked
        Boolean blocked = damage.getIfPresentMetaObject(Damage.BLOCKED);
        if (blocked == null || !blocked) return;

        // Get defender
        Entity defenderEntity = EntityUtils.getEntity(index, archetypeChunk);
        if (!(defenderEntity instanceof LivingEntity defender)) return;

        // Use centralized blocker detection
        ItemStack blocker = enchantmentManager.getActiveBlocker(defender);
        if (blocker == null) return;

        int reflectionLevel = enchantmentManager.getEnchantmentLevel(blocker, EnchantmentType.REFLECTION);
        if (reflectionLevel <= 0) return;

        // Use centralized damage context extraction
        EnchantmentManager.DamageContext ctx = enchantmentManager.getDamageContext(damage, commandBuffer);
        if (!ctx.hasAttacker()) return;

        // Calculate and apply reflected damage
        float originalAmount = damage.getInitialAmount(); 
        float reflectedAmount = (float) (originalAmount * reflectionLevel * EnchantmentType.REFLECTION.getEffectMultiplier());

        if (reflectedAmount <= 0) return;
        


        DamageCause attackCause = DamageCause.getAssetMap().getAsset("EntityAttack");
        if (attackCause == null) {
            attackCause = DamageCause.getAssetMap().getAsset("Physical");
        }
        
        if (attackCause != null) {
            Damage.EntitySource source = new Damage.EntitySource(archetypeChunk.getReferenceTo(index));
            Damage reflectionDamage = new Damage(source, attackCause, reflectedAmount);
            reflectionDamage.putMetaObject(IS_REFLECTION, true);
            DamageSystems.executeDamage(ctx.attackerRef(), commandBuffer, reflectionDamage);
            
            com.hypixel.hytale.server.core.universe.PlayerRef playerRef = store.getComponent(archetypeChunk.getReferenceTo(index), com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
            EnchantmentEventHelper.fireActivated(playerRef, blocker, EnchantmentType.REFLECTION, reflectionLevel);
        }
    }
}
