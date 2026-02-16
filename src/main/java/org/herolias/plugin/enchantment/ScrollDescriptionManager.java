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
    @SuppressWarnings("removal")
    public static void sendUpdatePacket(Player player) {
        if (player == null) return;
        
        // Fix: Use Universe to get PlayerRef from UUID since Player doesn't expose getPlayerRef()
        // verify if Universe is available
        if (Universe.get() == null) return;

        PlayerRef ref = Universe.get().getPlayer(player.getUuid());
        sendUpdatePacket(ref);
    }
    
    /**
     * Sends the current translation overrides to a specific player via ref, localized to their client language.
     */
    public static void sendUpdatePacket(PlayerRef playerRef) {
        if (playerRef == null || !playerRef.isValid()) return;
        
        try {
            // Determine player locale
            String locale = playerRef.getLanguage();
            if (locale == null || locale.isEmpty()) {
                locale = "en-US";
            }

            // Generate translations for this player's locale
            Map<String, String> translations = new HashMap<>();

            for (EnchantmentType type : EnchantmentType.values()) {
                String baseName = getScrollBaseName(type);
                for (int level = 1; level <= type.getMaxLevel(); level++) {
                    // Key format: items.Scroll_Name_Level.description
                    String scrollId = baseName + "_" + EnchantmentType.toRoman(level);
                    String translationKey = "server.items." + scrollId + ".description";
                    
                    // Resolve description using player's locale
                    String dynamicDescription = type.getBonusDescription(level, locale);
                    
                    if (dynamicDescription != null && !dynamicDescription.isEmpty()) {
                        translations.put(translationKey, dynamicDescription);
                    }
                }
            }
            
            if (!translations.isEmpty()) {
                // Create packet with correct constructor: (UpdateType, Map<String, String>)
                UpdateTranslations packet = new UpdateTranslations(UpdateType.AddOrUpdate, translations);
                
                if (playerRef.getPacketHandler() != null) {
                    playerRef.getPacketHandler().writeNoCache(packet);
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
        // Special cases for scrolls that don't follow the standard naming convention
        switch (type) {
            case PICK_PERFECT:
                return "Scroll_Silktouch";
            case ELEMENTAL_HEART:
                return "Scroll_ElementalHeart";
            case FAST_SWIM:
                return "Scroll_FastSwim";
            default:
                // Standard behavior: Scroll_PascalCase_...
                String[] parts = type.getId().split("_");
                StringBuilder sb = new StringBuilder("Scroll");
                for (String part : parts) {
                    sb.append("_");
                    sb.append(Character.toUpperCase(part.charAt(0)));
                    sb.append(part.substring(1));
                }
                return sb.toString();
        }
    }
    
    // Kept for backward compatibility if called from elsewhere, though deprecated behavior
    public static void reloadTranslations() {
       // No-op or trigger broadcast
       broadcastUpdatePacket(); 
    }
}
