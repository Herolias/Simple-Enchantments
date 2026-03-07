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
import org.herolias.plugin.enchantment.ItemCategory;

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
        EnchantmentManager enchantmentManager
    ) {
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
        ItemStack itemStack = this.itemContext.getItemStack();
        if (ItemStack.isEmpty(itemStack)) {
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        org.herolias.plugin.lang.LanguageManager languageManager = enchantmentManager.getPlugin().getLanguageManager();
        String lang = enchantmentManager.getPlugin().getUserSettingsManager().getLanguage(playerRef.getUuid());
        String clientLang = playerRef.getLanguage();

        // --- Scroll merge handling ---
        String targetItemId = itemStack.getItemId();
        boolean isTargetCustomScroll = "Scroll_Custom".equals(targetItemId);
        org.herolias.plugin.util.ScrollIdHelper.ScrollEnchantment targetScrollEnch =
            org.herolias.plugin.util.ScrollIdHelper.getEnchantmentFromScrollId(targetItemId);
        boolean isTargetRegularScroll = targetScrollEnch != null;

        if (isTargetCustomScroll || isTargetRegularScroll) {
            // Scroll-to-scroll merge
            handleScrollMerge(store, ref, playerRef, pageManager, itemStack,
                isTargetCustomScroll, targetScrollEnch, languageManager, lang, clientLang);
            return;
        }

        // --- Normal item enchantment path ---
        int targetLevel = Math.max(1, Math.min(this.level, enchantmentType.getMaxLevel()));
        ItemCategory category = enchantmentManager.categorizeItem(itemStack);

        if (!enchantmentType.canApplyTo(category) && !"Scroll_Custom".equals(targetItemId)) {
            String translatedName = languageManager.getRawMessage(enchantmentType.getNameKey(), lang, clientLang);
            playerRef.sendMessage(Message.raw("That item cannot be enchanted with " + translatedName + "."));
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        EnchantmentData data = enchantmentManager.getEnchantmentsFromItem(itemStack);
        int currentLevel = data.getLevel(enchantmentType);
        if (currentLevel >= targetLevel) {
            String translatedName = languageManager.getRawMessage(enchantmentType.getNameKey(), lang, clientLang) + " " + EnchantmentType.toRoman(currentLevel);
            playerRef.sendMessage(Message.raw("That item already has " + translatedName + "."));
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        org.herolias.plugin.enchantment.EnchantmentApplicationResult result = enchantmentManager.applyEnchantmentToItem(playerRef, itemStack, enchantmentType, targetLevel);
        if (!result.success()) {
            playerRef.sendMessage(Message.raw(result.message()));
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        ItemStack enchantedItem = result.item();

        ItemContainer heldItemContainer = this.heldItemContext.getContainer();
        ItemStack heldItemStack = this.heldItemContext.getItemStack();
        short heldItemSlot = this.heldItemContext.getSlot();

        ItemStackSlotTransaction removeTransaction =
            heldItemContainer.removeItemStackFromSlot(heldItemSlot, heldItemStack, 1);
        if (!removeTransaction.succeeded()) {
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        ItemStackSlotTransaction replaceTransaction =
            this.itemContext.getContainer().replaceItemStackInSlot(this.itemContext.getSlot(), itemStack, enchantedItem);
        if (!replaceTransaction.succeeded()) {
            SimpleItemContainer.addOrDropItemStack(store, ref, heldItemContainer, heldItemSlot, heldItemStack.withQuantity(1));
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        Message itemName = languageManager.getMessage(enchantedItem.getItem().getTranslationKey(), lang, clientLang);
        String translatedName = languageManager.getRawMessage(enchantmentType.getNameKey(), lang, clientLang) + " " + EnchantmentType.toRoman(targetLevel);
        Message appliedMessage = Message.raw("Applied " + translatedName + " to ").insert(itemName);
        playerRef.sendMessage(appliedMessage);
        pageManager.setPage(ref, store, Page.None);
    }

    /**
     * Handles merging two scrolls together.
     * - Same enchantment + same level: upgrade to next level (replaces target scroll)
     * - Different enchantment: combines into a Custom Scroll with both enchantments
     */
    private void handleScrollMerge(
        Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef,
        PageManager pageManager, ItemStack targetStack,
        boolean isTargetCustomScroll,
        org.herolias.plugin.util.ScrollIdHelper.ScrollEnchantment targetScrollEnch,
        org.herolias.plugin.lang.LanguageManager languageManager, String lang, String clientLang
    ) {
        // Build the merged enchantment data
        EnchantmentData mergedData = new EnchantmentData();

        if (isTargetCustomScroll) {
            // Copy existing enchantments from Custom Scroll
            EnchantmentData existingData = enchantmentManager.getEnchantmentsFromItem(targetStack);
            for (java.util.Map.Entry<EnchantmentType, Integer> entry : existingData.getAllEnchantments().entrySet()) {
                mergedData.addEnchantment(entry.getKey(), entry.getValue());
            }
        } else if (targetScrollEnch != null) {
            // Regular scroll has one enchantment
            mergedData.addEnchantment(targetScrollEnch.type(), targetScrollEnch.level());
        }

        // Add/upgrade the held scroll's enchantment
        int targetLevel = this.level;
        mergedData.addEnchantment(this.enchantmentType, targetLevel);

        // Determine the result item
        ItemStack resultStack;
        java.util.Map<EnchantmentType, Integer> allEnchants = mergedData.getAllEnchantments();

        if (allEnchants.size() == 1) {
            // Single enchantment — try to produce a regular scroll
            java.util.Map.Entry<EnchantmentType, Integer> entry = allEnchants.entrySet().iterator().next();
            EnchantmentType resultType = entry.getKey();
            int resultLevel = entry.getValue();

            if (resultLevel <= resultType.getMaxLevel()) {
                String scrollId = org.herolias.plugin.util.ScrollIdHelper.getScrollItemId(resultType, resultLevel);
                resultStack = new ItemStack(scrollId, 1);
                if (!resultStack.isValid() || resultStack.isEmpty()) {
                    // Fallback to Custom Scroll if the scroll item doesn't exist
                    resultStack = new ItemStack("Scroll_Custom", 1)
                        .withMetadata(EnchantmentData.METADATA_KEY, mergedData.toBson());
                }
            } else {
                // Level exceeds max — must be a Custom Scroll
                resultStack = new ItemStack("Scroll_Custom", 1)
                    .withMetadata(EnchantmentData.METADATA_KEY, mergedData.toBson());
            }
        } else {
            // Multiple enchantments — always a Custom Scroll
            resultStack = new ItemStack("Scroll_Custom", 1)
                .withMetadata(EnchantmentData.METADATA_KEY, mergedData.toBson());
        }

        // Remove the held scroll (source)
        ItemContainer heldItemContainer = this.heldItemContext.getContainer();
        ItemStack heldItemStack = this.heldItemContext.getItemStack();
        short heldItemSlot = this.heldItemContext.getSlot();

        ItemStackSlotTransaction removeHeld =
            heldItemContainer.removeItemStackFromSlot(heldItemSlot, heldItemStack, 1);
        if (!removeHeld.succeeded()) {
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        if (targetStack.getQuantity() == 1) {
            // Replace the target scroll with the result
            ItemStackSlotTransaction replaceTarget =
                this.itemContext.getContainer().replaceItemStackInSlot(this.itemContext.getSlot(), targetStack, resultStack);
            if (!replaceTarget.succeeded()) {
                // Rollback: give back the held scroll
                SimpleItemContainer.addOrDropItemStack(store, ref, heldItemContainer, heldItemSlot, heldItemStack.withQuantity(1));
                pageManager.setPage(ref, store, Page.None);
                return;
            }
        } else {
            // Remove 1 from the target stack
            ItemStackSlotTransaction removeTarget =
                this.itemContext.getContainer().removeItemStackFromSlot(this.itemContext.getSlot(), targetStack, 1);
            if (!removeTarget.succeeded()) {
                // Rollback: give back the held scroll
                SimpleItemContainer.addOrDropItemStack(store, ref, heldItemContainer, heldItemSlot, heldItemStack.withQuantity(1));
                pageManager.setPage(ref, store, Page.None);
                return;
            }
            // Give the new Custom Scroll back to the player
            SimpleItemContainer.addOrDropItemStack(store, ref, this.itemContext.getContainer(), this.itemContext.getSlot(), resultStack);
        }

        // Send success message
        String enchantName = languageManager.getRawMessage(enchantmentType.getNameKey(), lang, clientLang) + " " + EnchantmentType.toRoman(targetLevel);
        if (allEnchants.size() == 1) {
            playerRef.sendMessage(Message.raw("Upgraded scroll to " + enchantName + "."));
        } else {
            playerRef.sendMessage(Message.raw("Merged scrolls into a Custom Scroll."));
        }
        pageManager.setPage(ref, store, Page.None);
    }
}
