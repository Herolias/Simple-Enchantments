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
import org.herolias.plugin.enchantment.EnchantmentType;

public class EnchantScrollPageSupplier implements OpenCustomUIInteraction.CustomPageSupplier {
    public static final BuilderCodec<EnchantScrollPageSupplier> CODEC = BuilderCodec
            .builder(EnchantScrollPageSupplier.class, EnchantScrollPageSupplier::new)
            .append(
                    new KeyedCodec<String>("EnchantmentId", Codec.STRING),
                    (data, o) -> data.enchantmentId = o,
                    data -> data.enchantmentId)
            .add()
            .append(
                    new KeyedCodec<Integer>("Level", Codec.INTEGER),
                    (data, o) -> data.level = o,
                    data -> data.level)
            .add()
            .build();

    private String enchantmentId;
    private int level = 1;

    @Override
    public CustomUIPage tryCreate(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor,
            @Nonnull PlayerRef playerRef,
            @Nonnull InteractionContext context) {
        EnchantmentType type = EnchantmentType.fromId(this.enchantmentId);
        if (type == null) {
            playerRef.sendMessage(Message.raw("Unknown enchantment: " + this.enchantmentId));
            return null;
        }

        org.herolias.plugin.config.EnchantingConfig config = org.herolias.plugin.SimpleEnchanting.getInstance()
                .getConfigManager().getConfig();
        if (config.disabledEnchantments.getOrDefault(type.getId(), false)) {
            playerRef.sendMessage(Message.raw("This enchantment is currently disabled!").color("#FF5555"));
            return null;
        }

        int targetLevel = Math.max(1, Math.min(this.level, type.getMaxLevel()));
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
        return new EnchantScrollPage(playerRef, itemContainer, manager, type, targetLevel, heldItemContext);
    }
}
