package org.herolias.plugin.enchantment;

import com.hypixel.hytale.logger.HytaleLogger;
import org.herolias.tooltips.api.DynamicTooltipsApi;
import org.herolias.tooltips.api.DynamicTooltipsApiProvider;

import javax.annotation.Nonnull;

/**
 * Bridge class that isolates <b>all</b> compile-time references to
 * DynamicTooltipsLib into a single place.
 * <p>
 * <b>This class must never be loaded unless DynamicTooltipsLib is confirmed
 * present on the classpath.</b> Callers must guard every reference with a
 * {@code Class.forName} check or an {@code isTooltipsEnabled()} flag.
 * <p>
 * This pattern ensures that the rest of Simple-Enchantments loads and runs
 * normally even when DynamicTooltipsLib is not installed — tooltips are a
 * purely visual, optional feature.
 */
public final class TooltipBridge {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private TooltipBridge() {}

    /**
     * Registers the {@link EnchantmentTooltipProvider} with DynamicTooltipsLib.
     *
     * @return {@code true} if registration succeeded
     */
    public static boolean register(@Nonnull EnchantmentManager enchantmentManager) {
        DynamicTooltipsApi api = DynamicTooltipsApiProvider.get();
        if (api != null) {
            api.registerProvider(new EnchantmentTooltipProvider(enchantmentManager));
            LOGGER.atInfo().log("Registered EnchantmentTooltipProvider with DynamicTooltipsLib");
            return true;
        } else {
            LOGGER.atWarning().log("DynamicTooltipsLib API not yet initialized — tooltips will not display");
            return false;
        }
    }

    /**
     * Invalidates all tooltip caches and immediately refreshes every online
     * player's tooltips. Call after config changes.
     */
    public static void refreshAllPlayers() {
        DynamicTooltipsApi api = DynamicTooltipsApiProvider.get();
        if (api != null) {
            api.refreshAllPlayers();
            LOGGER.atInfo().log("Refreshed all player tooltips after config change");
        }
    }
}
