package org.herolias.plugin.crafting;

import com.hypixel.hytale.builtin.crafting.window.BenchWindow;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.windows.Window;
import com.hypixel.hytale.server.core.entity.entities.player.windows.WindowManager;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * A workaround system for a vanilla Hytale bug where BenchWindows
 * fail to rescan nearby crafting chests after a workbench upgrade.
 * 
 * This system monitors all players' open windows. If a player has a BenchWindow
 * open and its tier level changes from the previous tick, we explicitly
 * call invalidateExtraResources() to force a chest rescan.
 */

public class WorkbenchRefreshSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    
    // We only care about Players since only players have WindowManagers
    private static final Query<EntityStore> QUERY = Query.and(
            Player.getComponentType()
    );

    // Track the last known tier level for each player's open bench window
    private final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap playerLastTierLevel = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();

    public WorkbenchRefreshSystem() {
        // -1 indicates no bench window is currently open / tracked
        this.playerLastTierLevel.defaultReturnValue(-1);
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    @Override
    public void tick(float dt, int index, 
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk, 
                     @Nonnull Store<EntityStore> store, 
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        
        Entity entity = EntityUtils.getEntity(index, archetypeChunk);
        if (!(entity instanceof Player player)) return;

        com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId netIdComp = store.getComponent(archetypeChunk.getReferenceTo(index), com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId.getComponentType());
        if (netIdComp == null) return;
        int networkId = netIdComp.getId();
        
        WindowManager windowManager = player.getWindowManager();
        List<Window> openWindows = windowManager.getWindows();
        
        BenchWindow activeBenchWindow = null;
        
        // Find if the player has an active bench window
        for (Window window : openWindows) {
            if (window instanceof BenchWindow benchWindow) {
                activeBenchWindow = benchWindow;
                break;
            }
        }
        
        if (activeBenchWindow == null) {
            // Player closed their bench window (or never had one), clear our cache for them
            if (playerLastTierLevel.containsKey(networkId)) {
                playerLastTierLevel.remove(networkId);
            }
            return;
        }
        
        // Check if the bench is currently upgrading (progress > 0.0 but < 1.0)
        // During the upgrade (2-4 seconds), the vanilla CraftingManager cancels crafting
        // and clears out the upgrading items. We must not force an invalidation during this
        // time, or else the chest items disappear mid-upgrade. Wait for it to finish!
        if (activeBenchWindow.getData().has("tierUpgradeProgress")) {
            float upgradeProgress = activeBenchWindow.getData().get("tierUpgradeProgress").getAsFloat();
            if (upgradeProgress > 0.0f && upgradeProgress < 1.0f) {
                return; // Bench is actively upgrading, do not interfere yet
            }
        }
        
        // Player has an open bench window. Get its current tier level from its data json
        // Using windowData because BenchWindow has no public getTierLevel() method but exposes it in data
        int currentTierLevel = 1;
        if (activeBenchWindow.getData().has("tierLevel")) {
            currentTierLevel = activeBenchWindow.getData().get("tierLevel").getAsInt();
        }
        
        int lastTierLevel = playerLastTierLevel.get(networkId);
        
        // If this is the first time seeing this window, just record the tier
        if (lastTierLevel == -1) {
            playerLastTierLevel.put(networkId, currentTierLevel);
            return;
        }
        
        // If the tier level changed (upgrade completed), invalidate extra resources!
        if (currentTierLevel != lastTierLevel) {
            activeBenchWindow.invalidateExtraResources();
            playerLastTierLevel.put(networkId, currentTierLevel);
        }
    }
}
