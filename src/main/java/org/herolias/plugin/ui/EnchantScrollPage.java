package org.herolias.plugin.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.player.pages.choices.ChoiceBasePage;
import com.hypixel.hytale.server.core.entity.entities.player.pages.choices.ChoiceElement;
import com.hypixel.hytale.server.core.inventory.ItemContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
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

public class EnchantScrollPage extends ChoiceBasePage {
    private final EnchantmentManager enchantmentManager;
    private final PlayerRef playerRef;

    public EnchantScrollPage(
            @Nonnull PlayerRef playerRef,
            @Nonnull ItemContainer itemContainer,
            @Nonnull EnchantmentManager enchantmentManager,
            @Nonnull EnchantmentType enchantmentType,
            int level,
            @Nonnull ItemContext heldItemContext) {
        super(
                playerRef,
                EnchantScrollPage.getItemElements(itemContainer, enchantmentManager, enchantmentType, level,
                        heldItemContext),
                "Pages/EnchantScrollPage.ui");
        this.enchantmentManager = enchantmentManager;
        this.playerRef = playerRef;
    }

    @Override
    public void build(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder commandBuilder,
            @Nonnull UIEventBuilder eventBuilder,
            @Nonnull Store<EntityStore> store) {
        if (this.getElements().length > 0) {
            super.build(ref, commandBuilder, eventBuilder, store);
            translateLabels(commandBuilder);
            return;
        }
        commandBuilder.append(this.getPageLayout());
        commandBuilder.clear("#ElementList");
        commandBuilder.appendInline(
                "#ElementList",
                "Label #NoItemsLabel { Style: (Alignment: Center); }");
        translateLabels(commandBuilder);
    }

    private void translateLabels(UICommandBuilder commandBuilder) {
        org.herolias.plugin.lang.LanguageManager languageManager = enchantmentManager.getPlugin().getLanguageManager();
        String lang = enchantmentManager.getPlugin().getUserSettingsManager().getLanguage(this.playerRef.getUuid());
        String clientLang = this.playerRef.getLanguage();

        commandBuilder.set("#TitleLabel.TextSpans",
                languageManager.getMessage("customUI.enchantScrollPage.title", lang, clientLang));
        commandBuilder.set("#ItemLabel.TextSpans",
                languageManager.getMessage("customUI.enchantScrollPage.item", lang, clientLang));
        commandBuilder.set("#EnchantmentLabel.TextSpans",
                languageManager.getMessage("customUI.enchantScrollPage.enchantment", lang, clientLang));

        if (this.getElements().length == 0) {
            commandBuilder.set("#NoItemsLabel.TextSpans",
                    languageManager.getMessage("customUI.enchantScrollPage.noItems", lang, clientLang));
        }
    }

    @Nonnull
    protected static ChoiceElement[] getItemElements(
            @Nonnull ItemContainer itemContainer,
            @Nonnull EnchantmentManager enchantmentManager,
            @Nonnull EnchantmentType enchantmentType,
            int level,
            @Nonnull ItemContext heldItemContext) {
        ObjectArrayList<ChoiceElement> elements = new ObjectArrayList<>();
        int scrollLevel = Math.max(1, Math.min(level, enchantmentType.getMaxLevel()));
        boolean allowSameScrollUpgrades = enchantmentManager.getPlugin().getConfigManager()
                .getConfig().allowSameScrollUpgrades;

        for (short slot = 0; slot < itemContainer.getCapacity(); slot = (short) (slot + 1)) {
            ItemStack itemStack = itemContainer.getItemStack(slot);
            if (ItemStack.isEmpty(itemStack)) {
                continue;
            }

            // Skip the held scroll itself (the page iterates a combined container,
            // so the held slot has to be mapped onto the section container it lives in)
            if (isHeldSlot(itemContainer, slot, heldItemContext)) {
                continue;
            }

            String itemId = itemStack.getItemId();

            // Scrolls can no longer be enchanted via the menu; use Engraving Table instead.
            // This also guarantees the held scroll never shows up as a target.
            if (itemId != null && itemId.startsWith("Scroll_")) {
                continue;
            }

            // --- Regular item targets (existing logic) ---
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
                // Ignore self-conflict to allow upgrading
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
            elements.add(new EnchantScrollElement(
                    itemStack,
                    enchantmentType,
                    interactionTargetLevel,
                    currentLevel,
                    new EnchantItemInteraction(itemContext, heldItemContext, enchantmentType, interactionTargetLevel,
                            enchantmentManager),
                    enchantmentManager));
        }

        return elements.toArray(ChoiceElement[]::new);
    }

    /**
     * Returns true when {@code slot} of {@code container} is the slot the held
     * item was read from. {@code container} is usually a
     * {@link CombinedItemContainer} while the held context refers to one of its
     * section containers, so the combined slot index is mapped to the matching
     * inner container and slot before comparing.
     */
    static boolean isHeldSlot(@Nonnull ItemContainer container, short slot, @Nonnull ItemContext heldItemContext) {
        ItemContainer heldContainer = heldItemContext.getContainer();
        if (container == heldContainer) {
            return slot == heldItemContext.getSlot();
        }
        if (container instanceof CombinedItemContainer combined) {
            short remaining = slot;
            for (int i = 0; i < combined.getContainersSize(); i++) {
                ItemContainer inner = combined.getContainer(i);
                short capacity = inner.getCapacity();
                if (remaining < capacity) {
                    return inner == heldContainer && remaining == heldItemContext.getSlot();
                }
                remaining = (short) (remaining - capacity);
            }
        }
        return false;
    }
}
