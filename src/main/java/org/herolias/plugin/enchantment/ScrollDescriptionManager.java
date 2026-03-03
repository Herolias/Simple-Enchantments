package org.herolias.plugin.enchantment;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.assets.UpdateTranslations;
import com.hypixel.hytale.protocol.UpdateType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import org.herolias.plugin.SimpleEnchanting;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages dynamic descriptions for Enchantment Scrolls by sending per-player translation updates.
 * This ensures that descriptions are correct and localized even in the Crafting UI and Creative Menu.
 */
public class ScrollDescriptionManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Sends the current translation overrides to a specific player, localized to their client language.
     */
    public static void sendUpdatePacket(Player player) {
        if (player == null || player.getWorld() == null || player.getReference() == null) return;
        com.hypixel.hytale.server.core.universe.PlayerRef ref = player.getWorld().getEntityStore().getStore().getComponent(player.getReference(), com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
        sendUpdatePacket(ref);
    }
    
    /**
     * Sends the current translation overrides to a specific player via ref, localized to their client language.
     */
    public static void sendUpdatePacket(PlayerRef playerRef) {
        if (playerRef == null || !playerRef.isValid()) return;
        
        try {
            // Determine player locale (client language)
            String clientLocale = playerRef.getLanguage();
            if (clientLocale == null || clientLocale.isEmpty()) {
                clientLocale = "en-US";
            }
            
            // Get user custom language from settings
            String langCode = "default";
            try {
                org.herolias.plugin.SimpleEnchanting plugin = org.herolias.plugin.SimpleEnchanting.getInstance();
                if (plugin != null && plugin.getUserSettingsManager() != null) {
                    langCode = plugin.getUserSettingsManager().getLanguage(playerRef.getUuid());
                }
            } catch (Exception ignored) {}

            // Generate translations for this player's locale definition
            Map<String, String> translations = new HashMap<>();

            org.herolias.plugin.lang.LanguageManager lm = null;
            org.herolias.plugin.config.EnchantingConfig config = null;
            try {
                org.herolias.plugin.SimpleEnchanting plugin = org.herolias.plugin.SimpleEnchanting.getInstance();
                if (plugin != null) {
                    lm = plugin.getLanguageManager();
                    if (plugin.getConfigManager() != null) {
                        config = plugin.getConfigManager().getConfig();
                    }
                }
            } catch (Exception ignored) {}

            for (EnchantmentType type : EnchantmentType.values()) {
                String baseName = getScrollBaseName(type);
                boolean isDisabled = config != null && config.disabledEnchantments.getOrDefault(type.getId(), false);

                for (int level = 1; level <= type.getMaxLevel(); level++) {
                    // Key format: items.Scroll_Name_Level.description
                    String scrollId = baseName + "_" + EnchantmentType.toRoman(level);
                    String descKey = "server.items." + scrollId + ".description";
                    String nameKey = "server.items." + scrollId + ".name";
                    
                    // Resolve description using custom language and client fallback
                    String dynamicDescription = type.getBonusDescription(level, langCode, clientLocale);

                    // Append addon attribution for non-built-in enchantments
                    if (dynamicDescription != null && !dynamicDescription.isEmpty()
                            && type.getOwnerModId() != null) {
                        dynamicDescription += "\n<color is=\"#AAAAAA\">Added by " + type.getOwnerModId() + "</color>";
                    }

                    if (isDisabled) {
                        dynamicDescription = "<color is=\"#FF5555\">Disabled</color>\n\n" + (dynamicDescription == null ? "" : dynamicDescription);
                        
                        String baseTranslatedName = scrollId.replace("_", " ");
                        if (lm != null) {
                            String maybeTrans = lm.getRawMessage("items." + scrollId + ".name", langCode, clientLocale);
                            if (maybeTrans != null && !maybeTrans.startsWith("items.")) {
                                baseTranslatedName = maybeTrans;
                            }
                        }
                        translations.put(nameKey, baseTranslatedName + " (Disabled)");
                    } else {
                        // Crucial: overwrite any existing "(Disabled)" tag if re-enabled
                        String baseTranslatedName = scrollId.replace("_", " ");
                        if (lm != null) {
                            String maybeTrans = lm.getRawMessage("items." + scrollId + ".name", langCode, clientLocale);
                            if (maybeTrans != null && !maybeTrans.startsWith("items.")) {
                                baseTranslatedName = maybeTrans;
                            }
                        }
                        translations.put(nameKey, baseTranslatedName);
                    }
                    
                    if (dynamicDescription != null && !dynamicDescription.isEmpty()) {
                        translations.put(descKey, dynamicDescription);
                    }
                }
            }
            
            if (!translations.isEmpty()) {
                // Create packet with correct constructor: (UpdateType, Map<String, String>)
                UpdateTranslations packet = new UpdateTranslations(UpdateType.AddOrUpdate, translations);
                
                if (playerRef.getPacketHandler() != null) {
                    ((com.hypixel.hytale.server.core.receiver.IPacketReceiver) playerRef.getPacketHandler()).writeNoCache(packet);
                }
            }

        } catch (Exception e) {
             // Log error but don't crash
             LOGGER.atWarning().log("Failed to send scroll description update: " + e.getMessage());
        }
    }

    /**
     * Broadcasts the current translation overrides to all online players.
     * Call this after config reload.
     */
    public static void broadcastUpdatePacket() {
        if (Universe.get() != null) {
            for (PlayerRef playerRef : Universe.get().getPlayers()) {
                sendUpdatePacket(playerRef);
            }
        }
    }

    private static String getScrollBaseName(EnchantmentType type) {
        return type.getScrollBaseName();
    }
    
    // Kept for backward compatibility if called from elsewhere, though deprecated behavior
    public static void reloadTranslations() {
       // No-op or trigger broadcast
       broadcastUpdatePacket(); 
    }
}
