package org.herolias.plugin.enchantment;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.UpdateType;
import com.hypixel.hytale.protocol.InventorySection;
import com.hypixel.hytale.protocol.ItemWithAllMetadata;
import com.hypixel.hytale.protocol.packets.assets.UpdateTranslations;
import com.hypixel.hytale.protocol.packets.entities.EntityUpdates;
import com.hypixel.hytale.protocol.packets.inventory.UpdatePlayerInventory;
import com.hypixel.hytale.protocol.packets.window.UpdateWindow;
import com.hypixel.hytale.protocol.packets.window.OpenWindow;
import com.hypixel.hytale.protocol.ComponentUpdate;
import com.hypixel.hytale.protocol.EntityUpdate;
import com.hypixel.hytale.protocol.ItemUpdate;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;

import org.herolias.plugin.SimpleEnchanting;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages server-side translation updates for dynamic enchantment tooltips.
 * Intercepts outgoing packets to detect items with enchantments and sends localized
 * descriptions to players on-the-fly.
 */
public class EnchantmentTranslationManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final SimpleEnchanting plugin;
    private final EnchantmentManager enchantmentManager;

    // Cache of sent translation hashes per player to avoid spamming packets.
    // Key: Player UUID, Value: Set of content hashes sent this session.
    private final ConcurrentHashMap<UUID, Set<String>> sentTranslations = new ConcurrentHashMap<>();

    public EnchantmentTranslationManager(SimpleEnchanting plugin) {
        this.plugin = plugin;
        this.enchantmentManager = plugin.getEnchantmentManager();
    }

    /**
     * Initializes the manager by registering packet listeners.
     */
    public void init() {
        PacketAdapters.registerOutbound(this::onPacketOutbound);
        LOGGER.atInfo().log("EnchantmentTranslationManager initialized.");
    }

    private void onPacketOutbound(PlayerRef player, Packet packet) {
        if (player == null || !player.isValid()) return;

        try {
            if (packet instanceof UpdatePlayerInventory) {
                handleInventoryUpdate(player, (UpdatePlayerInventory) packet);
            } else if (packet instanceof UpdateWindow) {
                handleWindowUpdate(player, (UpdateWindow) packet);
            } else if (packet instanceof OpenWindow) {
                handleOpenWindow(player, (OpenWindow) packet);
            } else if (packet instanceof EntityUpdates) {
                handleEntityUpdates(player, (EntityUpdates) packet);
            }
        } catch (Exception e) {
            LOGGER.atWarning().log("Error handling outbound packet for translations: " + e.getMessage());
        }
    }

    private void handleInventoryUpdate(PlayerRef player, UpdatePlayerInventory packet) {
        // Check standard inventory sections
        checkSection(player, packet.hotbar);
        checkSection(player, packet.storage);
        checkSection(player, packet.armor);
        checkSection(player, packet.utility);
        checkSection(player, packet.backpack);
        // Add others if needed: packet.builderMaterial, packet.tools
    }

    private void checkSection(PlayerRef player, InventorySection section) {
        if (section == null || section.items == null) return;
        
        for (ItemWithAllMetadata item : section.items.values()) {
            checkAndSendTranslation(player, item);
        }
    }

    private void handleWindowUpdate(PlayerRef player, UpdateWindow packet) {
        if (packet.inventory != null && packet.inventory.items != null) {
            for (ItemWithAllMetadata item : packet.inventory.items.values()) {
                checkAndSendTranslation(player, item);
            }
        }
    }

    private void handleOpenWindow(PlayerRef player, OpenWindow packet) {
        if (packet.inventory != null && packet.inventory.items != null) {
            for (ItemWithAllMetadata item : packet.inventory.items.values()) {
                checkAndSendTranslation(player, item);
            }
        }
    }

    private void handleEntityUpdates(PlayerRef player, EntityUpdates packet) {
        if (packet.updates == null) return;

        for (EntityUpdate update : packet.updates) {
            if (update.updates != null) {
                for (ComponentUpdate component : update.updates) {
                    if (component instanceof ItemUpdate) {
                        ItemUpdate itemUpdate = (ItemUpdate) component;
                        if (itemUpdate.item != null) {
                            checkAndSendTranslation(player, itemUpdate.item);
                        }
                    }
                }
            }
        }
    }

    private void checkAndSendTranslation(PlayerRef player, ItemWithAllMetadata item) {
        if (item == null || item.metadata == null || item.metadata.isEmpty()) return;

        // Fast check before parsing
        if (!item.metadata.contains(EnchantmentData.METADATA_KEY)) return;

        EnchantmentData data = enchantmentManager.getEnchantmentsFromMetadata(item.metadata);
        if (data == null || data.isEmpty()) return;

        // Register the hash in the global cache so finding it by ID works if needed (though here we have the data directly)
        enchantmentManager.registerEnchantmentData(data);

        String hash = data.computeStableHash();
        UUID playerId = player.getUuid();

        Set<String> playerSent = sentTranslations.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());

        if (playerSent.contains(hash)) return;

        // Send translation update
        sendTranslationUpdate(player, hash, data);
        playerSent.add(hash);
    }

    private void sendTranslationUpdate(PlayerRef player, String hash, EnchantmentData data) {
        String locale = player.getLanguage();
        if (locale == null || locale.isEmpty()) locale = "en-US";

        String translationKey = "enchantment.combo." + hash;
        String localizedDescription = enchantmentManager.getLocalizedEnchantmentDescription(data, locale);

        if (localizedDescription == null || localizedDescription.isEmpty()) return;

        Map<String, String> translations = new HashMap<>(); 
        translations.put(translationKey, localizedDescription);

        UpdateTranslations packet = new UpdateTranslations(UpdateType.AddOrUpdate, translations);
        if (player.getPacketHandler() != null) {
            player.getPacketHandler().writeNoCache(packet);
        }
    }
    
    /**
     * Clears the cache for a player. Should be called on quit.
     */
    public void onPlayerQuit(UUID playerId) {
        sentTranslations.remove(playerId);
    }
}
