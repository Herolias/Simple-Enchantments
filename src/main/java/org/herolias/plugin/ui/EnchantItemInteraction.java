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
import org.herolias.plugin.enchantment.EnchantmentApplicationResult;
import org.herolias.plugin.enchantment.EnchantmentData;
import org.herolias.plugin.enchantment.EnchantmentManager;
import org.herolias.plugin.enchantment.EnchantmentType;
import org.herolias.plugin.enchantment.ItemCategory;

/**
 * Applies the held enchantment scroll to the chosen inventory item.
 * <p>
 * The {@link ItemContext}s captured when the page was opened are only
 * snapshots; {@link #run} re-reads both slots and works on the live stacks so a
 * stack that was split or merged while the page was open can never be
 * duplicated or lost.
 * <p>
 * Scroll targets are filtered out by {@link EnchantScrollPage}; scroll merging
 * is handled by the Engraving Table.
 */
public class EnchantItemInteraction extends ChoiceInteraction {
    private final ItemContext itemContext;
    private final ItemContext heldItemContext;
    private final EnchantmentType enchantmentType;
    private final int level;
    private final EnchantmentManager enchantmentManager;

    public EnchantItemInteraction(
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
        ItemContainer heldContainer = this.heldItemContext.getContainer();
        short heldItemSlot = this.heldItemContext.getSlot();
        ItemStack heldSnapshot = this.heldItemContext.getItemStack();
        ItemStack currentHeldItem = heldContainer.getItemStack(heldItemSlot);
        if (ItemStack.isEmpty(heldSnapshot) || ItemStack.isEmpty(currentHeldItem)
                || !currentHeldItem.isStackableWith(heldSnapshot)) {
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        // Re-read the live target so quantity changes since the page opened are respected
        ItemContainer targetContainer = this.itemContext.getContainer();
        short targetSlot = this.itemContext.getSlot();
        ItemStack snapshot = this.itemContext.getItemStack();
        ItemStack live = targetContainer.getItemStack(targetSlot);
        if (ItemStack.isEmpty(snapshot) || ItemStack.isEmpty(live) || !live.isStackableWith(snapshot)) {
            playerRef.sendMessage(Message.raw("The selected item changed. Please try again."));
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        org.herolias.plugin.lang.LanguageManager languageManager = enchantmentManager.getPlugin().getLanguageManager();
        String lang = enchantmentManager.getPlugin().getUserSettingsManager().getLanguage(playerRef.getUuid());
        String clientLang = playerRef.getLanguage();

        String targetItemId = live.getItemId();
        if (targetItemId != null && targetItemId.startsWith("Scroll_")) {
            // Never offered by EnchantScrollPage; scrolls are combined at the Engraving Table.
            playerRef.sendMessage(Message.raw("Scrolls can only be combined at the Engraving Table."));
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        int targetLevel = Math.max(1, Math.min(this.level, enchantmentType.getMaxLevel()));
        ItemCategory category = enchantmentManager.categorizeItem(live);

        if (!enchantmentType.canApplyTo(category)) {
            String translatedName = languageManager.getRawMessage(enchantmentType.getNameKey(), lang, clientLang);
            playerRef.sendMessage(Message.raw("That item cannot be enchanted with " + translatedName + "."));
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        EnchantmentData data = enchantmentManager.getEnchantmentsFromItem(live);
        int currentLevel = data.getLevel(enchantmentType);
        if (currentLevel >= targetLevel) {
            String translatedName = languageManager.getRawMessage(enchantmentType.getNameKey(), lang, clientLang) + " "
                    + EnchantmentType.toRoman(currentLevel);
            playerRef.sendMessage(Message.raw("That item already has " + translatedName + "."));
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        // Consume one scroll FIRST, before applying the enchantment
        ItemStackSlotTransaction removeTransaction = heldContainer.removeItemStackFromSlot(heldItemSlot,
                currentHeldItem, 1);
        if (!removeTransaction.succeeded()) {
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        // Enchant the live stack so the written quantity is the live quantity
        EnchantmentApplicationResult result = enchantmentManager
                .applyEnchantmentToItem(playerRef, live, enchantmentType, targetLevel);
        if (!result.success()) {
            // Rollback: give back the scroll
            SimpleItemContainer.addOrDropItemStack(store, ref, heldContainer, heldItemSlot,
                    currentHeldItem.withQuantity(1));
            playerRef.sendMessage(Message.raw(result.message()));
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        ItemStack enchantedItem = result.item();

        // Compare-and-replace against the live stack; fails if the slot changed in between
        ItemStackSlotTransaction replaceTransaction = targetContainer
                .replaceItemStackInSlot(targetSlot, live, enchantedItem);
        if (!replaceTransaction.succeeded()) {
            // Rollback: give back the scroll
            SimpleItemContainer.addOrDropItemStack(store, ref, heldContainer, heldItemSlot,
                    currentHeldItem.withQuantity(1));
            playerRef.sendMessage(Message.raw("The selected item changed. Please try again."));
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        // The enchanted item is committed to the inventory; notify listeners now
        enchantmentManager.fireItemEnchanted(playerRef, result);

        Message itemName = languageManager.getMessage(enchantedItem.getItem().getTranslationKey(), lang, clientLang);
        String translatedName = languageManager.getRawMessage(enchantmentType.getNameKey(), lang, clientLang) + " "
                + EnchantmentType.toRoman(targetLevel);
        Message appliedMessage = Message.raw("Applied " + translatedName + " to ").insert(itemName);
        playerRef.sendMessage(appliedMessage);
        pageManager.setPage(ref, store, Page.None);
    }
}
