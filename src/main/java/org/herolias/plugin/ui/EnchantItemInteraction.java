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

        int targetLevel = Math.max(1, Math.min(this.level, enchantmentType.getMaxLevel()));
        ItemCategory category = enchantmentManager.categorizeItem(itemStack);
        org.herolias.plugin.lang.LanguageManager languageManager = enchantmentManager.getPlugin().getLanguageManager();
        String lang = enchantmentManager.getPlugin().getUserSettingsManager().getLanguage(playerRef.getUuid());
        String clientLang = playerRef.getLanguage();

        if (!enchantmentType.canApplyTo(category)) {
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

        org.herolias.plugin.enchantment.EnchantmentApplicationResult result = enchantmentManager.applyEnchantmentToItem(itemStack, enchantmentType, targetLevel);
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
}
