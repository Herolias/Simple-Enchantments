package org.herolias.plugin.api;

import javax.annotation.Nonnull;

/**
 * Defines a single configurable multiplier for an enchantment.
 * <p>
 * Each enchantment can have one or more multiplier definitions. The primary multiplier
 * (set via {@link EnchantmentBuilder#multiplierPerLevel(double)}) is automatically
 * registered as the first definition. Additional multipliers can be added via
 * {@link EnchantmentBuilder#addMultiplier(String, double, String)}.
 * <p>
 * Each definition specifies:
 * <ul>
 *   <li>{@code key} — the config map key (e.g. "sharpness", "burn:duration")</li>
 *   <li>{@code defaultValue} — the default value used for fresh configs</li>
 *   <li>{@code labelKey} — the translation key for the config UI label</li>
 * </ul>
 */
public record MultiplierDefinition(
        @Nonnull String key,
        double defaultValue,
        @Nonnull String labelKey
) {
    public MultiplierDefinition {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("MultiplierDefinition key must not be null or empty");
        }
        if (labelKey == null || labelKey.isEmpty()) {
            throw new IllegalArgumentException("MultiplierDefinition labelKey must not be null or empty");
        }
    }
}
