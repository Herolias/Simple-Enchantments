package org.herolias.plugin.util;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Single access point for player/entity inventories built on the
 * per-section {@link InventoryComponent}s.
 * <p>
 * The legacy {@code Inventory} class is {@code @Deprecated(forRemoval = true)}
 * in the current server. It is a thin wrapper around the same component
 * containers, so every method here returns exactly the object the legacy
 * getter used to return. Keeping all reads in one place means the next server
 * API change is a single-file migration.
 * <p>
 * All methods must be called on the owning world thread (same requirement as
 * any {@link Store#getComponent} call).
 */
public final class InventoryAccess {

    private InventoryAccess() {
    }

    // ------------------------------------------------------------------
    // Resolution helpers
    // ------------------------------------------------------------------

    /**
     * Resolves a valid reference for a legacy entity object, or null.
     */
    @Nullable
    public static Ref<EntityStore> refOf(@Nullable Entity entity) {
        if (entity == null) {
            return null;
        }
        Ref<EntityStore> ref = entity.getReference();
        return ref != null && ref.isValid() ? ref : null;
    }

    // ------------------------------------------------------------------
    // Section containers
    // ------------------------------------------------------------------

    @Nullable
    public static ItemContainer getHotbar(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        InventoryComponent.Hotbar c = accessor.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        return c != null ? c.getInventory() : null;
    }

    @Nullable
    public static ItemContainer getStorage(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        InventoryComponent.Storage c = accessor.getComponent(ref, InventoryComponent.Storage.getComponentType());
        return c != null ? c.getInventory() : null;
    }

    @Nullable
    public static ItemContainer getArmor(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        InventoryComponent.Armor c = accessor.getComponent(ref, InventoryComponent.Armor.getComponentType());
        return c != null ? c.getInventory() : null;
    }

    @Nullable
    public static ItemContainer getUtility(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        InventoryComponent.Utility c = accessor.getComponent(ref, InventoryComponent.Utility.getComponentType());
        return c != null ? c.getInventory() : null;
    }

    @Nullable
    public static ItemContainer getTools(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        InventoryComponent.Tool c = accessor.getComponent(ref, InventoryComponent.Tool.getComponentType());
        return c != null ? c.getInventory() : null;
    }

    @Nullable
    public static ItemContainer getBackpack(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        InventoryComponent.Backpack c = accessor.getComponent(ref, InventoryComponent.Backpack.getComponentType());
        return c != null ? c.getInventory() : null;
    }

    /**
     * Resolves one of the fixed inventory sections by its negative section id
     * (hotbar -1, storage -2, armor -3, utility -5, tools -8, backpack -9).
     * Positive ids (open windows) are not handled here.
     */
    @Nullable
    public static ItemContainer getSectionById(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref, int sectionId) {
        return switch (sectionId) {
            case InventoryComponent.HOTBAR_SECTION_ID -> getHotbar(accessor, ref);
            case InventoryComponent.STORAGE_SECTION_ID -> getStorage(accessor, ref);
            case InventoryComponent.ARMOR_SECTION_ID -> getArmor(accessor, ref);
            case InventoryComponent.UTILITY_SECTION_ID -> getUtility(accessor, ref);
            case InventoryComponent.TOOLS_SECTION_ID -> getTools(accessor, ref);
            case InventoryComponent.BACKPACK_SECTION_ID -> getBackpack(accessor, ref);
            default -> null;
        };
    }

    // ------------------------------------------------------------------
    // Combined containers (same orderings as the legacy getters)
    // ------------------------------------------------------------------

    @Nonnull
    public static CombinedItemContainer getCombinedHotbarFirst(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        return InventoryComponent.getCombined(accessor, ref, InventoryComponent.HOTBAR_FIRST);
    }

    @Nonnull
    public static CombinedItemContainer getCombinedStorageFirst(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        return InventoryComponent.getCombined(accessor, ref, InventoryComponent.STORAGE_FIRST);
    }

    /** Legacy {@code getCombinedBackpackStorageHotbarFirst()} mapped to {@code HOTBAR_STORAGE_BACKPACK}, as vanilla does. */
    @Nonnull
    public static CombinedItemContainer getCombinedHotbarStorageBackpack(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        return InventoryComponent.getCombined(accessor, ref, InventoryComponent.HOTBAR_STORAGE_BACKPACK);
    }

    @Nonnull
    public static CombinedItemContainer getCombinedArmorHotbarUtilityStorage(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        return InventoryComponent.getCombined(accessor, ref, InventoryComponent.ARMOR_HOTBAR_UTILITY_STORAGE);
    }

    @Nonnull
    public static CombinedItemContainer getCombinedEverything(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        return InventoryComponent.getCombined(accessor, ref, InventoryComponent.EVERYTHING);
    }

    // ------------------------------------------------------------------
    // Active slots / held items
    // ------------------------------------------------------------------

    /** Item in hand: the active tools item when the tools section is in use, otherwise the active hotbar item. */
    @Nullable
    public static ItemStack getItemInHand(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        return InventoryComponent.getItemInHand(accessor, ref);
    }

    /** Convenience for legacy entity objects. Returns null if the entity has no valid reference. */
    @Nullable
    public static ItemStack getItemInHand(@Nullable Entity entity) {
        Ref<EntityStore> ref = refOf(entity);
        if (ref == null) {
            return null;
        }
        return getItemInHand(ref.getStore(), ref);
    }

    @Nullable
    public static ItemStack getActiveHotbarItem(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        InventoryComponent.Hotbar c = accessor.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        return c != null ? c.getActiveItem() : null;
    }

    /** Active hotbar slot, or {@link InventoryComponent#INACTIVE_SLOT_INDEX} when there is none. */
    public static byte getActiveHotbarSlot(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        InventoryComponent.Hotbar c = accessor.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        return c != null ? c.getActiveSlot() : InventoryComponent.INACTIVE_SLOT_INDEX;
    }

    /** Active utility (off-hand) item. */
    @Nullable
    public static ItemStack getUtilityItem(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        InventoryComponent.Utility c = accessor.getComponent(ref, InventoryComponent.Utility.getComponentType());
        return c != null ? c.getActiveItem() : null;
    }

    @Nullable
    public static ItemStack getUtilityItem(@Nullable Entity entity) {
        Ref<EntityStore> ref = refOf(entity);
        if (ref == null) {
            return null;
        }
        return getUtilityItem(ref.getStore(), ref);
    }

    /** Active tools item, or null. */
    @Nullable
    public static ItemStack getActiveToolItem(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        InventoryComponent.Tool c = accessor.getComponent(ref, InventoryComponent.Tool.getComponentType());
        return c != null ? c.getActiveItem() : null;
    }

    public static boolean isUsingToolsItem(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        InventoryComponent.Tool c = accessor.getComponent(ref, InventoryComponent.Tool.getComponentType());
        return c != null && c.isUsingToolsItem();
    }

    /**
     * The container and slot that currently hold the item in hand, so callers
     * can write back to the exact slot they read from (tools section when it is
     * active, otherwise the hotbar). Returns null when nothing is held.
     */
    @Nullable
    public static HeldSlot getHeldSlot(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        InventoryComponent.Tool tool = accessor.getComponent(ref, InventoryComponent.Tool.getComponentType());
        if (tool != null && tool.isUsingToolsItem() && tool.getActiveSlot() >= 0) {
            ItemStack item = tool.getActiveItem();
            if (item != null && !item.isEmpty()) {
                return new HeldSlot(tool.getInventory(), tool.getActiveSlot(), InventoryComponent.TOOLS_SECTION_ID, item);
            }
        }
        InventoryComponent.Hotbar hotbar = accessor.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        if (hotbar != null && hotbar.getActiveSlot() >= 0) {
            ItemStack item = hotbar.getActiveItem();
            if (item != null && !item.isEmpty()) {
                return new HeldSlot(hotbar.getInventory(), hotbar.getActiveSlot(), InventoryComponent.HOTBAR_SECTION_ID, item);
            }
        }
        return null;
    }

    /** A resolved held item together with the container and slot it lives in. */
    public record HeldSlot(@Nonnull ItemContainer container, short slot, int sectionId, @Nonnull ItemStack item) {
    }
}
