package org.herolias.plugin.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.PageManager;
import com.hypixel.hytale.server.core.entity.entities.player.pages.choices.ChoiceElement;
import com.hypixel.hytale.server.core.entity.entities.player.pages.choices.ChoiceInteraction;
import com.hypixel.hytale.server.core.inventory.ItemContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import org.herolias.plugin.enchantment.EnchantmentData;
import org.herolias.plugin.enchantment.EnchantmentManager;

/**
 * Element representing an enchanted item in the cleansing scroll selection list.
 * Clicking opens the enchantment selection page.
 */
public class CleansingScrollElement extends ChoiceElement {
    private final ItemStack itemStack;
    private final EnchantmentData enchantmentData;
    private final ItemContext itemContext;
    private final ItemContext heldItemContext;
    private final EnchantmentManager enchantmentManager;

    public CleansingScrollElement(
        ItemStack itemStack,
        EnchantmentData enchantmentData,
        ItemContext itemContext,
        ItemContext heldItemContext,
        EnchantmentManager enchantmentManager
    ) {
        this.itemStack = itemStack;
        this.enchantmentData = enchantmentData;
        this.itemContext = itemContext;
        this.heldItemContext = heldItemContext;
        this.enchantmentManager = enchantmentManager;
        
        // Create the interaction that opens the enchantment selection page
        this.interactions = new ChoiceInteraction[]{
            new OpenEnchantmentPageInteraction(itemContext, heldItemContext, enchantmentData, enchantmentManager)
        };
    }

    @Override
    public void addButton(
        @Nonnull UICommandBuilder commandBuilder,
        UIEventBuilder eventBuilder,
        String selector,
        PlayerRef playerRef
    ) {
        commandBuilder.append("#ElementList", "Pages/CleansingScrollElement.ui");
        commandBuilder.set(selector + " #Icon.ItemId", this.itemStack.getItemId().toString());
        org.herolias.plugin.lang.LanguageManager langManager = enchantmentManager.getPlugin().getLanguageManager();
        String lang = enchantmentManager.getPlugin().getUserSettingsManager().getLanguage(playerRef.getUuid());
        commandBuilder.set(selector + " #Name.TextSpans", langManager.getMessage(this.itemStack.getItem().getTranslationKey(), lang, playerRef.getLanguage()));

        int enchantCount = enchantmentData.getAllEnchantments().size();
        String enchantString = langManager.getRawMessage(enchantCount == 1 ? "customUI.cleansingScrollPage.enchantment.singular" : "customUI.cleansingScrollPage.enchantments", lang, playerRef.getLanguage());
        String detail = enchantCount + " " + enchantString;
        commandBuilder.set(selector + " #Detail.Text", detail);
    }

    /**
     * Interaction that opens the enchantment selection page.
     */
    private static class OpenEnchantmentPageInteraction extends ChoiceInteraction {
        private final ItemContext itemContext;
        private final ItemContext heldItemContext;
        private final EnchantmentData enchantmentData;
        private final EnchantmentManager enchantmentManager;

        public OpenEnchantmentPageInteraction(
            ItemContext itemContext,
            ItemContext heldItemContext,
            EnchantmentData enchantmentData,
            EnchantmentManager enchantmentManager
        ) {
            this.itemContext = itemContext;
            this.heldItemContext = heldItemContext;
            this.enchantmentData = enchantmentData;
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

            // Re-fetch enchantment data in case it changed
            EnchantmentData currentData = enchantmentManager.getEnchantmentsFromItem(itemStack);
            if (currentData.isEmpty()) {
                playerRef.sendMessage(Message.raw("This item no longer has any enchantments."));
                pageManager.setPage(ref, store, Page.None);
                return;
            }

            // Open the enchantment selection page
            CleansingEnchantmentPage enchantmentPage = new CleansingEnchantmentPage(
                playerRef,
                itemContext,
                heldItemContext,
                currentData,
                enchantmentManager
            );
            pageManager.openCustomPage(ref, store, enchantmentPage);
        }
    }
}
