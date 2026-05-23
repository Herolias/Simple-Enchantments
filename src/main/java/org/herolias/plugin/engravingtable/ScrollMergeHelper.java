package org.herolias.plugin.engravingtable;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.herolias.plugin.enchantment.EnchantmentData;
import org.herolias.plugin.enchantment.EnchantmentManager;
import org.herolias.plugin.enchantment.EnchantmentType;
import org.herolias.plugin.enchantment.NativeTooltipManager;
import org.herolias.plugin.util.ScrollIdHelper;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;

public final class ScrollMergeHelper {
    private ScrollMergeHelper() {
    }

    public record MergeResult(@Nullable ItemStack result, @Nullable String errorMessage) {
        public boolean isSuccessful() {
            return !ItemStack.isEmpty(this.result) && this.errorMessage == null;
        }
    }

    public static boolean isScroll(@Nullable ItemStack itemStack) {
        if (ItemStack.isEmpty(itemStack)) {
            return false;
        }
        return "Scroll_Custom".equals(itemStack.getItemId())
                || ScrollIdHelper.getEnchantmentFromScrollId(itemStack.getItemId()) != null;
    }

    public static boolean hasStoredEnchantments(@Nullable ItemStack itemStack, @Nonnull EnchantmentManager enchantmentManager) {
        return !extractScrollData(itemStack, enchantmentManager).isEmpty()
                || enchantmentManager.hasAnyEnabledEnchantment(itemStack);
    }

    @Nonnull
    public static MergeResult merge(
            @Nullable ItemStack primary,
            @Nullable ItemStack secondary,
            @Nonnull EnchantmentManager enchantmentManager) {
        if (!isScroll(primary)) {
            return new MergeResult(null, "Place a scroll in the first slot.");
        }
        if (ItemStack.isEmpty(secondary)) {
            return new MergeResult(null, "Place a second scroll in the extra slot to merge.");
        }
        if (!isScroll(secondary)) {
            return new MergeResult(null, "Only scrolls can be placed in the extra slot.");
        }
        
        if (isCleansingScroll(primary) || isCleansingScroll(secondary)) {
            return new MergeResult(null, "Cleansing scrolls cannot be merged.");
        }

        EnchantmentData primaryData = extractScrollData(primary, enchantmentManager);
        EnchantmentData secondaryData = extractScrollData(secondary, enchantmentManager);
        if (primaryData.isEmpty() || secondaryData.isEmpty()) {
            return new MergeResult(null, "Both scrolls need enchantment data.");
        }

        boolean allowSameScrollUpgrades = enchantmentManager.getPlugin().getConfigManager().getConfig().allowSameScrollUpgrades;

        EnchantmentData merged = primaryData.copy();
        boolean changed = false;

        for (Map.Entry<EnchantmentType, Integer> entry : secondaryData.getAllEnchantments().entrySet()) {
            EnchantmentType newType = entry.getKey();
            int newLevel = entry.getValue();

            int currentLevel = merged.getLevel(newType);

            if (currentLevel == 0) {
                boolean hasConflict = false;
                for (EnchantmentType existing : merged.getAllEnchantments().keySet()) {
                    if (newType.conflictsWith(existing)) {
                        hasConflict = true;
                        break;
                    }
                }
                if (hasConflict) {
                    return new MergeResult(null, "Scrolls have conflicting enchantments.");
                }
                merged.addEnchantment(newType, newLevel);
                changed = true;
            } else if (currentLevel == newLevel) {
                if (allowSameScrollUpgrades && currentLevel < newType.getMaxLevel()) {
                    merged.addEnchantment(newType, currentLevel + 1);
                    changed = true;
                } else {
                    return new MergeResult(null, "Cannot upgrade this enchantment any further.");
                }
            } else {
                if (newLevel > currentLevel) {
                    merged.addEnchantment(newType, newLevel);
                    changed = true;
                }
            }
        }

        if (!changed) {
            return new MergeResult(null, "These scrolls cannot be merged to create anything new.");
        }

        return new MergeResult(createResultScroll(merged), null);
    }

    private static boolean isCleansingScroll(ItemStack item) {
        if (ItemStack.isEmpty(item)) return false;
        ScrollIdHelper.ScrollEnchantment scrollEnch = ScrollIdHelper.getEnchantmentFromScrollId(item.getItemId());
        return scrollEnch != null && "cleansing".equals(scrollEnch.type().getId());
    }

    @Nonnull
    public static EnchantmentData extractScrollData(
            @Nullable ItemStack itemStack,
            @Nonnull EnchantmentManager enchantmentManager) {
        if (ItemStack.isEmpty(itemStack)) {
            return EnchantmentData.EMPTY;
        }
        if ("Scroll_Custom".equals(itemStack.getItemId())) {
            EnchantmentData data = enchantmentManager.getEnchantmentsFromItem(itemStack);
            return data.isEmpty() ? EnchantmentData.EMPTY : data;
        }

        ScrollIdHelper.ScrollEnchantment regularScroll = ScrollIdHelper.getEnchantmentFromScrollId(itemStack.getItemId());
        if (regularScroll == null) {
            return EnchantmentData.EMPTY;
        }

        EnchantmentData data = new EnchantmentData();
        data.addEnchantment(regularScroll.type(), regularScroll.level());
        return data;
    }

    @Nonnull
    private static ItemStack createResultScroll(@Nonnull EnchantmentData mergedData) {
        if (mergedData.getAllEnchantments().size() == 1) {
            Map.Entry<EnchantmentType, Integer> entry = mergedData.getAllEnchantments().entrySet().iterator().next();
            if (entry.getValue() <= entry.getKey().getMaxLevel()) {
                ItemStack regularScroll = new ItemStack(ScrollIdHelper.getScrollItemId(entry.getKey(), entry.getValue()), 1);
                if (regularScroll.isValid() && !regularScroll.isEmpty()) {
                    return regularScroll;
                }
            }
        }

        return NativeTooltipManager.withEnchantments(new ItemStack("Scroll_Custom", 1), mergedData);
    }
}
