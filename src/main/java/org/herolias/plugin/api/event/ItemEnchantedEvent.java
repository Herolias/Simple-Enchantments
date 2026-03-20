package org.herolias.plugin.api.event;

import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.herolias.plugin.enchantment.EnchantmentType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Event fired when an item is successfully enchanted.
 */
public class ItemEnchantedEvent implements IEvent<Void> {

    @Nullable
    private final PlayerRef playerRef;
    @Nonnull
    private final ItemStack item;
    @Nonnull
    private final EnchantmentType enchantment;
    private final int level;

    public ItemEnchantedEvent(@Nullable PlayerRef playerRef, @Nonnull ItemStack item,
            @Nonnull EnchantmentType enchantment, int level) {
        this.playerRef = playerRef;
        this.item = item;
        this.enchantment = enchantment;
        this.level = level;
    }

    /**
     * @return The player who enchanted the item, or null if it was enchanted via
     *         command/console without a specific player context.
     */
    @Nullable
    public PlayerRef getPlayerRef() {
        return playerRef;
    }

    /**
     * @return The item that was enchanted.
     */
    @Nonnull
    public ItemStack getItem() {
        return item;
    }

    /**
     * @return The enchantment that was applied.
     */
    @Nonnull
    public EnchantmentType getEnchantment() {
        return enchantment;
    }

    /**
     * @return The level of the enchantment applied.
     */
    public int getLevel() {
        return level;
    }
}
