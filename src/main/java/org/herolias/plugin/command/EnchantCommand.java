package org.herolias.plugin.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.enchantment.EnchantmentApplicationResult;
import org.herolias.plugin.enchantment.EnchantmentData;
import org.herolias.plugin.enchantment.EnchantmentManager;
import org.herolias.plugin.enchantment.EnchantmentType;
import org.herolias.plugin.util.InventoryAccess;

import javax.annotation.Nonnull;

/**
 * Command to apply enchantments to items using the metadata-based enchantment
 * system.
 * 
 * Enchantments are stored directly in ItemStack metadata, eliminating the need
 * for separate item variants per enchantment.
 * 
 * Usage: /enchant [enchantment] [level]
 * 
 * Examples:
 * /enchant sharpness 2 - Apply Sharpness 2
 * /enchant durability 3 - Apply Durability 3
 */
public class EnchantCommand extends AbstractPlayerCommand {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int MAX_LEVEL = 100;

    private final SimpleEnchanting plugin;
    private final EnchantmentManager enchantmentManager;

    public EnchantCommand(SimpleEnchanting plugin) {
        super("enchant", "Apply enchantment to held item. Usage: /enchant [enchantment] [level]");
        this.plugin = plugin;
        this.enchantmentManager = plugin.getEnchantmentManager();
        this.setAllowsExtraArguments(true);
        this.setPermissionGroups("hytale:WorldEditor"); // Ops only
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        Player player = store.getComponent(ref, Player.getComponentType());

        if (player == null) {
            context.sendMessage(Message.raw("Only players can use this command."));
            return;
        }

        CommandSender sender = context.sender();

        String rawInput = context.getInputString();
        String[] args = rawInput != null && !rawInput.isEmpty() ? rawInput.split("\\s+") : new String[0];

        if (args.length == 0) {
            sender.sendMessage(Message.raw("Usage: /enchant <enchantment> [level]"));
            return;
        }

        // Parse arguments
        EnchantmentType enchantmentType = null;
        int level = 1;

        for (String arg : args) {
            try {
                int parsed = Integer.parseInt(arg);
                if (parsed < 1) {
                    sender.sendMessage(Message.raw("Level must be at least 1 (got " + parsed + ")."));
                    return;
                }
                level = parsed;
            } catch (NumberFormatException e) {
                if (enchantmentType == null) {
                    enchantmentType = parseEnchantmentType(arg);
                }
            }
        }

        if (enchantmentType == null) {
            sender.sendMessage(Message.raw("Unknown enchantment. Please specify a valid enchantment name."));
            return;
        }

        // Clamp level
        if (level > MAX_LEVEL) {
            sender.sendMessage(Message.raw("Level " + level + " is too high. Max level is " + MAX_LEVEL + "."));
            level = MAX_LEVEL;
        }

        LOGGER.atInfo().log("Enchant request: %s %d", enchantmentType.getDisplayName(), level);

        // Resolve the exact container/slot of the held item (tools section or hotbar)
        InventoryAccess.HeldSlot held = InventoryAccess.getHeldSlot(store, ref);
        if (held == null) {
            sender.sendMessage(Message.raw("You must be holding an item!"));
            return;
        }
        ItemStack item = held.item();

        // Check if upgrade is meaningful (optional, but good UX)
        EnchantmentData currentEnchants = enchantmentManager.getEnchantmentsFromItem(item);
        int currentLevel = currentEnchants.getLevel(enchantmentType);
        if (currentLevel >= level) {
            sender.sendMessage(Message.raw("This item already has " + enchantmentType.getDisplayName() + " at level "
                    + currentLevel + " (Requesting: " + level + ")"));
            return;
        }

        try {
            // Apply enchantment (Delegates checks to manager)
            EnchantmentApplicationResult result = enchantmentManager
                    .applyEnchantmentToItem(playerRef, item, enchantmentType, level, true);

            if (!result.success()) {
                sender.sendMessage(Message.raw(result.message()));
                return;
            }

            // Write back to the slot we read from; fails if the held stack changed meanwhile
            ItemStackSlotTransaction transaction = held.container()
                    .replaceItemStackInSlot(held.slot(), item, result.item());
            if (!transaction.succeeded()) {
                sender.sendMessage(Message.raw("The held item changed while enchanting. Nothing was applied."));
                return;
            }

            // The item is committed; notify listeners
            enchantmentManager.fireItemEnchanted(playerRef, result);

            // Success message
            EnchantmentData newEnchants = enchantmentManager.getEnchantmentsFromItem(result.item());
            StringBuilder enchantList = new StringBuilder();
            for (var entry : newEnchants.getAllEnchantments().entrySet()) {
                if (enchantList.length() > 0)
                    enchantList.append(", ");
                enchantList.append(entry.getKey().getFormattedName(entry.getValue()));
            }

            sender.sendMessage(Message.raw("Enchanted! [" + enchantList + "]"));
            LOGGER.atInfo().log("%s enchanted %s with %s", sender.getUsername(), item.getItemId(),
                    enchantmentType.getFormattedName(level));

        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Failed to apply enchantment");
            sender.sendMessage(Message.raw("Failed to apply enchantment: " + e.getMessage()));
        }
    }

    private EnchantmentType parseEnchantmentType(String arg) {
        EnchantmentType type = EnchantmentType.fromId(arg.toLowerCase());
        if (type != null) {
            return type;
        }

        String cleanArg = arg.toLowerCase().replace(" ", "").replace("_", "");
        for (EnchantmentType t : EnchantmentType.values()) {
            String cleanName = t.getDisplayName().toLowerCase().replace(" ", "").replace("_", "");
            if (cleanName.equals(cleanArg) || t.getDisplayName().equalsIgnoreCase(arg)) {
                return t;
            }
        }
        return null;
    }
}
