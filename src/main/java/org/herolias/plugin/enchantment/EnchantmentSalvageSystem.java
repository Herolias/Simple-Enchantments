package org.herolias.plugin.enchantment;

import com.hypixel.hytale.builtin.crafting.state.ProcessingBenchState;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.entity.LivingEntityInventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ListTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MoveTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MoveType;
import com.hypixel.hytale.server.core.inventory.transaction.SlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import org.bson.BsonDocument;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * System to handle salvaging of enchanted items.
 * 
 * It strips metadata from items when they are placed in the Salvager Bench so they match the recipe.
 * If the item is removed by the player before processing, the metadata is restored.
 */
public class EnchantmentSalvageSystem {
    
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String BENCH_ID = "Salvagebench";
    
    // Tracks active sessions for players.
    // Key: Player UUID
    // Value: Session Data
    private final Map<UUID, SalvageSession> sessions = new ConcurrentHashMap<>();
    
    public EnchantmentSalvageSystem() {
    }

    /**
     * Starts a salvage session for the player and bench.
     * Hooks into the bench's container to monitor item changes.
     */
    public void startSession(Player player, ProcessingBenchState bench) {
        UUID playerId = player.getUuid();
        
        if (sessions.containsKey(playerId)) {
            return;
        }
        
        SalvageSession session = new SalvageSession(playerId, bench.getBlockPosition());
        sessions.put(playerId, session);
        
        if (!trackedContainers.containsKey(bench.getItemContainer())) {
             LOGGER.atInfo().log("Registering change listener for bench container.");
             bench.getItemContainer().registerChangeEvent(EventPriority.NORMAL, (event) -> {
                 this.onBenchItemChange(bench, event);
             });
             trackedContainers.put(bench.getItemContainer(), true);
        }
    }
    
    // Keep track of containers we are already listening to
    private final Map<ItemContainer, Boolean> trackedContainers = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    
    /**
     * Handles item changes in the Salvager Bench.
     */
    private void onBenchItemChange(ProcessingBenchState bench, ItemContainer.ItemContainerChangeEvent event) {
        processBenchTransaction(bench, event.container(), event.transaction());
    }
    
    private void processBenchTransaction(ProcessingBenchState bench, ItemContainer container, Transaction transaction) {
        if (!transaction.succeeded()) {
            return;
        }
        
        if (transaction instanceof SlotTransaction) {
            handleBenchSlotChange(bench, container, (SlotTransaction) transaction);
        } else if (transaction instanceof ListTransaction) {
            ListTransaction<?> listTransaction = (ListTransaction<?>) transaction;
            for (Object child : listTransaction.getList()) {
                if (child instanceof Transaction) {
                    processBenchTransaction(bench, container, (Transaction) child);
                }
            }
        } else if (transaction instanceof MoveTransaction) {
            MoveTransaction<?> moveTx = (MoveTransaction<?>) transaction;
            // MoveTransaction contains both add and remove parts.
            // We care about what happened TO THIS container.
            if (moveTx.getMoveType() == MoveType.MOVE_TO_SELF) {
                // Item matched TO this container (Add)
                if (moveTx.getAddTransaction() != null) {
                    processBenchTransaction(bench, container, moveTx.getAddTransaction());
                }
            } else if (moveTx.getMoveType() == MoveType.MOVE_FROM_SELF) {
                // Item matched FROM this container (Remove)
                 if (moveTx.getRemoveTransaction() != null) {
                    processBenchTransaction(bench, container, moveTx.getRemoveTransaction());
                }
            }
        } else if (transaction instanceof ItemStackTransaction) {
            ItemStackTransaction stackTx = (ItemStackTransaction) transaction;
            for (Transaction child : stackTx.getSlotTransactions()) {
                processBenchTransaction(bench, container, child);
            }
        }
    }

    private void handleBenchSlotChange(ProcessingBenchState bench, ItemContainer container, SlotTransaction transaction) {
        ItemStack newItem = transaction.getSlotAfter();
        ItemStack oldItem = transaction.getSlotBefore();
        short slot = transaction.getSlot();

        // Handling ADDITION of Enchanted Item
        if (newItem != null && !newItem.isEmpty()) {
            if (hasEnchantments(newItem)) {
                // Save the data
                saveEnchantmentData(bench, slot, newItem);
                
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
                BsonDocument savedMeta = getSavedEnchantmentData(bench, slot);
                if (savedMeta != null) {
                     queueGlobalRestore(oldItem.getItemId(), savedMeta);
                     
                     if (removed) {
                         clearSavedEnchantmentData(bench, slot);
                     }
                }
            }
        }
    }
    
    private boolean hasEnchantments(ItemStack item) {
        // Use EnchantmentManager to check if any enchantments are present
        org.herolias.plugin.SimpleEnchanting.getInstance().getEnchantmentManager().getEnchantmentsFromItem(item);
        return !org.herolias.plugin.SimpleEnchanting.getInstance().getEnchantmentManager().getEnchantmentsFromItem(item).isEmpty();
    }
    
    // --- Storage for Bench Slots ---
    private final Map<String, BsonDocument> benchSlotStorage = new ConcurrentHashMap<>();
    
    private String getSlotKey(ProcessingBenchState bench, short slot) {
        Vector3i pos = bench.getBlockPosition();
        return pos.x + "," + pos.y + "," + pos.z + ":" + slot;
    }
    
    private void saveEnchantmentData(ProcessingBenchState bench, short slot, ItemStack item) {
        // Save ALL metadata to preserve names, etc. and to be sure we strip everything that blocks the recipe
        BsonDocument data = item.getMetadata();
        if (data != null) {
            benchSlotStorage.put(getSlotKey(bench, slot), data);
        }
    }
    
    private BsonDocument getSavedEnchantmentData(ProcessingBenchState bench, short slot) {
        return benchSlotStorage.get(getSlotKey(bench, slot));
    }
    
    private void clearSavedEnchantmentData(ProcessingBenchState bench, short slot) {
        benchSlotStorage.remove(getSlotKey(bench, slot));
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
    
    private final List<FloatingRestore> floatingRestores = new CopyOnWriteArrayList<>();
    
    private void queueGlobalRestore(String itemId, BsonDocument meta) { 
         floatingRestores.add(new FloatingRestore(itemId, meta));
    }
    
    /**
     * Listens for player inventory changes to catch when they retrieve a stripped item.
     */
    public void onEntityInventoryChange(LivingEntityInventoryChangeEvent event) {
        if (!(event.getEntity() instanceof Player)) {
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
        
        long now = System.currentTimeMillis();
        floatingRestores.removeIf(r -> (now - r.timestamp) > 500); // 0.5 sec expiration
        
        if (floatingRestores.isEmpty()) {
            return;
        }
        
        if (hasEnchantments(newItem)) {
            return; 
        }
        
        Iterator<FloatingRestore> it = floatingRestores.iterator();
        while (it.hasNext()) {
             FloatingRestore r = it.next();
             if (r.itemId.equals(newItem.getItemId())) {
                 // Restore ALL metadata
                 ItemStack restored = newItem.withMetadata(r.metadata);
                 floatingRestores.remove(r);
                 container.setItemStackForSlot(transaction.getSlot(), restored);
                 break;
             }
        }
    }

    // Session tracking class
    private static class SalvageSession {
        final UUID playerId;
        final Vector3i benchPos;
        
        SalvageSession(UUID playerId, Vector3i benchPos) {
            this.playerId = playerId;
            this.benchPos = benchPos;
        }
    }
}
