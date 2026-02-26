package org.herolias.plugin.util;

import org.herolias.plugin.enchantment.EnchantmentType;

import java.util.Map;

/**
 * Utility for constructing scroll item IDs from enchantment type and level.
 * Shared by EnchantmentSalvageSystem and RemoveEnchantmentInteraction.
 */
public final class ScrollIdHelper {

    private ScrollIdHelper() {
        // Utility class — no instantiation
    }

    /**
     * Some scroll item names don't follow standard underscore-to-title-case conversion.
     * These overrides map the enchantment ID to the exact scroll name fragment.
     */
    private static final Map<String, String> SCROLL_NAME_OVERRIDES = Map.of(
        "fast_swim",        "FastSwim",
        "elemental_heart",  "ElementalHeart",
        "pick_perfect",     "Silktouch"
    );

    /**
     * Builds the scroll item ID for a given enchantment type and level.
     * e.g. SHARPNESS level 1 → "Scroll_Sharpness_I"
     *      LIFE_LEECH level 2 → "Scroll_Life_Leech_II"
     *      FAST_SWIM level 1  → "Scroll_FastSwim_I"
     *
     * @param type  the enchantment type
     * @param level the enchantment level (1-based)
     * @return the scroll item ID string
     */
    public static String getScrollItemId(EnchantmentType type, int level) {
        String id = type.getId();

        // Check for overrides first (irregular scroll names)
        String override = SCROLL_NAME_OVERRIDES.get(id);
        if (override != null) {
            return "Scroll_" + override + "_" + EnchantmentType.toRoman(level);
        }

        // Standard conversion: snake_case → Title_Case
        StringBuilder sb = new StringBuilder("Scroll_");
        boolean capitalizeNext = true;
        for (char c : id.toCharArray()) {
            if (c == '_') {
                sb.append('_');
                capitalizeNext = true;
            } else if (capitalizeNext) {
                sb.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                sb.append(c);
            }
        }
        sb.append('_');
        sb.append(EnchantmentType.toRoman(level));
        return sb.toString();
    }
}
