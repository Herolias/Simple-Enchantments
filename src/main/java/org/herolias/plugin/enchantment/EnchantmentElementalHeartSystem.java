package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ActionType;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.herolias.plugin.util.InventoryAccess;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Elemental Heart: essence consumed by casting with an enchanted staff is
 * given back.
 * <p>
 * A staff cast removes its configured essence through a {@code ModifyInventory}
 * interaction ({@code ItemToRemove}), which reaches the inventory as an
 * {@link ItemStackTransaction} of type {@link ActionType#REMOVE} whose query is
 * exactly that configured item. The refund is only made when
 * <ul>
 * <li>the transaction has that shape (drops, moves and quick-stacks do not),</li>
 * <li>the player holds an essence staff with the enchantment and a cast of that
 * staff is still running ({@link ActiveInteractionItems}), and</li>
 * <li>the removed stack is the item the interaction asked for and is an
 * ingredient (raw tag {@code Type=Ingredient}, carried by every essence).</li>
 * </ul>
 * Exactly the removed amount is added back to the slot it came from, falling
 * back to any free slot and finally to dropping it, so a full inventory never
 * loses the refund.
 * <p>
 * The save chance is {@code level * multiplier}; with the shipped default of
 * 1.0 per level the level-1 enchantment always saves, which is what the item
 * description says. Lowering the multiplier in the config makes it a chance.
 */
public class EnchantmentElementalHeartSystem extends AbstractRefundSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Raw tag ({@code Tag=Value}) carried by essence items. */
    private static final String INGREDIENT_TYPE_TAG = "Type=Ingredient";

    private final EnchantmentManager enchantmentManager;

    public EnchantmentElementalHeartSystem(@Nonnull EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentElementalHeartSystem initialized");
    }

    @Override
    public void handle(int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull InventoryChangeEvent event) {
        Transaction transaction = event.getTransaction();
        if (!(transaction instanceof ItemStackTransaction itemStackTransaction)
                || !itemStackTransaction.succeeded()
                || itemStackTransaction.getAction() != ActionType.REMOVE) {
            return;
        }
        Player player = archetypeChunk.getComponent(index, Player.getComponentType());
        UUIDComponent uuidComponent = archetypeChunk.getComponent(index, UUIDComponent.getComponentType());
        if (player == null || uuidComponent == null) {
            return;
        }
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);

        ItemStack staff = InventoryAccess.getItemInHand(store, ref);
        if (staff == null || staff.isEmpty()) {
            return;
        }
        ItemCategory category = enchantmentManager.categorizeItem(staff);
        if (category != ItemCategory.STAFF && category != ItemCategory.STAFF_ESSENCE) {
            return;
        }
        int level = enchantmentManager.getEnchantmentLevel(staff, EnchantmentType.ELEMENTAL_HEART);
        if (level <= 0) {
            return;
        }
        // Only consumption by a running cast of this staff is refundable; anything
        // else removing an ingredient (crafting, a status effect) is not.
        if (!ActiveInteractionItems.isUsingItem(ref, store, ActiveInteractionItems.ITEM_USE_TYPES, staff)) {
            return;
        }

        UUID playerUuid = uuidComponent.getUuid();
        ItemStack query = itemStackTransaction.getQuery();
        long tick = currentTick(store);
        boolean rolled = false;
        int refunded = 0;
        for (ItemStackSlotTransaction slotTransaction : itemStackTransaction.getSlotTransactions()) {
            if (!slotTransaction.succeeded()) {
                continue;
            }
            ItemStack before = slotTransaction.getSlotBefore();
            int removed = quantityOf(before) - quantityOf(slotTransaction.getSlotAfter());
            if (before == null || removed <= 0 || !isConsumedEssence(before, query)) {
                continue;
            }
            if (consumeDropMarker(playerUuid, event, slotTransaction.getSlot(), before, tick)) {
                continue;
            }
            if (!rolled) {
                rolled = true;
                if (!rollSave(level)) {
                    return;
                }
            }
            SimpleItemContainer.addOrDropItemStack(commandBuffer, ref, event.getItemContainer(),
                    slotTransaction.getSlot(), before.withQuantity(removed));
            refunded += removed;
        }

        if (refunded > 0) {
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            EnchantmentEventHelper.fireActivated(playerRef, staff, EnchantmentType.ELEMENTAL_HEART, level);
        }
    }

    private static boolean rollSave(int level) {
        double chance = level * EnchantmentType.ELEMENTAL_HEART.getEffectMultiplier();
        if (chance >= 1.0) {
            return true;
        }
        return chance > 0.0 && ThreadLocalRandom.current().nextDouble() < chance;
    }

    /**
     * The removed stack must be the item the staff's interaction configured as
     * {@code ItemToRemove} (the transaction query) and an ingredient.
     */
    private static boolean isConsumedEssence(@Nonnull ItemStack removed, @Nullable ItemStack query) {
        if (query == null || query.isEmpty() || !query.getItemId().equals(removed.getItemId())) {
            return false;
        }
        Item item = removed.getItem();
        return item != null && item.getData() != null && item.getData().getRawTags().containsKey(INGREDIENT_TYPE_TAG);
    }
}
