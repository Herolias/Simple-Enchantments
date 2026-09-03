package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Player entity lifecycle hooks for the enchantment systems.
 * <ul>
 * <li>On add (join, world change) the held/armor glow stats are initialised,
 * which the removed slot poller used to do on its first pass.</li>
 * <li>On remove every registered per-player cleanup runs so no system keeps
 * state for an entity that no longer exists. {@link #cleanupPlayer(UUID)} is
 * also the single entry point for the {@code PlayerDisconnectEvent} hook.</li>
 * </ul>
 * All cleanups must be idempotent; they run on both disconnect and removal.
 */
public class EnchantmentPlayerLifecycleSystem extends RefSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final EnchantmentManager enchantmentManager;
    private final List<Consumer<UUID>> cleanups;
    private final Query<EntityStore> query = Query.and(Player.getComponentType(), UUIDComponent.getComponentType());

    @SafeVarargs
    public EnchantmentPlayerLifecycleSystem(@Nonnull EnchantmentManager enchantmentManager,
            @Nonnull Consumer<UUID>... cleanups) {
        this.enchantmentManager = enchantmentManager;
        this.cleanups = List.of(cleanups);
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> ref,
            @Nonnull AddReason reason,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            EnchantmentVisualsHelper.updateGlowStats(ref, commandBuffer, enchantmentManager);
        } catch (RuntimeException e) {
            LOGGER.atWarning().atMostEvery(30, TimeUnit.SECONDS).withCause(e)
                    .log("Failed to initialise enchantment glow for a joining player");
        }
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> ref,
            @Nonnull RemoveReason reason,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        UUIDComponent uuidComponent = commandBuffer.getComponent(ref, UUIDComponent.getComponentType());
        if (uuidComponent != null) {
            cleanupPlayer(uuidComponent.getUuid());
        }
    }

    /** Runs every registered per-player cleanup. Safe to call more than once. */
    public void cleanupPlayer(@Nonnull UUID playerUuid) {
        for (Consumer<UUID> cleanup : cleanups) {
            try {
                cleanup.accept(playerUuid);
            } catch (RuntimeException e) {
                LOGGER.atWarning().withCause(e).log("Per-player enchantment cleanup failed for %s", playerUuid);
            }
        }
    }
}
