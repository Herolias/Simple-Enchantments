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
import org.herolias.plugin.enchantment.NativeTooltipManager;

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

        // Re-validate the held scroll is still in the expected slot (prevents drop-while-open exploit)
        ItemContainer scrollContainer = this.heldItemContext.getContainer();
        ItemStack currentScrollItem = scrollContainer.getItemStack(this.heldItemContext.getSlot());
        if (ItemStack.isEmpty(currentScrollItem)
                || !currentScrollItem.isStackableWith(this.heldItemContext.getItemStack())) {
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        ItemStack targetItemStack = this.itemContext.getItemStack();
        if (ItemStack.isEmpty(targetItemStack)) {
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        int targetLevel = Math.max(1, this.level);
        org.herolias.plugin.lang.LanguageManager languageManager = enchantmentManager.getPlugin().getLanguageManager();
        String lang = enchantmentManager.getPlugin().getUserSettingsManager().getLanguage(playerRef.getUuid());
        String clientLang = playerRef.getLanguage();

        // 2. Update/remove the scroll FIRST, before applying the enchantment
        ItemStack scrollItemStack = this.heldItemContext.getItemStack();
        short scrollSlot = this.heldItemContext.getSlot();
        EnchantmentData scrollData = enchantmentManager.getEnchantmentsFromItem(scrollItemStack);
        EnchantmentData updatedData = scrollData.copy();
        updatedData.removeEnchantment(enchantmentType);

        // Store the original scroll state for rollback
        ItemStackSlotTransaction scrollTransaction;
        if (updatedData.isEmpty()) {
            // No enchantments will remain — remove the scroll
            scrollTransaction = scrollContainer.removeItemStackFromSlot(scrollSlot, scrollItemStack, 1);
        } else {
            // Update scroll metadata with remaining enchantments
            ItemStack updatedScroll = NativeTooltipManager.withEnchantments(scrollItemStack,
                    updatedData, enchantmentManager);
            scrollTransaction = scrollContainer.replaceItemStackInSlot(scrollSlot, scrollItemStack, updatedScroll);
        }
        if (!scrollTransaction.succeeded()) {
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        // 3. Apply enchantment to target item
        org.herolias.plugin.enchantment.EnchantmentApplicationResult result = enchantmentManager
                .applyEnchantmentToItem(playerRef, targetItemStack, enchantmentType, targetLevel, true);
        if (!result.success()) {
            // Rollback: restore the scroll
            if (updatedData.isEmpty()) {
                SimpleItemContainer.addOrDropItemStack(store, ref, scrollContainer, scrollSlot,
                        scrollItemStack.withQuantity(1));
            } else {
                scrollContainer.replaceItemStackInSlot(scrollSlot,
                        scrollContainer.getItemStack(scrollSlot), scrollItemStack);
            }
            playerRef.sendMessage(Message.raw(result.message()));
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        ItemStack enchantedItem = result.item();

        // 4. Replace the target item with the enchanted version
        ItemStackSlotTransaction replaceTransaction = this.itemContext.getContainer()
                .replaceItemStackInSlot(this.itemContext.getSlot(), targetItemStack, enchantedItem);
        if (!replaceTransaction.succeeded()) {
            // Rollback: restore the scroll
            if (updatedData.isEmpty()) {
                SimpleItemContainer.addOrDropItemStack(store, ref, scrollContainer, scrollSlot,
                        scrollItemStack.withQuantity(1));
            } else {
                scrollContainer.replaceItemStackInSlot(scrollSlot,
                        scrollContainer.getItemStack(scrollSlot), scrollItemStack);
            }
            pageManager.setPage(ref, store, Page.None);
            return;
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
