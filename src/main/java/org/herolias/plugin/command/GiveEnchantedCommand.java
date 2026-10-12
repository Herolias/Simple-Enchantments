package org.herolias.plugin.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
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
import com.hypixel.hytale.server.core.util.BsonUtil;
import org.bson.BsonDocument;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.enchantment.EnchantmentData;
import org.herolias.plugin.enchantment.EnchantmentManager;
import org.herolias.plugin.enchantment.EnchantmentType;
import org.herolias.plugin.util.InventoryAccess;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;

/**
 * {@code /giveenchanted} - a vanilla-style give command that additionally accepts an
 * {@code enchants} argument ({@code id:level,id:level}).
 * <p>
 * The command is registered under its own name so the vanilla {@code /give}
 * command (and its {@code armor} sub-command) stay untouched.
 * <p>
 * Permissions mirror vanilla's give command:
 * <ul>
 * <li>{@code hytale.command.giveenchanted.self} - default group {@code hytale:Builder}</li>
 * <li>{@code hytale.command.giveenchanted.other} - default group {@code hytale:WorldEditor}</li>
 * </ul>
 */
public class GiveEnchantedCommand extends AbstractPlayerCommand {

    public static final String COMMAND_NAME = "giveenchanted";

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Message MESSAGE_COMMANDS_ERRORS_PLAYER_NOT_IN_WORLD = Message
            .translation("server.commands.errors.playerNotInWorld");

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
        super(COMMAND_NAME, "server.commands.give.desc");
        this.requirePermission(HytalePermissions.fromCommand(COMMAND_NAME + ".self"));
        this.setPermissionGroups("hytale:Builder");
        this.addUsageVariant(new GiveOtherEnchantedCommand());
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        Player playerComponent = store.getComponent(ref, Player.getComponentType());
        if (playerComponent == null) {
            context.sendMessage(MESSAGE_COMMANDS_ERRORS_PLAYER_NOT_IN_WORLD);
            return;
        }
        double durability = this.durabilityArg.provided(context) ? this.durabilityArg.get(context) : Double.MAX_VALUE;
        giveEnchanted(context, store, ref, playerRef, null,
                this.itemArg.get(context), this.quantityArg.get(context), durability,
                this.metadataArg.provided(context) ? this.metadataArg.get(context) : null,
                this.enchantsArg.provided(context) ? this.enchantsArg.get(context) : null);
    }

    /**
     * Shared implementation for the self and other variants. Must run on the
     * target's world thread.
     *
     * @param targetUsername null when giving to the command sender
     */
    private static void giveEnchanted(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef targetPlayerRef, @Nullable String targetUsername,
            @Nonnull Item item, int quantity, double durability, @Nullable String metadataStr,
            @Nullable String enchantsStr) {
        BsonDocument metadata = new BsonDocument();

        // 1. Parse base metadata if provided
        if (metadataStr != null) {
            try {
                metadata = BsonUtil.parseWithMaxDepth(metadataStr);
            } catch (Exception e) {
                context.sendMessage(
                        Message.translation("server.commands.give.invalidMetadata").param("error", e.getMessage()));
                return;
            }
        }

        // 2. Parse and merge enchantments if provided (rejects unknown ids and levels < 1)
        EnchantmentData enchantments = null;
        if (enchantsStr != null) {
            try {
                enchantments = parseEnchantments(enchantsStr);
            } catch (IllegalArgumentException e) {
                context.sendMessage(Message.translation("server.commands.give.invalidMetadata").param("error",
                        "Invalid enchants format: " + e.getMessage()));
                return;
            }
            if (!enchantments.isEmpty()) {
                metadata.put(EnchantmentData.METADATA_KEY, enchantments.toBson());
            }
        }

        // Vanilla passes null metadata when nothing was provided.
        BsonDocument finalMetadata = metadata.isEmpty() ? null : metadata;

        ItemStack stack = new ItemStack(item.getId(), quantity, finalMetadata).withDurability(durability);
        ItemStackTransaction transaction = InventoryAccess.getCombinedHotbarFirst(store, ref).addItemStack(stack);
        ItemStack remainder = transaction.getRemainder();
        Message itemNameMessage = Message.translation(item.getTranslationKey());
        boolean fullyGiven = remainder == null || remainder.isEmpty();
        boolean anythingGiven = fullyGiven || remainder.getQuantity() < quantity;

        if (fullyGiven) {
            if (targetUsername == null) {
                context.sendMessage(Message.translation("server.commands.give.received").param("quantity", quantity)
                        .param("item", itemNameMessage));
            } else {
                context.sendMessage(Message.translation("server.commands.give.gave")
                        .param("targetUsername", targetUsername).param("quantity", quantity)
                        .param("item", itemNameMessage));
            }
            if (enchantments != null && !enchantments.isEmpty()) {
                context.sendMessage(Message.raw((targetUsername == null ? "Applied enchantments: " : "With enchantments: ")
                        + enchantsStr));
            }
        } else {
            context.sendMessage(Message.translation("server.commands.give.insufficientInvSpace")
                    .param("quantity", quantity).param("item", itemNameMessage));
        }

        // The item is now in the inventory, so it is safe to notify listeners.
        if (anythingGiven) {
            fireItemEnchantedEvents(targetPlayerRef, stack, enchantments);
        }
    }

    /**
     * Parses an {@code enchants} argument of the form {@code id:level,id:level}.
     * Entries may also be separated by whitespace, {@code ;}, {@code +} or
     * {@code |}; namespaced addon ids ({@code my_mod:lightning:2}) and display
     * names are accepted.
     *
     * @throws IllegalArgumentException for malformed entries, unknown enchantments
     *                                  or levels below 1
     */
    @Nonnull
    static EnchantmentData parseEnchantments(@Nullable String raw) {
        EnchantmentData data = new EnchantmentData();
        if (raw == null || raw.isBlank()) {
            return data;
        }
        for (String part : raw.split("[,\\s;+|]+")) {
            if (part.isEmpty()) {
                continue;
            }
            int separator = part.lastIndexOf(':');
            if (separator <= 0 || separator == part.length() - 1) {
                throw new IllegalArgumentException("expected <enchantment>:<level>, got '" + part + "'");
            }
            String key = part.substring(0, separator).trim();
            String levelStr = part.substring(separator + 1).trim();

            EnchantmentType type = EnchantmentType.fromId(key);
            if (type == null) {
                type = EnchantmentType.findByDisplayName(key);
            }
            if (type == null) {
                throw new IllegalArgumentException("unknown enchantment '" + key + "'");
            }

            int level;
            try {
                level = Integer.parseInt(levelStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("level for '" + key + "' is not a number: '" + levelStr + "'");
            }
            if (level < 1) {
                throw new IllegalArgumentException("level for '" + key + "' must be at least 1 (got " + level + ")");
            }
            data.addEnchantment(type, level);
        }
        return data;
    }

    private static void fireItemEnchantedEvents(@Nullable PlayerRef playerRef, @Nonnull ItemStack stack,
            @Nullable EnchantmentData enchantments) {
        if (enchantments == null || enchantments.isEmpty()) {
            return;
        }
        SimpleEnchanting plugin = SimpleEnchanting.getInstance();
        EnchantmentManager manager = plugin != null ? plugin.getEnchantmentManager() : null;
        if (manager == null) {
            LOGGER.atWarning().log("EnchantmentManager unavailable; ItemEnchantedEvent not fired for /%s",
                    COMMAND_NAME);
            return;
        }
        for (Map.Entry<EnchantmentType, Integer> entry : enchantments.getAllEnchantments().entrySet()) {
            manager.fireItemEnchanted(playerRef, stack, entry.getKey(), entry.getValue());
        }
    }

    private static class GiveOtherEnchantedCommand extends CommandBase {
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
            this.requirePermission(HytalePermissions.fromCommand(COMMAND_NAME + ".other"));
            this.setPermissionGroups("hytale:WorldEditor");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            PlayerRef targetPlayerRef = this.playerArg.get(context);
            Ref<EntityStore> ref = targetPlayerRef.getReference();
            if (ref == null || !ref.isValid()) {
                context.sendMessage(MESSAGE_COMMANDS_ERRORS_PLAYER_NOT_IN_WORLD);
                return;
            }
            Store<EntityStore> store = ref.getStore();
            World world = store.getExternalData().getWorld();
            if (!world.isAlive()) {
                context.sendMessage(MESSAGE_COMMANDS_ERRORS_PLAYER_NOT_IN_WORLD);
                return;
            }

            // Resolve arguments on the command thread; the inventory write runs on the world thread.
            Item item = this.itemArg.get(context);
            int quantity = this.quantityArg.get(context);
            double durability = this.durabilityArg.provided(context) ? this.durabilityArg.get(context)
                    : Double.MAX_VALUE;
            String metadataStr = this.metadataArg.provided(context) ? this.metadataArg.get(context) : null;
            String enchantsStr = this.enchantsArg.provided(context) ? this.enchantsArg.get(context) : null;

            try {
                world.execute(() -> {
                    if (!ref.isValid()) {
                        context.sendMessage(MESSAGE_COMMANDS_ERRORS_PLAYER_NOT_IN_WORLD);
                        return;
                    }
                    Player playerComponent = store.getComponent(ref, Player.getComponentType());
                    PlayerRef playerRefComponent = store.getComponent(ref, PlayerRef.getComponentType());
                    if (playerComponent == null || playerRefComponent == null) {
                        context.sendMessage(MESSAGE_COMMANDS_ERRORS_PLAYER_NOT_IN_WORLD);
                        return;
                    }
                    giveEnchanted(context, store, ref, playerRefComponent, targetPlayerRef.getUsername(),
                            item, quantity, durability, metadataStr, enchantsStr);
                });
            } catch (Exception e) {
                // The world may be shutting down; report instead of silently dropping the command.
                LOGGER.atWarning().withCause(e).log("Failed to schedule /%s for %s", COMMAND_NAME,
                        targetPlayerRef.getUsername());
                context.sendMessage(MESSAGE_COMMANDS_ERRORS_PLAYER_NOT_IN_WORLD);
            }
        }
    }
}
