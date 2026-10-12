package org.herolias.plugin.enchantment;

import com.hypixel.hytale.event.IEventDispatcher;
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
     * <p>
     * The event object is only allocated when at least one listener is
     * registered: {@code EventBus.dispatchFor} returns a {@code NO_OP}
     * dispatcher whose {@link IEventDispatcher#hasListener()} is false when
     * nobody listens, so per-hit callers pay nothing in that case.
     *
     * @param playerRef The player who triggered the enchantment (nullable for
     *                  non-player entities)
     * @param item      The item bearing the enchantment
     * @param type      The enchantment that was activated
     * @param level     The level of the activated enchantment
     */
    public static void fireActivated(@Nullable PlayerRef playerRef,
            @Nonnull ItemStack item,
            @Nonnull EnchantmentType type,
            int level) {
        IEventDispatcher<EnchantmentActivatedEvent, EnchantmentActivatedEvent> dispatcher = HytaleServer.get()
                .getEventBus().dispatchFor(EnchantmentActivatedEvent.class);
        if (!dispatcher.hasListener()) {
            return;
        }
        dispatcher.dispatch(new EnchantmentActivatedEvent(playerRef, item, type, level));
    }
}
