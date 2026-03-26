package org.herolias.plugin.enchantment;

import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryChangeEvent;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ListTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MoveTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MoveType;
import com.hypixel.hytale.server.core.inventory.transaction.SlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import org.bson.BsonDocument;
import org.herolias.plugin.util.ScrollIdHelper;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * System to handle salvaging of enchanted items.
 * 
 * It strips metadata from items when they are placed in the Salvager Bench so
 * they match the recipe.
 * If the item is removed by the player before processing, the metadata is
 * restored.
 */
public class EnchantmentSalvageSystem extends EntityEventSystem<EntityStore, InventoryChangeEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String BENCH_ID = "Salvagebench";

    private final EnchantmentManager enchantmentManager;

    // Tracks active sessions for players.
    // Key: Player UUID
    // Value: Session Data
    private final Map<UUID, SalvageSession> sessions = new ConcurrentHashMap<>();
    /** Timeout for stale sessions (30 seconds). */
    private static final long SESSION_TIMEOUT_MS = 30_000;

    public EnchantmentSalvageSystem(EnchantmentManager enchantmentManager) {
        super(InventoryChangeEvent.class);
        this.enchantmentManager = enchantmentManager;
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    /**
     * Starts a salvage session for the player and bench.
     * Hooks into the bench's container to monitor item changes.
     */
    public void startSession(Player player, ProcessingBenchBlock bench, Vector3i blockPos) {
        if (player.getWorld() == null || player.getReference() == null)
            return;
        com.hypixel.hytale.server.core.entity.UUIDComponent uComp = player.getWorld().getEntityStore().getStore()
                .getComponent(player.getReference(),
                        com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
        if (uComp == null)
            return;
        UUID playerId = uComp.getUuid();

        if (sessions.containsKey(playerId)) {
            return;
        }

        SalvageSession session = new SalvageSession(playerId, blockPos);
        sessions.put(playerId, session);

        if (!trackedContainers.containsKey(bench.getItemContainer())) {
            LOGGER.atInfo().log("Registering change listener for bench container.");
            com.hypixel.hytale.server.core.universe.world.World world = player.getWorld();
            bench.getItemContainer().registerChangeEvent(EventPriority.NORMAL, (event) -> {
                this.onBenchItemChange(bench, world, blockPos, event);
            });
            trackedContainers.put(bench.getItemContainer(), true);
        }
    }

    // Keep track of containers we are already listening to
    private final Map<ItemContainer, Boolean> trackedContainers = java.util.Collections
            .synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * Handles item changes in the Salvager Bench.
     */
    private void onBenchItemChange(ProcessingBenchBlock bench, com.hypixel.hytale.server.core.universe.world.World world, Vector3i blockPos, ItemContainer.ItemContainerChangeEvent event) {
        processBenchTransaction(bench, world, blockPos, event.container(), event.transaction());
    }

    private void processBenchTransaction(ProcessingBenchBlock bench, com.hypixel.hytale.server.core.universe.world.World world, Vector3i blockPos, ItemContainer container, Transaction transaction) {
        if (!transaction.succeeded()) {
            return;
        }

        if (transaction instanceof SlotTransaction) {
            handleBenchSlotChange(bench, world, blockPos, container, (SlotTransaction) transaction);
        } else if (transaction instanceof ListTransaction) {
            ListTransaction<?> listTransaction = (ListTransaction<?>) transaction;
            for (Object child : listTransaction.getList()) {
                if (child instanceof Transaction) {
                    processBenchTransaction(bench, world, blockPos, container, (Transaction) child);
                }
            }
        } else if (transaction instanceof MoveTransaction) {
            MoveTransaction<?> moveTx = (MoveTransaction<?>) transaction;
            // MoveTransaction contains both add and remove parts.
            // We care about what happened TO THIS container.
            if (moveTx.getMoveType() == MoveType.MOVE_TO_SELF) {
                // Item matched TO this container (Add)
                if (moveTx.getAddTransaction() != null) {
                    processBenchTransaction(bench, world, blockPos, container, moveTx.getAddTransaction());
                }
            } else if (moveTx.getMoveType() == MoveType.MOVE_FROM_SELF) {
                // Item matched FROM this container (Remove)
                if (moveTx.getRemoveTransaction() != null) {
                    processBenchTransaction(bench, world, blockPos, container, moveTx.getRemoveTransaction());
                }
            }
        } else if (transaction instanceof ItemStackTransaction) {
            ItemStackTransaction stackTx = (ItemStackTransaction) transaction;
            for (Transaction child : stackTx.getSlotTransactions()) {
                processBenchTransaction(bench, world, blockPos, container, child);
            }
        }
    }

    private void handleBenchSlotChange(ProcessingBenchBlock bench, com.hypixel.hytale.server.core.universe.world.World world, Vector3i blockPos, ItemContainer container,
            SlotTransaction transaction) {
        ItemStack newItem = transaction.getSlotAfter();
        ItemStack oldItem = transaction.getSlotBefore();
        short slot = transaction.getSlot();

        // Handling ADDITION of Enchanted Item
        if (newItem != null && !newItem.isEmpty()) {
            if (hasEnchantments(newItem)) {
                // Save the data
                saveEnchantmentData(blockPos, slot, newItem);

                // Strip the item - removal of ALL metadata (preserves durability)
                ItemStack stripped = newItem.withMetadata((BsonDocument) null);

                // Update the slot with the stripped item.
                container.setItemStackForSlot(slot, stripped);
            }
        }

        // Handling REMOVAL of Item (or reduction in quantity/replacement)
        if (oldItem != null && !oldItem.isEmpty()) {
            boolean removed = newItem == null || newItem.isEmpty();
            boolean quantityReduced = !removed && newItem.getQuantity() < oldItem.getQuantity();

            if (removed || quantityReduced) {
                BsonDocument savedMeta = getSavedEnchantmentData(blockPos, slot);
                if (savedMeta != null) {
                    if (isProcessedByBench()) {
                        if (removed && org.herolias.plugin.SimpleEnchanting.getInstance().getConfigManager()
                                .getConfig().salvagerYieldsScroll) {
                            yieldScroll(bench, world, blockPos, savedMeta);
                        }
                    } else {
                        queueGlobalRestore(oldItem.getItemId(), savedMeta);
                    }

                    if (removed) {
                        clearSavedEnchantmentData(blockPos, slot);
                    }
                }
            }
        }
    }

    private boolean hasEnchantments(ItemStack item) {
        return !enchantmentManager.getEnchantmentsFromItem(item).isEmpty();
    }

    // --- Storage for Bench Slots (M-3: now has timestamps for leak prevention) ---

    private static class TimestampedMeta {
        final BsonDocument metadata;
        final long timestamp;

        TimestampedMeta(BsonDocument metadata) {
            this.metadata = metadata;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private final Map<String, TimestampedMeta> benchSlotStorage = new ConcurrentHashMap<>();
    /** Timeout for stale bench slot entries (60 seconds). */
    private static final long BENCH_SLOT_TIMEOUT_MS = 60_000;

    private String getSlotKey(Vector3i blockPos, short slot) {
        return blockPos.x + "," + blockPos.y + "," + blockPos.z + ":" + slot;
    }

    private void saveEnchantmentData(Vector3i blockPos, short slot, ItemStack item) {
        // Save ALL metadata to preserve names, etc. and to be sure we strip everything
        // that blocks the recipe
        BsonDocument data = item.getMetadata();
        if (data != null) {
            benchSlotStorage.put(getSlotKey(blockPos, slot), new TimestampedMeta(data));
        }
    }

    private BsonDocument getSavedEnchantmentData(Vector3i blockPos, short slot) {
        TimestampedMeta entry = benchSlotStorage.get(getSlotKey(blockPos, slot));
        return entry != null ? entry.metadata : null;
    }

    private void clearSavedEnchantmentData(Vector3i blockPos, short slot) {
        benchSlotStorage.remove(getSlotKey(blockPos, slot));
    }

    /**
     * Periodically evict stale bench slot entries that survived beyond the timeout.
     */
    private void cleanupStaleBenchSlots() {
        long now = System.currentTimeMillis();
        benchSlotStorage.entrySet().removeIf(e -> (now - e.getValue().timestamp) > BENCH_SLOT_TIMEOUT_MS);
    }

    // --- Global Restore Queue ---

    private static class FloatingRestore {
        final String itemId;
        final BsonDocument metadata;
        final long timestamp;

        public FloatingRestore(String itemId, BsonDocument metadata) {
            this.itemId = itemId;
            this.metadata = metadata;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private final List<FloatingRestore> floatingRestores = new ArrayList<>();

    private void queueGlobalRestore(String itemId, BsonDocument meta) {
        synchronized (floatingRestores) {
            floatingRestores.add(new FloatingRestore(itemId, meta));
        }
    }

    /**
     * Listens for player inventory changes to catch when they retrieve a stripped
     * item.
     */
    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk, @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull InventoryChangeEvent event) {
        LivingEntity entity = (LivingEntity) EntityUtils.getEntity(index, archetypeChunk);
        if (!(entity instanceof Player)) {
            return;
        }

        processInventoryTransaction(event.getItemContainer(), event.getTransaction());
    }

    private void processInventoryTransaction(ItemContainer container, Transaction transaction) {
        if (!transaction.succeeded()) {
            return;
        }

        if (transaction instanceof SlotTransaction) {
            handleInventorySlotChange(container, (SlotTransaction) transaction);
        } else if (transaction instanceof ListTransaction) {
            ListTransaction<?> listTransaction = (ListTransaction<?>) transaction;
            for (Object child : listTransaction.getList()) {
                if (child instanceof Transaction) {
                    processInventoryTransaction(container, (Transaction) child);
                }
            }
        } else if (transaction instanceof MoveTransaction) {
            MoveTransaction<?> moveTx = (MoveTransaction<?>) transaction;
            if (moveTx.getMoveType() == MoveType.MOVE_TO_SELF) {
                if (moveTx.getAddTransaction() != null) {
                    processInventoryTransaction(container, moveTx.getAddTransaction());
                }
            } else if (moveTx.getMoveType() == MoveType.MOVE_FROM_SELF) {
                if (moveTx.getRemoveTransaction() != null) {
                    processInventoryTransaction(container, moveTx.getRemoveTransaction());
                }
            }
        } else if (transaction instanceof ItemStackTransaction) {
            ItemStackTransaction stackTx = (ItemStackTransaction) transaction;
            for (Transaction child : stackTx.getSlotTransactions()) {
                processInventoryTransaction(container, child);
            }
        }
    }

    private void handleInventorySlotChange(ItemContainer container, SlotTransaction transaction) {
        ItemStack newItem = transaction.getSlotAfter();
        ItemStack oldItem = transaction.getSlotBefore();

        if (newItem == null || newItem.isEmpty()) {
            return;
        }

        if (hasEnchantments(newItem)) {
            return;
        }

        synchronized (floatingRestores) {
            long now = System.currentTimeMillis();
            floatingRestores.removeIf(r -> (now - r.timestamp) > 500); // 0.5 sec expiration

            if (floatingRestores.isEmpty()) {
                return;
            }

            Iterator<FloatingRestore> it = floatingRestores.iterator();
            while (it.hasNext()) {
                FloatingRestore r = it.next();
                if (r.itemId.equals(newItem.getItemId())) {
                    // Restore ALL metadata
                    ItemStack restored = newItem.withMetadata(r.metadata);
                    it.remove();
                    container.setItemStackForSlot(transaction.getSlot(), restored);
                    break;
                }
            }
        }

        // Periodically clean up stale bench slot and session entries
        cleanupStaleBenchSlots();
        cleanupStaleSessions();
    }

    /**
     * Determines whether the current item change originates from bench processing
     * by checking the call stack for ProcessingBenchBlock.tick().
     *
     * This only fires on bench item changes (not per-tick), so the performance
     * impact is acceptable. Alternative approaches (ThreadLocal, state heuristic)
     * proved unreliable because we cannot instrument ProcessingBenchBlock directly.
     */
    private boolean isProcessedByBench() {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            if (element.getClassName().equals("com.hypixel.hytale.builtin.crafting.state.ProcessingBenchBlock") &&
                    element.getMethodName().equals("tick")) {
                return true;
            }
        }
        return false;
    }

    private void yieldScroll(ProcessingBenchBlock bench, com.hypixel.hytale.server.core.universe.world.World world, Vector3i blockPos, BsonDocument savedMeta) {
        // Create a dummy item to deserialize the enchantment data from saved metadata
        ItemStack temp = new ItemStack("dummy", 1).withMetadata(savedMeta);
        EnchantmentData data = enchantmentManager.getEnchantmentsFromItem(temp);

        if (data.isEmpty())
            return;

        EnchantmentType bestType = null;
        int bestLevel = -1;
        boolean bestLegendary = false;

        java.util.List<EnchantmentType> candidates = new java.util.ArrayList<>();

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

        EnchantmentType chosenType = candidates
                .get(java.util.concurrent.ThreadLocalRandom.current().nextInt(candidates.size()));
        int chosenLevel = data.getLevel(chosenType);

        try {
            ItemStack scrollStack;

            if (chosenLevel > chosenType.getMaxLevel()) {
                // Level exceeds max — create a Custom Scroll with the enchantment in metadata
                EnchantmentData customScrollData = new EnchantmentData();
                customScrollData.addEnchantment(chosenType, chosenLevel);
                scrollStack = new ItemStack("Scroll_Custom", 1)
                        .withMetadata(EnchantmentData.METADATA_KEY, customScrollData.toBson());
            } else {
                String scrollId = ScrollIdHelper.getScrollItemId(chosenType, chosenLevel);
                scrollStack = new ItemStack(scrollId, 1);
            }

            if (scrollStack.isValid() && !scrollStack.isEmpty()) {
                ListTransaction<ItemStackTransaction> tx = bench.getItemContainer().addItemStacks(
                        java.util.Collections.singletonList(scrollStack), false, false, false);

                if (!tx.succeeded() || tx.getList().stream()
                        .anyMatch(t -> t.getRemainder() != null && !t.getRemainder().isEmpty())) {
                    com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> entityStore = world
                            .getEntityStore().getStore();
                    com.hypixel.hytale.math.vector.Vector3d dropPosition = blockPos.toVector3d()
                            .add(0.5, 1.0, 0.5);
                    com.hypixel.hytale.component.Holder<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>[] itemEntityHolders = com.hypixel.hytale.server.core.modules.entity.item.ItemComponent
                            .generateItemDrops(
                                    entityStore, java.util.Collections.singletonList(scrollStack), dropPosition,
                                    com.hypixel.hytale.math.vector.Vector3f.ZERO);
                    if (itemEntityHolders.length > 0) {
                        if (world.isAlive()) {
                            try {
                                world.execute(() -> entityStore.addEntities(itemEntityHolders,
                                        com.hypixel.hytale.component.AddReason.SPAWN));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to yield scroll from salvager: " + e.getMessage());
        }
    }

    /**
     * Ends a salvage session for the given player (M-2: prevents session map leak).
     */
    public void endSession(UUID playerId) {
        sessions.remove(playerId);
    }

    /** Evict sessions older than SESSION_TIMEOUT_MS. */
    private void cleanupStaleSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> (now - e.getValue().timestamp) > SESSION_TIMEOUT_MS);
    }

    // Session tracking class
    private static class SalvageSession {
        final UUID playerId;
        final Vector3i benchPos;
        final long timestamp;

        SalvageSession(UUID playerId, Vector3i benchPos) {
            this.playerId = playerId;
            this.benchPos = benchPos;
            this.timestamp = System.currentTimeMillis();
        }
    }
}
