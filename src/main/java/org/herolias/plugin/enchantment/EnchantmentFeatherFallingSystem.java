package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.dependency.SystemGroupDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * ECS system that reduces fall damage based on Feather Falling enchantment on
 * leg armor.
 * 
 * Effect: Reduces fall damage by 20% per level (up to 60% at level 3)
 * Applicable to: leg armor
 */
public class EnchantmentFeatherFallingSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Hytale's armor slot index 3 is Legs.
    private static final short LEGS_SLOT = 3;

    /** Damageable entities that actually wear armor. */
    private static final Query<EntityStore> QUERY = Query.and(
            EntityStatMap.getComponentType(),
            InventoryComponent.Armor.getComponentType());

    private final EnchantmentManager enchantmentManager;

    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemGroupDependency<>(Order.AFTER, DamageModule.get().getFilterDamageGroup()),
            new SystemDependency(Order.BEFORE, DamageSystems.ApplyDamage.class));

    public EnchantmentFeatherFallingSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentFeatherFallingSystem initialized");
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

        // Run only after Hytale's world/PvP/spawn-protection filters accepted the hit.
        if (damage.isCancelled() || damage.getAmount() <= 0) {
            return;
        }

        // Only process fall damage
        DamageCause damageCause = enchantmentManager.getDamageCause(damage.getDamageCauseIndex());
        if (damageCause == null || !enchantmentManager.isFallDamage(damageCause)) {
            return;
        }

        try {
            InventoryComponent.Armor armorComponent = archetypeChunk.getComponent(index,
                    InventoryComponent.Armor.getComponentType());
            ItemContainer armorContainer = armorComponent != null ? armorComponent.getInventory() : null;
            if (armorContainer == null || armorContainer.getCapacity() <= LEGS_SLOT) {
                return;
            }

            // Get leg armor from armor slot
            ItemStack legArmor = armorContainer.getItemStack(LEGS_SLOT);
            if (legArmor == null || legArmor.isEmpty()) {
                return;
            }

            // Check for Feather Falling enchantment
            int featherFallingLevel = enchantmentManager.getEnchantmentLevel(legArmor, EnchantmentType.FEATHER_FALLING);
            if (featherFallingLevel <= 0) {
                return;
            }

            // Calculate damage reduction (20% per level)
            double reductionPercent = featherFallingLevel * EnchantmentType.FEATHER_FALLING.getEffectMultiplier();
            double multiplier = 1.0 - reductionPercent;

            float originalDamage = damage.getAmount();
            float reducedDamage = (float) (originalDamage * multiplier);
            damage.setAmount(reducedDamage);

            if (archetypeChunk.getComponent(index, Player.getComponentType()) != null) {
                PlayerRef playerRef = store.getComponent(archetypeChunk.getReferenceTo(index),
                        PlayerRef.getComponentType());
                EnchantmentEventHelper.fireActivated(playerRef, legArmor, EnchantmentType.FEATHER_FALLING,
                        featherFallingLevel);
            }

            LOGGER.atFine().log("Feather Falling %d reduced fall damage: %.2f -> %.2f",
                    featherFallingLevel, originalDamage, reducedDamage);

        } catch (Exception e) {
            LOGGER.atWarning().atMostEvery(30, TimeUnit.SECONDS).withCause(e)
                    .log("Error in Feather Falling system");
        }
    }
}
