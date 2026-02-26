package org.herolias.plugin.listener;

import com.hypixel.hytale.logger.HytaleLogger;
import org.herolias.plugin.api.event.EnchantmentActivatedEvent;
import org.herolias.plugin.api.event.ItemEnchantedEvent;

public class EventLoggerListener {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public void onEnchantmentActivated(EnchantmentActivatedEvent event) {
        String playerName = event.getPlayerRef() != null ? event.getPlayerRef().getUuid().toString() : "Unknown/Non-Player";
        String itemName = event.getItem() != null ? event.getItem().getItemId() : "Unknown Item";
        String enchantmentName = event.getEnchantment() != null ? event.getEnchantment().name() : "Unknown Enchantment";
        int level = event.getLevel();

        LOGGER.atInfo().log("[DEBUG] EnchantmentActivatedEvent fired! Player: " + playerName +
                ", Item: " + itemName +
                ", Enchantment: " + enchantmentName +
                ", Level: " + level);
    }

    public void onItemEnchanted(ItemEnchantedEvent event) {
        String playerName = event.getPlayerRef() != null ? event.getPlayerRef().getUuid().toString() : "Unknown/Console";
        String itemName = event.getItem() != null ? event.getItem().getItemId() : "Unknown Item";
        String enchantmentName = event.getEnchantment() != null ? event.getEnchantment().name() : "Unknown Enchantment";
        int level = event.getLevel();

        LOGGER.atInfo().log("[DEBUG] ItemEnchantedEvent fired! Player: " + playerName +
                ", Item: " + itemName +
                ", Enchantment: " + enchantmentName +
                ", Level: " + level);
    }
}
