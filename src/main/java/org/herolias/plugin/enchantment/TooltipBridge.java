package org.herolias.plugin.enchantment;

import com.hypixel.hytale.logger.HytaleLogger;
import org.herolias.tooltips.api.DynamicTooltipsApi;
import org.herolias.tooltips.api.DynamicTooltipsApiProvider;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Bridge class that isolates <b>all</b> compile-time references to
 * DynamicTooltipsLib into a single place.
 * <p>
 * DynamicTooltipsLib is a <b>hard dependency</b> of Simple-Enchantments,
 * so this class is always safe to load. It provides a clean separation
 * of tooltip registration and refresh logic.
 */
public final class TooltipBridge {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static volatile Field tooltipRegistryField;
    private static volatile Method composeMethod;
    private static volatile boolean warnedAboutCompositionLookup;

    private TooltipBridge() {
    }

    /**
     * Registers the {@link EnchantmentTooltipProvider} with DynamicTooltipsLib.
     *
     * @return {@code true} if registration succeeded
     */
    public static boolean register(@Nonnull EnchantmentManager enchantmentManager) {
        DynamicTooltipsApi api = DynamicTooltipsApiProvider.get();
        if (api != null) {
            api.registerProvider(new EnchantmentTooltipProvider(enchantmentManager));

            api.setLanguageResolver(playerUuid -> {
                try {
                    org.herolias.plugin.SimpleEnchanting plugin = org.herolias.plugin.SimpleEnchanting.getInstance();
                    if (plugin != null) {
                        String lang = plugin.getUserSettingsManager().getLanguage(playerUuid);
                        if (lang != null && !"default".equalsIgnoreCase(lang)) {
                            return lang;
                        }
                    }
                } catch (Exception ignored) {
                }
                return null;
            });

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

    /**
     * Invalidates caches and immediately refreshes tooltips for a specific player.
     * Call after a player changes their custom language.
     */
    public static void refreshPlayer(@Nonnull java.util.UUID playerUuid) {
        DynamicTooltipsApi api = DynamicTooltipsApiProvider.get();
        if (api != null) {
            api.refreshPlayer(playerUuid);
        }
    }

    /**
     * Checks whether DynamicTooltipsLib has any registered provider that would
     * virtualize this exact item state in a CustomUI.
     * <p>
     * The public API does not currently expose composition lookup, so this uses a
     * small reflective bridge to avoid hard-coding Simple Enchantments metadata as
     * the only CustomUI-safe metadata. If DynamicTooltipsLib exposes this directly
     * later, this is the only place that needs to change.
     */
    public static boolean hasDynamicTooltip(
            @Nonnull String itemId,
            @Nullable String metadata,
            @Nullable String language) {
        DynamicTooltipsApi api = DynamicTooltipsApiProvider.get();
        if (api == null) {
            return false;
        }
        try {
            Field registryField = tooltipRegistryField;
            if (registryField == null) {
                registryField = api.getClass().getDeclaredField("registry");
                registryField.setAccessible(true);
                tooltipRegistryField = registryField;
            }

            Object registry = registryField.get(api);
            if (registry == null) {
                return false;
            }

            Method method = composeMethod;
            if (method == null) {
                method = registry.getClass().getMethod("compose", String.class, String.class, String.class);
                composeMethod = method;
            }

            return method.invoke(registry, itemId, metadata, language) != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warnedAboutCompositionLookup) {
                warnedAboutCompositionLookup = true;
                LOGGER.atWarning().log("Could not inspect DynamicTooltipsLib providers for CustomUI metadata: "
                        + e.getMessage());
            }
            return false;
        }
    }
}
