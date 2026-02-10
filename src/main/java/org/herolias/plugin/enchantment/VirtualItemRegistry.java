package org.herolias.plugin.enchantment;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ItemBase;
import com.hypixel.hytale.protocol.ItemTranslationProperties;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages "virtual" item definitions used to give each enchanted item instance
 * a unique tooltip without changing the server-side item ID.
 *
 * <h2>How it works</h2>
 * Hytale's tooltip system resolves item descriptions via translation keys that
 * are defined per item <em>type</em>.  All items of the same type share the same
 * description key, making it impossible to display different enchantments on two
 * items of the same base type.
 * <p>
 * This registry solves that by creating lightweight <b>virtual item definitions</b>:
 * <ul>
 *   <li>Each unique (baseItemId + enchantmentCombo) pair gets a deterministic
 *       virtual ID, e.g. {@code Tool_Pickaxe_Adamantite__enc_a1b2c3d4}.</li>
 *   <li>The virtual item's {@link ItemBase} is a deep clone of the original with
 *       only the {@code id} and {@code translationProperties.description} changed.</li>
 *   <li>Virtual items are sent to individual players via {@code UpdateItems}
 *       packets — they are <b>never registered</b> in the server's global asset store,
 *       so no game-logic compatibility issues arise.</li>
 * </ul>
 *
 * <h2>Slot tracking</h2>
 * The registry also maintains a per-player mapping of inventory slots to their
 * current virtual IDs.  This mapping is populated by the {@link InventoryPacketAdapter}
 * when processing outbound {@code UpdatePlayerInventory} packets, and is consumed
 * when translating item IDs in interaction-related packets
 * ({@code SyncInteractionChains}, {@code PlayInteractionFor}, etc.).
 *
 * <h2>Thread safety</h2>
 * All internal maps use {@link ConcurrentHashMap}. The registry is safe to use
 * from multiple world threads concurrently.
 */
public class VirtualItemRegistry {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Separator between the base item ID and the enchantment hash. */
    static final String VIRTUAL_SEPARATOR = "__enc_";

    /** Prefix for virtual item description translation keys. */
    private static final String DESC_KEY_PREFIX = "server.items.virtual.";

    /** Color used for the enchantment section header. */
    private static final String HEADER_COLOR = "#C8A2FF";
    /** Color used for standard enchantment lines. */
    private static final String ENCHANTMENT_COLOR = "#AA55FF";
    /** Color used for legendary enchantment lines. */
    private static final String LEGENDARY_COLOR = "#FFAA00";
    /** Color used for bonus description text. */
    private static final String BONUS_COLOR = "#AAAAAA";
    /** Prefix symbol for each enchantment line. */
    private static final String ENCHANT_SYMBOL = "\u2022 "; // Bullet point "• " (Supported)

    /**
     * Global cache: virtual item ID → cloned {@link ItemBase}.
     * Populated lazily.  Thread-safe via CHM.
     */
    private final ConcurrentHashMap<String, ItemBase> virtualItemCache = new ConcurrentHashMap<>();

    /**
     * Per-player tracking: which virtual item IDs have been sent to each player.
     * Used to avoid re-sending item definitions the client already knows about.
     * Outer key = player UUID.  Value = set of virtual IDs already sent.
     */
    private final ConcurrentHashMap<UUID, Set<String>> sentToPlayer = new ConcurrentHashMap<>();

    /**
     * Per-player slot tracking: maps inventory slot keys (e.g. "hotbar:0", "armor:2")
     * to the virtual item ID currently in that slot.
     * <p>
     * Populated during {@code UpdatePlayerInventory} processing by the adapter.
     */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, String>> playerSlotVirtualIds = new ConcurrentHashMap<>();

    /**
     * Per-player tracking of which real item types currently have their description
     * translation overridden in the hotbar.  Used to restore the original description
     * when the enchanted item is removed from the hotbar.
     */
    private final ConcurrentHashMap<UUID, Set<String>> playerHotbarOverrides = new ConcurrentHashMap<>();

    /**
     * Cache: base item ID → description translation key.
     * Populated lazily and never cleared (item definitions don't change at runtime).
     */
    private final ConcurrentHashMap<String, String> descriptionKeyCache = new ConcurrentHashMap<>();

    private final EnchantmentManager enchantmentManager;

