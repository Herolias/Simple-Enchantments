package org.herolias.plugin.listener;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.config.EnchantingConfig;
import org.herolias.plugin.config.UserSettingsManager;

/**
 * Shows a one-time welcome message to players when they join,
 * informing them about the new tooltip system and general mod usage.
 */
public class WelcomeListener {

    private final SimpleEnchanting plugin;

    public WelcomeListener(SimpleEnchanting plugin) {
        this.plugin = plugin;
    }

    @SuppressWarnings("removal")
    public void onPlayerReady(PlayerReadyEvent event) {
        Player player = event.getPlayer();
        String uuid = player.getUuid().toString();
        
        UserSettingsManager userSettingsManager = plugin.getUserSettingsManager();
        
        // Show the localized greeting message
        if (!userSettingsManager.hasSeenGreeting(player.getUuid())) {
            String clientLangCode = player.getPlayerRef().getLanguage();
            String langCode = userSettingsManager.getLanguage(player.getUuid());
            
            Message greeting = plugin.getLanguageManager().getMessage("chat.greeting", langCode, clientLangCode).color("#AA00AA").bold(true); // Changed to purple hex
            player.sendMessage(greeting);
            
            userSettingsManager.setHasSeenGreeting(player.getUuid(), true);
        }

        // Only show if tooltips are enabled (meaning the lib is present)
        if (!plugin.isTooltipsEnabled()) {
            return;
        }

        EnchantingConfig config = plugin.getConfigManager().getConfig();

        // Check if player has already been notified
        if (config.notifiedPlayers != null && config.notifiedPlayers.contains(uuid)) {
            return;
        }
        
        // Check if welcome message is disabled (e.g. fresh installs)
        if (config.skipWelcomeMessage) {
            return;
        }

        // Show Title
        EventTitleUtil.showEventTitleToPlayer(
            event.getPlayer().getPlayerRef(),
            Message.raw(""), // Title
            Message.raw("Enchantment Tooltips are here!").color("#FFAA00"), // Subtitle (Gold)
            false,
            null,
            1.5f,
            0.1f,
            2.0f
        );

        // Send Chat Message
        String border = "--------------------------------------------------";
        player.sendMessage(Message.raw(border).color("#FFAA00").bold(true));
        player.sendMessage(Message.raw("            Enchantment Tooltips are here! ").color("#FFAA00").bold(true)); // Centered Gold Bold
        player.sendMessage(Message.raw(" They replace the banner by default, but you can ").color("YELLOW"));
        player.sendMessage(Message.raw("                turn it back on in the config.").color("YELLOW"));
        player.sendMessage(Message.raw(border).color("#FFAA00").bold(true));

        // Mark player as notified and save
        if (config.notifiedPlayers == null) {
            config.notifiedPlayers = new java.util.ArrayList<>();
        }
        config.notifiedPlayers.add(uuid);
        plugin.getConfigManager().saveConfig();
        
        // Log it just for debug
        // plugin.getLogger().atInfo().log("Showed welcome message to " + player.getName());
    }
}
