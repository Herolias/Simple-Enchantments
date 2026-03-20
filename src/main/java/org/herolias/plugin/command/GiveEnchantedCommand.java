package org.herolias.plugin.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.command.commands.player.inventory.GiveArmorCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.DefaultArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.bson.BsonDocument;
import org.herolias.plugin.enchantment.EnchantmentData;
import org.herolias.plugin.enchantment.EnchantmentType;

import javax.annotation.Nonnull;

/**
 * Extended Give Command that supports applying enchantments via --enchants
 * argument.
 * Intended to replace the vanilla /give command.
 */
public class GiveEnchantedCommand extends AbstractPlayerCommand {
    @Nonnull
    private final RequiredArg<Item> itemArg = this.withRequiredArg("item", "server.commands.give.item.desc",
            ArgTypes.ITEM_ASSET);
    @Nonnull
    private final DefaultArg<Integer> quantityArg = this.withDefaultArg("quantity",
            "server.commands.give.quantity.desc", ArgTypes.INTEGER, Integer.valueOf(1), "1");
    @Nonnull
    private final OptionalArg<Double> durabilityArg = this.withOptionalArg("durability",
            "server.commands.give.durability.desc", ArgTypes.DOUBLE);
    @Nonnull
    private final OptionalArg<String> metadataArg = this.withOptionalArg("metadata",
            "server.commands.give.metadata.desc", ArgTypes.STRING);
    @Nonnull
    private final OptionalArg<String> enchantsArg = this.withOptionalArg("enchants", "Enchantments (id:level,id:level)",
            ArgTypes.STRING);

    public GiveEnchantedCommand() {
        super("give", "server.commands.give.desc");
        this.requirePermission(HytalePermissions.fromCommand("give.self"));
        this.addUsageVariant(new GiveOtherEnchantedCommand());
        this.addSubCommand(new GiveArmorCommand());
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        Player playerComponent = store.getComponent(ref, Player.getComponentType());
        assert (playerComponent != null);
        Item item = (Item) this.itemArg.get(context);
        Integer quantity = (Integer) this.quantityArg.get(context);
        double durability = Double.MAX_VALUE;
        if (this.durabilityArg.provided(context)) {
            durability = (Double) this.durabilityArg.get(context);
        }

        BsonDocument metadata = new BsonDocument();

        // 1. Parse base metadata if provided
        if (this.metadataArg.provided(context)) {
            String metadataStr = (String) this.metadataArg.get(context);
            try {
                metadata = BsonDocument.parse(metadataStr);
            } catch (Exception e) {
                context.sendMessage(
                        Message.translation("server.commands.give.invalidMetadata").param("error", e.getMessage()));
                return;
            }
        }

        // 2. Parse and merge enchantments if provided
        if (this.enchantsArg.provided(context)) {
            String enchantsStr = (String) this.enchantsArg.get(context);

            try {
                EnchantmentData data = EnchantmentData.deserialize(enchantsStr);
                if (!data.isEmpty()) {
                    metadata.put(EnchantmentData.METADATA_KEY, data.toBson());
                }
            } catch (Exception e) {
                context.sendMessage(Message.translation("server.commands.give.invalidMetadata").param("error",
                        "Invalid enchants format: " + e.getMessage()));
                return;
            }
        }

        // If metadata is empty, pass null to constructor to match vanilla behavior if
        // desired,
        // though passing empty doc is usually safe. Vanilla passes null if arg not
        // provided.
        // But here we constructed a new one.
        BsonDocument finalMetadata = metadata.isEmpty() ? null : metadata;

        ItemStack stack = new ItemStack(item.getId(), quantity, finalMetadata).withDurability(durability);
        ItemStackTransaction transaction = playerComponent.getInventory().getCombinedHotbarFirst().addItemStack(stack);
        ItemStack remainder = transaction.getRemainder();
        Message itemNameMessage = Message.translation(item.getTranslationKey());
        if (remainder == null || remainder.isEmpty()) {
            context.sendMessage(Message.translation("server.commands.give.received").param("quantity", quantity)
                    .param("item", itemNameMessage));
            // Feedback for enchantments
            if (finalMetadata != null && finalMetadata.containsKey(EnchantmentData.METADATA_KEY)) {
                context.sendMessage(Message.raw("Applied enchantments: " + this.enchantsArg.get(context)));
            }
        } else {
            context.sendMessage(Message.translation("server.commands.give.insufficientInvSpace")
                    .param("quantity", quantity).param("item", itemNameMessage));
        }
    }

