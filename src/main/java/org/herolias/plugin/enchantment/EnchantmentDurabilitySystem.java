package org.herolias.plugin.enchantment;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.entity.LivingEntityInventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.transaction.SlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import org.herolias.plugin.util.ProcessingGuard;

import java.util.Objects;

/**
 * Handles the Durability enchantment logic by listening to inventory changes.
 * 
 * Since the core game logic applies fixed durability loss based on Item config,
 * we intercept the inventory change event, detect if durability was lost,
 * and if the item has the Durability enchantment, we "refund" a portion of the loss.
 */
public class EnchantmentDurabilitySystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    
    private final EnchantmentManager enchantmentManager;
    private final ProcessingGuard guard = new ProcessingGuard();

    public EnchantmentDurabilitySystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentDurabilitySystem initialized");
    }

    public void onInventoryChange(LivingEntityInventoryChangeEvent event) {
        if (guard.isProcessing()) return;

        Transaction transaction = event.getTransaction();
        if (!(transaction instanceof SlotTransaction slotTransaction)) return;
        if (!slotTransaction.succeeded()) return;

        ItemStack before = slotTransaction.getSlotBefore();
        ItemStack after = slotTransaction.getSlotAfter();

        if (before == null || after == null || before.isEmpty() || after.isEmpty()) return;
        if (!Objects.equals(before.getItemId(), after.getItemId())) return;

        // Handle Sturdy enchantment (prevents max durability loss from repair kits)
        double beforeMax = before.getMaxDurability();
        double afterMax = after.getMaxDurability();
        if (afterMax + 0.001 < beforeMax && enchantmentManager.hasEnchantment(before, EnchantmentType.STURDY)) {
            guard.runGuarded(() -> {
                ItemStack correctedStack = after.withRestoredDurability(beforeMax);
                event.getItemContainer().replaceItemStackInSlot(slotTransaction.getSlot(), after, correctedStack);
            });
            return;
        }
        
        // Check if durability decreased
        if (after.getDurability() >= before.getDurability()) return;

        double loss = before.getDurability() - after.getDurability();
        if (loss < 0.001) return;

        if (!enchantmentManager.hasEnchantment(before, EnchantmentType.DURABILITY)) return;

        double multiplier = enchantmentManager.calculateDurabilityMultiplier(before);
        if (multiplier >= 1.0) return;

        double refund = loss * (1.0 - multiplier);
        if (refund > 0) {
            guard.runGuarded(() -> {
                ItemStack correctedStack = after.withIncreasedDurability(refund);
                event.getItemContainer().replaceItemStackInSlot(slotTransaction.getSlot(), after, correctedStack);
            });
        }
    }
}
