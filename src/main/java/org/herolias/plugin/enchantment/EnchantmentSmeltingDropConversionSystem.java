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

/**
 * Rewrites vanilla ore drops that were authorized by EnchantmentSmeltingSystem.
 * This runs before item visibility, pickup, merging, and physics so clients only
 * see the cooked result while other mods still receive the uncancelled break
 * event.
 */
public class EnchantmentSmeltingDropConversionSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final long PENDING_TTL_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final double MAX_DROP_DISTANCE_SQUARED = 1.25 * 1.25;

    private static final Query<EntityStore> QUERY = Query.and(
            ItemComponent.getComponentType(),
            TransformComponent.getComponentType(),
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

    public void recordPending(@Nonnull World world, @Nonnull Vector3i targetBlock,
            @Nullable PlayerRef playerRef, @Nonnull ItemStack tool) {
        long now = System.nanoTime();
        cleanupExpired(now);
        Vector3d dropPosition = new Vector3d(targetBlock.x() + 0.5, targetBlock.y(), targetBlock.z() + 0.5);
        pendingConversions.add(new PendingConversion(world, dropPosition, playerRef, tool,
                now + PENDING_TTL_NANOS));
    }

    @Override
    public void tick(float dt, int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        ItemComponent itemComponent = archetypeChunk.getComponent(index, ItemComponent.getComponentType());
        TransformComponent transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        if (itemComponent == null || transform == null) {
            return;
        }

        ItemStack itemStack = itemComponent.getItemStack();
        if (itemStack == null || itemStack.isEmpty()) {
            return;
        }

        SmeltingRecipeRegistry.SmeltingRecipe recipe = smeltingRecipeRegistry.getRecipe(itemStack);
        if (recipe == null) {
            return;
        }

        ItemStack output = recipe.createOutput(itemStack.getQuantity());
        if (output == null || output.isEmpty() || output.getItemId().equals(itemStack.getItemId())) {
            return;
        }

        long now = System.nanoTime();
        World world = store.getExternalData().getWorld();
        Vector3d itemPosition = transform.getPosition();

        cleanupExpired(now);

        for (PendingConversion pending : pendingConversions) {
            if (!pending.matches(world, itemPosition)) {
                continue;
            }

            itemComponent.setItemStack(output);
            if (pending.markActivated()) {
                EnchantmentEventHelper.fireActivated(pending.playerRef, pending.tool, EnchantmentType.SMELTING,
                        enchantmentManager.getEnchantmentLevel(pending.tool, EnchantmentType.SMELTING));
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
        private final long expiresAtNanos;
        private final AtomicBoolean activated = new AtomicBoolean(false);

        private PendingConversion(@Nonnull World world, @Nonnull Vector3d dropPosition,
                @Nullable PlayerRef playerRef, @Nonnull ItemStack tool, long expiresAtNanos) {
            this.world = world;
            this.dropPosition = dropPosition;
            this.playerRef = playerRef;
            this.tool = tool;
            this.expiresAtNanos = expiresAtNanos;
        }

        private boolean isExpired(long nowNanos) {
            return nowNanos > expiresAtNanos;
        }

        private boolean matches(@Nonnull World currentWorld, @Nonnull Vector3d itemPosition) {
            return world == currentWorld && dropPosition.distanceSquared(itemPosition) <= MAX_DROP_DISTANCE_SQUARED;
        }

        private boolean markActivated() {
            return activated.compareAndSet(false, true);
        }
    }
}
