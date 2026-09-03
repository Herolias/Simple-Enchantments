package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.event.events.ecs.CraftRecipeEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * ECS event system that cancels {@link CraftRecipeEvent.Pre} for recipes the
 * config has disabled: scrolls of disabled enchantments, all scrolls when
 * scroll crafting is off, and the Enchanting / Engraving Table recipes when
 * their crafting toggles are off.
 * <p>
 * {@code CraftRecipeEvent.Pre} extends {@code CancellableEcsEvent} and is
 * dispatched by the crafting manager through
 * {@code componentAccessor.invoke(ref, event)}, which only reaches
 * {@link EntityEventSystem}s registered on the entity store. A plain EventBus
 * registration for this event never fires, which is why this class exists.
 * <p>
 * Register with
 * {@code getEntityStoreRegistry().registerSystem(new CraftRecipeCancelSystem())}.
 */
public class CraftRecipeCancelSystem extends EntityEventSystem<EntityStore, CraftRecipeEvent.Pre> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Translation key (see {@code Server/Languages/en-US/server.lang}). */
    private static final String DISABLED_MESSAGE_KEY = "server.chat.recipe.disabled";

    public CraftRecipeCancelSystem() {
        super(CraftRecipeEvent.Pre.class);
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        // Crafting is invoked on the crafting entity's ref; match every archetype so
        // the cancel applies regardless of what kind of entity is crafting.
        return Archetype.empty();
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull CraftRecipeEvent.Pre event) {
        if (event.isCancelled()) {
            return;
        }

        CraftingRecipe recipe = event.getCraftedRecipe();
        if (!EnchantmentRecipeManager.shouldCancelCraft(recipe)) {
            return;
        }

        event.setCancelled(true);
        LOGGER.atFine().log("Cancelled crafting of disabled recipe %s", recipe.getId());

        PlayerRef playerRef = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
        if (playerRef != null && playerRef.isValid()) {
            playerRef.sendMessage(Message.translation(DISABLED_MESSAGE_KEY).color("#FF5555"));
        }
    }
}
