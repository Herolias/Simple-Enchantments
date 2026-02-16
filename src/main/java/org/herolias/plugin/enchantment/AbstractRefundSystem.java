package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.DropItemEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/**
 * Abstract base class for systems that refund items (like Eternal Shot or Soulbound).
 * 
 * Handles the tracking of manual player drops to ensure that manually dropped items
 * are NOT refunded, preventing infinite item duplication exploits.
 */
public abstract class AbstractRefundSystem {

    // Key: Player UUID, Value: Map of (slot -> timestamp)
    protected final Map<UUID, Map<Short, Long>> recentDrops = new ConcurrentHashMap<>();
    
    // How long to consider a drop "recent" (in milliseconds)
    protected static final long DROP_TRACKING_WINDOW_MS = 500;

    /**
     * Event handler for DropItemEvent.PlayerRequest (ECS event).
     * Records when a player manually drops an item so we don't refund it.
     */
    @SuppressWarnings("removal")
    public void onDropItemRequest(@Nonnull DropItemEvent.PlayerRequest event, 
                                   @Nonnull Ref<EntityStore> ref, 
                                   @Nonnull Store<EntityStore> store) {
        // Get the player component
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        
        short slotId = event.getSlotId();
        UUID playerKey = player.getUuid();
        
        // Record this drop with current timestamp
        recentDrops.computeIfAbsent(playerKey, k -> new ConcurrentHashMap<>())
                   .put(slotId, System.currentTimeMillis());
    }

    /**
     * Clean up old drop records for a player.
     */
    @SuppressWarnings("removal")
    protected void cleanupOldDropRecords(Player player) {
        UUID playerKey = player.getUuid();
        Map<Short, Long> playerDrops = recentDrops.get(playerKey);
        if (playerDrops == null) {
            return;
        }
        
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Short, Long>> iterator = playerDrops.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Short, Long> entry = iterator.next();
            if (now - entry.getValue() > DROP_TRACKING_WINDOW_MS) {
                iterator.remove();
            }
        }
        
        // Remove the player's entry if empty
        if (playerDrops.isEmpty()) {
            recentDrops.remove(playerKey);
        }
    }

    /**
     * Check if a slot was recently dropped by this player.
     * Use this before refunding an item.
     */
    @SuppressWarnings("removal")
    protected boolean wasRecentlyDropped(Player player, short slot) {
        UUID playerKey = player.getUuid();
        Map<Short, Long> playerDrops = recentDrops.get(playerKey);
        if (playerDrops == null) {
            return false;
        }
        
        Long dropTime = playerDrops.get(slot);
        if (dropTime == null) {
            return false;
        }
        
        long elapsed = System.currentTimeMillis() - dropTime;
        if (elapsed <= DROP_TRACKING_WINDOW_MS) {
            // This was a recent drop - remove the record and return true
            playerDrops.remove(slot);
            return true;
        }
        
        return false;
    }
}
