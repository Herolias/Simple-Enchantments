package org.herolias.plugin.enchantment;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import javax.annotation.Nullable;

/**
 * Result of an enchantment application attempt.
 */
public record EnchantmentApplicationResult(boolean success, String message, @Nullable ItemStack item) {

    public static EnchantmentApplicationResult success(ItemStack item, String message) {
        return new EnchantmentApplicationResult(true, message, item);
    }

    public static EnchantmentApplicationResult failure(String message) {
        return new EnchantmentApplicationResult(false, message, null);
    }
}
