package org.herolias.plugin.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.player.pages.choices.ChoiceBasePage;
import com.hypixel.hytale.server.core.entity.entities.player.pages.choices.ChoiceElement;
import com.hypixel.hytale.server.core.inventory.ItemContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import javax.annotation.Nonnull;
import org.herolias.plugin.enchantment.EnchantmentData;
import org.herolias.plugin.enchantment.EnchantmentManager;

/**
 * Page displaying all enchanted items in the player's inventory.
 * Selecting an item opens CleansingEnchantmentPage to choose which enchantment to remove.
 */
public class CleansingScrollPage extends ChoiceBasePage {
    private final EnchantmentManager enchantmentManager;
    private final PlayerRef playerRef;

    public CleansingScrollPage(
        @Nonnull PlayerRef playerRef,
        @Nonnull ItemContainer itemContainer,
        @Nonnull EnchantmentManager enchantmentManager,
        @Nonnull ItemContext heldItemContext
    ) {
        super(
            playerRef,
            CleansingScrollPage.getItemElements(itemContainer, enchantmentManager, heldItemContext),
            "Pages/CleansingScrollPage.ui"
        );
        this.enchantmentManager = enchantmentManager;
        this.playerRef = playerRef;
    }

    @Override
    public void build(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull UICommandBuilder commandBuilder,
        @Nonnull UIEventBuilder eventBuilder,
        @Nonnull Store<EntityStore> store
    ) {
        if (this.getElements().length > 0) {
            super.build(ref, commandBuilder, eventBuilder, store);
            translateLabels(commandBuilder);
            return;
        }
        commandBuilder.append(this.getPageLayout());
        commandBuilder.clear("#ElementList");
        commandBuilder.appendInline(
            "#ElementList",
            "Label #NoItemsLabel { Style: (Alignment: Center, TextColor: #333333); }"
        );
        translateLabels(commandBuilder);
    }

    private void translateLabels(UICommandBuilder commandBuilder) {
        org.herolias.plugin.lang.LanguageManager languageManager = enchantmentManager.getPlugin().getLanguageManager();
        String lang = enchantmentManager.getPlugin().getUserSettingsManager().getLanguage(this.playerRef.getUuid());
        String clientLang = this.playerRef.getLanguage();

        commandBuilder.set("#TitleLabel.TextSpans", languageManager.getMessage("customUI.cleansingScrollPage.title", lang, clientLang));
        commandBuilder.set("#ItemLabel.TextSpans", languageManager.getMessage("customUI.cleansingScrollPage.item", lang, clientLang));
        commandBuilder.set("#EnchantmentLabel.TextSpans", languageManager.getMessage("customUI.cleansingScrollPage.enchantments", lang, clientLang));
        
        if (this.getElements().length == 0) {
            commandBuilder.set("#NoItemsLabel.TextSpans", languageManager.getMessage("customUI.cleansingScrollPage.noItems", lang, clientLang));
        }
    }

    @Nonnull
    protected static ChoiceElement[] getItemElements(
        @Nonnull ItemContainer itemContainer,
        @Nonnull EnchantmentManager enchantmentManager,
        @Nonnull ItemContext heldItemContext
    ) {
        ObjectArrayList<ChoiceElement> elements = new ObjectArrayList<>();

        for (short slot = 0; slot < itemContainer.getCapacity(); slot = (short) (slot + 1)) {
            ItemStack itemStack = itemContainer.getItemStack(slot);
            if (ItemStack.isEmpty(itemStack)) {
                continue;
            }

            EnchantmentData data = enchantmentManager.getEnchantmentsFromItem(itemStack);
            if (data.isEmpty()) {
                continue;
            }

            // Item has at least one enchantment
            ItemContext itemContext = new ItemContext(itemContainer, slot, itemStack);
            elements.add(new CleansingScrollElement(
                itemStack,
                data,
                itemContext,
                heldItemContext,
                enchantmentManager
            ));
        }

        return elements.toArray(ChoiceElement[]::new);
    }
}
