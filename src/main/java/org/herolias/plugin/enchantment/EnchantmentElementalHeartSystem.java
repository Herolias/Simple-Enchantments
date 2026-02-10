package org.herolias.plugin.enchantment;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.entity.LivingEntityInventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import com.hypixel.hytale.server.core.inventory.transaction.SlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import org.herolias.plugin.util.ProcessingGuard;

/**
 * System handling the Elemental Heart enchantment.
 * 
 * Elemental Heart prevents the consumption of Essence items when using a Staff,
 * similar to how Eternal Shot works for arrows.
 */
public class EnchantmentElementalHeartSystem extends AbstractRefundSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final EnchantmentManager enchantmentManager;
    private final ProcessingGuard guard = new ProcessingGuard();
    
    public EnchantmentElementalHeartSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentElementalHeartSystem initialized");
    }

    public void onInventoryChange(LivingEntityInventoryChangeEvent event) {
        if (guard.isProcessing()) return;

        LivingEntity entity = event.getEntity();
        if (!(entity instanceof Player player)) return;

        Transaction transaction = event.getTransaction();
        ItemContainer container = event.getItemContainer();

        cleanupOldDropRecords(player);

        if (transaction instanceof ItemStackTransaction itemStackTransaction) {
            for (ItemStackSlotTransaction slotTx : itemStackTransaction.getSlotTransactions()) {
                processSlotTransaction(player, container, slotTx);
            }
        } else if (transaction instanceof SlotTransaction slotTransaction) {
            processSlotTransaction(player, container, slotTransaction);
        }
    }

    private void processSlotTransaction(Player player, ItemContainer container, SlotTransaction slotTransaction) {
        if (!slotTransaction.succeeded()) return;

        ItemStack slotBefore = slotTransaction.getSlotBefore();
        ItemStack slotAfter = slotTransaction.getSlotAfter();

        if (slotBefore == null || slotBefore.isEmpty()) return;
        if (!isEssenceItem(slotBefore.getItemId())) return;
        if (wasRecentlyDropped(player, slotTransaction.getSlot())) return;

        int beforeQty = slotBefore.getQuantity();
        int afterQty = (slotAfter == null || slotAfter.isEmpty()) ? 0 : slotAfter.getQuantity();
        if (beforeQty <= afterQty) return;

        Inventory inventory = player.getInventory();
        if (inventory == null) return;

        ItemStack weapon = inventory.getItemInHand();
        if (weapon == null || weapon.isEmpty()) return;

        ItemCategory category = enchantmentManager.categorizeItem(weapon);
        if (category != ItemCategory.STAFF && category != ItemCategory.STAFF_ESSENCE) return;

        int level = enchantmentManager.getEnchantmentLevel(weapon, EnchantmentType.ELEMENTAL_HEART);
        if (level <= 0) return;
        
        double chance = level * EnchantmentType.ELEMENTAL_HEART.getEffectMultiplier();
        if (Math.random() > chance) return;

        guard.runGuarded(() -> 
            container.replaceItemStackInSlot(slotTransaction.getSlot(), slotAfter, slotBefore)
        );
    }

    private boolean isEssenceItem(String itemId) {
        return itemId.toLowerCase().contains("essence");
    }
}
