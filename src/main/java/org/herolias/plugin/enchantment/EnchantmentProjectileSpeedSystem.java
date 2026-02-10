package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.asset.type.projectile.config.Projectile;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.ProjectileComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.physics.SimplePhysicsProvider;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.physics.util.ForceProviderStandardState;
import com.hypixel.hytale.server.core.modules.physics.util.PhysicsMath;
import com.hypixel.hytale.server.core.modules.projectile.config.StandardPhysicsProvider;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Applies Strength enchantment to projectile velocity to increase range.
 * 
 * Uses a deferred application mechanism: when a projectile is spawned, its initial
 * velocity may not be set yet (especially for StandardPhysicsProvider projectiles where
 * velocity is computed in the first physics tick). In such cases, the velocity multiplier
 * is stored and applied on a scheduled retry after the first tick.
 */
@SuppressWarnings("deprecation")
public class EnchantmentProjectileSpeedSystem extends RefSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Query<EntityStore> QUERY = Query.and(
        TransformComponent.getComponentType(),
        Query.or(ProjectileComponent.getComponentType(), StandardPhysicsProvider.getComponentType())
    );

    private final EnchantmentManager enchantmentManager;

    /**
     * Stores pending velocity modifications for projectiles whose velocity
     * wasn't available at spawn time. Keyed by projectile UUID.
     */
    private final Map<UUID, PendingVelocityMod> pendingVelocityMods = new ConcurrentHashMap<>();
    private static final long PENDING_MAX_AGE_MS = 500; // Max time to retry deferred velocity mods
    private static final int MAX_RETRY_ATTEMPTS = 5;

    private record PendingVelocityMod(double multiplier, long timestamp, int attempt) {}

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

        // Removed restriction: if (enchantmentManager.categorizeItem(weapon) != ItemCategory.RANGED_WEAPON) { return; }
        // This allows Staffs and other items to apply their enchantments to projectiles they spawn.

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

        double multiplier = enchantmentManager.calculateProjectileRangeMultiplier(strengthLevel);
        if (multiplier <= 1.0) {
            return;
        }

        // Try to apply velocity modification immediately
        boolean applied = tryApplyVelocityMultiplier(ref, store, commandBuffer, multiplier);

        if (!applied) {
            // Velocity not available yet - defer to next tick(s).
            // This commonly happens with StandardPhysicsProvider projectiles where
            // the initial launch velocity is computed in the first physics tick,
            // after the entity is added to the ECS.
            UUID projectileUuid = projectileUuidComponent != null ? projectileUuidComponent.getUuid() : null;
            if (projectileUuid != null) {
                pendingVelocityMods.put(projectileUuid, new PendingVelocityMod(multiplier, System.currentTimeMillis(), 0));
                scheduleDeferredApplication(projectileUuid);
            } else {
                LOGGER.atWarning().log("Cannot defer velocity modification: projectile has no UUID component");
            }
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
            pendingVelocityMods.remove(uuidComponent.getUuid());
        }
        NetworkId networkId = commandBuffer.getComponent(ref, NetworkId.getComponentType());
        if (networkId != null) {
            enchantmentManager.removeProjectileEnchantments(networkId.getId());
        }
    }

    /**
     * Attempts to apply the velocity multiplier immediately.
     * @return true if the velocity was successfully modified, false if velocity wasn't available yet
     */
    private boolean tryApplyVelocityMultiplier(@Nonnull Ref<EntityStore> ref,
                                                @Nonnull Store<EntityStore> store,
                                                @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                                double multiplier) {
        ProjectileComponent projectileComponent = commandBuffer.getComponent(ref, ProjectileComponent.getComponentType());
        if (projectileComponent != null) {
            return applySimpleProjectileRange(ref, commandBuffer, projectileComponent, multiplier);
        }

        StandardPhysicsProvider standardPhysics = commandBuffer.getComponent(ref, StandardPhysicsProvider.getComponentType());
        if (standardPhysics != null) {
            Velocity velocityComponent = commandBuffer.getComponent(ref, Velocity.getComponentType());
            return applyStandardProjectileRange(standardPhysics, velocityComponent, multiplier);
        }

        return false;
    }

    /**
     * Schedules a deferred velocity application for the next tick.
     * Uses Hytale's scheduled executor to retry after a short delay.
     */
    private void scheduleDeferredApplication(UUID projectileUuid) {
        try {
            com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                try {
                    processDeferredVelocityMod(projectileUuid);
                } catch (Exception e) {
                    LOGGER.atWarning().log("Error processing deferred velocity mod: " + e.getMessage());
                    pendingVelocityMods.remove(projectileUuid);
                }
            }, 50, TimeUnit.MILLISECONDS); // 1 tick delay
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to schedule deferred velocity modification: " + e.getMessage());
            pendingVelocityMods.remove(projectileUuid);
        }
    }

    /**
     * Processes a deferred velocity modification. Called from the scheduled executor
     * after one or more ticks, when the projectile's velocity should be available.
     */
    private void processDeferredVelocityMod(UUID projectileUuid) {
        PendingVelocityMod pending = pendingVelocityMods.get(projectileUuid);
        if (pending == null) return;

        // Check if expired
        if (System.currentTimeMillis() - pending.timestamp > PENDING_MAX_AGE_MS) {
            LOGGER.atFine().log("Deferred velocity mod expired for projectile " + projectileUuid);
            pendingVelocityMods.remove(projectileUuid);
            return;
        }

        // Look up the projectile entity by UUID
        Ref<EntityStore> projectileRef = null;
        try {
            com.hypixel.hytale.server.core.universe.Universe universe = com.hypixel.hytale.server.core.universe.Universe.get();
            if (universe == null) {
                pendingVelocityMods.remove(projectileUuid);
                return;
            }

            for (com.hypixel.hytale.server.core.universe.world.World world : universe.getWorlds().values()) {
                if (world == null) continue;
                // Execute on the world's thread for safety
                final Ref<EntityStore>[] foundRef = new Ref[]{null};
                world.execute(() -> {
                    var ref = world.getEntityStore().getRefFromUUID(projectileUuid);
                    if (ref != null && ref.isValid()) {
                        foundRef[0] = ref;
                        var store = ref.getStore();
                        
                        // Try applying velocity now
                        boolean applied = false;
                        
                        StandardPhysicsProvider standardPhysics = store.getComponent(ref, StandardPhysicsProvider.getComponentType());
                        if (standardPhysics != null) {
                            Vector3d velocity = standardPhysics.getVelocity();
                            if (velocity != null && velocity.length() > 0.001) {
                                velocity.scale(pending.multiplier);
                                Velocity velocityComponent = store.getComponent(ref, Velocity.getComponentType());
                                if (velocityComponent != null) {
                                    velocityComponent.set(velocity);
                                }
                                applied = true;
                                LOGGER.atFine().log("Deferred velocity mod applied (standard physics) for " + projectileUuid + " x" + pending.multiplier);
                            } else {
                                // Also try nextTickVelocity
                                ForceProviderStandardState state = standardPhysics.getForceProviderStandardState();
                                if (state != null && state.nextTickVelocity != null) {
                                    Vector3d nextVel = state.nextTickVelocity;
                                    if (nextVel.x != Double.MAX_VALUE && nextVel.y != Double.MAX_VALUE && nextVel.z != Double.MAX_VALUE
                                        && nextVel.length() > 0.001) {
                                        nextVel.scale(pending.multiplier);
                                        Velocity velocityComponent = store.getComponent(ref, Velocity.getComponentType());
                                        if (velocityComponent != null) {
                                            velocityComponent.set(nextVel);
                                        }
                                        applied = true;
                                        LOGGER.atFine().log("Deferred velocity mod applied (nextTickVelocity) for " + projectileUuid + " x" + pending.multiplier);
                                    }
                                }
                            }
                        }

                        if (!applied) {
                            ProjectileComponent projectileComponent = store.getComponent(ref, ProjectileComponent.getComponentType());
                            if (projectileComponent != null) {
                                SimplePhysicsProvider physicsProvider = projectileComponent.getSimplePhysicsProvider();
                                Vector3d vel = physicsProvider.getVelocity();
                                if (vel != null && vel.length() > 0.001) {
                                    physicsProvider.setVelocity(new Vector3d(vel).scale(pending.multiplier));
                                    applied = true;
                                    LOGGER.atFine().log("Deferred velocity mod applied (simple physics) for " + projectileUuid + " x" + pending.multiplier);
                                }
                            }
                        }

                        if (applied) {
                            pendingVelocityMods.remove(projectileUuid);
                        } else if (pending.attempt < MAX_RETRY_ATTEMPTS) {
                            // Retry with incremented attempt count
                            pendingVelocityMods.put(projectileUuid, 
                                new PendingVelocityMod(pending.multiplier, pending.timestamp, pending.attempt + 1));
                            scheduleDeferredApplication(projectileUuid);
                        } else {
                            LOGGER.atWarning().log("Failed to apply deferred velocity mod after " + MAX_RETRY_ATTEMPTS + " attempts for " + projectileUuid);
                            pendingVelocityMods.remove(projectileUuid);
                        }
                    }
                });
                if (foundRef[0] != null) break;
            }
        } catch (Exception e) {
            LOGGER.atWarning().log("Error in deferred velocity processing: " + e.getMessage());
            pendingVelocityMods.remove(projectileUuid);
        }
    }

    /**
     * Applies velocity multiplier to a SimplePhysicsProvider projectile.
     * @return true if velocity was successfully modified
     */
    private boolean applySimpleProjectileRange(@Nonnull Ref<EntityStore> ref,
                                               @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                               @Nonnull ProjectileComponent projectileComponent,
                                               double multiplier) {
        SimplePhysicsProvider physicsProvider = projectileComponent.getSimplePhysicsProvider();
        Vector3d currentVelocity = physicsProvider.getVelocity();
        if (currentVelocity.length() > 0.001) {
            physicsProvider.setVelocity(new Vector3d(currentVelocity).scale(multiplier));
            LOGGER.atFine().log("Applied simple projectile range multiplier: " + multiplier);
            return true;
        }

        TransformComponent transform = commandBuffer.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            return false;
        }

        Projectile projectileAsset = projectileComponent.getProjectile();
        if (projectileAsset == null) {
            return false;
        }

        double baseSpeed = projectileAsset.getMuzzleVelocity();
        if (baseSpeed <= 0.0) {
            return false;
        }

        Vector3f rotation = transform.getRotation();
        Vector3d direction = new Vector3d();
        PhysicsMath.vectorFromAngles(rotation.getYaw(), rotation.getPitch(), direction);
        direction.setLength(baseSpeed * multiplier);
        physicsProvider.setVelocity(direction);
        LOGGER.atFine().log("Applied simple projectile range from muzzle velocity: " + multiplier);
        return true;
    }

    /**
     * Applies velocity multiplier to a StandardPhysicsProvider projectile.
     * @return true if velocity was successfully modified
     */
    private boolean applyStandardProjectileRange(@Nonnull StandardPhysicsProvider standardPhysics,
                                                  Velocity velocityComponent,
                                                  double multiplier) {
        Vector3d currentVelocity = standardPhysics.getVelocity();
        if (currentVelocity != null && currentVelocity.length() > 0.001) {
            currentVelocity.scale(multiplier);
            if (velocityComponent != null) {
                velocityComponent.set(currentVelocity);
            }
            LOGGER.atFine().log("Applied standard projectile range multiplier (current velocity): " + multiplier);
            return true;
        }

        if (scaleStandardNextTickVelocity(standardPhysics, velocityComponent, multiplier)) {
            LOGGER.atFine().log("Applied standard projectile range multiplier (nextTickVelocity): " + multiplier);
            return true;
        }

        LOGGER.atFine().log("Velocity not available yet for standard projectile - will defer");
        return false;
    }

    private boolean scaleStandardNextTickVelocity(@Nonnull StandardPhysicsProvider standardPhysics,
                                                  Velocity velocityComponent,
                                                  double multiplier) {
        ForceProviderStandardState state = standardPhysics.getForceProviderStandardState();
        if (state == null) return false;

        Vector3d nextVelocity = state.nextTickVelocity;
        if (nextVelocity == null) {
            return false;
        }

        if (nextVelocity.x == Double.MAX_VALUE || nextVelocity.y == Double.MAX_VALUE || nextVelocity.z == Double.MAX_VALUE) {
            return false;
        }

        if (nextVelocity.length() < 0.001) {
            return false;
        }

        nextVelocity.scale(multiplier);
        if (velocityComponent != null) {
            velocityComponent.set(nextVelocity);
        }
        return true;
    }
}
