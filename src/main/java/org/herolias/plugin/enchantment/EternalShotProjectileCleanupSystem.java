package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.projectile.component.Projectile;
import com.hypixel.hytale.server.core.modules.projectile.config.StandardPhysicsProvider;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

public class EternalShotProjectileCleanupSystem extends EntityTickingSystem<EntityStore> {
    private final EnchantmentManager enchantmentManager;

    public EternalShotProjectileCleanupSystem(EnchantmentManager manager) {
        this.enchantmentManager = manager;
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return Query.and(
                Projectile.getComponentType(),
                StandardPhysicsProvider.getComponentType(),
                UUIDComponent.getComponentType());
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> buffer) {
        UUIDComponent uuidComp = chunk.getComponent(index, UUIDComponent.getComponentType());
        if (uuidComp == null) {
            return;
        }

        UUID uuid = uuidComp.getUuid();
        ProjectileEnchantmentData data = enchantmentManager.getProjectileEnchantments(uuid);

        // If it's not an Eternal Shot projectile, ignore it
        if (data == null || data.getEternalShotLevel() <= 0) {
            return;
        }

        StandardPhysicsProvider physics = chunk.getComponent(index, StandardPhysicsProvider.getComponentType());
        if (physics == null) {
            return;
        }

        // If the projectile is on the ground or resting, remove it immediately
        if (physics.isOnGround() || physics.getState() == StandardPhysicsProvider.STATE.RESTING) {
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            buffer.removeEntity(ref, RemoveReason.REMOVE);

            enchantmentManager.removeProjectileEnchantments(uuid);
        }
    }
}
