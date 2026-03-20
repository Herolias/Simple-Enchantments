package org.herolias.plugin.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.PageManager;
import com.hypixel.hytale.server.core.entity.entities.player.pages.choices.ChoiceElement;
import com.hypixel.hytale.server.core.entity.entities.player.pages.choices.ChoiceInteraction;
import com.hypixel.hytale.server.core.inventory.ItemContext;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import org.herolias.plugin.enchantment.EnchantmentManager;
import org.herolias.plugin.enchantment.EnchantmentType;

/**
 * Choice element for a single enchantment on the Custom Scroll.
 * When selected, opens the target item selection page for this enchantment.
 */
public class CustomScrollEnchantmentElement extends ChoiceElement {
    private final EnchantmentType enchantmentType;
    private final int level;
    private final ItemContext heldItemContext;
    private final ItemContainer itemContainer;
    private final EnchantmentManager enchantmentManager;
    private final PlayerRef playerRef;

    public CustomScrollEnchantmentElement(
            @Nonnull EnchantmentType enchantmentType,
            int level,
            @Nonnull ItemContext heldItemContext,
            @Nonnull ItemContainer itemContainer,
            @Nonnull EnchantmentManager enchantmentManager,
            @Nonnull PlayerRef playerRef) {
        this.enchantmentType = enchantmentType;
        this.level = level;
        this.heldItemContext = heldItemContext;
        this.itemContainer = itemContainer;
        this.enchantmentManager = enchantmentManager;
        this.playerRef = playerRef;

        // The interaction opens the item selection page for this enchantment
        this.interactions = new ChoiceInteraction[] {
                new CustomScrollSelectEnchantmentInteraction(
                        enchantmentType, level, heldItemContext, itemContainer, enchantmentManager)
        };
    }

    @Override
    public void addButton(
            @Nonnull UICommandBuilder commandBuilder,
            UIEventBuilder eventBuilder,
            String selector,
            PlayerRef playerRef) {
        commandBuilder.append("#ElementList", "Pages/CustomScrollEnchantmentElement.ui");

        org.herolias.plugin.lang.LanguageManager languageManager = enchantmentManager.getPlugin().getLanguageManager();
        String lang = enchantmentManager.getPlugin().getUserSettingsManager().getLanguage(playerRef.getUuid());
        String clientLang = playerRef.getLanguage();

        String translatedName = languageManager.getRawMessage(enchantmentType.getNameKey(), lang, clientLang);
        String displayName = translatedName + " " + EnchantmentType.toRoman(level);
        commandBuilder.set(selector + " #Name.TextSpans", com.hypixel.hytale.server.core.Message.raw(displayName));

        String bonusDesc = enchantmentType.getBonusDescription(level, lang, clientLang);
        commandBuilder.set(selector + " #Detail.Text", bonusDesc);
    }

    /**
     * Interaction that opens the item selection page when an enchantment is
     * selected.
     */
    static class CustomScrollSelectEnchantmentInteraction extends ChoiceInteraction {
        private final EnchantmentType enchantmentType;
        private final int level;
        private final ItemContext heldItemContext;
        private final ItemContainer itemContainer;
        private final EnchantmentManager enchantmentManager;

        CustomScrollSelectEnchantmentInteraction(
                @Nonnull EnchantmentType enchantmentType,
                int level,
                @Nonnull ItemContext heldItemContext,
                @Nonnull ItemContainer itemContainer,
                @Nonnull EnchantmentManager enchantmentManager) {
            this.enchantmentType = enchantmentType;
            this.level = level;
            this.heldItemContext = heldItemContext;
            this.itemContainer = itemContainer;
            this.enchantmentManager = enchantmentManager;
        }

        @Override
        public void run(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef) {
            Player playerComponent = store.getComponent(ref, Player.getComponentType());
            if (playerComponent == null) {
                return;
            }

            PageManager pageManager = playerComponent.getPageManager();
            CustomScrollItemPage itemPage = new CustomScrollItemPage(
                    playerRef, itemContainer, enchantmentManager, enchantmentType, level, heldItemContext);
            pageManager.openCustomPage(ref, store, itemPage);
        }
    }
}
