package org.herolias.plugin.ui;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.choices.ChoiceElement;
import com.hypixel.hytale.server.core.entity.entities.player.pages.choices.ChoiceInteraction;
import com.hypixel.hytale.server.core.inventory.ItemContext;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import javax.annotation.Nonnull;
import org.herolias.plugin.enchantment.EnchantmentManager;
import org.herolias.plugin.enchantment.EnchantmentType;

/**
 * Element representing an enchantment that can be removed.
 * Clicking triggers the removal interaction.
 */
public class CleansingEnchantmentElement extends ChoiceElement {
    private final EnchantmentType enchantmentType;
    private final int level;
    private final EnchantmentManager enchantmentManager;

    public CleansingEnchantmentElement(
        EnchantmentType enchantmentType,
        int level,
        ItemContext itemContext,
        ItemContext heldItemContext,
        EnchantmentManager enchantmentManager
    ) {
        this.enchantmentType = enchantmentType;
        this.level = level;
        this.enchantmentManager = enchantmentManager;

        this.interactions = new ChoiceInteraction[]{
            new RemoveEnchantmentInteraction(itemContext, heldItemContext, enchantmentType, enchantmentManager)
        };
    }

    @Override
    public void addButton(
        @Nonnull UICommandBuilder commandBuilder,
        UIEventBuilder eventBuilder,
        String selector,
        PlayerRef playerRef
    ) {
        // Use the new element UI for light backgrounds
        commandBuilder.append("#ElementList", "Pages/CleansingScrollElement.ui");
        
        org.herolias.plugin.lang.LanguageManager languageManager = enchantmentManager.getPlugin().getLanguageManager();
        String lang = enchantmentManager.getPlugin().getUserSettingsManager().getLanguage(playerRef.getUuid());
        String clientLang = playerRef.getLanguage();

        // Use a generic scroll icon for the enchantment display
        commandBuilder.set(selector + " #Icon.ItemId", "Scroll_Sharpness_I");
        
        String translatedName = languageManager.getRawMessage(enchantmentType.getNameKey(), lang, clientLang);
        commandBuilder.set(selector + " #Name.Text", translatedName + " " + EnchantmentType.toRoman(level));
        
        commandBuilder.set(selector + " #Detail.Text", languageManager.getRawMessage("customUI.cleansingEnchantmentPage.clickToRemove", lang, clientLang));
    }
}
