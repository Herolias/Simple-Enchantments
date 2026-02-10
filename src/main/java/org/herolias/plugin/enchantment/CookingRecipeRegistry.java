package org.herolias.plugin.enchantment;

import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Builds and resolves campfire-cooking recipes from loaded item assets.
 */
public class CookingRecipeRegistry extends AbstractRecipeRegistry<CookingRecipeRegistry.CookingRecipe> {

    @Override
    protected boolean isValidRecipe(@Nonnull CraftingRecipe recipe) {
        return checkBenchRequirement(recipe, "Campfire");
    }

    @Override
    protected CookingRecipe createRecipe(@Nonnull MaterialQuantity output, int inputQuantity, @Nullable String inputItemId, @Nullable String inputResourceTypeId) {
        return new CookingRecipe(output, inputQuantity, inputItemId, inputResourceTypeId);
    }

    public static class CookingRecipe extends AbstractRecipeRegistry.Recipe {
        public CookingRecipe(@Nonnull MaterialQuantity output, int inputQuantity, @Nullable String inputItemId, @Nullable String inputResourceTypeId) {
            super(output, inputQuantity, inputItemId, inputResourceTypeId);
        }
    }
}
