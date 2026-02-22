package org.herolias.plugin.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.PageManager;
import com.hypixel.hytale.server.core.entity.entities.player.pages.choices.ChoiceInteraction;
import com.hypixel.hytale.server.core.inventory.ItemContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.enchantment.EnchantmentData;
import org.herolias.plugin.enchantment.EnchantmentManager;
import org.herolias.plugin.enchantment.EnchantmentType;

/**
 * Interaction that removes an enchantment from an item and consumes the Scroll of Cleansing.
 */
public class RemoveEnchantmentInteraction extends ChoiceInteraction {
    private final ItemContext itemContext;
    private final ItemContext heldItemContext;
    private final EnchantmentType enchantmentType;
    private final EnchantmentManager enchantmentManager;

    public RemoveEnchantmentInteraction(
        ItemContext itemContext,
        ItemContext heldItemContext,
        EnchantmentType enchantmentType,
        EnchantmentManager enchantmentManager
    ) {
        this.itemContext = itemContext;
        this.heldItemContext = heldItemContext;
        this.enchantmentType = enchantmentType;
        this.enchantmentManager = enchantmentManager;
    }

    @Override
    public void run(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef) {
        Player playerComponent = store.getComponent(ref, Player.getComponentType());
        if (playerComponent == null) {
            return;
        }

        PageManager pageManager = playerComponent.getPageManager();
        
        // Validate item still exists and has the enchantment
        ItemStack itemStack = this.itemContext.getItemStack();
        if (ItemStack.isEmpty(itemStack)) {
            playerRef.sendMessage(Message.raw("The item is no longer available."));
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        EnchantmentData data = enchantmentManager.getEnchantmentsFromItem(itemStack);
        org.herolias.plugin.lang.LanguageManager languageManager = enchantmentManager.getPlugin().getLanguageManager();
        String lang = enchantmentManager.getPlugin().getUserSettingsManager().getLanguage(playerRef.getUuid());
        String clientLang = playerRef.getLanguage();

        if (!data.hasEnchantment(enchantmentType)) {
            String translatedName = languageManager.getRawMessage(enchantmentType.getNameKey(), lang, clientLang);
            playerRef.sendMessage(Message.raw("This item no longer has " + translatedName + "."));
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        // Get the level before removing (for message)
        int removedLevel = data.getLevel(enchantmentType);

        // Remove the enchantment
        data.removeEnchantment(enchantmentType);

        // Write back to item metadata
        org.bson.BsonDocument bson = data.isEmpty() ? null : data.toBson();
        ItemStack cleanedItem = itemStack.withMetadata(EnchantmentData.METADATA_KEY, bson);

        // Update visuals
        cleanedItem = enchantmentManager.updateItemVisuals(cleanedItem);

        // Consume the scroll
        ItemContainer heldItemContainer = this.heldItemContext.getContainer();
        ItemStack heldItemStack = this.heldItemContext.getItemStack();
        short heldItemSlot = this.heldItemContext.getSlot();

        ItemStackSlotTransaction removeTransaction =
            heldItemContainer.removeItemStackFromSlot(heldItemSlot, heldItemStack, 1);
        if (!removeTransaction.succeeded()) {
            playerRef.sendMessage(Message.raw("Failed to consume the scroll."));
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        // Replace the item with the cleaned version
        ItemStackSlotTransaction replaceTransaction =
            this.itemContext.getContainer().replaceItemStackInSlot(this.itemContext.getSlot(), itemStack, cleanedItem);
        if (!replaceTransaction.succeeded()) {
            // Restore the scroll if item replacement failed
            SimpleItemContainer.addOrDropItemStack(store, ref, heldItemContainer, heldItemSlot, heldItemStack.withQuantity(1));
            playerRef.sendMessage(Message.raw("Failed to update the item."));
            pageManager.setPage(ref, store, Page.None);
            return;
        }

        // Check config for returning enchantment as scroll
        boolean returnEnchantment = SimpleEnchanting.getInstance().getConfigManager().getConfig().returnEnchantmentOnCleanse;
        if (returnEnchantment) {
            // Try to give back the scroll for this enchantment
            String scrollId = getScrollItemId(enchantmentType, removedLevel);
            if (scrollId != null) {
                try {
                    // Use the ItemStack constructor with item ID string
                    ItemStack scrollStack = new ItemStack(scrollId, 1);
                    // Verify the item exists by checking if it's valid
                    if (scrollStack.isValid() && !scrollStack.isEmpty()) {
                        ItemContainer playerInventory = playerComponent.getInventory().getCombinedArmorHotbarUtilityStorage();
                        SimpleItemContainer.addOrDropItemStack(store, ref, playerInventory, (short) 0, scrollStack);
                        String translatedName = languageManager.getRawMessage(enchantmentType.getNameKey(), lang, clientLang) + " " + EnchantmentType.toRoman(removedLevel);
                        playerRef.sendMessage(Message.raw("Returned: " + translatedName));
                    }
                } catch (Exception e) {
                    // Scroll doesn't exist, don't return anything
                }
            }
        }

        // Send confirmation
        Message itemName = languageManager.getMessage(cleanedItem.getItem().getTranslationKey(), lang, clientLang);
        String translatedName = languageManager.getRawMessage(enchantmentType.getNameKey(), lang, clientLang) + " " + EnchantmentType.toRoman(removedLevel);
        Message removedMessage = Message.raw("Removed " + translatedName + " from ").insert(itemName);
        playerRef.sendMessage(removedMessage);
        
        pageManager.setPage(ref, store, Page.None);
    }

    /**
     * Attempts to construct the scroll item ID for a given enchantment type and level.
     */
    private String getScrollItemId(EnchantmentType type, int level) {
        // Convert enchantment ID to scroll name format
        // e.g. "sharpness" -> "Scroll_Sharpness_I", "life_leech" -> "Scroll_Life_Leech_I"
        String id = type.getId();
        
        // Convert to title case with underscores
        StringBuilder sb = new StringBuilder("Scroll_");
        boolean capitalizeNext = true;
        for (char c : id.toCharArray()) {
            if (c == '_') {
                sb.append('_');
                capitalizeNext = true;
            } else if (capitalizeNext) {
                sb.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                sb.append(c);
            }
        }
        
        // Append roman numeral level
        sb.append('_');
        sb.append(toRoman(level));
        
        return sb.toString();
    }

    private String toRoman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> "I";
        };
    }
}
