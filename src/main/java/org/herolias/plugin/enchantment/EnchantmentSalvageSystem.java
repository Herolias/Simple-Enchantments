package org.herolias.plugin.enchantment;

import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Vector3iUtil;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ListTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MaterialTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MoveTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MoveType;
import com.hypixel.hytale.server.core.inventory.transaction.SlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.herolias.plugin.util.ScrollIdHelper;
import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lets enchanted items be salvaged in the Salvager Bench.
 *
 * <p>
 * The vanilla recipe matcher ({@code ItemStack.isEquivalentType}) requires the
 * bench item to have exactly the recipe material's metadata, i.e. none. When an
 * enchanted item enters the bench's input container its metadata is therefore
 * saved (in full, so custom names survive too) and the slot is rewritten with a
 * bare item. What happens afterwards is decided from the container transaction
 * the bench reports:
 * </p>
 * <ul>
 * <li>a {@link MaterialTransaction} means the bench consumed the item
 * (optionally yielding a scroll into the bench output);</li>
 * <li>a {@link MoveTransaction} means a player moved it out again: the saved
 * metadata is written straight back onto the stack in the destination
 * container/slot the transaction names;</li>
 * <li>any other removal (bench broken, item dropped) clears the saved data.</li>
 * </ul>
 *
 * <p>
 * Benches are tracked by identity ({@link ProcessingBenchBlock} does not
 * override {@code equals}, unlike the content-compared item containers), the
 * bench listener registration is kept so it can be unregistered when the bench
 * unloads, and saved metadata lives with the tracked bench (no TTL).
 * </p>
 */