    private static class GiveOtherEnchantedCommand extends CommandBase {
        @Nonnull
        private static final Message MESSAGE_COMMANDS_ERRORS_PLAYER_NOT_IN_WORLD = Message
                .translation("server.commands.errors.playerNotInWorld");
        @Nonnull
        private final RequiredArg<PlayerRef> playerArg = this.withRequiredArg("player",
                "server.commands.argtype.player.desc", ArgTypes.PLAYER_REF);
        @Nonnull
        private final RequiredArg<Item> itemArg = this.withRequiredArg("item", "server.commands.give.item.desc",
                ArgTypes.ITEM_ASSET);
        @Nonnull
        private final DefaultArg<Integer> quantityArg = this.withDefaultArg("quantity",
                "server.commands.give.quantity.desc", ArgTypes.INTEGER, Integer.valueOf(1), "1");
        @Nonnull
        private final OptionalArg<Double> durabilityArg = this.withOptionalArg("durability",
                "server.commands.give.durability.desc", ArgTypes.DOUBLE);
        @Nonnull
        private final OptionalArg<String> metadataArg = this.withOptionalArg("metadata",
                "server.commands.give.metadata.desc", ArgTypes.STRING);
        @Nonnull
        private final OptionalArg<String> enchantsArg = this.withOptionalArg("enchants",
                "Enchantments (id:level,id:level)", ArgTypes.STRING);

        GiveOtherEnchantedCommand() {
            super("server.commands.give.other.desc");
            this.requirePermission(HytalePermissions.fromCommand("give.other"));
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            PlayerRef targetPlayerRef = (PlayerRef) this.playerArg.get(context);
            Ref<EntityStore> ref = targetPlayerRef.getReference();
            if (ref == null || !ref.isValid()) {
                context.sendMessage(MESSAGE_COMMANDS_ERRORS_PLAYER_NOT_IN_WORLD);
                return;
            }
            Store<EntityStore> store = ref.getStore();
            World world = store.getExternalData().getWorld();
            if (world.isAlive()) {
                try {
                    world.execute(() -> {
                        Player playerComponent = store.getComponent(ref, Player.getComponentType());
                        if (playerComponent == null) {
                            context.sendMessage(MESSAGE_COMMANDS_ERRORS_PLAYER_NOT_IN_WORLD);
                            return;
                        }
                        PlayerRef playerRefComponent = store.getComponent(ref, PlayerRef.getComponentType());
                        assert (playerRefComponent != null);
                        Item item = (Item) this.itemArg.get(context);
                        Integer quantity = (Integer) this.quantityArg.get(context);
                        double durability = Double.MAX_VALUE;
                        if (this.durabilityArg.provided(context)) {
                            durability = (Double) this.durabilityArg.get(context);
                        }

                        BsonDocument metadata = new BsonDocument();

                        // 1. Parse base metadata
                        if (this.metadataArg.provided(context)) {
                            String metadataStr = (String) this.metadataArg.get(context);
                            try {
                                metadata = BsonDocument.parse(metadataStr);
                            } catch (Exception e) {
                                context.sendMessage(Message.translation("server.commands.give.invalidMetadata")
                                        .param("error", e.getMessage()));
                                return;
                            }
                        }

                        // 2. Parse enchants
                        if (this.enchantsArg.provided(context)) {
                            String enchantsStr = (String) this.enchantsArg.get(context);

                            try {
                                EnchantmentData data = EnchantmentData.deserialize(enchantsStr);
                                if (!data.isEmpty()) {
                                    metadata.put(EnchantmentData.METADATA_KEY, data.toBson());
                                }
                            } catch (Exception e) {
                                context.sendMessage(Message.translation("server.commands.give.invalidMetadata")
                                        .param("error", "Invalid enchants format: " + e.getMessage()));
                                return;
                            }
                        }

                        BsonDocument finalMetadata = metadata.isEmpty() ? null : metadata;

                        ItemStack stack = new ItemStack(item.getId(), quantity, finalMetadata)
                                .withDurability(durability);
                        ItemStackTransaction transaction = playerComponent.getInventory().getCombinedHotbarFirst()
                                .addItemStack(stack);
                        ItemStack remainder = transaction.getRemainder();
                        Message itemNameMessage = Message.translation(item.getTranslationKey());
                        if (remainder == null || remainder.isEmpty()) {
                            context.sendMessage(Message.translation("server.commands.give.gave")
                                    .param("targetUsername", targetPlayerRef.getUsername()).param("quantity", quantity)
                                    .param("item", itemNameMessage));
                            // Feedback for enchantments
                            if (finalMetadata != null && finalMetadata.containsKey(EnchantmentData.METADATA_KEY)) {
                                context.sendMessage(Message.raw("With enchantments: " + this.enchantsArg.get(context)));
                            }
                        } else {
                            context.sendMessage(Message.translation("server.commands.give.insufficientInvSpace")
                                    .param("quantity", quantity).param("item", itemNameMessage));
                        }
                    });
                } catch (Exception ignored) {
                    // Ignore exceptions during shutdown phase
                }
            }
        }
    }
}
