package org.herolias.plugin.command;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.enchantment.EnchantmentData;
import org.herolias.plugin.enchantment.EnchantmentManager;
import org.herolias.plugin.enchantment.EnchantmentType;

import javax.annotation.Nonnull;

/**
 * Command to apply enchantments to items using the metadata-based enchantment system.
 * 
 * Enchantments are stored directly in ItemStack metadata, eliminating the need
 * for separate item variants per enchantment.
 * 
 * Usage: /enchant [enchantment] [level]
 * 
 * Examples:
 *   /enchant              - Apply Sharpness 1
 *   /enchant sharpness 2  - Apply Sharpness 2
 *   /enchant durability 3 - Apply Durability 3
 */
public class EnchantCommand extends CommandBase {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    
    private final SimpleEnchanting plugin;
    private final EnchantmentManager enchantmentManager;

    public EnchantCommand(SimpleEnchanting plugin) {
        super("enchant", "Apply enchantment to held item. Usage: /enchant [enchantment] [level]");
        this.plugin = plugin;
        this.enchantmentManager = plugin.getEnchantmentManager();
        this.setAllowsExtraArguments(true);
    }

    @Override
    protected void executeSync(@Nonnull CommandContext context) {
        CommandSender sender = context.sender();

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Message.raw("Only players can use this command."));
            return;
        }

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
                if (parsed >= 1) {
                    level = parsed;
                }
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
        if (level > 100) {
             sender.sendMessage(Message.raw("Level " + level + " is too high. Max level is 100."));
             level = 100;
        }

        LOGGER.atInfo().log("Enchant request: " + enchantmentType.getDisplayName() + " " + level);

        // Get item
        Inventory inventory = player.getInventory();
        ItemContainer hotbar = inventory.getHotbar();

        if (hotbar == null) {
             sender.sendMessage(Message.raw("Could not access your hotbar!"));
             return;
        }

        ItemStack item = inventory.getItemInHand();
        if (item == null || item.isEmpty()) {
            sender.sendMessage(Message.raw("You must be holding an item!"));
            return;
        }

        // Check if upgrade is meaningful (optional, but good UX)
        EnchantmentData currentEnchants = enchantmentManager.getEnchantmentsFromItem(item);
        int currentLevel = currentEnchants.getLevel(enchantmentType);
        if (currentLevel >= level) {
            sender.sendMessage(Message.raw("This item already has " + enchantmentType.getDisplayName() + " at level " + currentLevel + " (Requesting: " + level + ")"));
            return;
        }

        try {
            // Apply enchantment (Delegates checks to manager)
            org.herolias.plugin.enchantment.EnchantmentApplicationResult result = enchantmentManager.applyEnchantmentToItem(item, enchantmentType, level, true);

            if (!result.success()) {
                sender.sendMessage(Message.raw(result.message()));
                return;
            }

            // Update inventory
            hotbar.setItemStackForSlot((short) inventory.getActiveHotbarSlot(), result.item());
            inventory.markChanged();
            player.sendInventory();

            // Success message
            EnchantmentData newEnchants = enchantmentManager.getEnchantmentsFromItem(result.item());
            StringBuilder enchantList = new StringBuilder();
            for (var entry : newEnchants.getAllEnchantments().entrySet()) {
                if (enchantList.length() > 0) enchantList.append(", ");
                enchantList.append(entry.getKey().getFormattedName(entry.getValue()));
            }

            sender.sendMessage(Message.raw("Enchanted! [" + enchantList + "]"));
            LOGGER.atInfo().log(sender.getDisplayName() + " enchanted " + item.getItemId() + " with " + enchantmentType.getFormattedName(level));

        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to apply enchantment: " + e.getMessage());
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
