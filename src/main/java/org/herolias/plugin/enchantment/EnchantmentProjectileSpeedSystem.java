package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.ProjectileComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.projectile.config.StandardPhysicsProvider;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.system.RefSystem;

import javax.annotation.Nonnull;

/**
 * Captures enchantment levels from ranged weapons when a projectile is spawned.
 * 
 * When a projectile is added to the ECS, this system reads the shooter's weapon
 * enchantments and stores them on the projectile (by UUID and NetworkId) so that
 * damage/effect systems can apply them on hit.
 */
public class EnchantmentProjectileSpeedSystem extends RefSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Query<EntityStore> QUERY = Query.and(
        TransformComponent.getComponentType(),
        Query.or(ProjectileComponent.getComponentType(), StandardPhysicsProvider.getComponentType())
    );

    private final EnchantmentManager enchantmentManager;

    public EnchantmentProjectileSpeedSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentProjectileSpeedSystem initialized");
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> ref,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (!enchantmentManager.isProjectileEntity(ref, commandBuffer)) {
            return;
        }

        Ref<EntityStore> shooterRef = enchantmentManager.getProjectileShooter(ref, commandBuffer);
        if (shooterRef == null || !shooterRef.isValid()) {
            return;
        }

        Entity shooterEntity = EntityUtils.getEntity(shooterRef, commandBuffer);
        ItemStack weapon = enchantmentManager.getWeaponFromEntity(shooterEntity);
        if (weapon == null || weapon.isEmpty()) {
            return;
        }

        int strengthLevel = enchantmentManager.getEnchantmentLevel(weapon, EnchantmentType.STRENGTH);
        int eaglesEyeLevel = enchantmentManager.getEnchantmentLevel(weapon, EnchantmentType.EAGLES_EYE);
        int lootingLevel = enchantmentManager.getEnchantmentLevel(weapon, EnchantmentType.LOOTING);
        int freezeLevel = enchantmentManager.getEnchantmentLevel(weapon, EnchantmentType.FREEZE);
        int burnLevel = enchantmentManager.getEnchantmentLevel(weapon, EnchantmentType.BURN);
        int eternalShotLevel = enchantmentManager.getEnchantmentLevel(weapon, EnchantmentType.ETERNAL_SHOT);
        
        if (strengthLevel <= 0 && eaglesEyeLevel <= 0 && lootingLevel <= 0 && freezeLevel <= 0 && burnLevel <= 0 && eternalShotLevel <= 0) {
            return;
        }

        UUIDComponent projectileUuidComponent = commandBuffer.getComponent(ref, UUIDComponent.getComponentType());
        if (projectileUuidComponent != null) {
            enchantmentManager.storeProjectileEnchantments(projectileUuidComponent.getUuid(), strengthLevel, eaglesEyeLevel, lootingLevel, freezeLevel, burnLevel, eternalShotLevel);
        }
        NetworkId networkId = commandBuffer.getComponent(ref, NetworkId.getComponentType());
        if (networkId != null) {
            enchantmentManager.storeProjectileEnchantments(networkId.getId(), strengthLevel, eaglesEyeLevel, lootingLevel, freezeLevel, burnLevel, eternalShotLevel);
        }
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> ref,
                               @Nonnull com.hypixel.hytale.component.RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        UUIDComponent uuidComponent = commandBuffer.getComponent(ref, UUIDComponent.getComponentType());
        if (uuidComponent != null) {
            enchantmentManager.removeProjectileEnchantments(uuidComponent.getUuid());
        }
        NetworkId networkId = commandBuffer.getComponent(ref, NetworkId.getComponentType());
        if (networkId != null) {
            enchantmentManager.removeProjectileEnchantments(networkId.getId());
        }
    }
}
