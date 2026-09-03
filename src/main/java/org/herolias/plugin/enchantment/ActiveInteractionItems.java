package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Set;

/**
 * Resolves the item driving an entity's currently running interaction.
 * <p>
 * Stat drains caused by status effects and by interactions are
 * indistinguishable in the stat map (both are flagged predictable), so systems
 * that refund a cost must first establish that a player-driven interaction of
 * the relevant kind is still running this tick. One walk over the interaction
 * chains yields both that fact and the item used, so callers evaluate the
 * item's enchantments exactly once.
 */
final class ActiveInteractionItems {

    /** Interaction types that represent an item being actively used by the player. */
    static final Set<InteractionType> ITEM_USE_TYPES = EnumSet.of(
            InteractionType.Primary, InteractionType.Secondary,
            InteractionType.Ability1, InteractionType.Ability2, InteractionType.Ability3,
            InteractionType.Use, InteractionType.Pick);

    private ActiveInteractionItems() {
    }

    /**
     * Held item of the newest chain of one of the given types whose server
     * state is still {@link InteractionState#NotFinished}, or null when no such
     * chain is running.
     */
    @Nullable
    static ItemStack heldItemOfActiveChain(@Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Set<InteractionType> types) {
        InteractionManager interactionManager = accessor.getComponent(ref,
                InteractionModule.get().getInteractionManagerComponent());
        if (interactionManager == null) {
            return null;
        }
        long newestTimestamp = Long.MIN_VALUE;
        ItemStack newest = null;
        for (InteractionChain chain : interactionManager.getChains().values()) {
            if (chain == null || chain.getServerState() != InteractionState.NotFinished
                    || !types.contains(chain.getType())) {
                continue;
            }
            InteractionContext context = chain.getContext();
            if (context == null) {
                continue;
            }
            ItemStack heldItem = context.getHeldItem();
            if (ItemStack.isEmpty(heldItem)) {
                continue;
            }
            if (chain.getTimestamp() > newestTimestamp) {
                newestTimestamp = chain.getTimestamp();
                newest = heldItem;
            }
        }
        return newest;
    }

    /**
     * True when a chain of one of the given types is running with the given
     * item (compared by item id) in hand.
     */
    static boolean isUsingItem(@Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Set<InteractionType> types,
            @Nonnull ItemStack item) {
        ItemStack active = heldItemOfActiveChain(ref, accessor, types);
        return active != null && active.getItemId().equals(item.getItemId());
    }
}
