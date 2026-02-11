package org.herolias.plugin.interaction;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.Interaction;
import com.hypixel.hytale.protocol.InteractionSyncData;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.modules.projectile.ProjectileModule;
import com.hypixel.hytale.server.core.modules.projectile.config.BallisticData;
import com.hypixel.hytale.server.core.modules.projectile.config.BallisticDataProvider;
import com.hypixel.hytale.server.core.modules.projectile.config.ProjectileConfig;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.PositionUtil;
import com.hypixel.hytale.server.core.util.TargetUtil;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class LaunchDynamicProjectileInteraction extends com.hypixel.hytale.server.core.modules.projectile.interaction.ProjectileInteraction {

    public static final BuilderCodec<LaunchDynamicProjectileInteraction> CODEC = BuilderCodec.builder(LaunchDynamicProjectileInteraction.class, LaunchDynamicProjectileInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Fires a projectile based on loaded ammo.")
            .append(new KeyedCodec<String>("DefaultConfig", Codec.STRING), 
                    (LaunchDynamicProjectileInteraction o, String i) -> o.defaultConfig = i, 
                    (LaunchDynamicProjectileInteraction o) -> o.defaultConfig)
            .add()
            .build();

    protected String defaultConfig;

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        UUID generatedUUID;
        Vector3d direction;
        Vector3d position;
        boolean hasClientState;
        
        // --- Dynamic Config Resolution Start ---
        String resolvedConfigId = this.defaultConfig;
        
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
                        // If standard naming follows, we might want to strip "Weapon_" prefix or similar?
                        // Let's try direct concatenation first as it's most robust if we align JSONs.
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
        
        ProjectileConfig config = ProjectileConfig.getAssetMap().getAsset(resolvedConfigId);
        // --- Dynamic Config Resolution End ---

        if (config == null) {
            return;
        }
        
        // Copied from ProjectileInteraction.java base logic
        InteractionSyncData clientState = context.getClientState();
        Ref<EntityStore> ref = context.getEntity();
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        assert (commandBuffer != null);
        boolean bl = hasClientState = clientState != null && clientState.attackerPos != null && clientState.attackerRot != null;
        if (hasClientState) {
            position = PositionUtil.toVector3d(clientState.attackerPos);
            Vector3f lookVec = PositionUtil.toRotation(clientState.attackerRot);
            direction = new Vector3d(lookVec.getYaw(), lookVec.getPitch());
            generatedUUID = clientState.generatedUUID;
        } else {
            Transform lookVec = TargetUtil.getLook(ref, commandBuffer);
            position = lookVec.getPosition();
            direction = lookVec.getDirection();
            generatedUUID = null;
        }
        ProjectileModule.get().spawnProjectile(generatedUUID, ref, commandBuffer, config, position, direction);
    }
}
