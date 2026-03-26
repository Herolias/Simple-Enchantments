package org.herolias.plugin.enchantment;

import com.hypixel.hytale.protocol.BenchRequirement;
import com.hypixel.hytale.protocol.ItemResourceType;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for recipe registries that process single-input-single-output
 * recipes
 * (like Smelting or Campfire Cooking) from Hytale assets.
 */
public abstract class AbstractRecipeRegistry<T extends AbstractRecipeRegistry.Recipe> {

    protected final Map<String, T> byItemId = new HashMap<>();
    protected final Map<String, T> byResourceTypeId = new HashMap<>();
    private volatile boolean initialized;

    /**
     * Gets a recipe for the given input item.
     */
    @Nullable
    public T getRecipe(@Nonnull ItemStack input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        ensureInitialized();

        T recipe = byItemId.get(input.getItemId());
        if (recipe != null) {
            return recipe;
        }

        Item item = input.getItem();
        if (item == null) {
            return null;
        }

        ItemResourceType[] resourceTypes = item.getResourceTypes();
        if (resourceTypes == null) {
            return null;
        }

        for (ItemResourceType resourceType : resourceTypes) {
            if (resourceType == null || resourceType.id == null) {
                continue;
            }
            recipe = byResourceTypeId.get(resourceType.id);
            if (recipe != null) {
                return recipe;
            }
        }

        return null;
    }

    protected void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }

            for (Item item : List.copyOf(Item.getAssetMap().getAssetMap().values())) {
                if (item == null) {
                    continue;
                }

                List<CraftingRecipe> recipes = new ArrayList<>();
                item.collectRecipesToGenerate(recipes);
                if (recipes.isEmpty()) {
                    continue;
                }

                for (CraftingRecipe recipe : recipes) {
                    if (!isValidRecipe(recipe)) {
                        continue;
                    }

                    MaterialQuantity[] inputs = recipe.getInput();
                    if (inputs == null || inputs.length != 1) {
                        continue;
                    }

                    MaterialQuantity input = inputs[0];
                    MaterialQuantity output = recipe.getPrimaryOutput();
                    if (input == null || output == null || output.getItemId() == null) {
                        continue;
                    }

                    T registryRecipe = createRecipe(output, input.getQuantity(), input.getItemId(),
                            input.getResourceTypeId());
                    if (input.getItemId() != null) {
                        byItemId.putIfAbsent(input.getItemId(), registryRecipe);
                    }
                    if (input.getResourceTypeId() != null) {
                        byResourceTypeId.putIfAbsent(input.getResourceTypeId(), registryRecipe);
                    }
                }
            }

            initialized = true;
        }
    }

    /**
     * Checks if the crafting recipe belongs to this registry (e.g., checks bench
     * type).
     */
    protected abstract boolean isValidRecipe(@Nonnull CraftingRecipe recipe);

    /**
     * Creates an instance of the specific Recipe type.
     */
    protected abstract T createRecipe(@Nonnull MaterialQuantity output, int inputQuantity, @Nullable String inputItemId,
            @Nullable String inputResourceTypeId);

    protected boolean checkBenchRequirement(@Nonnull CraftingRecipe recipe, @Nonnull String requiredBenchId) {
        BenchRequirement[] requirements = recipe.getBenchRequirement();
        if (requirements == null) {
            return false;
        }
        for (BenchRequirement requirement : requirements) {
            if (requirement == null || requirement.id == null) {
                continue;
            }
            if (requirement.type == com.hypixel.hytale.protocol.BenchType.Processing
                    && requiredBenchId.equalsIgnoreCase(requirement.id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Represents a simple processing recipe.
     */
    public static class Recipe {
        private final MaterialQuantity output;
        private final int inputQuantity;
        private final String inputItemId;
        private final String inputResourceTypeId;

        public Recipe(@Nonnull MaterialQuantity output, int inputQuantity, @Nullable String inputItemId,
                @Nullable String inputResourceTypeId) {
            this.output = output;
            this.inputQuantity = inputQuantity;
            this.inputItemId = inputItemId;
            this.inputResourceTypeId = inputResourceTypeId;
        }

        @Nullable
        public ItemStack createOutput(int inputCount) {
            if (inputCount <= 0) {
                return null;
            }
            int totalOutput = Math.max(1, inputCount) * output.getQuantity();
            return output.clone(totalOutput).toItemStack();
        }

        public int getInputQuantity() {
            return inputQuantity;
        }

        @Nullable
        public String getInputItemId() {
            return inputItemId;
        }

        @Nullable
        public String getInputResourceTypeId() {
            return inputResourceTypeId;
        }

        @Nonnull
        public String getOutputItemId() {
            return output.getItemId();
        }
    }
}