package org.herolias.plugin.api;

import com.hypixel.hytale.logger.HytaleLogger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Static provider for the Enchantment API.
 * <p>
 * The API becomes available once Simple Enchantments' {@code setup()} has run.
 * Addons must therefore declare a dependency on Simple Enchantments in their
 * plugin manifest so that their own {@code setup()} is executed afterwards;
 * without the dependency the load order is undefined and {@link #get()} may
 * return {@code null}.
 */
public class EnchantmentApiProvider {

    private static volatile EnchantmentApi instance;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Gets the Enchantment API instance.
     * <p>
     * Available after {@code SimpleEnchanting.setup()}; optional integrations that
     * do not declare a hard dependency must guard against {@code null}. Hard
     * dependants can use {@link #getOrThrow()} instead.
     *
     * @return The API instance, or null if Simple Enchantments has not been set up yet
     */
    @Nullable
    public static EnchantmentApi get() {
        EnchantmentApi api = instance;
        if (api == null) {
            LOGGER.atWarning().log(
                    "Enchantment API accessed before SimpleEnchanting.setup() ran. Declare a dependency on Simple Enchantments so your plugin loads after it.");
        }
        return api;
    }

    /**
     * Gets the Enchantment API instance or fails loudly.
     *
     * @throws IllegalStateException if Simple Enchantments has not been set up yet
     */
    @Nonnull
    public static EnchantmentApi getOrThrow() {
        EnchantmentApi api = instance;
        if (api == null) {
            throw new IllegalStateException(
                    "Simple Enchantments API is not available yet: SimpleEnchanting.setup() has not run. "
                            + "Declare a dependency on Simple Enchantments in your plugin manifest so it loads first.");
        }
        return api;
    }

    /** Whether the API has been registered (i.e. Simple Enchantments finished setup()). */
    public static boolean isAvailable() {
        return instance != null;
    }

    /**
     * Registers the API instance.
     * Should only be called by the hosting plugin.
     */
    public static void register(EnchantmentApi api) {
        if (instance != null) {
            LOGGER.atWarning().log("Enchantment API already registered! Overwriting...");
        }
        instance = api;
        LOGGER.atInfo().log("Enchantment API registered.");
    }
}
