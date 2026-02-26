package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
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
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Applies Dexterity enchantment to stamina drain when blocking.
 */
public class EnchantmentStaminaSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final float MIN_STAMINA_MULTIPLIER = 0.1f;

    private final EnchantmentManager enchantmentManager;

    private final Set<Dependency<EntityStore>> dependencies = Set.<Dependency<EntityStore>>of(
        new SystemDependency<>(Order.BEFORE, DamageSystems.DamageStamina.class)
    );

    public EnchantmentStaminaSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentStaminaSystem initialized");
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
        if (damage.isCancelled()) return;

        Boolean blocked = damage.getIfPresentMetaObject(Damage.BLOCKED);
        if (blocked == null || !blocked) return;

        Entity targetEntity = EntityUtils.getEntity(index, archetypeChunk);
        if (!(targetEntity instanceof LivingEntity targetLiving)) return;

        // Use centralized blocker detection
        ItemStack blocker = enchantmentManager.getActiveBlocker(targetLiving);
        if (blocker == null) return;

        ItemCategory category = enchantmentManager.categorizeItem(blocker);
        if (!EnchantmentType.DEXTERITY.canApplyTo(category)) return;

        int dexterityLevel = enchantmentManager.getEnchantmentLevel(blocker, EnchantmentType.DEXTERITY);
        if (dexterityLevel <= 0) return;

        float reduction = (float) (dexterityLevel * EnchantmentType.DEXTERITY.getEffectMultiplier());
        float multiplier = Math.max(MIN_STAMINA_MULTIPLIER, 1.0f - reduction);

        Float existingMultiplier = damage.getIfPresentMetaObject(Damage.STAMINA_DRAIN_MULTIPLIER);
        if (existingMultiplier != null) {
            multiplier *= existingMultiplier.floatValue();
        }

        damage.putMetaObject(Damage.STAMINA_DRAIN_MULTIPLIER, multiplier);
        if (targetEntity instanceof com.hypixel.hytale.server.core.entity.entities.Player) {
             com.hypixel.hytale.server.core.universe.PlayerRef playerRef = store.getComponent(targetEntity.getReference(), com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
             EnchantmentEventHelper.fireActivated(playerRef, blocker, EnchantmentType.DEXTERITY, dexterityLevel);
        }
    }
}
