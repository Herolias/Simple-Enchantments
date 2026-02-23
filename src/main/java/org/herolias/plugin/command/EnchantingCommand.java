package org.herolias.plugin.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.ui.EnchantingPage;
import com.hypixel.hytale.protocol.GameMode;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;

/**
 * Command to open the user enchanting walkthrough and settings UI.
 * Accessible to all players.
 * 
 * Usage: /enchanting
 */
public class EnchantingCommand extends AbstractAsyncCommand {

    private static final Message MESSAGE_NOT_A_PLAYER = Message.raw("Only players can use this command.");

    private final SimpleEnchanting plugin;

    public EnchantingCommand(SimpleEnchanting plugin) {
        super("enchanting", "server.commands.enchanting.desc");
        this.plugin = plugin;
        this.setPermissionGroup(GameMode.Adventure); // Lower permissions to allow everyone
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
                    new EnchantingPage(playerRefComponent, plugin)
                );
            }
        }, world);
    }
}
