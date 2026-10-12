package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.ProjectileComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.projectile.config.StandardPhysicsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.herolias.plugin.util.InventoryAccess;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Captures enchantment levels from ranged weapons when a projectile is spawned.
 * <p>
 * When a projectile is spawned into the ECS, this system reads the shooter's
 * weapon enchantments once and stores them on the projectile (by UUID and
 * NetworkId) so that damage/effect systems can apply them on hit. Projectiles
 * that are merely loaded with a chunk are ignored: their shooter is long gone
 * and their enchantments, if any, are already recorded.
 */
public class EnchantmentProjectileSpeedSystem extends RefSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Query<EntityStore> QUERY = Query.and(
            TransformComponent.getComponentType(),
            Query.or(ProjectileComponent.getComponentType(), StandardPhysicsProvider.getComponentType()));

    /** Order matters: indexes into the array returned by getEnchantmentLevels. */
    private static final EnchantmentType[] PROJECTILE_ENCHANTMENTS = {
            EnchantmentType.STRENGTH, EnchantmentType.EAGLES_EYE, EnchantmentType.LOOTING,
            EnchantmentType.FREEZE, EnchantmentType.BURN, EnchantmentType.POISON, EnchantmentType.ETERNAL_SHOT };

    private final EnchantmentManager enchantmentManager;
    @Nullable
    private EnchantmentEternalShotSystem eternalShotSystem;

    public EnchantmentProjectileSpeedSystem(@Nonnull EnchantmentManager enchantmentManager) {
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
        if (reason != AddReason.SPAWN) {
            return;
        }
        if (!enchantmentManager.isProjectileEntity(ref, commandBuffer)) {
            return;
        }

        Ref<EntityStore> shooterRef = enchantmentManager.getProjectileShooter(ref, commandBuffer);
        if (shooterRef == null || !shooterRef.isValid()) {
            return;
        }

        ItemStack weapon = enchantmentManager.getWeaponFromEntity(shooterRef, commandBuffer);
        if (weapon == null || weapon.isEmpty()) {
            return;
        }

        int[] levels = enchantmentManager.getEnchantmentLevels(weapon, PROJECTILE_ENCHANTMENTS);
        int strengthLevel = levels[0];
        int eaglesEyeLevel = levels[1];
        int lootingLevel = levels[2];
        int freezeLevel = levels[3];
        int burnLevel = levels[4];
        int poisonLevel = levels[5];
        int eternalShotLevel = levels[6];

        boolean anyLevel = false;
        for (int level : levels) {
            if (level > 0) {
                anyLevel = true;
                break;
            }
        }
        if (!anyLevel) {
            return;
        }

        UUIDComponent projectileUuidComponent = commandBuffer.getComponent(ref, UUIDComponent.getComponentType());
        if (projectileUuidComponent != null) {
            enchantmentManager.storeProjectileEnchantments(projectileUuidComponent.getUuid(), strengthLevel,
                    eaglesEyeLevel, lootingLevel, freezeLevel, burnLevel, poisonLevel, eternalShotLevel);
        }
        NetworkId networkId = commandBuffer.getComponent(ref, NetworkId.getComponentType());
        if (networkId != null) {
            enchantmentManager.storeProjectileEnchantments(networkId.getId(), strengthLevel, eaglesEyeLevel,
                    lootingLevel, freezeLevel, burnLevel, poisonLevel, eternalShotLevel);
        }

        if (eternalShotLevel > 0) {
            refundEternalShotAmmo(shooterRef, weapon, eternalShotLevel, commandBuffer);
        }
    }

    /**
     * Gives one unit of the ammunition tracked by
     * {@link EnchantmentEternalShotSystem} back to the shooter. The unit is
     * announced to the tracker first so the resulting inventory addition is not
     * taken for a vanilla cancel refund; if the inventory is full the remainder
     * is dropped and the announcement withdrawn, because a dropped item never
     * produces an inventory addition.
     */
    private void refundEternalShotAmmo(@Nonnull Ref<EntityStore> shooterRef,
            @Nonnull ItemStack weapon,
            int level,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (eternalShotSystem == null) {
            return;
        }
        if (commandBuffer.getComponent(shooterRef, Player.getComponentType()) == null) {
            return;
        }
        UUIDComponent uuidComponent = commandBuffer.getComponent(shooterRef, UUIDComponent.getComponentType());
        if (uuidComponent == null) {
            return;
        }
        UUID playerUuid = uuidComponent.getUuid();

        ItemStack ammo = eternalShotSystem.getAndClearConsumedAmmo(playerUuid);
        if (ammo == null || ammo.isEmpty()) {
            return;
        }

        CombinedItemContainer inventory = InventoryAccess.getCombinedHotbarFirst(commandBuffer, shooterRef);
        eternalShotSystem.markPendingRefund(playerUuid, ammo.getQuantity());
        ItemStackTransaction transaction = inventory.addItemStack(ammo);
        ItemStack remainder = transaction.getRemainder();
        if (!ItemStack.isEmpty(remainder)) {
            eternalShotSystem.cancelPendingRefund(playerUuid, remainder.getQuantity());
            ItemUtils.dropItem(shooterRef, remainder, commandBuffer);
        }

        PlayerRef playerRef = commandBuffer.getComponent(shooterRef, PlayerRef.getComponentType());
        EnchantmentEventHelper.fireActivated(playerRef, weapon, EnchantmentType.ETERNAL_SHOT, level);
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> ref,
            @Nonnull RemoveReason reason,
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
