package org.herolias.plugin.engravingtable;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.herolias.plugin.enchantment.EnchantmentData;
import org.herolias.plugin.enchantment.EnchantmentManager;
import org.herolias.plugin.enchantment.EnchantmentType;
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

        EnchantmentData primaryData = extractScrollData(primary, enchantmentManager);
        EnchantmentData secondaryData = extractScrollData(secondary, enchantmentManager);
        if (primaryData.isEmpty() || secondaryData.isEmpty()) {
            return new MergeResult(null, "Both scrolls need enchantment data.");
        }

        EnchantmentData merged = trySingleEnchantUpgrade(primaryData, secondaryData);
        if (merged == null) {
            merged = primaryData.copy();
            for (Map.Entry<EnchantmentType, Integer> entry : secondaryData.getAllEnchantments().entrySet()) {
                int currentLevel = merged.getLevel(entry.getKey());
                merged.addEnchantment(entry.getKey(), Math.max(currentLevel, entry.getValue()));
            }
        }

        return new MergeResult(createResultScroll(merged), null);
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

    @Nullable
    private static EnchantmentData trySingleEnchantUpgrade(
            @Nonnull EnchantmentData primaryData,
            @Nonnull EnchantmentData secondaryData) {
        if (primaryData.getAllEnchantments().size() != 1 || secondaryData.getAllEnchantments().size() != 1) {
            return null;
        }

        Map.Entry<EnchantmentType, Integer> primary = primaryData.getAllEnchantments().entrySet().iterator().next();
        Map.Entry<EnchantmentType, Integer> secondary = secondaryData.getAllEnchantments().entrySet().iterator().next();
        if (primary.getKey() != secondary.getKey() || !primary.getValue().equals(secondary.getValue())) {
            return null;
        }

        EnchantmentData upgraded = new EnchantmentData();
        upgraded.addEnchantment(primary.getKey(), primary.getValue() + 1);
        return upgraded;
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

        return new ItemStack("Scroll_Custom", 1)
                .withMetadata(EnchantmentData.METADATA_KEY, mergedData.toBson());
    }
}
