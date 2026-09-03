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
import com.hypixel.hytale.server.core.meta.MetaKey;
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
 * ECS system that applies Reflection enchantment.
 * Reflects a portion of blocked damage back to the attacker.
 */
public class EnchantmentReflectionSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final EnchantmentManager enchantmentManager;

    /** Only entities with stats can be damaged (same scope as {@code DamageSystems.ApplyDamage}). */
    private static final Query<EntityStore> QUERY = Query.and(EntityStatMap.getComponentType());

    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency(Order.AFTER, DamageSystems.DamageStamina.class));

    public EnchantmentReflectionSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentReflectionSystem initialized");
    }

    /**
     * Marks damage produced by this system. Every attacker-side enchantment
     * (Sharpness, Life Leech, Frenzy, Knockback, Burn, Poison, Freeze, ...)
     * must ignore damage carrying this flag, because the "attacker" of a
     * reflection is the player being punished, not someone swinging a weapon.
     */
    public static final MetaKey<Boolean> IS_REFLECTION = Damage.META_REGISTRY
            .registerMetaObject(data -> Boolean.FALSE);

    /**
     * Asset index of the {@code Physical} damage cause used for reflected hits.
     * Resolved once from {@link DamageCause#PHYSICAL}; {@link Integer#MIN_VALUE}
     * while unknown.
     */
    private volatile int physicalCauseIndex = Integer.MIN_VALUE;

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

        if (damage.isCancelled())
            return;

        Boolean isReflection = damage.getIfPresentMetaObject(IS_REFLECTION);
        if (isReflection != null && isReflection)
            return;

        // Check if the damage was blocked
        Boolean blocked = damage.getIfPresentMetaObject(Damage.BLOCKED);
        if (blocked == null || !blocked)
            return;

        // The active-blocker lookup inspects the InteractionManager and still needs
        // the legacy entity handle.
        Entity defenderEntity = EntityUtils.getEntity(index, archetypeChunk);
        if (!(defenderEntity instanceof LivingEntity defender))
            return;

        // Use centralized blocker detection
        ItemStack blocker = enchantmentManager.getActiveBlocker(defender);
        if (blocker == null)
            return;

        int reflectionLevel = enchantmentManager.getEnchantmentLevel(blocker, EnchantmentType.REFLECTION);
        if (reflectionLevel <= 0)
            return;

        // Use centralized damage context extraction
        EnchantmentManager.DamageContext ctx = enchantmentManager.getDamageContext(damage, commandBuffer);
        if (!ctx.hasAttacker())
            return;

        // Calculate and apply reflected damage
        float originalAmount = damage.getInitialAmount();
        float reflectedAmount = (float) (originalAmount * reflectionLevel
                * EnchantmentType.REFLECTION.getEffectMultiplier());

        if (reflectedAmount <= 0)
            return;

        int causeIndex = physicalCauseIndex();
        if (causeIndex == Integer.MIN_VALUE)
            return;

        Ref<EntityStore> defenderRef = archetypeChunk.getReferenceTo(index);
        Damage reflectionDamage = new Damage(new Damage.EntitySource(defenderRef), causeIndex, reflectedAmount);
        reflectionDamage.putMetaObject(IS_REFLECTION, true);
        DamageSystems.executeDamage(ctx.attackerRef(), commandBuffer, reflectionDamage);

        PlayerRef playerRef = store.getComponent(defenderRef, PlayerRef.getComponentType());
        EnchantmentEventHelper.fireActivated(playerRef, blocker, EnchantmentType.REFLECTION, reflectionLevel);
    }

    private int physicalCauseIndex() {
        int idx = physicalCauseIndex;
        if (idx != Integer.MIN_VALUE) {
            return idx;
        }
        // DamageCause.PHYSICAL is populated by EntityModule once damage causes are loaded.
        DamageCause physical = DamageCause.PHYSICAL;
        if (physical != null && physical.getId() != null) {
            idx = DamageCause.getAssetMap().getIndexOrDefault(physical.getId(), Integer.MIN_VALUE);
            physicalCauseIndex = idx;
        }
        return idx;
    }
}
