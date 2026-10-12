package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemMergeSystem;
import com.hypixel.hytale.server.core.modules.entity.item.ItemPrePhysicsSystem;
import com.hypixel.hytale.server.core.modules.entity.item.ItemSystems;
import com.hypixel.hytale.server.core.modules.entity.item.PickupItemComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerItemEntityPickupSystem;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rewrites vanilla ore drops that were authorized by EnchantmentSmeltingSystem.
 * This runs before item visibility, pickup, merging, and physics so clients only
 * see the cooked result while other mods still receive the uncancelled break
 * event.
 *
 * <p>
 * A pending conversion only ever applies to item entities that were spawned by
 * the break it was recorded for:
 * </p>
 * <ul>
 * <li>the entity's {@link NetworkId} must be newer than the id watermark taken
 * when the break was recorded (network ids are handed out from a monotonic
 * counter when an item entity is added, so anything already lying around, or
 * dropped by a player before the break, is excluded);</li>
 * <li>the item id must be one of the drops the block's static drop list can
 * produce;</li>
 * <li>at most the number of convertible stacks the block can produce is
 * converted, after which the pending is consumed;</li>
 * <li>and the usual same-world, distance and TTL checks apply.</li>
 * </ul>
 */
public class EnchantmentSmeltingDropConversionSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final long PENDING_TTL_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final double MAX_DROP_DISTANCE_SQUARED = 1.25 * 1.25;

    private static final Query<EntityStore> QUERY = Query.and(
            ItemComponent.getComponentType(),
            TransformComponent.getComponentType(),
            NetworkId.getComponentType(),
            Query.not(PickupItemComponent.getComponentType()));

    private final EnchantmentManager enchantmentManager;
    private final SmeltingRecipeRegistry smeltingRecipeRegistry;
    private final ConcurrentLinkedQueue<PendingConversion> pendingConversions = new ConcurrentLinkedQueue<>();
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.BEFORE, ItemSystems.TrackerSystem.class),
            new SystemDependency<>(Order.BEFORE, ItemMergeSystem.class),
            new SystemDependency<>(Order.BEFORE, PlayerItemEntityPickupSystem.class),
            new SystemDependency<>(Order.BEFORE, ItemPrePhysicsSystem.class));

    public EnchantmentSmeltingDropConversionSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        this.smeltingRecipeRegistry = enchantmentManager.getSmeltingRecipeRegistry();
        LOGGER.atInfo().log("EnchantmentSmeltingDropConversionSystem initialized");
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    @Override
    @Nonnull
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    /**
     * Records that the drops of the block about to break at {@code targetBlock}
     * should be smelted.
     *
     * @param store                 the entity store of the world the block is in
     * @param targetBlock           the broken block position
     * @param playerRef             the breaking player, for the activation event
     * @param tool                  the tool used
     * @param smeltingLevel         the Smelting level on the tool
     * @param convertibleItemIds    item ids the block's drop list can produce that
     *                              have a smelting recipe
     * @param maxConvertibleStacks  upper bound on the number of convertible stacks
     *                              this break can spawn
     */
    public void recordPending(@Nonnull Store<EntityStore> store, @Nonnull Vector3i targetBlock,
            @Nullable PlayerRef playerRef, @Nonnull ItemStack tool, int smeltingLevel,
            @Nonnull Set<String> convertibleItemIds, int maxConvertibleStacks) {
        if (convertibleItemIds.isEmpty() || maxConvertibleStacks <= 0) {
            return;
        }
        long now = System.nanoTime();
        cleanupExpired(now);
        EntityStore entityStore = store.getExternalData();
        // Every entity added from here on receives a network id above this one.
        int networkIdWatermark = entityStore.takeNextNetworkId();
        Vector3d dropPosition = new Vector3d(targetBlock.x() + 0.5, targetBlock.y(), targetBlock.z() + 0.5);
        pendingConversions.add(new PendingConversion(entityStore.getWorld(), dropPosition, playerRef, tool,
                smeltingLevel, convertibleItemIds, maxConvertibleStacks, networkIdWatermark,
                now + PENDING_TTL_NANOS));
    }

    @Override
    public void tick(float dt, int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (pendingConversions.isEmpty()) {
            return;
        }

        ItemComponent itemComponent = archetypeChunk.getComponent(index, ItemComponent.getComponentType());
        TransformComponent transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        NetworkId networkId = archetypeChunk.getComponent(index, NetworkId.getComponentType());
        if (itemComponent == null || transform == null || networkId == null) {
            return;
        }

        ItemStack itemStack = itemComponent.getItemStack();
        if (itemStack == null || itemStack.isEmpty()) {
            return;
        }

        long now = System.nanoTime();
        cleanupExpired(now);

        World world = store.getExternalData().getWorld();
        Vector3d itemPosition = transform.getPosition();
        String itemId = itemStack.getItemId();

        for (PendingConversion pending : pendingConversions) {
            if (!pending.matches(world, itemPosition, networkId.getId(), itemId)) {
                continue;
            }

            // Only resolve the recipe once a pending actually claims this entity.
            SmeltingRecipeRegistry.SmeltingRecipe recipe = smeltingRecipeRegistry.getRecipe(itemStack);
            if (recipe == null) {
                return;
            }
            ItemStack output = recipe.createOutput(itemStack.getQuantity());
            if (output == null || output.isEmpty() || output.getItemId().equals(itemId)) {
                return;
            }

            if (!pending.claimStack()) {
                // Raced to exhaustion; drop it and let another pending (if any) try.
                pendingConversions.remove(pending);
                continue;
            }

            itemComponent.setItemStack(output);
            if (pending.isExhausted()) {
                pendingConversions.remove(pending);
            }
            if (pending.markActivated()) {
                EnchantmentEventHelper.fireActivated(pending.playerRef, pending.tool, EnchantmentType.SMELTING,
                        pending.smeltingLevel);
            }
            return;
        }
    }

    private void cleanupExpired(long nowNanos) {
        for (PendingConversion pending : pendingConversions) {
            if (pending.isExpired(nowNanos)) {
                pendingConversions.remove(pending);
            }
        }
    }

    private static final class PendingConversion {
        private final World world;
        private final Vector3d dropPosition;
        private final PlayerRef playerRef;
        private final ItemStack tool;
        private final int smeltingLevel;
        private final Set<String> convertibleItemIds;
        private final AtomicInteger remainingStacks;
        private final int networkIdWatermark;
        private final long expiresAtNanos;
        private final AtomicBoolean activated = new AtomicBoolean(false);

        private PendingConversion(@Nonnull World world, @Nonnull Vector3d dropPosition,
                @Nullable PlayerRef playerRef, @Nonnull ItemStack tool, int smeltingLevel,
                @Nonnull Set<String> convertibleItemIds, int maxConvertibleStacks, int networkIdWatermark,
                long expiresAtNanos) {
            this.world = world;
            this.dropPosition = dropPosition;
            this.playerRef = playerRef;
            this.tool = tool;
            this.smeltingLevel = smeltingLevel;
            this.convertibleItemIds = convertibleItemIds;
            this.remainingStacks = new AtomicInteger(maxConvertibleStacks);
            this.networkIdWatermark = networkIdWatermark;
            this.expiresAtNanos = expiresAtNanos;
        }

        private boolean isExpired(long nowNanos) {
            return nowNanos > expiresAtNanos;
        }

        private boolean isExhausted() {
            return remainingStacks.get() <= 0;
        }

        private boolean matches(@Nonnull World currentWorld, @Nonnull Vector3d itemPosition, int entityNetworkId,
                @Nonnull String itemId) {
            return world == currentWorld
                    && entityNetworkId > networkIdWatermark
                    && remainingStacks.get() > 0
                    && convertibleItemIds.contains(itemId)
                    && dropPosition.distanceSquared(itemPosition) <= MAX_DROP_DISTANCE_SQUARED;
        }

        /** Reserves one convertible stack; false if the budget is already used up. */
        private boolean claimStack() {
            while (true) {
                int current = remainingStacks.get();
                if (current <= 0) {
                    return false;
                }
                if (remainingStacks.compareAndSet(current, current - 1)) {
                    return true;
                }
            }
        }

        private boolean markActivated() {
            return activated.compareAndSet(false, true);
        }
    }
}
