package org.herolias.plugin.ui;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.choices.ChoiceElement;
import com.hypixel.hytale.server.core.entity.entities.player.pages.choices.ChoiceInteraction;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import javax.annotation.Nonnull;
import org.herolias.plugin.enchantment.EnchantmentType;
import org.herolias.plugin.enchantment.EnchantmentManager;
import org.herolias.plugin.enchantment.EnchantmentData;
import java.util.Map;

public class EnchantScrollElement extends ChoiceElement {
    private final ItemStack itemStack;
    private final EnchantmentType enchantmentType;
    private final int targetLevel;
    private final int currentLevel;
    private final EnchantmentManager enchantmentManager;

    public EnchantScrollElement(
        ItemStack itemStack,
        EnchantmentType enchantmentType,
        int targetLevel,
        int currentLevel,
        EnchantItemInteraction interaction,
        EnchantmentManager enchantmentManager
    ) {
        this.itemStack = itemStack;
        this.enchantmentType = enchantmentType;
        this.targetLevel = targetLevel;
        this.currentLevel = currentLevel;
        this.interactions = new ChoiceInteraction[]{interaction};
        this.enchantmentManager = enchantmentManager;
    }

    @Override
    public void addButton(
        @Nonnull UICommandBuilder commandBuilder,
        UIEventBuilder eventBuilder,
        String selector,
        PlayerRef playerRef
    ) {
        commandBuilder.append("#ElementList", "Pages/EnchantScrollElement.ui");
        commandBuilder.set(selector + " #Icon.ItemId", this.itemStack.getItemId().toString());
        String lang = enchantmentManager.getPlugin().getUserSettingsManager().getLanguage(playerRef.getUuid());
        String clientLang = playerRef.getLanguage();
        org.herolias.plugin.lang.LanguageManager languageManager = enchantmentManager.getPlugin().getLanguageManager();

        commandBuilder.set(selector + " #Name.TextSpans", languageManager.getMessage(this.itemStack.getItem().getTranslationKey(), lang, clientLang));

        StringBuilder detailBuilder = new StringBuilder();
        
        // Display existing enchantments
        EnchantmentData data = enchantmentManager.getEnchantmentsFromItem(itemStack);
        if (data != null && !data.getAllEnchantments().isEmpty()) {
            detailBuilder.append(languageManager.getRawMessage("customUI.enchantScrollPage.current", lang, clientLang)).append(" ");
            boolean first = true;
            for (Map.Entry<EnchantmentType, Integer> entry : data.getAllEnchantments().entrySet()) {
                if (!first) {
                    detailBuilder.append(", ");
                }
                String translatedName = languageManager.getRawMessage(entry.getKey().getNameKey(), lang, clientLang);
                detailBuilder.append(translatedName).append(" ").append(EnchantmentType.toRoman(entry.getValue()));
                first = false;
            }
            detailBuilder.append("\n");
        }

        String targetName = languageManager.getRawMessage(enchantmentType.getNameKey(), lang, clientLang) + " " + EnchantmentType.toRoman(targetLevel);
        if (currentLevel > 0) {
            String currentName = languageManager.getRawMessage(enchantmentType.getNameKey(), lang, clientLang) + " " + EnchantmentType.toRoman(currentLevel);
            detailBuilder.append(languageManager.getRawMessage("customUI.enchantScrollPage.upgrade", lang, clientLang)).append(" ").append(currentName)
                .append(" -> ").append(targetName);
        } else {
            detailBuilder.append(languageManager.getRawMessage("customUI.enchantScrollPage.apply", lang, clientLang)).append(" ").append(targetName);
        }
        
        commandBuilder.set(selector + " #Detail.Text", detailBuilder.toString());
    }

    public ItemStack getItemStack() {
        return this.itemStack;
    }
}
