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
import org.herolias.plugin.enchantment.EnchantmentType;
import org.herolias.plugin.enchantment.ItemCategory;

/**
 * Step 2 page for Custom Scroll: lists all items in the player's inventory
 * that can accept the selected enchantment.
 * <p>
 * Filtering logic mirrors {@link EnchantScrollPage}: category check, conflict check,
 * level check, blacklist check.
 */
public class CustomScrollItemPage extends ChoiceBasePage {
    private final EnchantmentManager enchantmentManager;
    private final PlayerRef playerRef;

    public CustomScrollItemPage(
        @Nonnull PlayerRef playerRef,
        @Nonnull ItemContainer itemContainer,
        @Nonnull EnchantmentManager enchantmentManager,
        @Nonnull EnchantmentType enchantmentType,
        int level,
        @Nonnull ItemContext heldItemContext
    ) {
        super(
            playerRef,
            getItemElements(itemContainer, enchantmentManager, enchantmentType, level, heldItemContext),
            "Pages/CustomScrollItemPage.ui"
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
            "Label #NoItemsLabel { Style: (Alignment: Center); }"
        );
        translateLabels(commandBuilder);
    }

    private void translateLabels(UICommandBuilder commandBuilder) {
        org.herolias.plugin.lang.LanguageManager languageManager = enchantmentManager.getPlugin().getLanguageManager();
        String lang = enchantmentManager.getPlugin().getUserSettingsManager().getLanguage(this.playerRef.getUuid());
        String clientLang = this.playerRef.getLanguage();

        commandBuilder.set("#TitleLabel.TextSpans", languageManager.getMessage("customUI.customScrollItemPage.title", lang, clientLang));
        commandBuilder.set("#ItemLabel.TextSpans", languageManager.getMessage("customUI.customScrollItemPage.item", lang, clientLang));
        commandBuilder.set("#EnchantmentLabel.TextSpans", languageManager.getMessage("customUI.customScrollItemPage.enchantment", lang, clientLang));

        if (this.getElements().length == 0) {
            commandBuilder.set("#NoItemsLabel.TextSpans", languageManager.getMessage("customUI.customScrollItemPage.noItems", lang, clientLang));
        }
    }

    @Nonnull
    protected static ChoiceElement[] getItemElements(
        @Nonnull ItemContainer itemContainer,
        @Nonnull EnchantmentManager enchantmentManager,
        @Nonnull EnchantmentType enchantmentType,
        int level,
        @Nonnull ItemContext heldItemContext
    ) {
        ObjectArrayList<ChoiceElement> elements = new ObjectArrayList<>();
        int scrollLevel = Math.max(1, level);
        boolean allowSameScrollUpgrades = enchantmentManager.getPlugin().getConfigManager().getConfig().allowSameScrollUpgrades;

        for (short slot = 0; slot < itemContainer.getCapacity(); slot = (short) (slot + 1)) {
            ItemStack itemStack = itemContainer.getItemStack(slot);
            if (ItemStack.isEmpty(itemStack)) {
                continue;
            }

            // Don't show the held Custom Scroll itself as a target
            if (slot == heldItemContext.getSlot() && itemContainer == heldItemContext.getContainer()) {
                continue;
            }

            if (!enchantmentManager.canAcceptEnchantment(itemStack, enchantmentType)) {
                continue;
            }

            if (org.herolias.plugin.enchantment.ItemCategoryManager.getInstance().isBlacklisted(itemStack)) {
                continue;
            }

            EnchantmentData data = enchantmentManager.getEnchantmentsFromItem(itemStack);

            // Filter out items with conflicting enchantments
            boolean hasConflict = false;
            for (EnchantmentType existing : data.getAllEnchantments().keySet()) {
                if (existing == enchantmentType) {
                    continue;
                }
                if (enchantmentType.conflictsWith(existing)) {
                    hasConflict = true;
                    break;
                }
            }
            if (hasConflict) {
                continue;
            }

            int currentLevel = data.getLevel(enchantmentType);
            int interactionTargetLevel = scrollLevel;

            if (currentLevel == scrollLevel) {
                if (allowSameScrollUpgrades && currentLevel < enchantmentType.getMaxLevel()) {
                    interactionTargetLevel = currentLevel + 1;
                } else {
                    continue;
                }
            } else if (currentLevel > scrollLevel) {
                continue;
            }

            ItemContext itemContext = new ItemContext(itemContainer, slot, itemStack);
            elements.add(new CustomScrollItemElement(
                itemStack,
                enchantmentType,
                interactionTargetLevel,
                currentLevel,
                new CustomScrollApplyInteraction(itemContext, heldItemContext, enchantmentType, interactionTargetLevel, enchantmentManager),
                enchantmentManager
            ));
        }

        elements.sort((a, b) -> {
            boolean aIsScroll = ((CustomScrollItemElement) a).getItemStack().getItemId().startsWith("Scroll_");
            boolean bIsScroll = ((CustomScrollItemElement) b).getItemStack().getItemId().startsWith("Scroll_");
            if (aIsScroll && !bIsScroll) return 1;
            if (!aIsScroll && bIsScroll) return -1;
            return 0; // maintain original order otherwise
        });

        return elements.toArray(ChoiceElement[]::new);
    }
}
