package org.herolias.plugin.enchantment;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.herolias.plugin.api.event.EnchantmentActivatedEvent;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Utility class for dispatching enchantment-related events.
 * Centralizes the 3-line event dispatch boilerplate that was previously
 * duplicated across 15+ ECS systems.
 */
public final class EnchantmentEventHelper {

    private EnchantmentEventHelper() {
        // Utility class — no instantiation
    }

    /**
     * Fires an {@link EnchantmentActivatedEvent} to notify listeners that an
     * enchantment effect was triggered.
     *
     * @param playerRef The player who triggered the enchantment (nullable for non-player entities)
     * @param item      The item bearing the enchantment
     * @param type      The enchantment that was activated
     * @param level     The level of the activated enchantment
     */
    public static void fireActivated(@Nullable PlayerRef playerRef,
                                      @Nonnull ItemStack item,
                                      @Nonnull EnchantmentType type,
                                      int level) {
        EnchantmentActivatedEvent event = new EnchantmentActivatedEvent(playerRef, item, type, level);
        HytaleServer.get().getEventBus()
            .dispatchFor(EnchantmentActivatedEvent.class).dispatch(event);
    }
}
