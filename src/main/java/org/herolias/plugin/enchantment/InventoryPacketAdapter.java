package org.herolias.plugin.enchantment;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ItemBase;
import com.hypixel.hytale.protocol.ItemWithAllMetadata;
import com.hypixel.hytale.protocol.InventorySection;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.UpdateType;
import com.hypixel.hytale.protocol.packets.assets.UpdateItems;
import com.hypixel.hytale.protocol.packets.assets.UpdateTranslations;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChains;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.protocol.packets.inventory.UpdatePlayerInventory;
import com.hypixel.hytale.protocol.packets.player.MouseInteraction;
import com.hypixel.hytale.protocol.packets.window.OpenWindow;
import com.hypixel.hytale.protocol.packets.window.UpdateWindow;
import com.hypixel.hytale.protocol.packets.interface_.CustomPage;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bidirectional packet adapter that provides per-item enchantment tooltips
 * using a <b>hybrid</b> strategy: virtual item IDs for display-only sections
 * and description-translation overrides for interaction sections.
 *
 * <h2>Problem</h2>
 * Hytale's tooltip system resolves item descriptions per item <em>type</em>.
 * Two items of the same type always share the same description key, making it
 * impossible to show different enchantments on two items of the same base type.
 * <p>
 * Swapping item IDs to virtual ones works well for <b>display-only</b> sections
 * (storage, armor, backpack, chests), but the client's interaction system
 * (attack, mine, slot switching) does not properly initialise for items whose
 * IDs were dynamically added via {@code UpdateItems}.  This causes the active
 * slot to "snap back" and blocks interactions.
 *
 * <h2>Solution: hybrid approach</h2>
 * <table>
 *   <tr><th>Section</th><th>Strategy</th><th>Why</th></tr>
 *   <tr><td>Hotbar, Utility, Tools</td>
 *       <td>Keep real item IDs; override the item type's description translation
 *           with enchantment text via {@code UpdateTranslations}</td>
 *       <td>These sections participate in {@link SyncInteractionChains}. The
 *           client needs real item IDs for interactions to work.</td></tr>
 *   <tr><td>Armor, Storage, Backpack, BuilderMaterial</td>
 *       <td>Virtual item IDs (clone + swap)</td>
 *       <td>Items in these sections are never referenced in interaction packets.
 *           Virtual IDs give unique per-instance tooltips.</td></tr>
 *   <tr><td>Containers (chests, etc.)</td>
 *       <td>Virtual item IDs (clone + swap)</td>
 *       <td>Container items are not part of the player's interaction state.</td></tr>
 * </table>
 *
 * <h2>Translation-override details (hotbar / utility / tools)</h2>
 * For each enchanted item <em>type</em> found in the hotbar/utility/tools sections,
 * we override the <b>real</b> description translation key with the enchantment text.
 * This means all instances of the same item type in these sections share the same
 * enriched description (the first enchanted instance found wins).  When an item
 * type is no longer enchanted in these sections, the original description is restored.
 * <p>
 * <b>Known limitation:</b> if the player has two items of the same type with
 * <em>different</em> enchantments in the hotbar (e.g. two Adamantite Pickaxes),
 * both will show the description of whichever is processed first.  This is an
 * inherent limitation of the shared translation-key approach.
 *
 * <h2>Inbound safety net</h2>
 * As an extra safety measure, the inbound filter translates any virtual item IDs
 * in {@link MouseInteraction} and {@link SyncInteractionChains} packets back to
 * real IDs.  This protects against edge cases where a virtual ID leaks into an
 * interaction section (e.g. item moved from storage to hotbar between packet
 * sends).
 */
public class InventoryPacketAdapter {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final VirtualItemRegistry virtualItemRegistry;
    private final EnchantmentManager enchantmentManager;

    /** Registered outbound filter handle (needed for deregistration). */
    private PacketFilter outboundFilter;
    /** Registered inbound filter handle (needed for deregistration). */
    private PacketFilter inboundFilter;

    /**
     * Re-entrancy guard.  Set to {@code true} while the outbound filter is
     * processing a packet and sending auxiliary packets via {@code writeNoCache()}.
     * Those auxiliary packets pass through the outbound filter again; this flag
     * tells us to let them through unmodified.
     */
    private final ThreadLocal<Boolean> isProcessing = ThreadLocal.withInitial(() -> false);

    /**
     * Per-player tracking of the active hotbar slot index.
     * Updated from outbound {@code SyncInteractionChains} packets.
     * Used during {@code UpdatePlayerInventory} processing to decide which
     * hotbar slot keeps the real item ID (the active one) and which slots
     * get virtual IDs (the non-active enchanted ones).
     */
    private final ConcurrentHashMap<UUID, Integer> playerActiveHotbarSlot = new ConcurrentHashMap<>();

    /**
     * Per-player cached hotbar data.  Cached during {@code UpdatePlayerInventory}
     * processing and used to build corrective hotbar packets when the active
     * slot changes (detected via {@code SyncInteractionChains}).
     */
    private final ConcurrentHashMap<UUID, CachedHotbarData> hotbarCache = new ConcurrentHashMap<>();

    /**
     * Snapshot of the hotbar section at the time of the last
     * {@code UpdatePlayerInventory} processing.  Stores enough information
     * to reconstruct the hotbar with a different active slot without needing
     * to access the server's {@code Inventory} object.
     */
    private static class CachedHotbarData {
        short capacity;
        /** Slot → original (unmodified, deep-cloned) {@link ItemWithAllMetadata}. */
        Map<Integer, ItemWithAllMetadata> originalItems;
        /** Slot → parsed enchantment data (only for enchanted slots). */
        Map<Integer, EnchantmentData> enchantedSlots;
        /** Slot → pre-computed virtual item ID (only for enchanted slots). */
        Map<Integer, String> virtualIds;
    }

