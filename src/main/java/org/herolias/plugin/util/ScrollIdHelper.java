package org.herolias.plugin.util;

import org.herolias.plugin.enchantment.EnchantmentType;

import java.util.Map;

/**
 * Utility for constructing scroll item IDs from enchantment type and level,
 * and reversing scroll IDs back to enchantment types.
 * Shared by EnchantmentSalvageSystem, RemoveEnchantmentInteraction, and merge logic.
 */
public final class ScrollIdHelper {

    private ScrollIdHelper() {
        // Utility class — no instantiation
    }

    /**
     * Builds the scroll item ID for a given enchantment type and level.
     * Delegates to {@link EnchantmentType#getScrollBaseName()} to correctly
     * resolve custom scroll names and namespace stripping for addons.
     *
     * @param type  the enchantment type
     * @param level the enchantment level (1-based)
     * @return the scroll item ID string
     */
    public static String getScrollItemId(EnchantmentType type, int level) {
        return type.getScrollBaseName() + "_" + EnchantmentType.toRoman(level);
    }

    /**
     * Result of reverse-mapping a scroll item ID to enchantment type and level.
     */
    public static record ScrollEnchantment(EnchantmentType type, int level) {}

    /**
     * Reverse-maps a scroll item ID to its enchantment type and level.
     * Iterates all registered enchantments and checks if the item ID matches
     * any scroll pattern (baseName + "_" + roman numeral).
     *
     * @param itemId the item ID string (e.g. "Scroll_Sharpness_II")
     * @return the enchantment type and level, or null if no match
     */
    public static ScrollEnchantment getEnchantmentFromScrollId(String itemId) {
        if (itemId == null || !itemId.startsWith("Scroll_")) return null;

        for (EnchantmentType type : EnchantmentType.values()) {
            String baseName = type.getScrollBaseName();
            if (itemId.startsWith(baseName + "_")) {
                String suffix = itemId.substring(baseName.length() + 1);
                int level = romanToInt(suffix);
                if (level > 0) {
                    return new ScrollEnchantment(type, level);
                }
            }
        }
        return null;
    }

    /**
     * Converts a Roman numeral string to an integer.
     * Supports I through X (levels 1-10).
     */
    private static int romanToInt(String roman) {
        return switch (roman) {
            case "I" -> 1;
            case "II" -> 2;
            case "III" -> 3;
            case "IV" -> 4;
            case "V" -> 5;
            case "VI" -> 6;
            case "VII" -> 7;
            case "VIII" -> 8;
            case "IX" -> 9;
            case "X" -> 10;
            default -> 0;
        };
    }
}
