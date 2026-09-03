package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.herolias.plugin.util.InventoryAccess;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player cache of the armor enchantment levels that ticking systems need
 * every tick (Night Vision and Waterbreathing on the helmet, Fast Swim on the
 * gloves).
 * <p>
 * Reading enchantment metadata means parsing BSON; doing that for every
 * player every tick is wasteful because armor changes rarely. Levels are
 * computed lazily on first use and dropped again when the player's armor
 * section changes ({@link EnchantmentVisualsListener} calls
 * {@link #invalidate(UUID)} for every armor {@link InventoryChangeEvent}), when
 * the player disconnects or their entity is removed
 * ({@link #cleanupPlayer(UUID)}), and when the enabled-enchantment
 * configuration is reloaded ({@link #invalidateAll()}).
 */
public final class EquipmentLevelCache {

    /** Cached levels for one player. */
    public record ArmorLevels(int nightVision, int waterbreathing, int fastSwim) {
        public static final ArmorLevels NONE = new ArmorLevels(0, 0, 0);
    }

    private static final ConcurrentHashMap<UUID, ArmorLevels> CACHE = new ConcurrentHashMap<>();

    private EquipmentLevelCache() {
    }

    /**
     * Levels for the player, computed from the armor container on a cache miss.
     */
    @Nonnull
    public static ArmorLevels get(@Nonnull UUID playerUuid,
            @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull EnchantmentManager enchantmentManager) {
        ArmorLevels cached = CACHE.get(playerUuid);
        if (cached != null) {
            return cached;
        }
        ArmorLevels computed = compute(accessor, ref, enchantmentManager);
        CACHE.put(playerUuid, computed);
        return computed;
    }

    @Nonnull
    private static ArmorLevels compute(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull EnchantmentManager enchantmentManager) {
        ItemContainer armor = InventoryAccess.getArmor(accessor, ref);
        if (armor == null) {
            return ArmorLevels.NONE;
        }
        ItemStack helmet = armor.getItemStack((short) ItemArmorSlot.Head.getValue());
        ItemStack gloves = armor.getItemStack((short) ItemArmorSlot.Hands.getValue());
        int[] helmetLevels = enchantmentManager.getEnchantmentLevels(helmet,
                EnchantmentType.NIGHT_VISION, EnchantmentType.WATERBREATHING);
        int fastSwim = enchantmentManager.getEnchantmentLevel(gloves, EnchantmentType.FAST_SWIM);
        if (helmetLevels[0] == 0 && helmetLevels[1] == 0 && fastSwim == 0) {
            return ArmorLevels.NONE;
        }
        return new ArmorLevels(helmetLevels[0], helmetLevels[1], fastSwim);
    }

    /** True when the inventory change concerns the armor section. */
    public static boolean isArmorChange(@Nonnull InventoryChangeEvent event) {
        return event.getComponentType() == InventoryComponent.Armor.getComponentType()
                || event.getInventory() instanceof InventoryComponent.Armor;
    }

    /** Drops the cached levels so the next read recomputes them. */
    public static void invalidate(@Nonnull UUID playerUuid) {
        CACHE.remove(playerUuid);
    }

    /** Drops every cached entry (enabled-enchantment configuration changed). */
    public static void invalidateAll() {
        CACHE.clear();
    }

    /** Removes the player's entry (disconnect / entity removal). */
    public static void cleanupPlayer(@Nonnull UUID playerUuid) {
        CACHE.remove(playerUuid);
    }
}
