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
import javax.annotation.Nullable;
import org.herolias.plugin.enchantment.EnchantmentApplicationResult;
import org.herolias.plugin.enchantment.EnchantmentData;
import org.herolias.plugin.enchantment.EnchantmentManager;
import org.herolias.plugin.enchantment.EnchantmentType;
import org.herolias.plugin.enchantment.NativeTooltipManager;

/**
 * Interaction that transfers an enchantment from the Custom Scroll to a target
 * item.
 * <p>
 * Steps:
 * 1. Re-read the live scroll and target stacks (the captured contexts are only
 * snapshots)
 * 2. Take one scroll off its stack and, if enchantments remain on it, prepare
 * the updated scroll
 * 3. Apply the enchantment to the live target item and commit it
 * 4. Return the updated scroll (if any) and close the UI
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

        // 1. Re-validate the held scroll is still in the expected slot (prevents drop-while-open exploit)
        ItemContainer scrollContainer = this.heldItemContext.getContainer();
        short scrollSlot = this.heldItemContext.getSlot();
        ItemStack scrollSnapshot = this.heldItemContext.getItemStack();
        ItemStack liveScroll = scrollContainer.getItemStack(scrollSlot);
        if (ItemStack.isEmpty(scrollSnapshot) || ItemStack.isEmpty(liveScroll)
                || !liveScroll.isStackableWith(scrollSnapshot)) {
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        // Re-read the live target so quantity changes since the page opened are respected
        ItemContainer targetContainer = this.itemContext.getContainer();
        short targetSlot = this.itemContext.getSlot();
        ItemStack targetSnapshot = this.itemContext.getItemStack();
        ItemStack liveTarget = targetContainer.getItemStack(targetSlot);
        if (ItemStack.isEmpty(targetSnapshot) || ItemStack.isEmpty(liveTarget)
                || !liveTarget.isStackableWith(targetSnapshot)) {
            playerRef.sendMessage(Message.raw("The selected item changed. Please try again."));
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        int targetLevel = Math.max(1, this.level);
        org.herolias.plugin.lang.LanguageManager languageManager = enchantmentManager.getPlugin().getLanguageManager();
        String lang = enchantmentManager.getPlugin().getUserSettingsManager().getLanguage(playerRef.getUuid());
        String clientLang = playerRef.getLanguage();

        EnchantmentData scrollData = enchantmentManager.getEnchantmentsFromItem(liveScroll);
        if (!scrollData.hasEnchantment(enchantmentType)) {
            playerRef.sendMessage(Message.raw("The scroll no longer holds that enchantment."));
            pageManager.setPage(ref, store, Page.None);
            return;
        }
        EnchantmentData updatedData = scrollData.copy();
        updatedData.removeEnchantment(enchantmentType);

        // 2. Update/remove ONE scroll first, before applying the enchantment.
        // Only a single scroll is ever consumed, so a stack of identical custom
        // scrolls keeps its remaining copies untouched.
        ItemStack oneScroll = liveScroll.withQuantity(1);
        ItemStack updatedScroll = updatedData.isEmpty() ? null
                : NativeTooltipManager.withEnchantments(oneScroll, updatedData, enchantmentManager);
        boolean replacedInPlace = false;
        ItemStack leftoverScroll = null; // returned to the player after the target commit succeeds

        ItemStackSlotTransaction scrollTransaction;
        if (liveScroll.getQuantity() == 1 && updatedScroll != null) {
            scrollTransaction = scrollContainer.replaceItemStackInSlot(scrollSlot, liveScroll, updatedScroll);
            replacedInPlace = true;
        } else {
            scrollTransaction = scrollContainer.removeItemStackFromSlot(scrollSlot, liveScroll, 1);
            leftoverScroll = updatedScroll;
        }
        if (!scrollTransaction.succeeded()) {
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        // 3. Apply enchantment to the live target item
        EnchantmentApplicationResult result = enchantmentManager
                .applyEnchantmentToItem(playerRef, liveTarget, enchantmentType, targetLevel, true);
        if (!result.success()) {
            rollbackScroll(store, ref, scrollContainer, scrollSlot, oneScroll, updatedScroll, replacedInPlace);
            playerRef.sendMessage(Message.raw(result.message()));
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        ItemStack enchantedItem = result.item();

        // Compare-and-replace the target against its live stack
        ItemStackSlotTransaction replaceTransaction = targetContainer
                .replaceItemStackInSlot(targetSlot, liveTarget, enchantedItem);
        if (!replaceTransaction.succeeded()) {
            rollbackScroll(store, ref, scrollContainer, scrollSlot, oneScroll, updatedScroll, replacedInPlace);
            playerRef.sendMessage(Message.raw("The selected item changed. Please try again."));
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        // The enchanted item is committed; notify listeners
        enchantmentManager.fireItemEnchanted(playerRef, result);

        // 4. Hand back the updated scroll that was split off a larger stack
        if (leftoverScroll != null) {
            SimpleItemContainer.addOrDropItemStack(store, ref, scrollContainer, scrollSlot, leftoverScroll);
        }

        Message itemName = languageManager.getMessage(enchantedItem.getItem().getTranslationKey(), lang, clientLang);
        String translatedName = languageManager.getRawMessage(enchantmentType.getNameKey(), lang, clientLang) + " "
                + EnchantmentType.toRoman(targetLevel);
        Message appliedMessage = Message.raw("Transferred " + translatedName + " to ").insert(itemName);
        playerRef.sendMessage(appliedMessage);
        pageManager.setPage(ref, store, Page.None);
    }

    /**
     * Restores the single consumed scroll after a failed target commit.
     */
    private static void rollbackScroll(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull ItemContainer scrollContainer, short scrollSlot, @Nonnull ItemStack originalScroll,
            @Nullable ItemStack updatedScroll, boolean replacedInPlace) {
        if (replacedInPlace && updatedScroll != null) {
            ItemStackSlotTransaction restore = scrollContainer.replaceItemStackInSlot(scrollSlot, updatedScroll,
                    originalScroll);
            if (restore.succeeded()) {
                return;
            }
        }
        SimpleItemContainer.addOrDropItemStack(store, ref, scrollContainer, scrollSlot, originalScroll);
    }
}