    public VirtualItemRegistry(@Nonnull EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Virtual ID generation
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Generates a deterministic virtual item ID for the given base item + enchantment combo.
     * The ID is stable: the same base item with the same enchantments always produces the
     * same virtual ID, regardless of map iteration order.
     *
     * @param baseItemId the real item ID (e.g. {@code "Tool_Pickaxe_Adamantite"})
     * @param data       the enchantment data (must not be empty)
     * @return a virtual ID, e.g. {@code "Tool_Pickaxe_Adamantite__enc_a1b2c3d4"}
     */
    @Nonnull
    public String generateVirtualId(@Nonnull String baseItemId, @Nonnull EnchantmentData data) {
        String hash = computeEnchantmentHash(data);
        return baseItemId + VIRTUAL_SEPARATOR + hash;
    }

    /**
     * Extracts the original (base) item ID from a virtual ID.
     *
     * @return the base item ID, or {@code null} if the given ID is not virtual
     */
    @Nullable
    public static String getBaseItemId(@Nonnull String virtualOrRealId) {
        int idx = virtualOrRealId.indexOf(VIRTUAL_SEPARATOR);
        return idx > 0 ? virtualOrRealId.substring(0, idx) : null;
    }

    /**
     * Checks whether the given item ID is a virtual enchantment ID.
     */
    public static boolean isVirtualId(@Nonnull String itemId) {
        return itemId.contains(VIRTUAL_SEPARATOR);
    }

    /**
     * Gets the unique description translation key for a virtual item.
     */
    @Nonnull
    public static String getVirtualDescriptionKey(@Nonnull String virtualId) {
        return DESC_KEY_PREFIX + virtualId + ".description";
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ItemBase management
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Gets or creates the {@link ItemBase} protocol packet for a virtual item.
     * <p>
     * The returned {@code ItemBase} is a deep clone of the original item's protocol
     * representation, with the {@code id} and {@code translationProperties.description}
     * changed to point to a unique virtual description key.
     *
     * @param baseItemId the real item ID to clone from
     * @param virtualId  the virtual item ID to assign
     * @return the virtual {@code ItemBase}, or {@code null} if the base item was not found
     */
    @Nullable
    public ItemBase getOrCreateVirtualItemBase(@Nonnull String baseItemId, @Nonnull String virtualId) {
        return virtualItemCache.computeIfAbsent(virtualId, vId -> {
            try {
                Item originalItem = Item.getAssetMap().getAsset(baseItemId);
                if (originalItem == null) {
                    LOGGER.atWarning().log("Cannot create virtual item: base item not found: " + baseItemId);
                    return null;
                }

                ItemBase originalPacket = originalItem.toPacket();
                if (originalPacket == null) {
                    LOGGER.atWarning().log("Cannot create virtual item: toPacket() returned null for: " + baseItemId);
                    return null;
                }

                // Deep clone — we must not modify the cached original
                ItemBase clone = originalPacket.clone();
                clone.id = virtualId;

                // Give the virtual item its own unique description translation key.
                // The name stays the same (item still appears as "Adamantite Pickaxe").
                String virtualDescKey = getVirtualDescriptionKey(virtualId);
                if (clone.translationProperties != null) {
                    clone.translationProperties = clone.translationProperties.clone();
                    clone.translationProperties.description = virtualDescKey;
                } else {
                    clone.translationProperties = new ItemTranslationProperties();
                    clone.translationProperties.name = "server.items." + baseItemId + ".name";
                    clone.translationProperties.description = virtualDescKey;
                }

                return clone;
            } catch (Exception e) {
                LOGGER.atWarning().log("Failed to create virtual item for " + virtualId + ": " + e.getMessage());
                return null;
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Per-player sent-item tracking
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns the subset of {@code virtualIds} that have <b>not</b> yet been sent
     * to the given player, and marks all of them as sent.
     */
    @Nonnull
    public Set<String> markAndGetUnsent(@Nonnull UUID playerUuid, @Nonnull Set<String> virtualIds) {
        Set<String> sentSet = sentToPlayer.computeIfAbsent(playerUuid, k -> ConcurrentHashMap.newKeySet());
        Set<String> unsent = new HashSet<>();
        for (String vId : virtualIds) {
            if (sentSet.add(vId)) {
                unsent.add(vId);
            }
        }
        return unsent;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Per-player slot-to-virtualId tracking
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Records the virtual ID currently occupying a given inventory slot for a player.
     * Called by the adapter during {@code UpdatePlayerInventory} processing.
     *
     * @param playerUuid the player's UUID
     * @param slotKey    a string key like {@code "hotbar:0"}, {@code "armor:2"}, etc.
     * @param virtualId  the virtual item ID, or {@code null} to clear the slot
     */
    public void trackSlotVirtualId(@Nonnull UUID playerUuid, @Nonnull String slotKey, @Nullable String virtualId) {
        ConcurrentHashMap<String, String> slotMap = playerSlotVirtualIds.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>());
        if (virtualId != null) {
            slotMap.put(slotKey, virtualId);
        } else {
            slotMap.remove(slotKey);
        }
    }

    /**
     * Looks up the virtual ID currently in a given inventory slot for a player.
     *
     * @param playerUuid the player's UUID
     * @param slotKey    a string key like {@code "hotbar:0"}, {@code "utility:1"}, etc.
     * @return the virtual item ID, or {@code null} if the slot has no virtual item
     */
    @Nullable
    public String getSlotVirtualId(@Nonnull UUID playerUuid, @Nonnull String slotKey) {
        Map<String, String> slotMap = playerSlotVirtualIds.get(playerUuid);
        return slotMap != null ? slotMap.get(slotKey) : null;
    }

    /**
     * Searches all tracked slots for a player to find a virtual ID whose base item ID
     * matches the given real item ID.  If multiple slots match, returns the first found
     * (preferring hotbar slots).
     *
     * @param playerUuid the player's UUID
     * @param realItemId the real item ID to match against
     * @return a matching virtual item ID, or {@code null} if none found
     */
    @Nullable
    public String findVirtualIdForBaseItem(@Nonnull UUID playerUuid, @Nonnull String realItemId) {
        Map<String, String> slotMap = playerSlotVirtualIds.get(playerUuid);
        if (slotMap == null || slotMap.isEmpty()) return null;

        // First pass: check hotbar slots (most likely for hand interactions)
        for (Map.Entry<String, String> entry : slotMap.entrySet()) {
            if (entry.getKey().startsWith("hotbar:")) {
                String baseId = getBaseItemId(entry.getValue());
                if (realItemId.equals(baseId)) {
                    return entry.getValue();
                }
            }
        }

        // Second pass: check any slot
        for (Map.Entry<String, String> entry : slotMap.entrySet()) {
            String baseId = getBaseItemId(entry.getValue());
            if (realItemId.equals(baseId)) {
                return entry.getValue();
            }
        }

        return null;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Hotbar description-override tracking
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Atomically replaces the set of real item types whose description translations
     * are currently overridden for a player's hotbar, and returns the previous set.
     * <p>
     * The adapter calls this during each {@code UpdatePlayerInventory} to determine
     * which item types need their description <b>restored</b> (types that were in the
     * old set but not the new one).
     *
     * @param playerUuid      the player's UUID
     * @param currentOverrides the set of real item type IDs currently needing an override
     * @return the <b>previous</b> set of overridden types, or {@code null} if none
     */
    @Nullable
    public Set<String> getAndUpdateHotbarOverrides(@Nonnull UUID playerUuid,
                                                   @Nonnull Set<String> currentOverrides) {
        if (currentOverrides.isEmpty()) {
            return playerHotbarOverrides.remove(playerUuid);
        }
        return playerHotbarOverrides.put(playerUuid, new HashSet<>(currentOverrides));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Cleans up all per-player state when a player disconnects.
     */
    public void onPlayerLeave(@Nonnull UUID playerUuid) {
        sentToPlayer.remove(playerUuid);
        playerSlotVirtualIds.remove(playerUuid);
        playerHotbarOverrides.remove(playerUuid);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Description building
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Builds the enriched item description that includes formatted enchantment lines.
     * <p>
     * Format example (using Hytale HTML-like markup):
     * <pre>
     * A sturdy adamantite pickaxe.
     *
     * &lt;color is="#C8A2FF"&gt;Enchantments:&lt;/color&gt;
     * &lt;color is="#AA55FF"&gt;◆ Fortune III&lt;/color&gt; &lt;color is="#AAAAAA"&gt;Extra drop chance increased by 75%&lt;/color&gt;
     * &lt;color is="#AA55FF"&gt;◆ Efficiency II&lt;/color&gt; &lt;color is="#AAAAAA"&gt;Mining speed increased by 40%&lt;/color&gt;
     * </pre>
     *
     * @param originalDesc the original item description (may be null or empty)
     * @param data         the enchantment data to display
     * @return the enriched description string
     */
    @Nonnull
    public String buildEnchantedDescription(@Nullable String originalDesc, @Nonnull EnchantmentData data) {
        StringBuilder sb = new StringBuilder();

        if (originalDesc != null && !originalDesc.isEmpty()) {
            sb.append(originalDesc);
            sb.append("\n\n");
        }

        sb.append("<color is=\"").append(HEADER_COLOR).append("\">Enchantments:</color>");

        for (Map.Entry<EnchantmentType, Integer> entry : data.getAllEnchantments().entrySet()) {
            EnchantmentType type = entry.getKey();
            int level = entry.getValue();

            if (!enchantmentManager.isEnchantmentEnabled(type)) continue;

            sb.append('\n');
            String color = type.isLegendary() ? LEGENDARY_COLOR : ENCHANTMENT_COLOR;
            sb.append("<color is=\"").append(color).append("\">");
            sb.append(ENCHANT_SYMBOL);
            sb.append(type.getFormattedName(level));
            sb.append("</color>");
            
            String bonus = type.getBonusDescription(level);
            if (bonus != null && !bonus.isEmpty()) {
                sb.append(" <color is=\"").append(BONUS_COLOR).append("\">");
                sb.append(bonus);
                sb.append("</color>");
            }
        }

        return sb.toString();
    }

    /**
     * Resolves the original (unmodified) description for a base item ID using
     * the server's i18n system.
     *
     * @param baseItemId the real item ID
     * @param language   the player's language (may be null for default)
     * @return the original description text, or empty string if not found
     */
    @Nonnull
    public String getOriginalDescription(@Nonnull String baseItemId, @Nullable String language) {
        try {
            String descKey = resolveDescriptionKey(baseItemId);
            I18nModule i18n = I18nModule.get();
            if (i18n == null) return "";
            String msg = i18n.getMessage(language, descKey);
            return msg != null ? msg : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Returns the description translation key for a base item type.
     * <p>
     * This is the <b>real</b> key used by the original item definition — as opposed
     * to the virtual description key used by virtual items.  It is used by the
     * adapter's hotbar/utility/tools translation-override logic, which overrides the
     * real key's text with enchantment info.
     *
     * @param baseItemId the real item ID
     * @return the description translation key (never null)
     */
    @Nonnull
    public String getItemDescriptionKey(@Nonnull String baseItemId) {
        return resolveDescriptionKey(baseItemId);
    }

    /**
     * Resolves the description translation key for a base item type.
     * Uses the item's own {@code TranslationProperties.Description} if set,
     * otherwise falls back to the conventional key pattern.
     */
    @Nonnull
    private String resolveDescriptionKey(@Nonnull String baseItemId) {
        return descriptionKeyCache.computeIfAbsent(baseItemId, id -> {
            try {
                Item item = Item.getAssetMap().getAsset(id);
                if (item != null) {
                    return item.getDescriptionTranslationKey();
                }
            } catch (Exception e) {
                LOGGER.atFine().log("Could not resolve description key for " + id + ": " + e.getMessage());
            }
            return "server.items." + id + ".description";
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Hash computation
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Computes a deterministic hash string from enchantment data.
     * Enchantments are sorted alphabetically by enchantment ID to ensure stability
     * regardless of the internal map iteration order.
     *
     * @return an 8-character lowercase hex hash, e.g. {@code "a1b2c3d4"}
     */
    @Nonnull
    private String computeEnchantmentHash(@Nonnull EnchantmentData data) {
        TreeMap<String, Integer> sorted = new TreeMap<>();
        for (Map.Entry<EnchantmentType, Integer> entry : data.getAllEnchantments().entrySet()) {
            sorted.put(entry.getKey().getId(), entry.getValue());
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : sorted.entrySet()) {
            if (sb.length() > 0) sb.append('_');
            sb.append(entry.getKey()).append(entry.getValue());
        }

        return String.format("%08x", sb.toString().hashCode());
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Full cache clear
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Clears all caches.  Safe to call on plugin reload.
     */
    public void clearCache() {
        virtualItemCache.clear();
        sentToPlayer.clear();
        playerSlotVirtualIds.clear();
        playerHotbarOverrides.clear();
        descriptionKeyCache.clear();
    }
}
