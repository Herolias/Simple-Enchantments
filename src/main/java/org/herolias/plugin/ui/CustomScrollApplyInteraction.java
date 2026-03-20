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
import org.herolias.plugin.enchantment.EnchantmentData;
import org.herolias.plugin.enchantment.EnchantmentManager;
import org.herolias.plugin.enchantment.EnchantmentType;

/**
 * Interaction that transfers an enchantment from the Custom Scroll to a target
 * item.
 * <p>
 * Steps:
 * 1. Apply the enchantment to the target item
 * 2. Remove the enchantment from the scroll's metadata
 * 3. If no enchantments remain, remove the scroll from inventory
 * 4. Close the UI
 */
public class CustomScrollApplyInteraction extends ChoiceInteraction {
    private final ItemContext itemContext;
    private final ItemContext heldItemContext;
    private final EnchantmentType enchantmentType;
    private final int level;
    private final EnchantmentManager enchantmentManager;

    public CustomScrollApplyInteraction(
            ItemContext itemContext,
            ItemContext heldItemContext,
            EnchantmentType enchantmentType,
            int level,
            EnchantmentManager enchantmentManager) {
        this.itemContext = itemContext;
        this.heldItemContext = heldItemContext;
        this.enchantmentType = enchantmentType;
        this.level = level;
        this.enchantmentManager = enchantmentManager;
    }

    @Override
    public void run(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef) {
        Player playerComponent = store.getComponent(ref, Player.getComponentType());
        if (playerComponent == null) {
            return;
        }

        PageManager pageManager = playerComponent.getPageManager();
        ItemStack targetItemStack = this.itemContext.getItemStack();
        if (ItemStack.isEmpty(targetItemStack)) {
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        int targetLevel = Math.max(1, this.level);
        org.herolias.plugin.lang.LanguageManager languageManager = enchantmentManager.getPlugin().getLanguageManager();
        String lang = enchantmentManager.getPlugin().getUserSettingsManager().getLanguage(playerRef.getUuid());
        String clientLang = playerRef.getLanguage();

        // 1. Apply enchantment to target item
        org.herolias.plugin.enchantment.EnchantmentApplicationResult result = enchantmentManager
                .applyEnchantmentToItem(playerRef, targetItemStack, enchantmentType, targetLevel, true);
        if (!result.success()) {
            playerRef.sendMessage(Message.raw(result.message()));
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        ItemStack enchantedItem = result.item();

        // 2. Replace the target item with the enchanted version
        ItemStackSlotTransaction replaceTransaction = this.itemContext.getContainer()
                .replaceItemStackInSlot(this.itemContext.getSlot(), targetItemStack, enchantedItem);
        if (!replaceTransaction.succeeded()) {
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        // 3. Remove the enchantment from the Custom Scroll
        ItemStack scrollItemStack = this.heldItemContext.getItemStack();
        if (!ItemStack.isEmpty(scrollItemStack)) {
            EnchantmentData scrollData = enchantmentManager.getEnchantmentsFromItem(scrollItemStack);
            EnchantmentData updatedData = scrollData.copy();
            updatedData.removeEnchantment(enchantmentType);

            if (updatedData.isEmpty()) {
                // No enchantments left — remove the scroll
                ItemContainer scrollContainer = this.heldItemContext.getContainer();
                scrollContainer.removeItemStackFromSlot(this.heldItemContext.getSlot(), scrollItemStack, 1);
            } else {
                // Update scroll metadata with remaining enchantments
                ItemStack updatedScroll = scrollItemStack.withMetadata(EnchantmentData.METADATA_KEY,
                        updatedData.toBson());
                ItemContainer scrollContainer = this.heldItemContext.getContainer();
                scrollContainer.replaceItemStackInSlot(this.heldItemContext.getSlot(), scrollItemStack, updatedScroll);
            }
        }

        // 4. Send success message and close UI
        Message itemName = languageManager.getMessage(enchantedItem.getItem().getTranslationKey(), lang, clientLang);
        String translatedName = languageManager.getRawMessage(enchantmentType.getNameKey(), lang, clientLang) + " "
                + EnchantmentType.toRoman(targetLevel);
        Message appliedMessage = Message.raw("Transferred " + translatedName + " to ").insert(itemName);
        playerRef.sendMessage(appliedMessage);
        pageManager.setPage(ref, store, Page.None);
    }
}
