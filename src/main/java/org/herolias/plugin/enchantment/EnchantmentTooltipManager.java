package org.herolias.plugin.enchantment;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.entity.LivingEntityInventoryChangeEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Manages per-player item tooltip translations to display enchantments in item descriptions.
 *
 * <h2>Architecture (v2 — Virtual Item IDs)</h2>
 * The heavy lifting is now performed by two new components:
 * <ul>
 *   <li>{@link VirtualItemRegistry} — generates deterministic virtual item IDs for each
 *       unique (baseItem + enchantmentCombo) pair and creates {@code ItemBase} clones
 *       with unique description translation keys.</li>
 *   <li>{@link InventoryPacketAdapter} — intercepts outbound {@code UpdatePlayerInventory}
 *       packets and transparently swaps enchanted item IDs with virtual IDs.  It also
 *       sends the virtual item definitions and their description translations to the
 *       client <em>before</em> the inventory packet.</li>
 * </ul>
 * <p>
 * This class retains its role as the <b>lifecycle coordinator</b>: it listens for
 * inventory change events and player connect/disconnect events, and delegates to
 * the virtual item system as needed.
 *
 * <h2>Why the old conflict-resolution logic is gone</h2>
 * Previously, all items of the same type shared one description translation key,
 * so showing different enchantments on two identical-type items was impossible
 * (a "conflict"). The virtual-ID system eliminates this entirely: every enchanted
 * item instance gets its own unique description key, so no conflicts can occur.
 */
public class EnchantmentTooltipManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final EnchantmentManager enchantmentManager;
    private final VirtualItemRegistry virtualItemRegistry;
    private final InventoryPacketAdapter packetAdapter;

    public EnchantmentTooltipManager(@Nonnull EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        this.virtualItemRegistry = new VirtualItemRegistry(enchantmentManager);
        this.packetAdapter = new InventoryPacketAdapter(virtualItemRegistry, enchantmentManager);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Initialization
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Registers the outbound packet adapter. Call once during plugin setup.
     * <p>
     * After this call, the adapter will automatically intercept every
     * {@code UpdatePlayerInventory} packet and swap enchanted item IDs to
     * virtual IDs. No additional event listeners are needed for the core
     * tooltip functionality.
     */
    public void registerPacketAdapter() {
        packetAdapter.register();
        LOGGER.atInfo().log("Virtual item tooltip system initialized");
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Event hooks (kept for lifecycle management)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Inventory change event handler. Registered globally for all living entities;
     * filters to players only.
     * <p>
     * With the virtual-ID system, the actual tooltip updates are handled by the
     * {@link InventoryPacketAdapter} at the packet level. This handler is kept
     * for any supplementary logic that might be needed in the future.
     */
    public void onInventoryChange(LivingEntityInventoryChangeEvent event) {
        // The InventoryPacketAdapter handles all tooltip logic at the packet level.
        // This handler is intentionally minimal.
    }

    /**
     * Called from {@link EnchantmentSlotTracker} when the active hotbar slot changes.
     * <p>
     * With the virtual-ID system, each enchanted item has its own unique description
     * key, so active-slot changes no longer affect which enchantments are shown.
     * This method is now a no-op.
     */
    public void onActiveSlotChanged(@Nonnull PlayerRef playerRef, @Nonnull Player player) {
        // No-op: virtual IDs eliminate the need for focus-based conflict resolution.
    }

    /**
     * Called when a player first connects. Performs initial setup.
     * <p>
     * No explicit action is needed here because the packet adapter intercepts
     * the initial inventory sync packet and handles virtual ID injection automatically.
     */
    public void onPlayerJoin(@Nonnull PlayerRef playerRef, @Nonnull Player player) {
        // No-op: the packet adapter handles initial inventory sync automatically.
    }

    /**
     * Cleans up per-player tracking data when a player disconnects.
     */
    public void onPlayerLeave(@Nonnull UUID playerUuid) {
        virtualItemRegistry.onPlayerLeave(playerUuid);
        packetAdapter.onPlayerLeave(playerUuid);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Accessors
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Gets the virtual item registry used by this manager.
     */
    @Nonnull
    public VirtualItemRegistry getVirtualItemRegistry() {
        return virtualItemRegistry;
    }

    /**
     * Gets the packet adapter used by this manager.
     */
    @Nonnull
    public InventoryPacketAdapter getPacketAdapter() {
        return packetAdapter;
    }

    /**
     * Sends virtual item definitions and translations to a player.
     * <p>
     * This is used by the {@link org.herolias.plugin.api.EnchantmentApi} to enable
     * third-party mods to display enchantment tooltips in their CustomUIs.
     *
     * @param playerRef    The player to send the data to
     * @param virtualItems Map of virtual item ID to ItemBase definitions
     * @param translations Map of translation key to translated text
     */
    public void sendVirtualItemData(@Nonnull PlayerRef playerRef,
                                    @Nonnull java.util.Map<String, com.hypixel.hytale.protocol.ItemBase> virtualItems,
                                    @Nonnull java.util.Map<String, String> translations) {
        packetAdapter.sendAuxiliaryPackets(playerRef, virtualItems, translations);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Clears all internal caches. Safe to call on plugin reload.
     */
    public void clearCache() {
        virtualItemRegistry.clearCache();
    }
}