public class EnchantmentSalvageSystem extends EntityEventSystem<EntityStore, InventoryChangeEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String BENCH_ID = "Salvagebench";

    private final EnchantmentManager enchantmentManager;

    /**
     * Benches we listen to, keyed by identity. Values must not strongly reference
     * their key (the listener lambda captures the {@link TrackedBench}, which
     * only holds the bench weakly), otherwise the weak map could never clear.
     */
    private final Map<ProcessingBenchBlock, TrackedBench> trackedBenches = Collections
            .synchronizedMap(new WeakHashMap<>());

    public EnchantmentSalvageSystem(EnchantmentManager enchantmentManager) {
        super(InventoryChangeEvent.class);
        this.enchantmentManager = enchantmentManager;
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    /**
     * No longer used: restores are paired with the bench transaction that moved
     * the item, so player inventory events carry no information for this
     * system. Kept only because the plugin registers this class as an ECS
     * system; see the integration notes for removing that registration.
     */
    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull InventoryChangeEvent event) {
        // intentionally empty
    }

    // ────────────────────────────────────────────────────────────────────
    // Bench tracking
    // ────────────────────────────────────────────────────────────────────

    /**
     * Starts listening to a Salvager Bench's input container (idempotent). Must
     * be called on the bench's world thread.
     */
    public void trackBench(@Nonnull World world, @Nonnull ProcessingBenchBlock bench, @Nonnull Vector3i blockPos) {
        ItemContainer input = bench.getInputContainer();
        if (input == null) {
            return;
        }
        synchronized (trackedBenches) {
            pruneStale(world);
            if (trackedBenches.containsKey(bench)) {
                return;
            }
            TrackedBench tracked = new TrackedBench(bench, world, new Vector3i(blockPos));
            tracked.registration = input.registerChangeEvent(EventPriority.NORMAL,
                    event -> onBenchChange(tracked, event));
            trackedBenches.put(bench, tracked);
            LOGGER.atFine().log("Tracking salvage bench at %s", blockPos);
        }
    }

    /**
     * Unregisters listeners for benches that are gone (GC'd, world unloaded, or no
     * longer the block entity at their position). Only benches in {@code world}
     * can be checked against the chunk store from the caller's thread.
     */
    private void pruneStale(@Nonnull World world) {
        Iterator<Map.Entry<ProcessingBenchBlock, TrackedBench>> it = trackedBenches.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ProcessingBenchBlock, TrackedBench> entry = it.next();
            ProcessingBenchBlock bench = entry.getKey();
            TrackedBench tracked = entry.getValue();
            boolean stale;
            if (bench == null || !tracked.world.isAlive()) {
                stale = true;
            } else if (tracked.world != world) {
                continue;
            } else {
                stale = findBenchAt(world, tracked.blockPos.x, tracked.blockPos.y, tracked.blockPos.z) != bench;
            }
            if (stale) {
                tracked.untrack();
                it.remove();
                LOGGER.atFine().log("Untracked stale salvage bench at %s", tracked.blockPos);
            }
        }
    }

    /**
     * Resolves the processing bench component at a (base) block position, or
     * null. Must be called on the world's thread.
     */
    @Nullable
    static ProcessingBenchBlock findBenchAt(@Nonnull World world, int x, int y, int z) {
        ChunkStore chunkStoreManager = world.getChunkStore();
        Store<ChunkStore> chunkStore = chunkStoreManager.getStore();
        Ref<ChunkStore> sectionRef = chunkStoreManager.getChunkSectionReferenceAtBlock(x, y, z);
        if (sectionRef == null || !sectionRef.isValid()) {
            return null;
        }
        Ref<ChunkStore> blockRef = BlockModule.getBlockEntity(chunkStore, sectionRef, x, y, z);
        if (blockRef == null || !blockRef.isValid()) {
            return null;
        }
        return chunkStore.getComponent(blockRef, ProcessingBenchBlock.getComponentType());
    }

    /** Per-bench listener state. Holds the bench weakly (see {@link #trackedBenches}). */
    private static final class TrackedBench {
        final WeakReference<ProcessingBenchBlock> bench;
        final World world;
        final Vector3i blockPos;
        /** Metadata stripped from the item in each input slot, until it leaves. */
        final Map<Short, BsonDocument> strippedMetadata = new ConcurrentHashMap<>();
        volatile EventRegistration<Void, ItemContainer.ItemContainerChangeEvent> registration;

        TrackedBench(@Nonnull ProcessingBenchBlock bench, @Nonnull World world, @Nonnull Vector3i blockPos) {
            this.bench = new WeakReference<>(bench);
            this.world = world;
            this.blockPos = blockPos;
        }

        void untrack() {
            EventRegistration<Void, ItemContainer.ItemContainerChangeEvent> current = registration;
            if (current != null) {
                current.unregister();
            }
            strippedMetadata.clear();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Bench container transactions
    // ────────────────────────────────────────────────────────────────────

    private void onBenchChange(@Nonnull TrackedBench tracked,
            @Nonnull ItemContainer.ItemContainerChangeEvent event) {
        ProcessingBenchBlock bench = tracked.bench.get();
        if (bench == null) {
            return;
        }
        try {
            process(tracked, bench, event.container(), event.transaction(), false);
        } catch (RuntimeException e) {
            LOGGER.atWarning().withCause(e).log("Salvage bench listener failed at %s", tracked.blockPos);
        }
    }

    private void process(@Nonnull TrackedBench tracked, @Nonnull ProcessingBenchBlock bench,
            @Nonnull ItemContainer container, @Nullable Transaction transaction, boolean consumption) {
        if (transaction == null || !transaction.succeeded()) {
            return;
        }
        if (transaction instanceof MoveTransaction<?> move) {
            handleMove(tracked, container, move);
        } else if (transaction instanceof MaterialTransaction material) {
            // Produced by ItemContainer.removeMaterials, i.e. the bench consuming inputs.
            List<SlotTransaction> slots = new ArrayList<>();
            flatten(material, slots);
            handleSlots(tracked, bench, container, slots, true);
        } else if (transaction instanceof ListTransaction<?> list) {
            for (Transaction child : list.getList()) {
                process(tracked, bench, container, child, consumption);
            }
        } else if (transaction instanceof ItemStackTransaction stack) {
            handleSlots(tracked, bench, container, new ArrayList<>(stack.getSlotTransactions()), consumption);
        } else if (transaction instanceof SlotTransaction slot) {
            handleSlots(tracked, bench, container, List.of(slot), consumption);
        }
    }

    /** Collects the successful slot-level transactions nested in {@code transaction}. */
    private static void flatten(@Nullable Transaction transaction, @Nonnull List<SlotTransaction> out) {
        if (transaction == null || !transaction.succeeded()) {
            return;
        }
        if (transaction instanceof SlotTransaction slot) {
            out.add(slot);
        } else if (transaction instanceof ItemStackTransaction stack) {
            out.addAll(stack.getSlotTransactions());
        } else if (transaction instanceof ListTransaction<?> list) {
            for (Transaction child : list.getList()) {
                flatten(child, out);
            }
        }
    }

    /**
     * Slot changes on the bench that are not part of a move: strip enchanted
     * arrivals; on departures either yield a scroll (bench consumption) or just
     * forget the saved data (no destination to restore into).
     */
    private void handleSlots(@Nonnull TrackedBench tracked, @Nonnull ProcessingBenchBlock bench,
            @Nonnull ItemContainer container, @Nonnull List<SlotTransaction> slots, boolean consumption) {
        for (SlotTransaction slot : slots) {
            BsonDocument saved = takeSavedIfDeparted(tracked, slot);
            if (saved == null) {
                continue;
            }
            if (consumption) {
                if (salvagerYieldsScroll()) {
                    yieldScroll(bench, tracked.world, tracked.blockPos, saved);
                }
            } else {
                LOGGER.atFine().log(
                        "Stripped item left salvage bench slot %d without a paired destination; enchantments dropped",
                        slot.getSlot());
            }
        }
        for (SlotTransaction slot : slots) {
            stripIfEnchanted(tracked, container, slot.getSlot());
        }
    }

    /**
     * A move between the bench and another container. Whatever left the bench is
     * restored in the destination slot(s) the transaction names; whatever
     * arrived is stripped.
     */
    private void handleMove(@Nonnull TrackedBench tracked, @Nonnull ItemContainer benchContainer,
            @Nonnull MoveTransaction<?> move) {
        Transaction benchSide;
        Transaction otherSide;
        if (move.getMoveType() == MoveType.MOVE_FROM_SELF) {
            benchSide = move.getRemoveTransaction();
            otherSide = move.getAddTransaction();
        } else {
            benchSide = move.getAddTransaction();
            otherSide = move.getRemoveTransaction();
        }
        ItemContainer otherContainer = move.getOtherContainer();

        List<SlotTransaction> benchSlots = new ArrayList<>();
        flatten(benchSide, benchSlots);
        List<SlotTransaction> otherSlots = new ArrayList<>();
        flatten(otherSide, otherSlots);

        for (SlotTransaction slot : benchSlots) {
            ItemStack before = slot.getSlotBefore();
            BsonDocument saved = takeSavedIfDeparted(tracked, slot);
            if (saved == null || before == null) {
                continue;
            }
            int restored = restoreInto(otherContainer, otherSlots, before.getItemId(), saved);
            if (restored == 0) {
                LOGGER.atFine().log("Could not locate destination for stripped %s leaving salvage bench slot %d",
                        before.getItemId(), slot.getSlot());
            }
        }
        for (SlotTransaction slot : benchSlots) {
            stripIfEnchanted(tracked, benchContainer, slot.getSlot());
        }
    }

    /**
     * If the slot's item left (removed, replaced by another item, or reduced),
     * returns the metadata saved for it, evicting it unless part of the stack
     * remains. Returns null otherwise.
     */
    @Nullable
    private static BsonDocument takeSavedIfDeparted(@Nonnull TrackedBench tracked, @Nonnull SlotTransaction slot) {
        if (!slot.succeeded()) {
            return null;
        }
        ItemStack before = slot.getSlotBefore();
        ItemStack after = slot.getSlotAfter();
        if (before == null || before.isEmpty()) {
            return null;
        }
        boolean gone = after == null || after.isEmpty() || !after.getItemId().equals(before.getItemId());
        boolean reduced = !gone && after.getQuantity() < before.getQuantity();
        if (!gone && !reduced) {
            return null;
        }
        short index = slot.getSlot();
        return gone ? tracked.strippedMetadata.remove(index) : tracked.strippedMetadata.get(index);
    }

    /**
     * Writes {@code saved} back onto the stack(s) of {@code itemId} that the
     * destination side of a move reports. Returns the number of slots restored.
     */
    private int restoreInto(@Nonnull ItemContainer destination, @Nonnull List<SlotTransaction> destinationSlots,
            @Nonnull String itemId, @Nonnull BsonDocument saved) {
        int restored = 0;
        for (SlotTransaction slot : destinationSlots) {
            ItemStack landed = slot.getSlotAfter();
            if (landed == null || landed.isEmpty() || !landed.getItemId().equals(itemId)) {
                continue;
            }
            if (EnchantmentData.hasAny(enchantmentManager.readEnchantmentDocument(landed))) {
                continue; // already enchanted; nothing to restore here
            }
            ItemStack live = destination.getItemStack(slot.getSlot());
            if (live == null || live.isEmpty() || !live.isEquivalentType(landed)) {
                continue;
            }
            ItemStack restoredStack = withRestoredMetadata(live, saved);
            if (destination.replaceItemStackInSlot(slot.getSlot(), live, restoredStack).succeeded()) {
                restored++;
            }
        }
        return restored;
    }

    /**
     * Saves and strips the metadata of the item currently in {@code slot} if it
     * is enchanted. Reads the live slot so a re-dispatched event for the same
     * transaction is a no-op.
     */
    private void stripIfEnchanted(@Nonnull TrackedBench tracked, @Nonnull ItemContainer container, short slot) {
        ItemStack live = container.getItemStack(slot);
        if (live == null || live.isEmpty()) {
            return;
        }
        BsonDocument metadata = live.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        if (!EnchantmentData.hasAny(enchantmentManager.readEnchantmentDocument(live))) {
            return;
        }
        tracked.strippedMetadata.put(slot, metadata.clone());
        // Full strip: the recipe matcher needs metadata to be exactly null. The
        // saved document is restored verbatim when the item leaves.
        ItemStack stripped = live.withMetadata((BsonDocument) null);
        if (!container.replaceItemStackInSlot(slot, live, stripped).succeeded()) {
            tracked.strippedMetadata.remove(slot);
        }
    }

    /** Re-applies saved metadata; keys the stack acquired meanwhile are kept. */
    @Nonnull
    private static ItemStack withRestoredMetadata(@Nonnull ItemStack stack, @Nonnull BsonDocument saved) {
        BsonDocument merged = saved.clone();
        BsonDocument current = stack.getMetadata();
        if (current != null) {
            for (Map.Entry<String, BsonValue> entry : current.entrySet()) {
                merged.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        return stack.withMetadata(merged);
    }

    // ────────────────────────────────────────────────────────────────────
    // Scroll yield
    // ────────────────────────────────────────────────────────────────────

    private static boolean salvagerYieldsScroll() {
        return org.herolias.plugin.SimpleEnchanting.getInstance().getConfigManager().getConfig().salvagerYieldsScroll;
    }

    /** Enchantments contained in a saved metadata document. */
    @Nonnull
    private static EnchantmentData enchantmentsOf(@Nonnull BsonDocument savedMetadata) {
        BsonValue value = savedMetadata.get(EnchantmentData.METADATA_KEY);
        if (value == null) {
            return EnchantmentData.EMPTY;
        }
        if (value.isDocument()) {
            return EnchantmentData.fromBson(value.asDocument());
        }
        if (value.isString()) {
            return EnchantmentData.deserialize(value.asString().getValue());
        }
        return EnchantmentData.EMPTY;
    }

    private void yieldScroll(@Nonnull ProcessingBenchBlock bench, @Nonnull World world, @Nonnull Vector3i blockPos,
            @Nonnull BsonDocument savedMeta) {
        EnchantmentData data = enchantmentsOf(savedMeta);
        if (data.isEmpty())
            return;

        EnchantmentType bestType = null;
        int bestLevel = -1;
        boolean bestLegendary = false;

        List<EnchantmentType> candidates = new ArrayList<>();

        for (Map.Entry<EnchantmentType, Integer> entry : data.getAllEnchantments().entrySet()) {
            EnchantmentType type = entry.getKey();
            int level = entry.getValue();
            boolean isLegendary = type.isLegendary();

            if (bestType == null) {
                bestType = type;
                bestLevel = level;
                bestLegendary = isLegendary;
                candidates.add(type);
                continue;
            }

            if (isLegendary && !bestLegendary) {
                bestType = type;
                bestLevel = level;
                bestLegendary = true;
                candidates.clear();
                candidates.add(type);
            } else if (isLegendary == bestLegendary) {
                if (level > bestLevel) {
                    bestType = type;
                    bestLevel = level;
                    candidates.clear();
                    candidates.add(type);
                } else if (level == bestLevel) {
                    candidates.add(type);
                }
            }
        }

        if (candidates.isEmpty())
            return;

        EnchantmentType chosenType = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        int chosenLevel = data.getLevel(chosenType);
        if (chosenLevel < 1)
            return;

        try {
            ItemStack scrollStack;

            if (chosenLevel > chosenType.getMaxLevel()) {
                // Level exceeds max — create a Custom Scroll with the enchantment in metadata
                EnchantmentData customScrollData = new EnchantmentData();
                customScrollData.addEnchantment(chosenType, chosenLevel);
                scrollStack = NativeTooltipManager.withEnchantments(new ItemStack("Scroll_Custom", 1),
                        customScrollData, enchantmentManager);
            } else {
                String scrollId = ScrollIdHelper.getScrollItemId(chosenType, chosenLevel);
                scrollStack = new ItemStack(scrollId, 1);
            }

            if (!scrollStack.isValid() || scrollStack.isEmpty()) {
                return;
            }

            boolean placed = false;
            ItemContainer output = bench.getOutputContainer();
            if (output != null) {
                ListTransaction<ItemStackTransaction> tx = output.addItemStacks(
                        Collections.singletonList(scrollStack), false, false, false);
                placed = tx.succeeded() && tx.getList().stream()
                        .noneMatch(t -> t.getRemainder() != null && !t.getRemainder().isEmpty());
            }
            if (!placed) {
                dropAtBench(world, blockPos, scrollStack);
            }
        } catch (RuntimeException e) {
            LOGGER.atSevere().withCause(e).log("Failed to yield scroll from salvager at %s", blockPos);
        }
    }

    /** Fallback when the bench output is full: drop the scroll on top of the bench. */
    private static void dropAtBench(@Nonnull World world, @Nonnull Vector3i blockPos, @Nonnull ItemStack stack) {
        if (!world.isAlive()) {
            return;
        }
        Store<EntityStore> entityStore = world.getEntityStore().getStore();
        Vector3d dropPosition = Vector3iUtil.toVector3d(blockPos).add(0.5, 1.0, 0.5);
        Holder<EntityStore>[] itemEntityHolders = ItemComponent.generateItemDrops(entityStore,
                Collections.singletonList(stack), dropPosition, new Rotation3f());
        if (itemEntityHolders.length == 0) {
            return;
        }
        try {
            world.execute(() -> entityStore.addEntities(itemEntityHolders, AddReason.SPAWN));
        } catch (RuntimeException e) {
            LOGGER.atWarning().withCause(e).log("Could not drop salvaged scroll at %s", blockPos);
        }
    }
}
