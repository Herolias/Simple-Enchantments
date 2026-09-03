package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.DropItemEvent;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.herolias.plugin.util.InventoryAccess;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Base class for systems that refund consumed items (Eternal Shot, Elemental
 * Heart).
 * <p>
 * Refund systems react to {@link InventoryChangeEvent}s. A player manually
 * dropping an item from a slot also produces a removal transaction, so the
 * drop request is correlated with the inventory change it causes: the
 * {@link DropItemEvent.PlayerRequest} names the inventory section and slot,
 * and the subsequent {@code InventoryChangeEvent} carries the component type
 * (= section) and slot of the removal. A drop request is recorded together
 * with the item that sat in that slot and the world tick it was made on, and
 * a removal that matches section, slot and item id within a few ticks is
 * treated as the drop and skipped.
 * <p>
 * The request event is cancellable and other listeners may cancel it after
 * this plugin has seen it, so a marker is only ever consumed by a matching
 * removal or discarded once it is older than {@link #DROP_MARKER_MAX_AGE_TICKS};
 * a cancelled drop therefore never costs more than one skipped refund on that
 * exact slot and item.
 */
public abstract class AbstractRefundSystem extends EntityEventSystem<EntityStore, InventoryChangeEvent> {

    /**
     * Inventory change events are queued by the inventory component and
     * dispatched by the per-section change-event systems, normally within the
     * same or the following tick. Anything older than this cannot be the drop.
     */
    private static final long DROP_MARKER_MAX_AGE_TICKS = 5;

    private record DropMarker(int sectionId, short slot, @Nonnull String itemId, long tick) {
    }

    private final Query<EntityStore> query = Query.and(Player.getComponentType(), UUIDComponent.getComponentType());

    /** Pending drop requests per player, consumed by the matching inventory change. */
    private final Map<UUID, ConcurrentLinkedQueue<DropMarker>> pendingDrops = new ConcurrentHashMap<>();

    protected AbstractRefundSystem() {
        super(InventoryChangeEvent.class);
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return query;
    }

    /**
     * Records a player's drop request so the removal it causes is not refunded.
     * Called by {@link DropItemEventSystem} while the request event is being
     * dispatched, i.e. before the slot is actually modified.
     */
    public void onDropItemRequest(@Nonnull DropItemEvent.PlayerRequest event,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store) {
        if (event.isCancelled()) {
            return;
        }
        UUIDComponent uuidComponent = store.getComponent(ref, UUIDComponent.getComponentType());
        if (uuidComponent == null) {
            return;
        }
        // Only the player's own sections can hold refundable items; open windows
        // (positive ids) are other containers and are not tracked.
        ItemContainer section = InventoryAccess.getSectionById(store, ref, event.getInventorySectionId());
        if (section == null) {
            return;
        }
        ItemStack inSlot = section.getItemStack(event.getSlotId());
        if (ItemStack.isEmpty(inSlot)) {
            return;
        }
        pendingDrops.computeIfAbsent(uuidComponent.getUuid(), k -> new ConcurrentLinkedQueue<>())
                .add(new DropMarker(event.getInventorySectionId(), event.getSlotId(), inSlot.getItemId(), currentTick(store)));
    }

    /**
     * Returns true (and consumes the marker) when the given removal is the
     * result of a recorded drop request: same inventory section, same slot,
     * same item id, and not older than {@link #DROP_MARKER_MAX_AGE_TICKS}.
     * Expired markers are discarded on the way.
     */
    protected boolean consumeDropMarker(@Nonnull UUID playerUuid,
            @Nonnull InventoryChangeEvent event,
            short slot,
            @Nonnull ItemStack removed,
            long currentTick) {
        ConcurrentLinkedQueue<DropMarker> markers = pendingDrops.get(playerUuid);
        if (markers == null) {
            return false;
        }
        boolean matched = false;
        for (Iterator<DropMarker> it = markers.iterator(); it.hasNext();) {
            DropMarker marker = it.next();
            if (currentTick - marker.tick() > DROP_MARKER_MAX_AGE_TICKS) {
                it.remove();
                continue;
            }
            if (!matched
                    && marker.slot() == slot
                    && marker.itemId().equals(removed.getItemId())
                    && InventoryComponent.getComponentTypeById(marker.sectionId()) == event.getComponentType()) {
                it.remove();
                matched = true;
            }
        }
        if (markers.isEmpty()) {
            pendingDrops.remove(playerUuid);
        }
        return matched;
    }

    /** Forgets everything tracked for a player (disconnect / entity removal). */
    public void cleanupPlayer(@Nonnull UUID playerUuid) {
        pendingDrops.remove(playerUuid);
    }

    /** Current tick of the world owning the store. */
    protected static long currentTick(@Nonnull Store<EntityStore> store) {
        return store.getExternalData().getWorld().getTick();
    }

    protected static int quantityOf(@Nullable ItemStack stack) {
        return stack == null || stack.isEmpty() ? 0 : stack.getQuantity();
    }
}
