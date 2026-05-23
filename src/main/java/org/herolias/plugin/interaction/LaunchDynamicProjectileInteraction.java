package org.herolias.plugin.interaction;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Vector3dUtil;
import org.joml.Vector3d;
import com.hypixel.hytale.protocol.InteractionSyncData;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.projectile.ProjectileModule;
import com.hypixel.hytale.server.core.modules.projectile.config.ProjectileConfig;
import com.hypixel.hytale.server.core.modules.projectile.interaction.ProjectileInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.PositionUtil;
import com.hypixel.hytale.server.core.util.TargetUtil;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class LaunchDynamicProjectileInteraction
        extends ProjectileInteraction {

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
        UUID generatedUUID;
        Vector3d direction;
        Vector3d position;

        String resolvedConfigId = resolveConfigId(context);
        if (resolvedConfigId == null) {
            return;
        }

        ProjectileConfig config = ProjectileConfig.getAssetMap().getAsset(resolvedConfigId);
        if (config == null) {
            return;
        }

        // Copied from ProjectileInteraction.java base logic with dynamic config selection.
        InteractionSyncData clientState = context.getClientState();
        Ref<EntityStore> ref = context.getEntity();
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        assert (commandBuffer != null);
        boolean hasClientState = clientState != null && clientState.attackerPos != null
                && clientState.attackerRot != null;
        if (hasClientState) {
            position = PositionUtil.toVector3d(clientState.attackerPos);
            Rotation3f lookVec = PositionUtil.toRotation(clientState.attackerRot);
            direction = Vector3dUtil.setYawPitch(lookVec.yaw(), lookVec.pitch(), new Vector3d());
            generatedUUID = clientState.generatedUUID;
        } else {
            Transform lookVec = TargetUtil.getLook(ref, commandBuffer);
            position = lookVec.getPosition();
            direction = lookVec.getDirection();
            generatedUUID = null;
        }
        ProjectileModule.get().spawnProjectile(generatedUUID, ref, commandBuffer, config, position, direction);
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
        if (context.getEntity() != null) {
            CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
            if (commandBuffer != null) {
                Player player = commandBuffer.getComponent(context.getEntity(), Player.getComponentType());
                if (player != null) {
                    ItemStack heldItem = player.getInventory().getItemInHand();
                    if (heldItem != null && !heldItem.isEmpty()) {
                        String loadedAmmoId = heldItem.getFromMetadataOrNull("LoadedAmmoId", Codec.STRING);
                        if (loadedAmmoId != null) {
                            // Attempt to resolve config from Ammo ID
                            // Strategy: try "Projectile_Config_" + AmmoID
                            // Example: Weapon_Arrow_Iron -> Projectile_Config_Weapon_Arrow_Iron
                            String candidateId = "Projectile_Config_" + loadedAmmoId;
                            if (ProjectileConfig.getAssetMap().getAsset(candidateId) != null) {
                                resolvedConfigId = candidateId;
                            } else {
                                // Try replacing "Weapon_" with nothing if present?
                                // e.g. Weapon_Arrow_Iron -> Projectile_Config_Arrow_Iron
                                String simplifiedId = loadedAmmoId.replace("Weapon_", "");
                                candidateId = "Projectile_Config_" + simplifiedId;
                                if (ProjectileConfig.getAssetMap().getAsset(candidateId) != null) {
                                    resolvedConfigId = candidateId;
                                }
                            }
                        }
                    }
                }
            }
        }
        return resolvedConfigId;
    }
}
