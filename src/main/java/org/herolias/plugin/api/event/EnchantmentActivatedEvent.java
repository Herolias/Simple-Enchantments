package org.herolias.plugin.api.event;

import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.herolias.plugin.enchantment.EnchantmentType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Event fired when an enchantment successfully activates its effect.
 * (e.g. Fortune drops extra items, Sharpness deals extra damage, etc).
 */
public class EnchantmentActivatedEvent implements IEvent<Void> {

    @Nullable
    private final PlayerRef playerRef;
    @Nonnull
    private final ItemStack item;
    @Nonnull
    private final EnchantmentType enchantment;
    private final int level;

    public EnchantmentActivatedEvent(@Nullable PlayerRef playerRef, @Nonnull ItemStack item,
            @Nonnull EnchantmentType enchantment, int level) {
        this.playerRef = playerRef;
        this.item = item;
        this.enchantment = enchantment;
        this.level = level;
    }

    /**
     * @return The player whose enchantment activated, or null if it was triggered
     *         by a non-player entity.
     */
    @Nullable
    public PlayerRef getPlayerRef() {
        return playerRef;
    }

    /**
     * @return The item that triggered the enchantment effect.
     */
    @Nonnull
    public ItemStack getItem() {
        return item;
    }

    /**
     * @return The enchantment that activated.
     */
    @Nonnull
    public EnchantmentType getEnchantment() {
        return enchantment;
    }

    /**
     * @return The current level of the activated enchantment.
     */
    public int getLevel() {
        return level;
    }
}
