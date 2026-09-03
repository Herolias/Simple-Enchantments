package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InventorySetActiveSlotEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.TimeUnit;

/**
 * Reacts to active-slot changes (hotbar, utility/off-hand and tools sections).
 * <p>
 * The server invokes {@link InventorySetActiveSlotEvent} from
 * {@code ActiveSlotInventoryComponent.setActiveSlot} after the slot has been
 * changed, on the owning world thread. This replaces the former scheduled
 * per-tick slot poller: the held-item glow is refreshed right away and the
 * Eternal Shot tracker is told that the weapon left the player's hand.
 */
public class EnchantmentActiveSlotSystem extends EntityEventSystem<EntityStore, InventorySetActiveSlotEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final EnchantmentManager enchantmentManager;
    @Nullable
    private final EnchantmentEternalShotSystem eternalShotSystem;
    private final Query<EntityStore> query = Query.and(Player.getComponentType(), UUIDComponent.getComponentType());

    public EnchantmentActiveSlotSystem(@Nonnull EnchantmentManager enchantmentManager,
            @Nullable EnchantmentEternalShotSystem eternalShotSystem) {
        super(InventorySetActiveSlotEvent.class);
        this.enchantmentManager = enchantmentManager;
        this.eternalShotSystem = eternalShotSystem;
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void handle(int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull InventorySetActiveSlotEvent event) {
        int sectionId = event.getInventorySectionId();
        boolean heldItemChanged = sectionId == InventoryComponent.HOTBAR_SECTION_ID
                || sectionId == InventoryComponent.TOOLS_SECTION_ID;
        if (!heldItemChanged && sectionId != InventoryComponent.UTILITY_SECTION_ID) {
            return;
        }
        Player player = archetypeChunk.getComponent(index, Player.getComponentType());
        if (player == null) {
            return;
        }
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);

        try {
            EnchantmentVisualsHelper.updateGlowStats(ref, store, enchantmentManager);
        } catch (RuntimeException e) {
            LOGGER.atWarning().atMostEvery(30, TimeUnit.SECONDS).withCause(e)
                    .log("Failed to refresh enchantment glow after an active slot change");
        }

        if (heldItemChanged && eternalShotSystem != null) {
            UUIDComponent uuidComponent = archetypeChunk.getComponent(index, UUIDComponent.getComponentType());
            if (uuidComponent != null) {
                eternalShotSystem.onSlotChanged(uuidComponent.getUuid());
            }
        }
    }
}
