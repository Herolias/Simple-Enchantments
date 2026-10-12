package org.herolias.plugin.enchantment;

import com.hypixel.hytale.builtin.crafting.CraftingPlugin;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.BenchRequirement;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.protocol.ItemResourceType;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for recipe registries that process single-input-single-output
 * recipes (like Smelting or Campfire Cooking).
 *
 * <p>
 * Recipes are taken from the crafting plugin's per-bench registry
 * ({@link CraftingPlugin#getBenchRecipes(BenchType, String)}), so recipes
 * generated from item assets, standalone recipe assets under
 * {@code Item/Recipes/**} and recipes added by other plugins are all
 * included. The registry is built lazily once, into temporary maps that are
 * published atomically; {@link #reload()} rebuilds it (wire it to
 * {@code LoadedAssetsEvent<CraftingRecipe>} so asset reloads are picked up).
 * </p>
 */
public abstract class AbstractRecipeRegistry<T extends AbstractRecipeRegistry.Recipe> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Immutable, atomically published lookup tables. */
    private record Snapshot<T>(Map<String, T> byItemId, Map<String, T> byResourceTypeId) {
    }

    private volatile Snapshot<T> snapshot;
    private volatile int generation;

    /**
     * Gets a recipe for the given input item.
     */
    @Nullable
    public T getRecipe(@Nonnull ItemStack input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        Snapshot<T> current = snapshot();
        if (current == null) {
            // No recipe assets are loaded yet; retry on the next call.
            return null;
        }

        T recipe = current.byItemId().get(input.getItemId());
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
            recipe = current.byResourceTypeId().get(resourceType.id);
            if (recipe != null) {
                return recipe;
            }
        }

        return null;
    }

    /**
     * Forces the registry to be built if it is not yet.
     *
     * @return true if a snapshot is available
     */
    public boolean ensureBuilt() {
        return snapshot() != null;
    }

    /**
     * Monotonic counter incremented every time a new snapshot is published.
     * Callers that cache results derived from this registry can compare it to
     * detect a {@link #reload()}.
     */
    public int getGeneration() {
        return generation;
    }

    /**
     * Rebuilds the registry from the currently loaded recipes and publishes the
     * result atomically. Intended to be called from
     * {@code LoadedAssetsEvent<CraftingRecipe>}.
     */
    public void reload() {
        synchronized (this) {
            Snapshot<T> built = build();
            if (built != null) {
                publish(built);
            }
        }
    }

    @Nullable
    private Snapshot<T> snapshot() {
        Snapshot<T> current = snapshot;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = snapshot;
            if (current != null) {
                return current;
            }
            Snapshot<T> built = build();
            if (built != null) {
                publish(built);
            }
            return built;
        }
    }

    private void publish(@Nonnull Snapshot<T> built) {
        snapshot = built;
        generation++;
    }

    /**
     * Computes a new snapshot. Returns null when no crafting recipes are loaded
     * at all (so nothing is cached and a later call retries) or when the scan
     * failed.
     */
    @Nullable
    private Snapshot<T> build() {
        List<CraftingRecipe> candidates;
        try {
            candidates = CraftingPlugin.getBenchRecipes(BenchType.Processing, benchId());
            if (candidates.isEmpty()) {
                // Bench registry not populated (yet); fall back to the raw asset map.
                candidates = new ArrayList<>(CraftingRecipe.getAssetMap().getAssetMap().values());
            }
        } catch (RuntimeException e) {
            LOGGER.atWarning().withCause(e).log("Failed to collect %s recipes; will retry later", benchId());
            return null;
        }
        if (candidates.isEmpty()) {
            return null;
        }

        Map<String, T> byItemId = new HashMap<>();
        Map<String, T> byResourceTypeId = new HashMap<>();
        try {
            for (CraftingRecipe recipe : candidates) {
                if (recipe == null || !isValidRecipe(recipe)) {
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
        } catch (RuntimeException e) {
            LOGGER.atWarning().withCause(e).log("Failed to build %s recipe registry; will retry later", benchId());
            return null;
        }

        LOGGER.atFine().log("Built %s recipe registry: %d item recipes, %d resource-type recipes", benchId(),
                byItemId.size(), byResourceTypeId.size());
        return new Snapshot<>(Collections.unmodifiableMap(byItemId), Collections.unmodifiableMap(byResourceTypeId));
    }

    /**
     * The processing bench id whose recipes make up this registry (e.g.
     * {@code "Furnace"}).
     */
    @Nonnull
    protected abstract String benchId();

    /**
     * Checks if the crafting recipe belongs to this registry (e.g., checks bench
     * type).
     */
    protected boolean isValidRecipe(@Nonnull CraftingRecipe recipe) {
        return checkBenchRequirement(recipe, benchId());
    }

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
            if (requirement.type == BenchType.Processing && requiredBenchId.equalsIgnoreCase(requirement.id)) {
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
            int recipesCompleted = inputCount / Math.max(1, inputQuantity);
            if (recipesCompleted <= 0) {
                return null;
            }
            int totalOutput = recipesCompleted * output.getQuantity();
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