    public InventoryPacketAdapter(
            @Nonnull VirtualItemRegistry virtualItemRegistry,
            @Nonnull EnchantmentManager enchantmentManager) {
        this.virtualItemRegistry = virtualItemRegistry;
        this.enchantmentManager = enchantmentManager;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Registration / deregistration
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Registers both the outbound and inbound packet filters.
     * Call once during plugin setup.
     */
    public void register() {
        outboundFilter = PacketAdapters.registerOutbound((PlayerPacketFilter) this::onOutboundPacket);
        inboundFilter = PacketAdapters.registerInbound((PlayerPacketFilter) this::onInboundPacket);
        LOGGER.atInfo().log("InventoryPacketAdapter registered (outbound + inbound filters)");
    }

    /**
     * Deregisters both filters. Call on plugin shutdown.
     */
    public void deregister() {
        if (outboundFilter != null) {
            try {
                PacketAdapters.deregisterOutbound(outboundFilter);
            } catch (Exception e) {
                LOGGER.atWarning().log("Failed to deregister outbound filter: " + e.getMessage());
            }
            outboundFilter = null;
        }
        if (inboundFilter != null) {
            try {
                PacketAdapters.deregisterInbound(inboundFilter);
            } catch (Exception e) {
                LOGGER.atWarning().log("Failed to deregister inbound filter: " + e.getMessage());
            }
            inboundFilter = null;
        }
    }

    /**
     * Cleans up per-player state when a player disconnects.
     */
    public void onPlayerLeave(@Nonnull UUID playerUuid) {
        playerActiveHotbarSlot.remove(playerUuid);
        hotbarCache.remove(playerUuid);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  INBOUND filter  (client → server)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Intercepts inbound packets to translate virtual item IDs back to real IDs.
     * <p>
     * This is a <b>safety net</b>.  Under normal operation, the hotbar/utility/tools
     * sections keep real item IDs, so the client should already send real IDs.
     * However, if an item is moved from a display-only section (where it has a
     * virtual ID) to the hotbar between inventory syncs, the client might send
     * a virtual ID.  This filter catches that case.
     *
     * @return always {@code false} — we never block inbound packets
     */
    private boolean onInboundPacket(@Nonnull PlayerRef playerRef, @Nonnull Packet packet) {
        try {
            if (packet instanceof MouseInteraction mousePacket) {
                translateMouseInteraction(mousePacket);
            } else if (packet instanceof SyncInteractionChains syncPacket) {
                translateInboundSyncInteractionChains(syncPacket);
            }
        } catch (Exception e) {
            LOGGER.atWarning().log("Error in inbound packet adapter for "
                    + playerRef.getUuid() + ": " + e.getMessage());
        }
        return false; // Never block inbound packets
    }

    /**
     * If the client sent a virtual item ID in {@code itemInHandId}, replace it
     * with the real (base) item ID so the server can process the interaction.
     */
    private void translateMouseInteraction(@Nonnull MouseInteraction packet) {
        if (packet.itemInHandId != null && VirtualItemRegistry.isVirtualId(packet.itemInHandId)) {
            String baseId = VirtualItemRegistry.getBaseItemId(packet.itemInHandId);
            if (baseId != null) {
                packet.itemInHandId = baseId;
            }
        }
    }

    /**
     * Translates any virtual item IDs in an inbound {@link SyncInteractionChains}
     * packet back to real IDs.  Safety net for edge cases.
     */
    private void translateInboundSyncInteractionChains(@Nonnull SyncInteractionChains syncPacket) {
        for (SyncInteractionChain chain : syncPacket.updates) {
            translateInboundChainItemIds(chain);
        }
    }

    /**
     * Recursively translates virtual → real item IDs in a single inbound
     * {@link SyncInteractionChain} and its forks.
     */
    private void translateInboundChainItemIds(@Nonnull SyncInteractionChain chain) {
        if (chain.itemInHandId != null && VirtualItemRegistry.isVirtualId(chain.itemInHandId)) {
            String baseId = VirtualItemRegistry.getBaseItemId(chain.itemInHandId);
            if (baseId != null) chain.itemInHandId = baseId;
        }
        if (chain.utilityItemId != null && VirtualItemRegistry.isVirtualId(chain.utilityItemId)) {
            String baseId = VirtualItemRegistry.getBaseItemId(chain.utilityItemId);
            if (baseId != null) chain.utilityItemId = baseId;
        }
        if (chain.toolsItemId != null && VirtualItemRegistry.isVirtualId(chain.toolsItemId)) {
            String baseId = VirtualItemRegistry.getBaseItemId(chain.toolsItemId);
            if (baseId != null) chain.toolsItemId = baseId;
        }
        if (chain.newForks != null) {
            for (SyncInteractionChain fork : chain.newForks) {
                if (fork != null) translateInboundChainItemIds(fork);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  OUTBOUND filter  (server → client)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Intercepts outbound packets to swap item IDs and override translations.
     * <p>
     * Only inventory-related packets are processed.  Interaction packets
     * ({@code SyncInteractionChains}, {@code PlayInteractionFor}) are left
     * unmodified because the hotbar/utility/tools sections keep real item IDs.
     *
     * @return always {@code false} — we never block outbound packets.
     */
    private boolean onOutboundPacket(@Nonnull PlayerRef playerRef, @Nonnull Packet packet) {
        // Re-entrancy guard: if we're currently processing and sending auxiliary
        // packets via writeNoCache(), let those packets through unmodified.
        if (isProcessing.get()) {
            return false;
        }

        isProcessing.set(true);
        try {
            if (packet instanceof UpdatePlayerInventory invPacket) {
                processPlayerInventory(playerRef, invPacket);
            } else if (packet instanceof OpenWindow openWindow) {
                processWindowInventory(playerRef, openWindow.inventory);
            } else if (packet instanceof UpdateWindow updateWindow) {
                processWindowInventory(playerRef, updateWindow.inventory);
            } else if (packet instanceof SyncInteractionChains syncPacket) {
                detectHotbarSlotChange(playerRef, syncPacket);
            } else if (packet instanceof CustomPage customPage) {
                processCustomPage(playerRef, customPage);
            }
        } catch (Exception e) {
            LOGGER.atWarning().log("Error in outbound packet adapter for "
                    + playerRef.getUuid() + ": " + e.getMessage());
        } finally {
            isProcessing.set(false);
        }

        return false; // Never block — we modified the packet in-place
    }

    // ───────────────────────────────────────────────────────────────────────
    //  Player inventory processing
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Processes an outbound {@link UpdatePlayerInventory} packet using the
     * <b>hybrid strategy</b>:
     * <ul>
     *   <li><b>Hotbar:</b> enchanted items in <b>non-active</b> slots get virtual
     *       IDs (unique per-instance tooltips).  The <b>active</b> slot keeps its
     *       real ID (interactions work) and gets a description-translation override.
     *       The original hotbar data is cached for building corrective packets when
     *       the active slot changes.</li>
     *   <li><b>Utility, Tools:</b> keep real item IDs; enchanted item types get a
     *       description-translation override (same limitation as before — same-type
     *       items share the active item's description).</li>
     *   <li><b>Display-only sections</b> (armor, storage, backpack, builderMaterial):
     *       enchanted items get virtual IDs (clone + swap) for unique per-instance
     *       tooltips.</li>
     * </ul>
     */
    private void processPlayerInventory(@Nonnull PlayerRef playerRef,
                                        @Nonnull UpdatePlayerInventory packet) {
        UUID playerUuid = playerRef.getUuid();
        String language = playerRef.getLanguage();

        Map<String, ItemBase> newVirtualItems = new LinkedHashMap<>();
        Map<String, String> translations = new LinkedHashMap<>();

        // ── Hotbar: virtual IDs for non-active slots, real ID for active slot ──
        int activeSlot = playerActiveHotbarSlot.getOrDefault(playerUuid, 0);
        Set<String> overriddenTypes = new HashSet<>();
        processHotbarSection(playerUuid, activeSlot, packet.hotbar, language,
                newVirtualItems, translations, overriddenTypes);

        // ── Utility / Tools: translation overrides only (no virtual IDs) ──
        collectInteractiveTranslations(packet.utility, language, translations, overriddenTypes);
        collectInteractiveTranslations(packet.tools, language, translations, overriddenTypes);

        // Restore descriptions for item types that were overridden before but aren't now
        Set<String> previousOverrides = virtualItemRegistry.getAndUpdateHotbarOverrides(
                playerUuid, overriddenTypes);
        if (previousOverrides != null) {
            for (String prevType : previousOverrides) {
                if (!overriddenTypes.contains(prevType)) {
                    String descKey = virtualItemRegistry.getItemDescriptionKey(prevType);
                    String originalDesc = virtualItemRegistry.getOriginalDescription(prevType, language);
                    translations.put(descKey, originalDesc);
                }
            }
        }

        // ── Display-only sections: virtual item IDs ──
        processSection(playerUuid, "armor", packet.armor, language, newVirtualItems, translations);
        processSection(playerUuid, "storage", packet.storage, language, newVirtualItems, translations);
        processSection(playerUuid, "backpack", packet.backpack, language, newVirtualItems, translations);
        processSection(playerUuid, "builderMaterial", packet.builderMaterial, language, newVirtualItems, translations);

        sendAuxiliaryPackets(playerRef, newVirtualItems, translations);
    }

    // ───────────────────────────────────────────────────────────────────────
    //  Window (chest/container) inventory processing
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Processes the inventory section of an {@link OpenWindow} or {@link UpdateWindow}
     * packet.  Same logic as player inventory, but without slot tracking (container
     * slots don't participate in interaction sync).
     */
    private void processWindowInventory(@Nonnull PlayerRef playerRef,
                                        @Nullable InventorySection section) {
        if (section == null) return;

        UUID playerUuid = playerRef.getUuid();
        String language = playerRef.getLanguage();

        Map<String, ItemBase> newVirtualItems = new LinkedHashMap<>();
        Map<String, String> translations = new LinkedHashMap<>();

        // null sectionName = don't track slots (container items)
        processSection(playerUuid, null, section, language, newVirtualItems, translations);

        sendAuxiliaryPackets(playerRef, newVirtualItems, translations);
    }

    // ───────────────────────────────────────────────────────────────────────
    //  CustomUI (CustomPage) processing
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Processes an outbound {@link CustomPage} packet to swap real item IDs with
     * virtual IDs in UI commands.  This allows enchantment tooltips to display
     * correctly in CustomUI pages (e.g. /market, enchant scroll selection).
     * <p>
     * The method scans {@link CustomUICommand#data} for item ID references and
     * replaces them with virtual IDs if the item is enchanted in the player's
     * inventory.
     */
    private void processCustomPage(@Nonnull PlayerRef playerRef,
                                   @Nonnull CustomPage customPage) {
        if (customPage.commands == null || customPage.commands.length == 0) {
            return;
        }

        UUID playerUuid = playerRef.getUuid();
        String language = playerRef.getLanguage();

        Map<String, ItemBase> newVirtualItems = new LinkedHashMap<>();
        Map<String, String> translations = new LinkedHashMap<>();

        for (CustomUICommand command : customPage.commands) {
            if (command.data == null || command.data.isEmpty()) {
                continue;
            }

            // The UICommandBuilder wraps values in a BsonDocument like: {"0":"value"}
            // For item IDs, this appears as: {"0":"Tool_Pickaxe_Adamantite"}
            // We need to parse this and check if it's an item ID that should be swapped
            String modifiedData = processCustomUICommandData(
                    playerUuid, language, command.data, newVirtualItems, translations);
            if (modifiedData != null) {
                command.data = modifiedData;
            }
        }

        // Send virtual item definitions and translations before the CustomPage packet
        sendAuxiliaryPackets(playerRef, newVirtualItems, translations);
    }

    /**
     * Parses and processes the data field of a {@link CustomUICommand} to find
     * and replace item IDs with virtual IDs.
     * <p>
     * Handles two formats:
     * <ul>
     *   <li>Simple string: {@code {"0":"Tool_Pickaxe"}}</li>
     *   <li>ItemGridSlot array: {@code {"0":[{"ItemStack":{"ItemId":"Tool_Pickaxe",...},...}]}}</li>
     * </ul>
     *
     * @param playerUuid      the player's UUID
     * @param language        the player's language for translations
     * @param data            the original command data (JSON string)
     * @param newVirtualItems accumulator for virtual item definitions to send
     * @param translations    accumulator for translations to send
     * @return the modified data string, or {@code null} if no changes were made
     */
    @Nullable
    private String processCustomUICommandData(
            @Nonnull UUID playerUuid,
            @Nullable String language,
            @Nonnull String data,
            @Nonnull Map<String, ItemBase> newVirtualItems,
            @Nonnull Map<String, String> translations) {

        try {
            // Parse the JSON wrapper
            BsonDocument doc = BsonDocument.parse(data);
            BsonValue value = doc.get("0");

            if (value == null) {
                return null;
            }

            boolean modified = false;

            // Case 1: Simple string value like {"0":"Tool_Pickaxe"}
            if (value.isString()) {
                String potentialItemId = value.asString().getValue();

                // Skip if already a virtual ID
                if (!VirtualItemRegistry.isVirtualId(potentialItemId)) {
                    String virtualId = findVirtualIdForItem(playerUuid, potentialItemId, language,
                            newVirtualItems, translations);
                    if (virtualId != null) {
                        doc.put("0", new org.bson.BsonString(virtualId));
                        modified = true;
                    }
                }
            }
            // Case 2: Array of ItemGridSlot like {"0":[{"ItemStack":{"ItemId":"..."}}]}
            else if (value.isArray()) {
                org.bson.BsonArray array = value.asArray();
                for (int i = 0; i < array.size(); i++) {
                    BsonValue element = array.get(i);
                    if (element.isDocument()) {
                        if (processItemGridSlotDocument(playerUuid, language,
                                element.asDocument(), newVirtualItems, translations)) {
                            modified = true;
                        }
                    }
                }
            }

            return modified ? doc.toJson() : null;

        } catch (Exception e) {
            // Failed to parse or process — leave data unchanged
            LOGGER.atFine().log("Could not process CustomUICommand data: " + e.getMessage());
        }

        return null;
    }

    /**
     * Processes an ItemGridSlot document to find and replace ItemStack.ItemId
     * with a virtual ID if the item is enchanted.
     *
     * @return true if the document was modified
     */
    private boolean processItemGridSlotDocument(
            @Nonnull UUID playerUuid,
            @Nullable String language,
            @Nonnull BsonDocument slotDoc,
            @Nonnull Map<String, ItemBase> newVirtualItems,
            @Nonnull Map<String, String> translations) {

        BsonValue itemStackValue = slotDoc.get("ItemStack");
        if (itemStackValue == null || !itemStackValue.isDocument()) {
            return false;
        }

        BsonDocument itemStackDoc = itemStackValue.asDocument();
        BsonValue itemIdValue = itemStackDoc.get("ItemId");
        if (itemIdValue == null || !itemIdValue.isString()) {
            return false;
        }

        String itemId = itemIdValue.asString().getValue();

        // Skip if already virtual
        if (VirtualItemRegistry.isVirtualId(itemId)) {
            return false;
        }

        String virtualId = findVirtualIdForItem(playerUuid, itemId, language,
                newVirtualItems, translations);

        if (virtualId != null) {
            itemStackDoc.put("ItemId", new org.bson.BsonString(virtualId));
            return true;
        }

        return false;
    }


    /**
     * Looks up whether the player has an enchanted item with the given base ID
     * in their tracked slots, and if so, returns the virtual ID and ensures
     * the virtual item definition and translation are queued for sending.
     *
     * @return the virtual ID if found and enchanted, or {@code null} otherwise
     */
    @Nullable
    private String findVirtualIdForItem(
            @Nonnull UUID playerUuid,
            @Nonnull String itemId,
            @Nullable String language,
            @Nonnull Map<String, ItemBase> newVirtualItems,
            @Nonnull Map<String, String> translations) {

        // First, check if we already have a virtual ID mapped for this base item
        String virtualId = virtualItemRegistry.findVirtualIdForBaseItem(playerUuid, itemId);

        if (virtualId != null) {
            // Ensure the virtual item definition is available
            ItemBase virtualBase = virtualItemRegistry.getOrCreateVirtualItemBase(itemId, virtualId);
            if (virtualBase != null) {
                newVirtualItems.put(virtualId, virtualBase);

                // Build the description translation for this virtual item
                String descKey = VirtualItemRegistry.getVirtualDescriptionKey(virtualId);
                if (!translations.containsKey(descKey)) {
                    // We need to reconstruct the enchantment data from the virtual ID
                    // The virtual ID contains a hash of the enchantments, but we need the actual data
                    // We can get this by looking up the tracked slot data
                    EnchantmentData enchData = findEnchantmentDataForVirtualId(playerUuid, virtualId);
                    if (enchData != null) {
                        String originalDesc = virtualItemRegistry.getOriginalDescription(itemId, language);
                        String enchantedDesc = virtualItemRegistry.buildEnchantedDescription(originalDesc, enchData);
                        translations.put(descKey, enchantedDesc);
                    }
                }
            }
            return virtualId;
        }

        return null;
    }

    /**
     * Finds the enchantment data associated with a virtual ID by searching
     * the cached hotbar data for the matching enchantment hash.
     */
    @Nullable
    private EnchantmentData findEnchantmentDataForVirtualId(
            @Nonnull UUID playerUuid,
            @Nonnull String virtualId) {
        // Check if we have cached hotbar data for this player
        CachedHotbarData cache = hotbarCache.get(playerUuid);
        if (cache != null && cache.virtualIds != null) {
            for (Map.Entry<Integer, String> entry : cache.virtualIds.entrySet()) {
                if (virtualId.equals(entry.getValue())) {
                    return cache.enchantedSlots.get(entry.getKey());
                }
            }
        }
        return null;
    }

    // ───────────────────────────────────────────────────────────────────────
    //  Core section processing (shared by player inventory & containers)
    // ───────────────────────────────────────────────────────────────────────


    /**
     * Processes a single {@link InventorySection}: for each enchanted item,
     * clones its {@link ItemWithAllMetadata}, sets the virtual ID on the clone,
     * and replaces the entry in the section's items map.
     * <p>
     * The original {@code ItemWithAllMetadata} objects (cached in
     * {@code ItemStack.cachedPacket}) are <b>never modified</b>.
     *
     * @param playerUuid     player UUID for slot tracking
     * @param sectionName    section name for slot tracking (e.g. "hotbar"), or
     *                       {@code null} to skip tracking (for container items)
     * @param section        the inventory section to process
     * @param language       the player's language for description resolution
     * @param newVirtualItems accumulator for virtual ItemBase definitions
     * @param translations   accumulator for description translations
     */
    private void processSection(
            @Nonnull UUID playerUuid,
            @Nullable String sectionName,
            @Nullable InventorySection section,
            @Nullable String language,
            @Nonnull Map<String, ItemBase> newVirtualItems,
            @Nonnull Map<String, String> translations) {

        if (section == null || section.items == null || section.items.isEmpty()) return;

        for (Map.Entry<Integer, ItemWithAllMetadata> entry : section.items.entrySet()) {
            int slot = entry.getKey();
            ItemWithAllMetadata itemPacket = entry.getValue();

            if (itemPacket == null || itemPacket.itemId == null || itemPacket.itemId.isEmpty()) {
                // Clear slot tracking for empty slots
                if (sectionName != null) {
                    virtualItemRegistry.trackSlotVirtualId(playerUuid, sectionName + ":" + slot, null);
                }
                continue;
            }

            // Skip items that are already virtual (shouldn't happen, but be safe)
            if (VirtualItemRegistry.isVirtualId(itemPacket.itemId)) {
                continue;
            }

            // Fast check: does this item's metadata contain enchantments?
            EnchantmentData enchData = parseEnchantmentsFromPacket(itemPacket.metadata);
            if (enchData == null || enchData.isEmpty()) {
                // Not enchanted — clear slot tracking
                if (sectionName != null) {
                    virtualItemRegistry.trackSlotVirtualId(playerUuid, sectionName + ":" + slot, null);
                }
                continue;
            }

            String baseItemId = itemPacket.itemId;
            String virtualId = virtualItemRegistry.generateVirtualId(baseItemId, enchData);

            // Get or create the virtual ItemBase definition
            ItemBase virtualBase = virtualItemRegistry.getOrCreateVirtualItemBase(baseItemId, virtualId);
            if (virtualBase == null) {
                if (sectionName != null) {
                    virtualItemRegistry.trackSlotVirtualId(playerUuid, sectionName + ":" + slot, null);
                }
                continue;
            }

            newVirtualItems.put(virtualId, virtualBase);

            // Build the enchanted description for this virtual item's unique key
            String descKey = VirtualItemRegistry.getVirtualDescriptionKey(virtualId);
            if (!translations.containsKey(descKey)) {
                String originalDesc = virtualItemRegistry.getOriginalDescription(baseItemId, language);
                String enchantedDesc = virtualItemRegistry.buildEnchantedDescription(originalDesc, enchData);
                translations.put(descKey, enchantedDesc);
            }

            // Clone the ItemWithAllMetadata, set the virtual ID on the CLONE,
            // and replace the map entry.  The original (cached in ItemStack)
            // is untouched.
            ItemWithAllMetadata clonedItem = itemPacket.clone();
            clonedItem.itemId = virtualId;
            section.items.put(slot, clonedItem);

            // Track this slot's virtual ID for interaction translation
            if (sectionName != null) {
                virtualItemRegistry.trackSlotVirtualId(playerUuid, sectionName + ":" + slot, virtualId);
            }
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    //  Hotbar section processing (virtual IDs for non-active slots)
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Processes the hotbar section of an outbound {@link UpdatePlayerInventory}:
     * <ul>
     *   <li><b>Active slot:</b> keeps the real item ID.  If enchanted, overrides the
     *       item type's description translation with enchantment text (via
     *       {@code overriddenTypes} and {@code translations}).</li>
     *   <li><b>Non-active enchanted slots:</b> cloned, swapped to virtual item IDs.
     *       Each gets a unique description via the virtual item's own translation key.</li>
     * </ul>
     * Also caches the original hotbar data for building corrective packets when the
     * active slot changes.
     *
     * @param playerUuid     the player's UUID
     * @param activeSlot     the currently active hotbar slot index
     * @param hotbar         the hotbar inventory section (may be null)
     * @param language       the player's language
     * @param newVirtualItems accumulator for new virtual item definitions
     * @param translations   accumulator for translation overrides
     * @param overriddenTypes accumulator for real item types whose descriptions are overridden
     */
    private void processHotbarSection(
            @Nonnull UUID playerUuid,
            int activeSlot,
            @Nullable InventorySection hotbar,
            @Nullable String language,
            @Nonnull Map<String, ItemBase> newVirtualItems,
            @Nonnull Map<String, String> translations,
            @Nonnull Set<String> overriddenTypes) {

        if (hotbar == null || hotbar.items == null || hotbar.items.isEmpty()) {
            hotbarCache.remove(playerUuid);
            return;
        }

        // Build cache for corrective packets
        CachedHotbarData cache = new CachedHotbarData();
        cache.capacity = hotbar.capacity;
        cache.originalItems = new HashMap<>();
        cache.enchantedSlots = new HashMap<>();
        cache.virtualIds = new HashMap<>();

        for (Map.Entry<Integer, ItemWithAllMetadata> entry : hotbar.items.entrySet()) {
            int slot = entry.getKey();
            ItemWithAllMetadata itemPacket = entry.getValue();

            if (itemPacket == null || itemPacket.itemId == null || itemPacket.itemId.isEmpty()) continue;
            if (VirtualItemRegistry.isVirtualId(itemPacket.itemId)) continue;

            // Cache the original item (deep clone)
            cache.originalItems.put(slot, itemPacket.clone());

            EnchantmentData enchData = parseEnchantmentsFromPacket(itemPacket.metadata);
            if (enchData == null || enchData.isEmpty()) continue;

            String baseItemId = itemPacket.itemId;
            String virtualId = virtualItemRegistry.generateVirtualId(baseItemId, enchData);
            cache.enchantedSlots.put(slot, enchData);
            cache.virtualIds.put(slot, virtualId);

            if (slot == activeSlot) {
                // ── Active slot: keep real ID, override the type's description ──
                if (!overriddenTypes.contains(baseItemId)) {
                    overriddenTypes.add(baseItemId);
                    String descKey = virtualItemRegistry.getItemDescriptionKey(baseItemId);
                    String originalDesc = virtualItemRegistry.getOriginalDescription(baseItemId, language);
                    String enchantedDesc = virtualItemRegistry.buildEnchantedDescription(originalDesc, enchData);
                    translations.put(descKey, enchantedDesc);
                }
            } else {
                // ── Non-active slot: swap to virtual ID ──
                ItemBase virtualBase = virtualItemRegistry.getOrCreateVirtualItemBase(baseItemId, virtualId);
                if (virtualBase == null) continue;

                newVirtualItems.put(virtualId, virtualBase);

                String descKey = VirtualItemRegistry.getVirtualDescriptionKey(virtualId);
                if (!translations.containsKey(descKey)) {
                    String originalDesc = virtualItemRegistry.getOriginalDescription(baseItemId, language);
                    String enchantedDesc = virtualItemRegistry.buildEnchantedDescription(originalDesc, enchData);
                    translations.put(descKey, enchantedDesc);
                }

                // Clone, swap ID, replace in the section's items map
                ItemWithAllMetadata clonedItem = itemPacket.clone();
                clonedItem.itemId = virtualId;
                hotbar.items.put(slot, clonedItem);
            }
        }

        hotbarCache.put(playerUuid, cache);
    }

    // ───────────────────────────────────────────────────────────────────────
    //  Hotbar slot change detection and corrective packet sending
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Detects active hotbar slot changes from outbound {@link SyncInteractionChains}
     * packets.  When a change is detected, sends a corrective
     * {@link UpdatePlayerInventory} that swaps the old active slot to a virtual ID
     * and the new active slot back to the real ID.
     * <p>
     * <b>Why {@code SyncInteractionChains}?</b>  Hytale handles hotbar slot changes
     * through the interaction system, not via {@code SetActiveSlot} (which is only
     * for utility/tools sections).  The server sends {@code SyncInteractionChains}
     * whenever the interaction state changes, including slot switches.  The chain's
     * {@code activeHotbarSlot} field reflects the current active slot.
     */
    private void detectHotbarSlotChange(@Nonnull PlayerRef playerRef,
                                        @Nonnull SyncInteractionChains syncPacket) {
        if (syncPacket.updates == null || syncPacket.updates.length == 0) return;

        int newActiveSlot = syncPacket.updates[0].activeHotbarSlot;
        UUID playerUuid = playerRef.getUuid();
        Integer oldActiveSlot = playerActiveHotbarSlot.put(playerUuid, newActiveSlot);

        // If the active slot changed and we have cached hotbar data, send a corrective packet
        if (oldActiveSlot != null && oldActiveSlot != newActiveSlot) {
            sendCorrectiveHotbar(playerRef, oldActiveSlot, newActiveSlot);
        }
    }

    /**
     * Builds and sends a corrective {@link UpdatePlayerInventory} packet with only
     * the hotbar section populated.  The corrective packet:
     * <ul>
     *   <li>Sets the <b>old</b> active slot to its <b>virtual</b> ID (if enchanted)</li>
     *   <li>Sets the <b>new</b> active slot to its <b>real</b> ID (if enchanted)</li>
     *   <li>Sends translation overrides/restorations as needed</li>
     * </ul>
     * <p>
     * All other sections in the {@code UpdatePlayerInventory} are left {@code null},
     * which the client interprets as "no change" for those sections.
     * <p>
     * The packet is sent via {@code writeNoCache()} while {@code isProcessing} is
     * set, so the outbound filter does <b>not</b> re-process it.
     */
    private void sendCorrectiveHotbar(@Nonnull PlayerRef playerRef,
                                      int oldActiveSlot, int newActiveSlot) {
        UUID playerUuid = playerRef.getUuid();
        CachedHotbarData cache = hotbarCache.get(playerUuid);
        if (cache == null || cache.originalItems.isEmpty()) return;

        String language = playerRef.getLanguage();
        Map<String, ItemBase> newVirtualItems = new LinkedHashMap<>();
        Map<String, String> translations = new LinkedHashMap<>();

        // ── Restore old active slot's type description ──
        EnchantmentData oldEnchData = cache.enchantedSlots.get(oldActiveSlot);
        if (oldEnchData != null) {
            ItemWithAllMetadata oldOriginal = cache.originalItems.get(oldActiveSlot);
            if (oldOriginal != null) {
                String descKey = virtualItemRegistry.getItemDescriptionKey(oldOriginal.itemId);
                String originalDesc = virtualItemRegistry.getOriginalDescription(oldOriginal.itemId, language);
                translations.put(descKey, originalDesc);
            }
        }

        // ── Override new active slot's type description ──
        EnchantmentData newEnchData = cache.enchantedSlots.get(newActiveSlot);
        if (newEnchData != null) {
            ItemWithAllMetadata newOriginal = cache.originalItems.get(newActiveSlot);
            if (newOriginal != null) {
                String descKey = virtualItemRegistry.getItemDescriptionKey(newOriginal.itemId);
                String originalDesc = virtualItemRegistry.getOriginalDescription(newOriginal.itemId, language);
                String enchantedDesc = virtualItemRegistry.buildEnchantedDescription(originalDesc, newEnchData);
                // This overwrites the restoration if old and new are the same item type
                translations.put(descKey, enchantedDesc);
            }
        }

        // ── Build corrective hotbar section with ALL slots ──
        InventorySection correctiveHotbar = new InventorySection();
        correctiveHotbar.capacity = cache.capacity;
        correctiveHotbar.items = new HashMap<>();

        for (Map.Entry<Integer, ItemWithAllMetadata> entry : cache.originalItems.entrySet()) {
            int slot = entry.getKey();
            ItemWithAllMetadata original = entry.getValue();
            EnchantmentData enchData = cache.enchantedSlots.get(slot);
            String virtualId = cache.virtualIds.get(slot);

            if (enchData != null && virtualId != null) {
                if (slot == newActiveSlot) {
                    // New active slot → real ID
                    correctiveHotbar.items.put(slot, original.clone());
                } else {
                    // Non-active enchanted slot → virtual ID
                    String baseItemId = original.itemId;
                    ItemBase virtualBase = virtualItemRegistry.getOrCreateVirtualItemBase(baseItemId, virtualId);
                    if (virtualBase != null) {
                        newVirtualItems.put(virtualId, virtualBase);

                        String descKey = VirtualItemRegistry.getVirtualDescriptionKey(virtualId);
                        if (!translations.containsKey(descKey)) {
                            String origDesc = virtualItemRegistry.getOriginalDescription(baseItemId, language);
                            String enchDesc = virtualItemRegistry.buildEnchantedDescription(origDesc, enchData);
                            translations.put(descKey, enchDesc);
                        }

                        ItemWithAllMetadata cloned = original.clone();
                        cloned.itemId = virtualId;
                        correctiveHotbar.items.put(slot, cloned);
                    } else {
                        correctiveHotbar.items.put(slot, original.clone());
                    }
                }
            } else {
                // Non-enchanted → keep original
                correctiveHotbar.items.put(slot, original.clone());
            }
        }

        // Send auxiliary packets (UpdateItems + UpdateTranslations) before the
        // corrective inventory packet.  Both go via writeNoCache, which triggers
        // onOutboundPacket → isProcessing is true → skipped.
        sendAuxiliaryPackets(playerRef, newVirtualItems, translations);

        // Send corrective UpdatePlayerInventory (only hotbar section populated;
        // null sections = "no change" on the client)
        try {
            UpdatePlayerInventory correctivePacket = new UpdatePlayerInventory();
            correctivePacket.hotbar = correctiveHotbar;
            playerRef.getPacketHandler().writeNoCache(correctivePacket);
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to send corrective hotbar packet: " + e.getMessage());
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    //  Interactive section translation overrides (utility / tools)
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Scans an interaction section (hotbar, utility, or tools) for enchanted items
     * and adds description-translation overrides.  Items in these sections keep
     * their <b>real</b> item IDs — only the <b>translation text</b> for the item
     * type's description key is overridden.
     * <p>
     * For each enchanted item type found, the real description key (e.g.
     * {@code server.items.Tool_Pickaxe_Adamantite.description}) is added to the
     * translations map with enriched text that includes the enchantment lines.
     * <p>
     * If the same item type appears multiple times with different enchantments,
     * only the first one's enchantments are used (known limitation of the shared
     * translation-key approach).
     *
     * @param section         the inventory section to scan (may be null)
     * @param language        the player's language for description resolution
     * @param translations    accumulator for translation overrides
     * @param overriddenTypes accumulator for item type IDs that were overridden
     */
    private void collectInteractiveTranslations(
            @Nullable InventorySection section,
            @Nullable String language,
            @Nonnull Map<String, String> translations,
            @Nonnull Set<String> overriddenTypes) {

        if (section == null || section.items == null || section.items.isEmpty()) return;

        for (ItemWithAllMetadata itemPacket : section.items.values()) {
            if (itemPacket == null || itemPacket.itemId == null || itemPacket.itemId.isEmpty()) continue;

            // Skip items that somehow already have a virtual ID
            if (VirtualItemRegistry.isVirtualId(itemPacket.itemId)) continue;

            EnchantmentData enchData = parseEnchantmentsFromPacket(itemPacket.metadata);
            if (enchData == null || enchData.isEmpty()) continue;

            String baseItemId = itemPacket.itemId;

            // Only override once per item type (first enchanted instance wins)
            if (overriddenTypes.contains(baseItemId)) continue;
            overriddenTypes.add(baseItemId);

            String descKey = virtualItemRegistry.getItemDescriptionKey(baseItemId);
            String originalDesc = virtualItemRegistry.getOriginalDescription(baseItemId, language);
            String enchantedDesc = virtualItemRegistry.buildEnchantedDescription(originalDesc, enchData);
            translations.put(descKey, enchantedDesc);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Metadata parsing
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Parses enchantment data from the packet's metadata JSON string.
     *
     * @param metadataJson the metadata JSON from {@link ItemWithAllMetadata#metadata}
     * @return parsed enchantment data, or {@code null} if no enchantments found
     */
    @Nullable
    private EnchantmentData parseEnchantmentsFromPacket(@Nullable String metadataJson) {
        if (metadataJson == null || metadataJson.isEmpty()) return null;

        // Quick string-contains check to avoid JSON parsing for items without enchantments
        if (!metadataJson.contains("\"" + EnchantmentData.METADATA_KEY + "\"")) return null;

        try {
            BsonDocument doc = BsonDocument.parse(metadataJson);
            BsonValue enchBson = doc.get(EnchantmentData.METADATA_KEY);
            if (enchBson == null || !enchBson.isDocument()) return null;

            EnchantmentData data = EnchantmentData.fromBson(enchBson.asDocument());
            return data.isEmpty() ? null : data;
        } catch (Exception e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Auxiliary packet sending
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Sends {@code UpdateItems} and {@code UpdateTranslations} packets for
     * virtual items that the player hasn't seen yet.
     * <p>
     * Called via {@code writeNoCache()} during the outbound filter callback.
     * These packets enter the channel <b>before</b> the inventory packet that
     * triggered this processing, ensuring the client knows about virtual items
     * before it sees them in the inventory.
     * <p>
     * This method is also called by the API for third-party mod integration.
     */
    public void sendAuxiliaryPackets(@Nonnull PlayerRef playerRef,
                                     @Nonnull Map<String, ItemBase> newVirtualItems,
                                     @Nonnull Map<String, String> translations) {
        if (newVirtualItems.isEmpty() && translations.isEmpty()) return;

        UUID playerUuid = playerRef.getUuid();

        // Send virtual item definitions the player hasn't seen yet
        Set<String> unsentItems = virtualItemRegistry.markAndGetUnsent(
                playerUuid, newVirtualItems.keySet());
        if (!unsentItems.isEmpty()) {
            Map<String, ItemBase> toSend = new LinkedHashMap<>();
            for (String vId : unsentItems) {
                ItemBase base = newVirtualItems.get(vId);
                if (base != null) {
                    toSend.put(vId, base);
                }
            }
            if (!toSend.isEmpty()) {
                sendUpdateItems(playerRef, toSend);
            }
        }

        // Send translations (always, since descriptions may change)
        if (!translations.isEmpty()) {
            sendTranslations(playerRef, translations);
        }
    }

    /**
     * Sends an {@code UpdateItems} packet with virtual item definitions to a
     * single player.  Uses {@code writeNoCache} so the server doesn't cache
     * the per-player virtual items.
     */
    private void sendUpdateItems(@Nonnull PlayerRef playerRef,
                                 @Nonnull Map<String, ItemBase> items) {
        try {
            UpdateItems packet = new UpdateItems();
            packet.type = UpdateType.AddOrUpdate;
            packet.items = items;
            packet.removedItems = new String[0];
            packet.updateModels = true;
            packet.updateIcons = true;
            playerRef.getPacketHandler().writeNoCache(packet);
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to send UpdateItems for virtual items: " + e.getMessage());
        }
    }

    /**
     * Sends an {@code UpdateTranslations} packet with enchantment descriptions
     * to a single player.
     */
    private void sendTranslations(@Nonnull PlayerRef playerRef,
                                  @Nonnull Map<String, String> translations) {
        try {
            UpdateTranslations packet = new UpdateTranslations(UpdateType.AddOrUpdate, translations);
            playerRef.getPacketHandler().writeNoCache(packet);
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to send UpdateTranslations for virtual items: " + e.getMessage());
        }
    }
}
