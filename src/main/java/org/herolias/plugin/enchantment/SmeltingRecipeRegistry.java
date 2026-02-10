package org.herolias.plugin.enchantment;

import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Builds and resolves furnace-smelting recipes from loaded item assets.
 */
public class SmeltingRecipeRegistry extends AbstractRecipeRegistry<SmeltingRecipeRegistry.SmeltingRecipe> {

    @Override
    protected boolean isValidRecipe(@Nonnull CraftingRecipe recipe) {
        return checkBenchRequirement(recipe, "Furnace");
    }

    @Override
    protected SmeltingRecipe createRecipe(@Nonnull MaterialQuantity output, int inputQuantity, @Nullable String inputItemId, @Nullable String inputResourceTypeId) {
        return new SmeltingRecipe(output, inputQuantity, inputItemId, inputResourceTypeId);
    }

    public static class SmeltingRecipe extends AbstractRecipeRegistry.Recipe {
        public SmeltingRecipe(@Nonnull MaterialQuantity output, int inputQuantity, @Nullable String inputItemId, @Nullable String inputResourceTypeId) {
            super(output, inputQuantity, inputItemId, inputResourceTypeId);
        }
    }
}
