package org.herolias.plugin.enchantment;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import javax.annotation.Nullable;

/**
 * Result of an enchantment application attempt.
 */
public record EnchantmentApplicationResult(boolean success, String message, @Nullable ItemStack item,
        @Nullable EnchantmentType type, int level) {

    public static EnchantmentApplicationResult success(ItemStack item, String message) {
        return new EnchantmentApplicationResult(true, message, item, null, 0);
    }

    public static EnchantmentApplicationResult success(ItemStack item, String message, EnchantmentType type, int level) {
        return new EnchantmentApplicationResult(true, message, item, type, level);
    }

    public static EnchantmentApplicationResult failure(String message) {
        return new EnchantmentApplicationResult(false, message, null, null, 0);
    }
}
