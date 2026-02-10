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

public class EnchantScrollElement extends ChoiceElement {
    private final ItemStack itemStack;
    private final EnchantmentType enchantmentType;
    private final int targetLevel;
    private final int currentLevel;

    public EnchantScrollElement(
        ItemStack itemStack,
        EnchantmentType enchantmentType,
        int targetLevel,
        int currentLevel,
        EnchantItemInteraction interaction
    ) {
        this.itemStack = itemStack;
        this.enchantmentType = enchantmentType;
        this.targetLevel = targetLevel;
        this.currentLevel = currentLevel;
        this.interactions = new ChoiceInteraction[]{interaction};
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
        commandBuilder.set(selector + " #Name.TextSpans", Message.translation(this.itemStack.getItem().getTranslationKey()));

        String detail;
        if (currentLevel > 0) {
            detail = "Upgrade: " + enchantmentType.getFormattedName(currentLevel)
                + " -> " + enchantmentType.getFormattedName(targetLevel);
        } else {
            detail = "Apply: " + enchantmentType.getFormattedName(targetLevel);
        }
        commandBuilder.set(selector + " #Detail.Text", detail);
    }
}
