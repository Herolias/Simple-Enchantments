package org.herolias.plugin.interaction;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.Interaction;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.codec.ProtocolCodecs;

import javax.annotation.Nonnull;
import java.util.List;

public class ConsumeAmmoInteraction extends SimpleInstantInteraction {

    public static final BuilderCodec<ConsumeAmmoInteraction> CODEC = BuilderCodec.builder(ConsumeAmmoInteraction.class, ConsumeAmmoInteraction::new, SimpleInstantInteraction.CODEC)
        .documentation("Consumes the first ammo item found in the player's inventory.")
        .appendInherited(new KeyedCodec<>("RequiredGameMode", ProtocolCodecs.GAMEMODE), 
            (interaction, s) -> interaction.requiredGameMode = s, 
            interaction -> interaction.requiredGameMode, 
            (interaction, parent) -> interaction.requiredGameMode = parent.requiredGameMode)
        .add()
        .build();

    private GameMode requiredGameMode;

    @Override
    @Nonnull
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        Ref<EntityStore> ref = context.getEntity();
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Player playerComponent = commandBuffer.getComponent(ref, Player.getComponentType());

        if (playerComponent == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        boolean hasRequiredGameMode = this.requiredGameMode == null || playerComponent.getGameMode() == this.requiredGameMode;
        if (!hasRequiredGameMode) {
            return;
        }

        if (playerComponent.getGameMode() == GameMode.Creative) {
            // Creative mode doesn't consume ammo
            return;
        }
        // Original firstRun method end

        // New code starts here, replacing the inventory and ammo finding logic
        // and adding the metadata update.

        CombinedItemContainer inventory = playerComponent.getInventory().getCombinedHotbarFirst();
        
        // Find ammo item
        ItemStack ammoItem = null;
        for (int i = 0; i < inventory.getCapacity(); i++) {
            ItemStack stack = inventory.getItemStack((short)i);
            if (stack != null && !stack.isEmpty()) {
                Item item = stack.getItem();
                if (item != null && item.getData() != null && item.getData().getRawTags().containsKey("Category:Ammunition")) {
                     ammoItem = stack;
                     break;
                }
            }
        }

        if (ammoItem != null) {
            String ammoId = ammoItem.getItemId();
            ItemStackTransaction transaction = inventory.removeItemStack(ammoItem.withQuantity(1), true, true);
            if (!transaction.succeeded()) {
                context.getState().state = InteractionState.Failed;
            } else {
                // Store the used ammo ID on the crossbow (item in hand)
                Inventory playerInventory = playerComponent.getInventory();
                ItemStack crossbow = playerInventory.getItemInHand();
                
                if (crossbow != null && !crossbow.isEmpty()) {
                     // We use a simple String codec for the ammo ID
                     ItemStack updatedCrossbow = crossbow.withMetadata("LoadedAmmoId", com.hypixel.hytale.codec.Codec.STRING, ammoId);
                     // Update the item in the inventory
                     if (playerInventory.getActiveHotbarSlot() != -1) {
                        playerInventory.getHotbar().setItemStackForSlot((short)playerInventory.getActiveHotbarSlot(), updatedCrossbow);
                     }
                }
            }
        } else {
             context.getState().state = InteractionState.Failed;
        }
    }
}
