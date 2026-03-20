package org.herolias.plugin.enchantment;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import com.hypixel.hytale.logger.HytaleLogger;
import org.herolias.plugin.SimpleEnchanting;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight ticker that tracks player hotbar slot changes.
 * Only triggers updates when the active slot actually changes,
 * avoiding heavy per-tick calculations.
 *
 * Also handles:
 * <ul>
 * <li>Detecting newly connected players and triggering initial tooltip
 * setup</li>
 * <li>Updating tooltip translations when the active slot changes (priority item
 * may differ)</li>
 * <li>Cleaning up tooltip data for disconnected players</li>
 * </ul>
 */
public class EnchantmentSlotTracker implements Runnable {

    private final EnchantmentManager enchantmentManager;
    private final EnchantmentEternalShotSystem eternalShotSystem;
    private final Map<UUID, Byte> lastSlotMap = new ConcurrentHashMap<>();
    /** Set of player UUIDs we have already processed for initial tooltip setup. */
    private final Set<UUID> knownPlayers = ConcurrentHashMap.newKeySet();

    public EnchantmentSlotTracker(EnchantmentManager enchantmentManager,
            EnchantmentEternalShotSystem eternalShotSystem) {
        this.enchantmentManager = enchantmentManager;
        this.eternalShotSystem = eternalShotSystem;
    }

    @Override
    public void run() {
        try {
            if (Universe.get() == null)
                return;

            // Collect currently online player UUIDs for disconnect detection
            Set<UUID> onlinePlayers = new HashSet<>();

            for (World world : Universe.get().getWorlds().values()) {
                if (world == null || !world.isAlive() || !world.isTicking())
                    continue;

                // Safely iterate players to track who is currently online
                for (PlayerRef playerRef : world.getPlayerRefs()) {
                    if (playerRef != null && playerRef.isValid()) {
                        onlinePlayers.add(playerRef.getUuid());
                    }
                }

                try {
                    world.execute(() -> {
                        for (PlayerRef playerRef : world.getPlayerRefs()) {
                            checkPlayerSlot(playerRef);
                        }
                    });
                } catch (Exception e) {
                    if (e.getCause() instanceof IllegalThreadStateException
                            || e.toString().contains("IllegalThreadStateException")) {
                        // Ignore thread state exceptions when worlds are shutting down
                    } else {
                        HytaleLogger.getLogger().atSevere()
                                .log("Error queueing EnchantmentSlotTracker task: " + e.getMessage());
                    }
                }
            }

            // Clean up disconnected players
            Set<UUID> disconnected = new HashSet<>(knownPlayers);
            disconnected.removeAll(onlinePlayers);
            for (UUID uuid : disconnected) {
                knownPlayers.remove(uuid);
                lastSlotMap.remove(uuid);
            }
        } catch (Exception e) {
            HytaleLogger.getLogger().atSevere().log("Error in EnchantmentSlotTracker: " + e.getMessage());
        }
    }

    private void checkPlayerSlot(PlayerRef playerRef) {
        if (playerRef == null || !playerRef.isValid()) {
            if (playerRef != null) {
                lastSlotMap.remove(playerRef.getUuid());
            }
            return;
        }

        var ref = playerRef.getReference();
        if (ref == null || !ref.isValid())
            return;

        var store = ref.getStore();
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null)
            return;

        Inventory inventory = player.getInventory();
        if (inventory == null)
            return;

        UUID uuid = playerRef.getUuid();

        // ── Track new players ──
        knownPlayers.add(uuid);

        byte currentSlot = inventory.getActiveHotbarSlot();

        // Also track off-hand state (simple empty check is sufficient for glow logic)
        ItemStack offHandItem = inventory.getUtilityItem();
        boolean hasOffHand = offHandItem != null && !offHandItem.isEmpty();
        // Use bit 7 (128) to store offhand presence combined with slot index (0-8).
        byte combinedState = (byte) (currentSlot | (hasOffHand ? 0x80 : 0x00));

        Byte lastState = lastSlotMap.get(uuid);

        if (lastState == null || lastState != combinedState) {
            // State changed!
            lastSlotMap.put(uuid, combinedState);

            // 1. Update Glow (Held item changed)
            EnchantmentVisualsHelper.updateGlowStats(ref, store, player, enchantmentManager);

            // 2. Show Title (only if slot actually changed, not just offhand)
            // Mask out the offhand bit to check slot index
            byte lastSlotIndex = lastState != null ? (byte) (lastState & 0x7F) : -1;
            if (currentSlot != lastSlotIndex) {
                // Notify Eternal Shot system about slot change with the PREVIOUS item
                if (lastSlotIndex >= 0) {
                    ItemStack previousItem = inventory.getHotbar().getItemStack(lastSlotIndex);
                    eternalShotSystem.onSlotChanged(player, previousItem);
                }
                showEnchantmentTitle(playerRef, player, currentSlot);
                // DynamicTooltipsLib handles tooltip updates via packet interception
            }
        }
    }

    private void showEnchantmentTitle(PlayerRef playerRef, Player player, byte slot) {
        if (!SimpleEnchanting.getInstance().getUserSettingsManager().getShowEnchantmentBanner(playerRef.getUuid())) {
            return;
        }

        ItemStack item = player.getInventory().getHotbar().getItemStack(slot);
        Message displayMessage = enchantmentManager.getEnchantmentDisplayMessage(item, playerRef);

        if (displayMessage != null) {
            EventTitleUtil.hideEventTitleFromPlayer(playerRef, 0.0f);
            EventTitleUtil.showEventTitleToPlayer(
                    playerRef,
                    Message.raw(""),
                    displayMessage.color("GOLD"),
                    false,
                    null,
                    1.5f,
                    0.1f,
                    0.2f);
        } else {
            EventTitleUtil.hideEventTitleFromPlayer(playerRef, 0.1f);
        }
    }

}
