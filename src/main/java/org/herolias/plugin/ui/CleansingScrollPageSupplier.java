package org.herolias.plugin.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.inventory.ItemContext;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.enchantment.EnchantmentManager;

/**
 * Page supplier for the Scroll of Cleansing.
 * Opens a UI showing all enchanted items in the player's inventory.
 */
public class CleansingScrollPageSupplier implements OpenCustomUIInteraction.CustomPageSupplier {
    public static final BuilderCodec<CleansingScrollPageSupplier> CODEC = BuilderCodec
            .builder(CleansingScrollPageSupplier.class, CleansingScrollPageSupplier::new)
            .build();

    @Override
    public CustomUIPage tryCreate(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor,
            @Nonnull PlayerRef playerRef,
            @Nonnull InteractionContext context) {
        Player playerComponent = componentAccessor.getComponent(ref, Player.getComponentType());
        if (playerComponent == null) {
            return null;
        }

        ItemContext heldItemContext = context.createHeldItemContext();
        if (heldItemContext == null) {
            return null;
        }

        ItemContainer itemContainer = playerComponent.getInventory().getCombinedArmorHotbarUtilityStorage();
        if (itemContainer == null) {
            return null;
        }

        EnchantmentManager manager = SimpleEnchanting.getInstance().getEnchantmentManager();
        return new CleansingScrollPage(playerRef, itemContainer, manager, heldItemContext);
    }
}
