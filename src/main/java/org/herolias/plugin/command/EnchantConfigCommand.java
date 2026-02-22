package org.herolias.plugin.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.ui.EnchantConfigPage;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;

/**
 * Command to open the in-game enchantment configuration UI.
 * Requires operator permissions.
 * 
 * Usage: /enchantconfig
 */
public class EnchantConfigCommand extends AbstractAsyncCommand {

    private static final Message MESSAGE_NOT_A_PLAYER = Message.raw("Only players can use this command.");
    private static final Message MESSAGE_PLAYER_NOT_IN_WORLD = Message.translation("server.commands.errors.playerNotInWorld");

    private final SimpleEnchanting plugin;

    public EnchantConfigCommand(SimpleEnchanting plugin) {
        super("enchantconfig", "server.commands.enchantconfig.desc");
        this.plugin = plugin;
        this.setPermissionGroup(GameMode.Creative); // Requires OP/Creative permissions
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext context) {
        if (!context.isPlayer()) {
            context.sendMessage(MESSAGE_NOT_A_PLAYER);
            return CompletableFuture.completedFuture(null);
        }

        Ref<EntityStore> playerRef = context.senderAsPlayerRef();
        if (playerRef == null || !playerRef.isValid()) {
            context.sendMessage(MESSAGE_PLAYER_NOT_IN_WORLD);
            return CompletableFuture.completedFuture(null);
        }

        Store<EntityStore> store = playerRef.getStore();
        World world = store.getExternalData().getWorld();

        return CompletableFuture.runAsync(() -> {
            Player playerComponent = store.getComponent(playerRef, Player.getComponentType());
            PlayerRef playerRefComponent = store.getComponent(playerRef, PlayerRef.getComponentType());
            
            if (playerComponent != null && playerRefComponent != null) {
                playerComponent.getPageManager().openCustomPage(
                    playerRef, 
                    store, 
                    new EnchantConfigPage(playerRefComponent, plugin.getConfigManager(), plugin.getUserSettingsManager(), plugin.getLanguageManager())
                );
            }
        }, world);
    }
}
