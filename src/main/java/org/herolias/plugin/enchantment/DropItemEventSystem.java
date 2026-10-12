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
import com.hypixel.hytale.server.core.event.events.ecs.DropItemEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Forwards {@link DropItemEvent.PlayerRequest} to the refund systems so they
 * can tell a manual drop apart from item consumption (see
 * {@link AbstractRefundSystem#onDropItemRequest}).
 */
public class DropItemEventSystem extends EntityEventSystem<EntityStore, DropItemEvent.PlayerRequest> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final EnchantmentEternalShotSystem eternalShotSystem;
    @Nullable
    private final EnchantmentElementalHeartSystem elementalHeartSystem;
    private final Query<EntityStore> query = Query.and(Player.getComponentType(), UUIDComponent.getComponentType());

    public DropItemEventSystem(@Nonnull EnchantmentEternalShotSystem eternalShotSystem,
            @Nullable EnchantmentElementalHeartSystem elementalHeartSystem) {
        super(DropItemEvent.PlayerRequest.class);
        this.eternalShotSystem = eternalShotSystem;
        this.elementalHeartSystem = elementalHeartSystem;
        LOGGER.atInfo().log("DropItemEventSystem initialized");
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
            @Nonnull DropItemEvent.PlayerRequest event) {
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        eternalShotSystem.onDropItemRequest(event, ref, store);
        if (elementalHeartSystem != null) {
            elementalHeartSystem.onDropItemRequest(event, ref, store);
        }
    }
}
