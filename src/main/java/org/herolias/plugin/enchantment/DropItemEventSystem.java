package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.ecs.DropItemEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * ECS event system that listens for DropItemEvent.PlayerRequest events.
 * This is used by EnchantmentEternalShotSystem to track when players manually
 * drop items, so it can distinguish between dropped arrows and shot arrows.
 */
public class DropItemEventSystem extends EntityEventSystem<EntityStore, DropItemEvent.PlayerRequest> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final EnchantmentEternalShotSystem eternalShotSystem;
    private final EnchantmentElementalHeartSystem elementalHeartSystem;

    public DropItemEventSystem(EnchantmentEternalShotSystem eternalShotSystem, EnchantmentElementalHeartSystem elementalHeartSystem) {
        super(DropItemEvent.PlayerRequest.class);
        this.eternalShotSystem = eternalShotSystem;
        this.elementalHeartSystem = elementalHeartSystem;
        LOGGER.atInfo().log("DropItemEventSystem initialized");
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        // Handle all entities (we filter by Player in the handler)
        return Archetype.empty();
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull DropItemEvent.PlayerRequest event) {
        // Get the entity ref from the archetype chunk at the current index
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        
        // Delegate to the EternalShot system to track this drop
        eternalShotSystem.onDropItemRequest(event, ref, store);
        // Delegate to Elemental Heart system too
        if (elementalHeartSystem != null) {
            elementalHeartSystem.onDropItemRequest(event, ref, store);
        }
    }
}
