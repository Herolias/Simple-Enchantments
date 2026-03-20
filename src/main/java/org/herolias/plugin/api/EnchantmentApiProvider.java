package org.herolias.plugin.api;

import com.hypixel.hytale.logger.HytaleLogger;
import javax.annotation.Nullable;

/**
 * Static provider for the Enchantment API.
 * Access point for other mods.
 */
public class EnchantmentApiProvider {

    private static EnchantmentApi instance;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Gets the Enchantment API instance.
     * 
     * @return The API instance, or null if not yet initialized
     */
    @Nullable
    public static EnchantmentApi get() {
        if (instance == null) {
            LOGGER.atWarning().log("Enchantment API accessed before initialization!");
        }
        return instance;
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
