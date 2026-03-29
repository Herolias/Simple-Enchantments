package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.SwitchActiveSlotEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * System to handle SwitchActiveSlotEvent.
 * Previously used for Eternal Shot slot-switch detection, but the ECS event
 * dispatch never fires reliably. Slot switch detection is now handled by
 * the per-tick EnchantmentSlotTracker instead.
 * This class is kept registered as a no-op for safety.
 */
public class SwitchActiveSlotSystem extends EntityEventSystem<EntityStore, SwitchActiveSlotEvent> {

    public SwitchActiveSlotSystem() {
        super(SwitchActiveSlotEvent.class);
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void handle(int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull SwitchActiveSlotEvent event) {
        // No-op: Slot switch detection is handled by EnchantmentSlotTracker
    }
}
