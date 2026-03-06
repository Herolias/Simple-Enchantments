package org.herolias.plugin.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.player.pages.choices.ChoiceBasePage;
import com.hypixel.hytale.server.core.entity.entities.player.pages.choices.ChoiceElement;
import com.hypixel.hytale.server.core.inventory.ItemContext;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import javax.annotation.Nonnull;
import org.herolias.plugin.enchantment.EnchantmentData;
import org.herolias.plugin.enchantment.EnchantmentManager;
import org.herolias.plugin.enchantment.EnchantmentType;

import java.util.Map;

/**
 * Step 1 page for Custom Scroll: lists all enchantments on the scroll.
 * Selecting an enchantment opens the target item selection page.
 */
public class CustomScrollEnchantmentPage extends ChoiceBasePage {
    private final EnchantmentManager enchantmentManager;
    private final PlayerRef playerRef;

    public CustomScrollEnchantmentPage(
        @Nonnull PlayerRef playerRef,
        @Nonnull EnchantmentData scrollData,
        @Nonnull ItemContext heldItemContext,
        @Nonnull ItemContainer itemContainer,
        @Nonnull EnchantmentManager enchantmentManager
    ) {
        super(
            playerRef,
            getEnchantmentElements(scrollData, heldItemContext, itemContainer, enchantmentManager, playerRef),
            "Pages/CustomScrollEnchantmentPage.ui"
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
            "Label #NoEnchantmentsLabel { Style: (Alignment: Center); }"
        );
        translateLabels(commandBuilder);
    }

    private void translateLabels(UICommandBuilder commandBuilder) {
        org.herolias.plugin.lang.LanguageManager languageManager = enchantmentManager.getPlugin().getLanguageManager();
        String lang = enchantmentManager.getPlugin().getUserSettingsManager().getLanguage(this.playerRef.getUuid());
        String clientLang = this.playerRef.getLanguage();

        commandBuilder.set("#TitleLabel.TextSpans", languageManager.getMessage("customUI.customScrollEnchantmentPage.title", lang, clientLang));
        commandBuilder.set("#SelectHintLabel.TextSpans", languageManager.getMessage("customUI.customScrollEnchantmentPage.selectHint", lang, clientLang));

        if (this.getElements().length == 0) {
            commandBuilder.set("#NoEnchantmentsLabel.TextSpans", languageManager.getMessage("customUI.customScrollEnchantmentPage.noEnchantments", lang, clientLang));
        }
    }

    @Nonnull
    protected static ChoiceElement[] getEnchantmentElements(
        @Nonnull EnchantmentData scrollData,
        @Nonnull ItemContext heldItemContext,
        @Nonnull ItemContainer itemContainer,
        @Nonnull EnchantmentManager enchantmentManager,
        @Nonnull PlayerRef playerRef
    ) {
        ObjectArrayList<ChoiceElement> elements = new ObjectArrayList<>();

        for (Map.Entry<EnchantmentType, Integer> entry : scrollData.getAllEnchantments().entrySet()) {
            EnchantmentType type = entry.getKey();
            int level = entry.getValue();

            elements.add(new CustomScrollEnchantmentElement(
                type,
                level,
                heldItemContext,
                itemContainer,
                enchantmentManager,
                playerRef
            ));
        }

        return elements.toArray(ChoiceElement[]::new);
    }
}
