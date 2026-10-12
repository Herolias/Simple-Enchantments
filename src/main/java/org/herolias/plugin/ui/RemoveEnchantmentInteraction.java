package org.herolias.plugin.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.PageManager;
import com.hypixel.hytale.server.core.entity.entities.player.pages.choices.ChoiceInteraction;
import com.hypixel.hytale.server.core.inventory.ItemContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.enchantment.EnchantmentData;
import org.herolias.plugin.enchantment.EnchantmentManager;
import org.herolias.plugin.enchantment.EnchantmentType;
import org.herolias.plugin.enchantment.NativeTooltipManager;
import org.herolias.plugin.util.InventoryAccess;

/**
 * Interaction that removes an enchantment from an item and consumes the Scroll
 * of Cleansing.
 * <p>
 * The captured {@link ItemContext}s are snapshots; the live slots are re-read
 * before anything is written so quantity changes while the page was open can
 * never duplicate or destroy items.
 */
public class RemoveEnchantmentInteraction extends ChoiceInteraction {
    private final ItemContext itemContext;
    private final ItemContext heldItemContext;
    private final EnchantmentType enchantmentType;
    private final EnchantmentManager enchantmentManager;

    public RemoveEnchantmentInteraction(
            ItemContext itemContext,
            ItemContext heldItemContext,
            EnchantmentType enchantmentType,
            EnchantmentManager enchantmentManager) {
        this.itemContext = itemContext;
        this.heldItemContext = heldItemContext;
        this.enchantmentType = enchantmentType;
        this.enchantmentManager = enchantmentManager;
    }

    @Override
    public void run(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef) {
        Player playerComponent = store.getComponent(ref, Player.getComponentType());
        if (playerComponent == null) {
            return;
        }

        PageManager pageManager = playerComponent.getPageManager();

        // Re-validate the held scroll is still in the expected slot (prevents drop-while-open exploit)
        ItemContainer heldContainer = this.heldItemContext.getContainer();
        short heldItemSlot = this.heldItemContext.getSlot();
        ItemStack heldSnapshot = this.heldItemContext.getItemStack();
        ItemStack currentHeldItem = heldContainer.getItemStack(heldItemSlot);
        if (ItemStack.isEmpty(heldSnapshot) || ItemStack.isEmpty(currentHeldItem)
                || !currentHeldItem.isStackableWith(heldSnapshot)) {
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        // Validate the item still exists (live read) and has the enchantment
        ItemContainer targetContainer = this.itemContext.getContainer();
        short targetSlot = this.itemContext.getSlot();
        ItemStack snapshot = this.itemContext.getItemStack();
        ItemStack live = targetContainer.getItemStack(targetSlot);
        if (ItemStack.isEmpty(snapshot) || ItemStack.isEmpty(live) || !live.isStackableWith(snapshot)) {
            playerRef.sendMessage(Message.raw("The item is no longer available."));
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        EnchantmentData data = enchantmentManager.getEnchantmentsFromItem(live);
        org.herolias.plugin.lang.LanguageManager languageManager = enchantmentManager.getPlugin().getLanguageManager();
        String lang = enchantmentManager.getPlugin().getUserSettingsManager().getLanguage(playerRef.getUuid());
        String clientLang = playerRef.getLanguage();

        if (!data.hasEnchantment(enchantmentType)) {
            String translatedName = languageManager.getRawMessage(enchantmentType.getNameKey(), lang, clientLang);
            playerRef.sendMessage(Message.raw("This item no longer has " + translatedName + "."));
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        // Get the level before removing (for message)
        int removedLevel = data.getLevel(enchantmentType);

        // Remove the enchantment
        data.removeEnchantment(enchantmentType);

        // Write back to item metadata, derived from the live stack (keeps live quantity)
        org.bson.BsonDocument bson = data.isEmpty() ? null : data.toBson();
        ItemStack cleanedItem = NativeTooltipManager.withEnchantments(live, bson, enchantmentManager);

        // Consume the scroll
        ItemStackSlotTransaction removeTransaction = heldContainer.removeItemStackFromSlot(heldItemSlot,
                currentHeldItem, 1);
        if (!removeTransaction.succeeded()) {
            playerRef.sendMessage(Message.raw("Failed to consume the scroll."));
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        // Compare-and-replace the item with the cleaned version
        ItemStackSlotTransaction replaceTransaction = targetContainer
                .replaceItemStackInSlot(targetSlot, live, cleanedItem);
        if (!replaceTransaction.succeeded()) {
            // Restore the scroll if item replacement failed
            SimpleItemContainer.addOrDropItemStack(store, ref, heldContainer, heldItemSlot,
                    currentHeldItem.withQuantity(1));
            playerRef.sendMessage(Message.raw("Failed to update the item."));
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        // Check config for returning enchantment as scroll
        boolean returnEnchantment = SimpleEnchanting.getInstance().getConfigManager()
                .getConfig().returnEnchantmentOnCleanse;
        if (returnEnchantment) {
            try {
                ItemStack scrollStack;
                if (removedLevel > enchantmentType.getMaxLevel()) {
                    // Level exceeds max — create a Custom Scroll with the enchantment in metadata
                    EnchantmentData customScrollData = new EnchantmentData();
                    customScrollData.addEnchantment(enchantmentType, removedLevel);
                    scrollStack = NativeTooltipManager.withEnchantments(new ItemStack("Scroll_Custom", 1),
                            customScrollData, enchantmentManager);
                } else {
                    String scrollId = getScrollItemId(enchantmentType, removedLevel);
                    scrollStack = new ItemStack(scrollId, 1);
                }
                if (scrollStack.isValid() && !scrollStack.isEmpty()) {
                    ItemContainer playerInventory = InventoryAccess.getCombinedArmorHotbarUtilityStorage(store, ref);
                    SimpleItemContainer.addOrDropItemStack(store, ref, playerInventory, scrollStack);
                    String translatedName = languageManager.getRawMessage(enchantmentType.getNameKey(), lang,
                            clientLang) + " " + EnchantmentType.toRoman(removedLevel);
                    playerRef.sendMessage(Message.raw("Returned: " + translatedName));
                }
            } catch (Exception e) {
                // Scroll doesn't exist, don't return anything
            }
        }

        // Send confirmation
        Message itemName = languageManager.getMessage(cleanedItem.getItem().getTranslationKey(), lang, clientLang);
        String translatedName = languageManager.getRawMessage(enchantmentType.getNameKey(), lang, clientLang) + " "
                + EnchantmentType.toRoman(removedLevel);
        Message removedMessage = Message.raw("Removed " + translatedName + " from ").insert(itemName);
        playerRef.sendMessage(removedMessage);

        pageManager.setPage(ref, store, Page.None);
    }

    /**
     * Attempts to construct the scroll item ID for a given enchantment type and
     * level.
     */
    private String getScrollItemId(EnchantmentType type, int level) {
        return org.herolias.plugin.util.ScrollIdHelper.getScrollItemId(type, level);
    }
}
