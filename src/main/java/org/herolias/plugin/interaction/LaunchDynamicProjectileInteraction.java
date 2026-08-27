package org.herolias.plugin.interaction;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.HashUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.InteractionSyncData;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.InteractionEntry;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.projectile.ProjectileModule;
import com.hypixel.hytale.server.core.modules.projectile.config.ProjectileConfig;
import com.hypixel.hytale.server.core.modules.projectile.interaction.ProjectileInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class LaunchDynamicProjectileInteraction
        extends ProjectileInteraction {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final double SPAWN_TRANSFORM_DESYNC_ALLOWANCE_MULTIPLIER = 1.25;
    private static final double SPAWN_TRANSFORM_DESYNC_ALLOWANCE_MINIMUM_SQUARED = 0.5;

    public static final BuilderCodec<LaunchDynamicProjectileInteraction> CODEC = BuilderCodec
            .builder(LaunchDynamicProjectileInteraction.class, LaunchDynamicProjectileInteraction::new,
                    ProjectileInteraction.CODEC)
            .documentation("Fires a projectile based on loaded ammo.")
            .append(new KeyedCodec<String>("DefaultConfig", Codec.STRING),
                    (LaunchDynamicProjectileInteraction o, String i) -> {
                        o.defaultConfig = i;
                        if (o.config == null) {
                            o.config = i;
                        }
                    },
                    (LaunchDynamicProjectileInteraction o) -> o.defaultConfig)
            .add()
            .build();

    protected String defaultConfig;

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context,
            @Nonnull CooldownHandler cooldownHandler) {
        String resolvedConfigId = resolveConfigId(context);
        if (resolvedConfigId == null) {
            return;
        }

        ProjectileConfig config = ProjectileConfig.getAssetMap().getAsset(resolvedConfigId);
        if (config == null) {
            return;
        }

        // Mirrors Update 6's ProjectileInteraction launch validation while selecting
        // the projectile config dynamically from the loaded ammo.
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        assert (commandBuffer != null);

        Ref<EntityStore> owningEntity = context.getOwningEntity();
        Ref<EntityStore> creatorRef = owningEntity != null && owningEntity.isValid()
                ? owningEntity
                : context.getEntity();

        InteractionSyncData clientState = context.getClientState();
        UUID generatedUUID = clientState != null ? clientState.generatedUUID : null;
        Transform spawnTransform = getProjectileSpawnSource(clientState, context);
        Rotation3f rotation = spawnTransform.getRotation();
        if (ignorePitch) {
            rotation.setPitch(0.0f);
        }
        if (ignoreYaw) {
            rotation.setYaw(0.0f);
        }
        if (ignoreRoll) {
            rotation.setRoll(0.0f);
        }

        InteractionChain chain = context.getChain();
        InteractionEntry entry = context.getEntry();
        Long scaleSeed = chain != null && entry != null
                ? HashUtil.hash(chain.getChainId(), entry.getIndex())
                : null;

        ProjectileModule.get().spawnProjectile(generatedUUID, scaleSeed, creatorRef, commandBuffer, config,
                spawnTransform.getPosition(), spawnTransform.getDirection());
    }

    @Override
    @Nullable
    public ProjectileConfig getConfig() {
        String configId = this.config != null ? this.config : this.defaultConfig;
        return configId != null ? ProjectileConfig.getAssetMap().getAsset(configId) : null;
    }

    @Nullable
    private String resolveConfigId(@Nonnull InteractionContext context) {
        String resolvedConfigId = this.defaultConfig != null ? this.defaultConfig : this.config;
        ItemStack heldItem = context.getHeldItem();
        if (heldItem != null && !heldItem.isEmpty()) {
            String loadedAmmoId = heldItem.getFromMetadataOrNull("LoadedAmmoId", Codec.STRING);
            if (loadedAmmoId != null) {
                String candidateId = "Projectile_Config_" + loadedAmmoId;
                if (ProjectileConfig.getAssetMap().getAsset(candidateId) != null) {
                    resolvedConfigId = candidateId;
                } else {
                    String simplifiedId = loadedAmmoId.replace("Weapon_", "");
                    candidateId = "Projectile_Config_" + simplifiedId;
                    if (ProjectileConfig.getAssetMap().getAsset(candidateId) != null) {
                        resolvedConfigId = candidateId;
                    }
                }
            }
        }
        return resolvedConfigId;
    }

    @Nonnull
    private Transform getProjectileSpawnSource(@Nullable InteractionSyncData clientData,
            @Nonnull InteractionContext context) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        assert (commandBuffer != null);

        Transform serverTransform = TargetUtil.getLook(context.getEntity(), commandBuffer);
        if (clientData == null || clientData.attackerPos == null || clientData.attackerRot == null) {
            return serverTransform;
        }

        double distanceSquared = serverTransform.getPosition().distanceSquared(
                clientData.attackerPos.x, clientData.attackerPos.y, clientData.attackerPos.z);
        Velocity velocity = commandBuffer.getComponent(context.getEntity(), Velocity.getComponentType());
        double clientVelocitySquared = velocity != null ? velocity.getClientVelocity().lengthSquared() : 0.0;
        double allowanceSquared = Math.max(clientVelocitySquared, SPAWN_TRANSFORM_DESYNC_ALLOWANCE_MINIMUM_SQUARED);

        if (distanceSquared > allowanceSquared * SPAWN_TRANSFORM_DESYNC_ALLOWANCE_MULTIPLIER) {
            LOGGER.at(Level.WARNING).log("%s was too far from requested projectile spawn position (%f > %f)",
                    EntityUtils.getEntityName(context.getEntity(), commandBuffer), distanceSquared, allowanceSquared);
            return serverTransform;
        }

        return new Transform(clientData.attackerPos.x, clientData.attackerPos.y, clientData.attackerPos.z,
                clientData.attackerRot.pitch, clientData.attackerRot.yaw, clientData.attackerRot.roll);
    }
}
