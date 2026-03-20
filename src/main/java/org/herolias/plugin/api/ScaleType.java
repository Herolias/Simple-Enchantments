package org.herolias.plugin.api;

import java.util.function.IntToDoubleFunction;

/**
 * Predefined scaling curves for enchantment multipliers.
 * <p>
 * Each scale type defines how the effect multiplier changes with enchantment
 * level.
 * The returned function takes a level and a base multiplier-per-level, and
 * returns
 * the total scaled value for that level.
 * <p>
 * Example usage:
 * 
 * <pre>{@code
 * api.registerEnchantment("my_mod:lightning", "Lightning Strike")
 *         .multiplierPerLevel(0.15)
 *         .scale(ScaleType.DIMINISHING)
 *         .build();
 * }</pre>
 *
 * @see EnchantmentBuilder#scale(ScaleType)
 * @see EnchantmentBuilder#scale(double)
 * @see EnchantmentBuilder#scale(IntToDoubleFunction)
 */
public enum ScaleType {

    /**
     * Linear scaling: {@code level * multiplier}.
     * Equal gains per level. This is the default.
     */
    LINEAR,

    /**
     * Quadratic scaling: {@code level² * multiplier}.
     * Accelerating returns — higher levels are disproportionately stronger.
     */
    QUADRATIC,

    /**
     * Diminishing scaling: {@code √level * multiplier}.
     * Front-loaded gains — first levels matter most, later levels taper off.
     */
    DIMINISHING,

    /**
     * Exponential scaling: {@code (2^level - 1) * multiplier}.
     * Extreme late-game power spikes.
     */
    EXPONENTIAL,

    /**
     * Logarithmic scaling: {@code ln(level + 1) * multiplier}.
     * Soft cap feel — gains slow down significantly at higher levels.
     */
    LOGARITHMIC;

    /**
     * Converts this scale type into a function that computes the total
     * scaled multiplier given a level and base multiplier-per-level.
     *
     * @param multiplierPerLevel the base multiplier per level
     * @return a function mapping level → total scaled value
     */
    public IntToDoubleFunction toFunction(double multiplierPerLevel) {
        return switch (this) {
            case LINEAR -> level -> level * multiplierPerLevel;
            case QUADRATIC -> level -> (long) level * level * multiplierPerLevel;
            case DIMINISHING -> level -> Math.sqrt(level) * multiplierPerLevel;
            case EXPONENTIAL -> level -> (Math.pow(2, level) - 1) * multiplierPerLevel;
            case LOGARITHMIC -> level -> Math.log(level + 1) * multiplierPerLevel;
        };
    }
}
