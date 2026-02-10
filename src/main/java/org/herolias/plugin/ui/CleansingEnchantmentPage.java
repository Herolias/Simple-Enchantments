package org.herolias.plugin.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.player.pages.choices.ChoiceBasePage;
import com.hypixel.hytale.server.core.entity.entities.player.pages.choices.ChoiceElement;
import com.hypixel.hytale.server.core.inventory.ItemContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
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
 * Page displaying all enchantments on a selected item.
 * Selecting an enchantment removes it from the item.
 */
public class CleansingEnchantmentPage extends ChoiceBasePage {
    public CleansingEnchantmentPage(
        @Nonnull PlayerRef playerRef,
        @Nonnull ItemContext itemContext,
        @Nonnull ItemContext heldItemContext,
        @Nonnull EnchantmentData enchantmentData,
        @Nonnull EnchantmentManager enchantmentManager
    ) {
        super(
            playerRef,
            CleansingEnchantmentPage.getEnchantmentElements(itemContext, heldItemContext, enchantmentData, enchantmentManager),
            "Pages/CleansingScrollEnchantmentPage.ui"
        );
    }

    @Override
    public void build(
        @Nonnull Ref<EntityStore> ref,
        @Nonnull UICommandBuilder commandBuilder,
        @Nonnull UIEventBuilder eventBuilder,
        @Nonnull Store<EntityStore> store
    ) {
        super.build(ref, commandBuilder, eventBuilder, store);
    }

    @Nonnull
    protected static ChoiceElement[] getEnchantmentElements(
        @Nonnull ItemContext itemContext,
        @Nonnull ItemContext heldItemContext,
        @Nonnull EnchantmentData enchantmentData,
        @Nonnull EnchantmentManager enchantmentManager
    ) {
        ObjectArrayList<ChoiceElement> elements = new ObjectArrayList<>();

        for (Map.Entry<EnchantmentType, Integer> entry : enchantmentData.getAllEnchantments().entrySet()) {
            EnchantmentType type = entry.getKey();
            int level = entry.getValue();

            elements.add(new CleansingEnchantmentElement(
                type,
                level,
                itemContext,
                heldItemContext,
                enchantmentManager
            ));
        }

        return elements.toArray(ChoiceElement[]::new);
    }
}
