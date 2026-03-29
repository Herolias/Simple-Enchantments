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
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.ProjectileComponent;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.projectile.config.StandardPhysicsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.system.RefSystem;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Captures enchantment levels from ranged weapons when a projectile is spawned.
 * 
 * When a projectile is added to the ECS, this system reads the shooter's weapon
 * enchantments and stores them on the projectile (by UUID and NetworkId) so
 * that
 * damage/effect systems can apply them on hit.
 */
public class EnchantmentProjectileSpeedSystem extends RefSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Query<EntityStore> QUERY = Query.and(
            TransformComponent.getComponentType(),
            Query.or(ProjectileComponent.getComponentType(), StandardPhysicsProvider.getComponentType()));

    private final EnchantmentManager enchantmentManager;
    private @Nullable EnchantmentEternalShotSystem eternalShotSystem;

    public EnchantmentProjectileSpeedSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentProjectileSpeedSystem initialized");
    }

    public void setEternalShotSystem(@Nonnull EnchantmentEternalShotSystem system) {
        this.eternalShotSystem = system;
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

        if (strengthLevel <= 0 && eaglesEyeLevel <= 0 && lootingLevel <= 0 && freezeLevel <= 0 && burnLevel <= 0
                && eternalShotLevel <= 0) {
            return;
        }

        UUIDComponent projectileUuidComponent = commandBuffer.getComponent(ref, UUIDComponent.getComponentType());
        if (projectileUuidComponent != null) {
            enchantmentManager.storeProjectileEnchantments(projectileUuidComponent.getUuid(), strengthLevel,
                    eaglesEyeLevel, lootingLevel, freezeLevel, burnLevel, eternalShotLevel);
        }
        NetworkId networkId = commandBuffer.getComponent(ref, NetworkId.getComponentType());
        if (networkId != null) {
            enchantmentManager.storeProjectileEnchantments(networkId.getId(), strengthLevel, eaglesEyeLevel,
                    lootingLevel, freezeLevel, burnLevel, eternalShotLevel);
        }

        if (eternalShotLevel > 0) {
            refundEternalShotAmmo(shooterRef, shooterEntity, weapon, eternalShotLevel, commandBuffer);
        }
    }

    /**
     * Refunds 1 ammo to the shooter's inventory when an Eternal Shot projectile
     * is spawned. Uses the tracked consumed ammo from {@link EnchantmentEternalShotSystem},
     * falling back to searching the inventory for any ammo item.
     */
    private void refundEternalShotAmmo(@Nonnull Ref<EntityStore> shooterRef, @Nonnull Entity shooterEntity,
            @Nonnull ItemStack weapon, int level, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (eternalShotSystem == null)
            return;

        if (!(shooterEntity instanceof Player player))
            return;

        UUIDComponent uuidComp = commandBuffer.getComponent(shooterRef, UUIDComponent.getComponentType());
        if (uuidComp == null)
            return;
        UUID playerUuid = uuidComp.getUuid();

        ItemStack ammo = eternalShotSystem.getAndClearConsumedAmmo(playerUuid);
        if (ammo == null) {
            ammo = eternalShotSystem.findAmmoInInventory(player);
        }

        if (ammo == null)
            return;

        Inventory inventory = player.getInventory();
        if (inventory == null)
            return;

        SimpleItemContainer.addOrDropItemStack(commandBuffer, shooterRef,
                inventory.getCombinedHotbarFirst(), ammo);

        PlayerRef playerRef = commandBuffer.getComponent(shooterRef, PlayerRef.getComponentType());
        EnchantmentEventHelper.fireActivated(playerRef, weapon, EnchantmentType.ETERNAL_SHOT, level);
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
