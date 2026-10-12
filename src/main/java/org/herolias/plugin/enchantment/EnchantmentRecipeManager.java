package org.herolias.plugin.enchantment;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetLoadResult;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.RawAsset;
import com.hypixel.hytale.assetstore.codec.ContainedAssetCodec;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.BenchRequirement;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.Bench;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.BenchTierLevel;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.BenchUpgradeRequirement;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import org.bson.BsonDocument;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.config.EnchantingConfig;
import org.herolias.plugin.config.EnchantingConfig.ConfigIngredient;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages dynamic enabling/disabling and config overrides of crafting recipes
 * (enchantment scrolls, the Enchanting Table and the Engraving Table).
 * <p>
 * Three layers of protection:
 * <ul>
 * <li>When recipes are loaded, recipes for disabled scrolls / disabled table
 * crafting are removed from the asset store (so they disappear from the bench
 * UI) and recipes whose ingredients or tier differ from the config are replaced
 * by a config-shaped copy loaded through the {@code CraftingRecipe} codec.</li>
 * <li>{@link #reload()} re-applies the same logic to the live asset store after
 * the config was changed in-game: newly disabled recipes are removed, recipes
 * that were previously removed are restored, and overrides are refreshed.</li>
 * <li>{@link CraftRecipeCancelSystem} (an ECS event system) calls
 * {@link #shouldCancelCraft(CraftingRecipe)} to cancel any craft that still
 * slips through (e.g. a client that cached the recipe list).</li>
 * </ul>
 * Everything this class loads into the asset store is registered under the
 * plugin's own asset-pack key so it is removed with the plugin.
 */
public class EnchantmentRecipeManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String ENCHANTING_TABLE_RECIPE_PREFIX = "Enchanting_Table";
    private static final String ENGRAVING_TABLE_RECIPE_PREFIX = "Engraving_Table";
    private static final String WORKBENCH_ID = "Workbench";
    private static final String ENCHANTING_BENCH_ID = "Enchantingbench";
    private static final String GENERATED_RECIPE_INFIX = "_Recipe_Generated_";

    // Maps enchantment ID to list of scroll item IDs (not recipe IDs)
    // e.g., "sharpness" -> ["Scroll_Sharpness_I", "Scroll_Sharpness_II", "Scroll_Sharpness_III"]
    private static final Map<String, List<String>> ENCHANTMENT_SCROLL_ITEMS = new HashMap<>();

    // Set of disabled scroll item IDs for quick lookup
    private static final Set<String> DISABLED_SCROLL_ITEM_IDS = ConcurrentHashMap.newKeySet();

    /**
     * Recipes we removed from the asset store because they were disabled, kept so
     * {@link #reload()} can restore them when they are enabled again.
     */
    private static final Map<String, CraftingRecipe> REMOVED_RECIPES = new ConcurrentHashMap<>();

    private static SimpleEnchanting plugin;
    private static boolean initialized = false;
    private static boolean isApplyingOverrides = false;

    // ───────────────────── Lifecycle ─────────────────────

    /**
     * Registers the asset listeners. Should be called during plugin setup().
     * The {@link CraftRecipeCancelSystem} must be registered separately on the
     * entity store registry.
     *
     * @param pluginInstance The SimpleEnchanting plugin instance
     */
    public static void registerEventListener(@Nonnull SimpleEnchanting pluginInstance) {
        plugin = pluginInstance;

        if (!initialized) {
            initializeScrollItemMap();
            initialized = true;
        }

        // Build the set of disabled scroll item IDs based on config
        buildDisabledScrollSet();

        // Recipes: remove disabled ones / apply config overrides as they are loaded
        plugin.getEventRegistry().register(
                LoadedAssetsEvent.class,
                CraftingRecipe.class,
                EnchantmentRecipeManager::onRecipeLoad);

        // Block types: enchanting table upgrade overrides + addon bench categories
        plugin.getEventRegistry().register(
                LoadedAssetsEvent.class,
                BlockType.class,
                EnchantmentRecipeManager::onBlockTypeLoad);

        // NOTE: CraftRecipeEvent.Pre is an ECS event (CancellableEcsEvent dispatched
        // via componentAccessor.invoke). It is handled by CraftRecipeCancelSystem,
        // which must be registered with getEntityStoreRegistry().registerSystem(...).

        LOGGER.atInfo().log("EnchantmentRecipeManager registered asset listeners");
        LOGGER.atInfo().log("Disabled enchantment scroll items: %s", DISABLED_SCROLL_ITEM_IDS);
    }

    /**
     * Resets all static state. Call from plugin {@code shutdown()}. Assets this
     * class loaded are removed by the server together with the plugin's asset
     * pack.
     */
    public static void unload() {
        plugin = null;
        initialized = false;
        isApplyingOverrides = false;
        ENCHANTMENT_SCROLL_ITEMS.clear();
        DISABLED_SCROLL_ITEM_IDS.clear();
        REMOVED_RECIPES.clear();
    }

    /**
     * Re-applies the config to the live recipe store. Call after the config was
     * changed (e.g. from the in-game config editor).
     */
    public static void reload() {
        if (plugin == null)
            return;

        if (!initialized) {
            initializeScrollItemMap();
            initialized = true;
        }

        // 1. Rebuild the disabled set based on new config
        buildDisabledScrollSet();
        LOGGER.atInfo().log("Reload: updated disabled recipe set (%d scroll items)", DISABLED_SCROLL_ITEM_IDS.size());

        // 2. Re-evaluate every recipe currently in the store plus the ones we removed
        Map<String, CraftingRecipe> candidates = new LinkedHashMap<>();
        try {
            candidates.putAll(CraftingRecipe.getAssetMap().getAssetMap());
        } catch (Exception e) {
            LOGGER.atSevere().withCause(e).log("Reload: could not read the CraftingRecipe asset map");
            return;
        }
        for (Map.Entry<String, CraftingRecipe> removed : REMOVED_RECIPES.entrySet()) {
            candidates.putIfAbsent(removed.getKey(), removed.getValue());
        }

        processRecipes(candidates, true);
    }

    // ───────────────────── Craft cancellation (used by CraftRecipeCancelSystem) ─────────────────────

    /**
     * @return true if crafting the given recipe must be blocked by the current
     *         config (disabled scroll, scroll crafting disabled, or table
     *         crafting disabled).
     */
    public static boolean shouldCancelCraft(@Nullable CraftingRecipe recipe) {
        if (plugin == null || recipe == null)
            return false;

        String outId = recipe.getPrimaryOutput() != null ? recipe.getPrimaryOutput().getItemId() : null;

        if (outId != null && DISABLED_SCROLL_ITEM_IDS.contains(outId)) {
            return true;
        }

        String recipeId = recipe.getId();
        if (recipeId != null) {
            for (String disabledScrollId : DISABLED_SCROLL_ITEM_IDS) {
                if (recipeId.startsWith(disabledScrollId + GENERATED_RECIPE_INFIX)) {
                    return true;
                }
            }
        }

        EnchantingConfig config = plugin.getConfigManager().getConfig();
        if (!config.enableEnchantingTableCrafting && isTableRecipe(recipeId, outId, ENCHANTING_TABLE_RECIPE_PREFIX)) {
            return true;
        }
        if (!config.enableEngravingTableCrafting && isTableRecipe(recipeId, outId, ENGRAVING_TABLE_RECIPE_PREFIX)) {
            return true;
        }
        return false;
    }

    private static boolean isTableRecipe(@Nullable String recipeId, @Nullable String outId, String tablePrefix) {
        if (outId != null && (tablePrefix.equals(outId) || (tablePrefix + "_Item").equals(outId))) {
            return true;
        }
        return recipeId != null && recipeId.startsWith(tablePrefix);
    }

    // ───────────────────── Scroll bookkeeping ─────────────────────

    /**
     * Initializes the enchantment ID to scroll item ID mapping.
     * Scroll items follow the pattern: Scroll_{EnchantmentName}_{Level}
     */
    private static void initializeScrollItemMap() {
        ENCHANTMENT_SCROLL_ITEMS.clear();
        for (EnchantmentType type : EnchantmentType.values()) {
            List<String> scrollItemIds = new ArrayList<>();
            String baseName = type.getScrollBaseName();

            for (int level = 1; level <= type.getMaxLevel(); level++) {
                scrollItemIds.add(baseName + "_" + EnchantmentType.toRoman(level));
            }

            ENCHANTMENT_SCROLL_ITEMS.put(type.getId(), scrollItemIds);
        }
    }

    /**
     * Builds the set of disabled scroll item IDs based on current config.
     */
    private static void buildDisabledScrollSet() {
        // Re-initialize map to capture any late-registered addon enchantments
        initializeScrollItemMap();

        DISABLED_SCROLL_ITEM_IDS.clear();

        if (plugin == null) {
            return;
        }

        EnchantingConfig config = plugin.getConfigManager().getConfig();

        // If scroll crafting is disabled, add ALL scrolls and return
        if (!config.enableScrollCrafting) {
            for (List<String> scrollIds : ENCHANTMENT_SCROLL_ITEMS.values()) {
                DISABLED_SCROLL_ITEM_IDS.addAll(scrollIds);
            }
            if (config.scrollRecipes != null) {
                DISABLED_SCROLL_ITEM_IDS.addAll(config.scrollRecipes.keySet());
            }
            // Explicitly disable the Cleansing scroll as well, since it's not a standard
            // enchantment type
            DISABLED_SCROLL_ITEM_IDS.add("Scroll_Cleansing");

            LOGGER.atInfo().log("Scroll crafting disabled by config. All scroll recipes will be removed.");
            return;
        }

        // Check for missing optional dependencies
        boolean hasPerfectParries = plugin.isPerfectParriesModPresent();
        LOGGER.atInfo().log("Perfect Parries mod present: %s", hasPerfectParries);
        if (!hasPerfectParries) {
            LOGGER.atInfo().log("Perfect Parries mod not found. Disabling Riposte and Coup de Grâce scrolls.");
        }

        for (EnchantmentType type : EnchantmentType.values()) {
            boolean isDisabled = config.disabledEnchantments.getOrDefault(type.getId(), false);

            // Explicitly disable Riposte and Coup de Grace if mod is missing
            if (!hasPerfectParries && (type == EnchantmentType.RIPOSTE || type == EnchantmentType.COUP_DE_GRACE)) {
                isDisabled = true;
            }

            if (isDisabled) {
                List<String> scrollItemIds = ENCHANTMENT_SCROLL_ITEMS.get(type.getId());
                if (scrollItemIds != null) {
                    DISABLED_SCROLL_ITEM_IDS.addAll(scrollItemIds);
                    LOGGER.atInfo().log("Enchantment '%s' is disabled, will filter scrolls: %s", type.getId(),
                            scrollItemIds);
                }
            }
        }
    }

    // ───────────────────── Recipe processing ─────────────────────

    /**
     * Event handler called when recipes are loaded.
     */
    private static void onRecipeLoad(
            LoadedAssetsEvent<String, CraftingRecipe, DefaultAssetMap<String, CraftingRecipe>> event) {
        if (plugin == null) {
            return;
        }
        if (isApplyingOverrides) {
            // Nested event fired by our own loadAssets/loadBuffersWithKeys call
            return;
        }

        // Rebuild the disabled scroll set here to catch any late-registered
        // enchantments from Addon mods!
        buildDisabledScrollSet();

        processRecipes(event.getLoadedAssets(), false);
    }

    private enum Action {
        KEEP, REMOVE, OVERRIDE
    }

    private static final class Decision {
        final Action action;
        final CraftingRecipe replacement;
        final String reason;

        private Decision(Action action, CraftingRecipe replacement, String reason) {
            this.action = action;
            this.replacement = replacement;
            this.reason = reason;
        }

        static Decision keep() {
            return new Decision(Action.KEEP, null, null);
        }

        static Decision remove(String reason) {
            return new Decision(Action.REMOVE, null, reason);
        }

        static Decision override(CraftingRecipe replacement, String reason) {
            return new Decision(Action.OVERRIDE, replacement, reason);
        }
    }

    /**
     * Evaluates every candidate recipe against the config and applies the result
     * to the asset store: removals, restores (reload only) and overrides.
     *
     * @param candidates recipe id to recipe (freshly loaded recipes, or on reload
     *                   the whole store plus previously removed recipes)
     * @param reload     true when called from {@link #reload()}
     */
    private static void processRecipes(Map<String, CraftingRecipe> candidates, boolean reload) {
        EnchantingConfig config = plugin.getConfigManager().getConfig();
        DefaultAssetMap<String, CraftingRecipe> assetMap = CraftingRecipe.getAssetMap();

        List<String> recipeIdsToRemove = new ArrayList<>();
        List<CraftingRecipe> recipesToRestore = new ArrayList<>();
        Map<String, CraftingRecipe> overrides = new LinkedHashMap<>();

        for (Map.Entry<String, CraftingRecipe> entry : candidates.entrySet()) {
            String recipeId = entry.getKey();
            CraftingRecipe recipe = entry.getValue();
            if (recipeId == null || recipe == null)
                continue;

            Decision decision;
            try {
                decision = decide(recipeId, recipe, config);
            } catch (Exception e) {
                LOGGER.atSevere().withCause(e).log("Failed to evaluate recipe %s against the config", recipeId);
                continue;
            }

            boolean inStore = assetMap.getAsset(recipeId) != null;

            if (decision.action == Action.REMOVE) {
                if (inStore) {
                    REMOVED_RECIPES.put(recipeId, recipe);
                    recipeIdsToRemove.add(recipeId);
                    LOGGER.atInfo().log("Marking for removal (%s): %s", decision.reason, recipeId);
                }
                // else: already removed earlier and still disabled - keep it cached
                continue;
            }

            // Enabled: restore if we removed it earlier (reload only)
            if (!inStore) {
                CraftingRecipe removed = REMOVED_RECIPES.remove(recipeId);
                if (removed != null && reload) {
                    recipesToRestore.add(removed);
                    LOGGER.atInfo().log("Restoring previously removed recipe: %s", recipeId);
                }
            }

            if (decision.action == Action.OVERRIDE && decision.replacement != null) {
                LOGGER.atInfo().log("Applying config override (%s) to recipe %s", decision.reason, recipeId);
                overrides.put(recipeId, decision.replacement);
            }
        }

        String packKey = getPackKey();

        // Restore first so overrides layer on top of the restored original
        if (!recipesToRestore.isEmpty()) {
            try {
                isApplyingOverrides = true;
                AssetLoadResult<String, CraftingRecipe> result = CraftingRecipe.getAssetStore()
                        .loadAssets(packKey, recipesToRestore);
                LOGGER.atInfo().log("Restored %d recipe(s)", result.getLoadedAssets().size());
                if (result.hasFailed()) {
                    LOGGER.atSevere().log("Failed to restore recipes: %s", result.getFailedToLoadKeys());
                }
            } catch (Exception e) {
                LOGGER.atSevere().withCause(e).log("Failed to restore previously removed recipes");
            } finally {
                isApplyingOverrides = false;
            }
        }

        if (!overrides.isEmpty()) {
            try {
                isApplyingOverrides = true;
                List<RawAsset<String>> rawAssets = new ArrayList<>(overrides.size());
                for (Map.Entry<String, CraftingRecipe> e : overrides.entrySet()) {
                    RawAsset<String> raw = toRawAsset(e.getKey(), e.getValue());
                    if (raw != null)
                        rawAssets.add(raw);
                }
                if (!rawAssets.isEmpty()) {
                    // Codec path: decodes, validates, runs processConfig and assigns the id
                    AssetLoadResult<String, CraftingRecipe> result = CraftingRecipe.getAssetStore()
                            .loadBuffersWithKeys(packKey, rawAssets, AssetUpdateQuery.DEFAULT, true);
                    LOGGER.atInfo().log("Applied %d recipe override(s)", result.getLoadedAssets().size());
                    if (result.hasFailed()) {
                        LOGGER.atSevere().log("Failed to apply recipe overrides: %s", result.getFailedToLoadKeys());
                    }
                }
            } catch (Exception e) {
                LOGGER.atSevere().withCause(e).log("Failed to apply recipe overrides");
            } finally {
                isApplyingOverrides = false;
            }
        }

        if (!recipeIdsToRemove.isEmpty()) {
            try {
                CraftingRecipe.getAssetStore().removeAssets(recipeIdsToRemove);
                LOGGER.atInfo().log("Removed %d disabled recipe(s)", recipeIdsToRemove.size());
            } catch (Exception e) {
                LOGGER.atSevere().withCause(e).log("Failed to remove disabled recipes");
            }
        }
    }

    /**
     * Decides what to do with a single recipe under the current config.
     */
    private static Decision decide(String recipeId, CraftingRecipe recipe, EnchantingConfig config) {
        Map<String, List<ConfigIngredient>> recipeOverrides = config.scrollRecipes != null
                ? config.scrollRecipes
                : Map.of();

        // Enchanting Table recipe
        if (recipeId.startsWith(ENCHANTING_TABLE_RECIPE_PREFIX)) {
            if (!config.enableEnchantingTableCrafting) {
                return Decision.remove("enchanting table crafting disabled");
            }
            return decideTableOverride(recipe, config.enchantingTableRecipe, config.enchantingTableCraftingTier);
        }

        // Engraving Table recipe
        if (recipeId.startsWith(ENGRAVING_TABLE_RECIPE_PREFIX)) {
            if (!config.enableEngravingTableCrafting) {
                return Decision.remove("engraving table crafting disabled");
            }
            return decideTableOverride(recipe, config.engravingTableRecipe, config.engravingTableCraftingTier);
        }

        // Scroll recipes
        String scrollItemId = getScrollItemForRecipe(recipeId, recipe, recipeOverrides);
        if (scrollItemId != null) {
            if (DISABLED_SCROLL_ITEM_IDS.contains(scrollItemId)) {
                return Decision.remove("disabled scroll");
            }

            List<ConfigIngredient> overrideIngredients = null;
            Integer overrideTier = null;

            List<ConfigIngredient> rawList = recipeOverrides.get(scrollItemId);
            if (rawList != null && !rawList.isEmpty()) {
                List<ConfigIngredient> ingredientsOnly = new ArrayList<>();
                for (ConfigIngredient ci : rawList) {
                    if (ci.UnlocksAtTier != null) {
                        overrideTier = ci.UnlocksAtTier;
                    } else {
                        ingredientsOnly.add(ci);
                    }
                }

                if (!ingredientsOnly.isEmpty() && !doesRecipeMatch(recipe, ingredientsOnly)) {
                    overrideIngredients = ingredientsOnly;
                }
            }

            // Tier already matches -> no change needed
            if (overrideTier != null) {
                int current = getBenchTier(recipe, ENCHANTING_BENCH_ID);
                if (current != -1 && current == overrideTier) {
                    overrideTier = null;
                }
            }

            if (overrideIngredients != null || overrideTier != null) {
                return Decision.override(
                        applyModifications(recipe, overrideIngredients, overrideTier, ENCHANTING_BENCH_ID),
                        "scroll recipe config");
            }
            return Decision.keep();
        }

        // Fallback for ID-based detection if output is somehow null or weird (legacy
        // support for generated IDs)
        for (String disabledScrollId : DISABLED_SCROLL_ITEM_IDS) {
            if (recipeId.startsWith(disabledScrollId + GENERATED_RECIPE_INFIX)) {
                return Decision.remove("disabled scroll (id match)");
            }
        }

        return Decision.keep();
    }

    private static Decision decideTableOverride(CraftingRecipe recipe, List<ConfigIngredient> configuredIngredients,
            int configuredTier) {
        List<ConfigIngredient> overrideIngredients = null;
        Integer overrideTier = null;

        if (configuredIngredients != null && !configuredIngredients.isEmpty()
                && !doesRecipeMatch(recipe, configuredIngredients)) {
            overrideIngredients = configuredIngredients;
        }

        int currentTier = getBenchTier(recipe, WORKBENCH_ID);
        if (currentTier != -1 && currentTier != configuredTier) {
            overrideTier = configuredTier;
        }

        if (overrideIngredients != null || overrideTier != null) {
            return Decision.override(applyModifications(recipe, overrideIngredients, overrideTier, WORKBENCH_ID),
                    "table recipe config");
        }
        return Decision.keep();
    }

    /**
     * Helper to identify if a recipe matches a known scroll.
     */
    @Nullable
    private static String getScrollItemForRecipe(String recipeId, CraftingRecipe recipe,
            Map<String, List<ConfigIngredient>> recipeOverrides) {
        // 1. Check overrides keys
        for (String key : recipeOverrides.keySet()) {
            if (recipeId.startsWith(key + GENERATED_RECIPE_INFIX)) {
                return key;
            }
        }

        // 2. Check output (more robust)
        if (recipe.getPrimaryOutput() != null) {
            String outId = recipe.getPrimaryOutput().getItemId();
            if (outId != null && outId.startsWith("Scroll_")) {
                return outId;
            }
        }

        // 3. Fallback check against known scroll items
        for (List<String> scrollIds : ENCHANTMENT_SCROLL_ITEMS.values()) {
            for (String scrollId : scrollIds) {
                if (recipeId.startsWith(scrollId + GENERATED_RECIPE_INFIX)) {
                    return scrollId;
                }
            }
        }

        return null;
    }

    private static int getBenchTier(CraftingRecipe recipe, String benchId) {
        if (recipe.getBenchRequirement() == null)
            return -1;
        for (BenchRequirement br : recipe.getBenchRequirement()) {
            if (br.id != null && br.id.equals(benchId)) {
                return br.requiredTierLevel;
            }
        }
        return -1;
    }

    /**
     * Creates a copy of {@code original} with the configured ingredients and/or
     * bench tier. The id is assigned when the copy is loaded through the codec.
     */
    private static CraftingRecipe applyModifications(CraftingRecipe original,
            @Nullable List<ConfigIngredient> ingredients, @Nullable Integer tier, String benchId) {
        MaterialQuantity[] newInputs = original.getInput();

        // Apply ingredient override if present
        if (ingredients != null) {
            List<ConfigIngredient> actualIngredients = new ArrayList<>();
            for (ConfigIngredient ci : ingredients) {
                if (ci.UnlocksAtTier == null) {
                    if (ci.item != null || ci.isResourceType()) {
                        actualIngredients.add(ci);
                    } else {
                        LOGGER.atSevere().log(
                                "Invalid recipe ingredient found in config: both item and resourceType are null. Please check your config keys (e.g. use 'item' instead of 'itemId').");
                    }
                }
            }
            if (!actualIngredients.isEmpty() || ingredients.isEmpty()) {
                newInputs = new MaterialQuantity[actualIngredients.size()];
                for (int i = 0; i < actualIngredients.size(); i++) {
                    ConfigIngredient ci = actualIngredients.get(i);
                    int amt = ci.amount != null ? ci.amount : 1;
                    if (ci.isResourceType()) {
                        newInputs[i] = new MaterialQuantity(null, ci.resourceType, null, amt, null);
                    } else {
                        newInputs[i] = new MaterialQuantity(ci.item, null, null, amt, null);
                    }
                }
            }
        }

        // Deep copy bench requirements to modify safely
        BenchRequirement[] newRequirements = original.getBenchRequirement();
        if (tier != null && newRequirements != null) {
            newRequirements = new BenchRequirement[original.getBenchRequirement().length];
            for (int i = 0; i < original.getBenchRequirement().length; i++) {
                BenchRequirement origReq = original.getBenchRequirement()[i];
                newRequirements[i] = origReq.clone();
                if (benchId.equals(newRequirements[i].id)) {
                    newRequirements[i].requiredTierLevel = tier;
                }
            }
        }

        int outputQuantity = original.getPrimaryOutput() != null ? original.getPrimaryOutput().getQuantity() : 1;

        return new CraftingRecipe(
                newInputs,
                original.getPrimaryOutput(),
                original.getOutputs(),
                outputQuantity,
                newRequirements,
                original.getTimeSeconds(),
                original.isKnowledgeRequired(),
                original.getRequiredMemoriesLevel());
    }

    /**
     * Encodes a recipe with the {@code CraftingRecipe} codec into an in-memory
     * JSON buffer keyed by {@code recipeId}, ready for
     * {@code AssetStore.loadBuffersWithKeys}. Decoding it assigns the id and runs
     * {@code processConfig}, so no reflection is needed.
     */
    @Nullable
    private static RawAsset<String> toRawAsset(String recipeId, CraftingRecipe recipe) {
        try {
            AssetExtraInfo<String> extraInfo = new AssetExtraInfo<>(
                    new AssetExtraInfo.Data(CraftingRecipe.class, recipeId, null));
            BsonDocument doc = CraftingRecipe.CODEC.encode(recipe, extraInfo);
            // The key is supplied by the RawAsset; drop it from the body if the codec wrote it
            String keyField = CraftingRecipe.CODEC.getKeyCodec() != null
                    ? CraftingRecipe.CODEC.getKeyCodec().getKey()
                    : null;
            if (keyField != null) {
                doc.remove(keyField);
            }
            return new RawAsset<>((Path) null, recipeId, null, 0, doc.toJson().toCharArray(), null,
                    ContainedAssetCodec.Mode.NONE);
        } catch (Exception e) {
            LOGGER.atSevere().withCause(e).log("Failed to encode recipe override for %s", recipeId);
            return null;
        }
    }

    private static boolean doesRecipeMatch(CraftingRecipe recipe, List<ConfigIngredient> configuredIngredients) {
        List<ConfigIngredient> actualIngredients = new ArrayList<>();
        for (ConfigIngredient ci : configuredIngredients) {
            if (ci.UnlocksAtTier == null) {
                actualIngredients.add(ci);
            }
        }

        MaterialQuantity[] currentInputs = recipe.getInput();
        if (currentInputs == null) {
            return actualIngredients.isEmpty();
        }

        if (currentInputs.length != actualIngredients.size()) {
            return false;
        }

        // Build maps keyed by a composite key "item:<id>" or "rt:<id>" to handle both types
        Map<String, Integer> currentMap = new HashMap<>();
        for (MaterialQuantity mq : currentInputs) {
            String key;
            if (mq.getResourceTypeId() != null) {
                key = "rt:" + mq.getResourceTypeId();
            } else {
                key = "item:" + mq.getItemId();
            }
            currentMap.merge(key, mq.getQuantity(), Integer::sum);
        }

        Map<String, Integer> configMap = new HashMap<>();
        for (ConfigIngredient ci : actualIngredients) {
            String key;
            if (ci.isResourceType()) {
                key = "rt:" + ci.resourceType;
            } else if (ci.item != null) {
                key = "item:" + ci.item;
            } else {
                continue;
            }
            int amt = ci.amount != null ? ci.amount : 1;
            configMap.merge(key, amt, Integer::sum);
        }

        return currentMap.equals(configMap);
    }

    @Nonnull
    private static String getPackKey() {
        return plugin.getIdentifier().toString();
    }

    // ───────────────────── Public queries ─────────────────────

    /**
     * Enables (re-adds) recipes for a specific enchantment.
     * With the reload strategy this is handled by {@link #reload()}; kept for API
     * compatibility.
     *
     * @param enchantmentId The enchantment ID (e.g., "sharpness")
     */
    public static void enableEnchantmentRecipes(@Nonnull String enchantmentId) {
        // No-op: handled by reload()
    }

    /**
     * Gets all scroll item IDs associated with an enchantment.
     *
     * @param enchantmentId The enchantment ID
     * @return List of scroll item IDs, or empty list if none found
     */
    @Nonnull
    public static List<String> getScrollItemsForEnchantment(@Nonnull String enchantmentId) {
        return ENCHANTMENT_SCROLL_ITEMS.getOrDefault(enchantmentId, List.of());
    }

    /**
     * Checks if the recipes for an enchantment are currently disabled.
     *
     * @param enchantmentId The enchantment ID
     * @return True if recipes are disabled (removed from asset store)
     */
    public static boolean areRecipesDisabled(@Nonnull String enchantmentId) {
        List<String> scrollItemIds = ENCHANTMENT_SCROLL_ITEMS.get(enchantmentId);
        if (scrollItemIds == null || scrollItemIds.isEmpty()) {
            return false;
        }
        return DISABLED_SCROLL_ITEM_IDS.containsAll(scrollItemIds);
    }

    /** @return an unmodifiable view of the disabled scroll item IDs. */
    @Nonnull
    public static Set<String> getDisabledScrollItemIds() {
        return java.util.Collections.unmodifiableSet(DISABLED_SCROLL_ITEM_IDS);
    }

    // ───────────────────── Block types (bench upgrades / addon categories) ─────────────────────

    private static void onBlockTypeLoad(
            LoadedAssetsEvent<String, BlockType, DefaultAssetMap<String, BlockType>> event) {
        if (plugin == null)
            return;
        EnchantingConfig config = plugin.getConfigManager().getConfig();
        String enchantingTableId = "Enchanting_Table";

        if (event.getLoadedAssets().containsKey(enchantingTableId)) {
            BlockType block = event.getLoadedAssets().get(enchantingTableId);
            if (config.enchantingTableUpgrades != null && !config.enchantingTableUpgrades.isEmpty()) {
                LOGGER.atInfo().log("Applying Enchanting Table upgrade overrides on BlockType");
                applyBlockUpgrades(block, config.enchantingTableUpgrades);
            }
            // Inject custom addon crafting categories into the bench
            injectAddonCraftingCategories(block);
        }
    }

    /**
     * Injects custom crafting categories (registered by addon mods) into the
     * Enchanting Table's bench categories array so they appear as tabs.
     */
    private static void injectAddonCraftingCategories(BlockType block) {
        try {
            java.util.Collection<org.herolias.plugin.api.CraftingCategoryDefinition> allDefs = org.herolias.plugin.api.CraftingCategoryDefinition
                    .values();

            // Filter to only addon (non-built-in) categories
            List<org.herolias.plugin.api.CraftingCategoryDefinition> addonCategories = allDefs.stream()
                    .filter(d -> !d.isBuiltIn()).collect(java.util.stream.Collectors.toList());

            if (addonCategories.isEmpty())
                return;

            Field benchField = BlockType.class.getDeclaredField("bench");
            benchField.setAccessible(true);
            Object bench = benchField.get(block);
            if (bench == null)
                return;

            // CraftingBench extends Bench, categories field is on CraftingBench
            Class<?> craftingBenchClass = bench.getClass();
            Field categoriesField = craftingBenchClass.getDeclaredField("categories");
            categoriesField.setAccessible(true);

            Object[] existingCategories = (Object[]) categoriesField.get(bench);
            if (existingCategories == null)
                existingCategories = new Object[0];

            // Check which addon categories aren't already present
            Set<String> existingIds = new HashSet<>();
            for (Object cat : existingCategories) {
                java.lang.reflect.Method getId = cat.getClass().getMethod("getId");
                existingIds.add((String) getId.invoke(cat));
            }

            List<Object> newCategories = new ArrayList<>(java.util.Arrays.asList(existingCategories));
            Class<?> benchCategoryClass = Class.forName(
                    "com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.CraftingBench$BenchCategory");

            for (org.herolias.plugin.api.CraftingCategoryDefinition def : addonCategories) {
                if (existingIds.contains(def.getCategoryId()))
                    continue;

                // Create BenchCategory(id, name, icon, itemCategories)
                Object benchCategory = benchCategoryClass.getDeclaredConstructor(
                        String.class, String.class, String.class,
                        Class.forName(
                                "[Lcom.hypixel.hytale.server.core.asset.type.blocktype.config.bench.CraftingBench$BenchItemCategory;"))
                        .newInstance(
                                def.getCategoryId(),
                                "server.benchCategories." + def.getCategoryId(),
                                def.getIconPath(),
                                null);

                newCategories.add(benchCategory);

                // Register the display name translation
                plugin.getLanguageManager().putTranslation(
                        "benchCategories." + def.getCategoryId(), def.getDisplayName());

                LOGGER.atInfo().log("Injected addon crafting category tab: %s (%s)", def.getCategoryId(),
                        def.getDisplayName());
            }

            // Write back the expanded array
            Object newArray = java.lang.reflect.Array.newInstance(benchCategoryClass, newCategories.size());
            for (int i = 0; i < newCategories.size(); i++) {
                java.lang.reflect.Array.set(newArray, i, newCategories.get(i));
            }
            categoriesField.set(bench, newArray);

        } catch (Exception e) {
            LOGGER.atSevere().withCause(e).log("Failed to inject addon crafting categories");
        }
    }

    private static void applyBlockUpgrades(BlockType block, Map<String, List<ConfigIngredient>> upgrades) {
        try {
            Field benchField = BlockType.class.getDeclaredField("bench");
            benchField.setAccessible(true);
            Bench bench = (Bench) benchField.get(block);

            if (bench != null) {
                Field tiersField = Bench.class.getDeclaredField("tierLevels");
                tiersField.setAccessible(true);
                BenchTierLevel[] tiers = (BenchTierLevel[]) tiersField.get(bench);

                if (tiers != null) {
                    updateTier(tiers, 0, upgrades.get("Upgrade_1"));
                    updateTier(tiers, 1, upgrades.get("Upgrade_2"));
                    updateTier(tiers, 2, upgrades.get("Upgrade_3"));

                    tiersField.set(bench, tiers);
                }
            }
        } catch (Exception e) {
            LOGGER.atSevere().withCause(e).log("Failed to apply block upgrades");
        }
    }

    private static void updateTier(BenchTierLevel[] tiers, int index, List<ConfigIngredient> ingredients) {
        if (index >= tiers.length || ingredients == null)
            return;

        try {
            BenchTierLevel tier = tiers[index];
            if (tier == null)
                return;

            MaterialQuantity[] materials = new MaterialQuantity[ingredients.size()];
            for (int i = 0; i < ingredients.size(); i++) {
                ConfigIngredient ci = ingredients.get(i);
                int amt = ci.amount != null ? ci.amount : 1;
                if (ci.isResourceType()) {
                    materials[i] = new MaterialQuantity(null, ci.resourceType, null, amt, null);
                } else {
                    materials[i] = new MaterialQuantity(ci.item, null, null, amt, null);
                }
            }

            BenchUpgradeRequirement newReq = new BenchUpgradeRequirement(materials, 5.0f);

            Field reqField = BenchTierLevel.class.getDeclaredField("upgradeRequirement");
            reqField.setAccessible(true);
            reqField.set(tier, newReq);

        } catch (Exception e) {
            LOGGER.atSevere().withCause(e).log("Failed to update bench tier %d", index);
        }
    }
}
