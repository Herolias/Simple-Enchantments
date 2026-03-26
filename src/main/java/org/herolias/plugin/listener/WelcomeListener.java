package org.herolias.plugin.listener;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.config.EnchantingConfig;
import org.herolias.plugin.config.UserSettingsManager;

/**
 * Shows a one-time welcome message to players when they join.
 */
public class WelcomeListener {

    private final SimpleEnchanting plugin;

    public WelcomeListener(SimpleEnchanting plugin) {
        this.plugin = plugin;
    }

    public void onPlayerReady(PlayerReadyEvent event) {
        Player player = event.getPlayer();
        if (player.getWorld() == null || player.getReference() == null)
            return;

        player.getWorld().execute(() -> {
            com.hypixel.hytale.server.core.universe.PlayerRef playerRef = player.getWorld().getEntityStore().getStore()
                    .getComponent(player.getReference(),
                            com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
            if (playerRef == null)
                return;

            UserSettingsManager userSettingsManager = plugin.getUserSettingsManager();
            EnchantingConfig config = plugin.getConfigManager().getConfig();

            // Show the localized greeting message
            if (!userSettingsManager.hasSeenGreeting(playerRef.getUuid())) {
                if (config.showWelcomeMessage) {
                    String clientLangCode = playerRef.getLanguage();
                    String langCode = userSettingsManager.getLanguage(playerRef.getUuid());

                    Message greeting = plugin.getLanguageManager().getMessage("chat.greeting", langCode, clientLangCode)
                            .color("#AA00AA").bold(true);
                    player.sendMessage(greeting);
                }
                userSettingsManager.setHasSeenGreeting(playerRef.getUuid(), true);
            }
        });
    }
}
