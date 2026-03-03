package org.herolias.plugin.enchantment;

import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.Bench;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.BenchTierLevel;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.BenchUpgradeRequirement;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.protocol.BenchRequirement;
import java.lang.reflect.Field;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.config.EnchantingConfig;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import org.herolias.plugin.config.EnchantingConfig.ConfigIngredient;
import java.util.stream.Collectors;

/**
 * Manages dynamic enabling/disabling of enchantment scroll recipes based on config settings.
 * 
 * When an enchantment is disabled in the config, its scroll recipes are removed from
 * the asset store, making them disappear from the Enchanting Table UI.
 * 
 * This uses event-based detection to intercept recipes as they are loaded.
 */
public class EnchantmentRecipeManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    
    // Maps enchantment ID to list of scroll item IDs (not recipe IDs)
    // e.g., "sharpness" -> ["Scroll_Sharpness_I", "Scroll_Sharpness_II", "Scroll_Sharpness_III"]
    private static final Map<String, List<String>> ENCHANTMENT_SCROLL_ITEMS = new HashMap<>();
    
    // Set of disabled scroll item IDs for quick lookup
    private static final Set<String> DISABLED_SCROLL_ITEM_IDS = new HashSet<>();
    
    private static SimpleEnchanting plugin;
    private static boolean initialized = false;
    
    public static void reload() {
        if (plugin == null) return;
        
        if (!initialized) {
            initializeScrollItemMap();
            initialized = true;
        }
        
        // 1. Rebuild the disabled set based on new config
        buildDisabledScrollSet();
        LOGGER.atInfo().log("Reload: Updated disabled recipe set. Count: " + DISABLED_SCROLL_ITEM_IDS.size());
        
        // No longer modifying AssetStore directly to avoid runtime crashes.
        // Instead, we rely on onCraft event cancellation.
    }

    /**
     * Event handler called BEFORE a recipe is crafted.
     * Cancels the event if the recipe produces a disabled item.
     */
    private static void onCraft(com.hypixel.hytale.server.core.event.events.ecs.CraftRecipeEvent.Pre event) {
        if (plugin == null) return;
        
        CraftingRecipe recipe = event.getCraftedRecipe();
        if (recipe == null) return;
        
        // Check if output is disabled
        if (recipe.getPrimaryOutput() != null) {
            String itemId = recipe.getPrimaryOutput().getItemId();
            if (itemId != null) {
                // Check exact ID match or Scroll logic
                if (DISABLED_SCROLL_ITEM_IDS.contains(itemId)) {
                    event.setCancelled(true);
                    
                    // Ideally notify player, but event doesn't give Player directly easily without looking up entity
                    // We can try to get entity from event if needed, but cancellation is enough/safe.
                    // If we want to notify:
                    /*
                    if (event.getEntity().getType() == Player.getComponentType()) {
                         // Send message
                    }
                    */
                    return;
                }
            }
        }
        
        // Check if it's the Enchanting Table and it's disabled globally
        EnchantingConfig config = plugin.getConfigManager().getConfig();
        if (config.disableEnchantmentCrafting) {
             // If ID starts with Enchanting_Table (default bench id) or upgrades
             // Actually, primary output for table is "Enchanting_Table_Item" usually?
             // Or we check recipe ID.
             // CraftingRecipe doesn't expose ID easily without reflection, but we can check output item.
             
             if (recipe.getPrimaryOutput() != null) {
                 String outId = recipe.getPrimaryOutput().getItemId();
                 if ("Enchanting_Table".equals(outId) || "Enchanting_Table_Item".equals(outId)) {
                     event.setCancelled(true);
                     return;
                 }
             }
        }
    }

    /**
     * Helper to identify if a recipe matches a known scroll.
     */
    private static String getScrollItemForRecipe(String recipeId, CraftingRecipe recipe) {
        EnchantingConfig config = plugin.getConfigManager().getConfig();
        // Fix: config.scrollRecipes is Map<String, List<ConfigIngredient>>
        Map<String, List<ConfigIngredient>> recipeOverrides = config.scrollRecipes; 
        
        // 1. Check overrides keys
        for (String key : recipeOverrides.keySet()) {
             if (recipeId.startsWith(key + "_Recipe_Generated_")) {
                 return key;
             }
        }
        
        // 2. Check output
        if (recipe.getPrimaryOutput() != null) {
            String outId = recipe.getPrimaryOutput().getItemId();
            if (outId != null && outId.startsWith("Scroll_")) {
                return outId;
            }
        }
        
        // 3. Fallback check against known scroll items
        for (List<String> scrollIds : ENCHANTMENT_SCROLL_ITEMS.values()) {
            for (String scrollId : scrollIds) {
                 if (recipeId.startsWith(scrollId + "_Recipe_Generated_")) {
                     return scrollId;
                 }
            }
        }
        
        return null;
    }

    /**
     * Initializes the enchantment ID to scroll item ID mapping.
     * Scroll items follow the pattern: Scroll_{EnchantmentName}_{Level}
     */
    private static void initializeScrollItemMap() {
        for (EnchantmentType type : EnchantmentType.values()) {
            List<String> scrollItemIds = new ArrayList<>();
            String baseName = getScrollBaseName(type);
            
            for (int level = 1; level <= type.getMaxLevel(); level++) {
                String scrollItemId = baseName + "_" + EnchantmentType.toRoman(level);
                scrollItemIds.add(scrollItemId);
            }
            
            ENCHANTMENT_SCROLL_ITEMS.put(type.getId(), scrollItemIds);
        }
    }
    
    /**
     * Gets the scroll base name for an enchantment type.
     * Converts enchantment ID to scroll naming convention.
     */
    private static String getScrollBaseName(EnchantmentType type) {
        return type.getScrollBaseName();
    }
    
    /**
     * Registers the event listener for recipe loading.
     * Should be called during plugin setup().
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
        
        // Register event listener for when recipes are loaded
        plugin.getEventRegistry().register(
            LoadedAssetsEvent.class, 
            CraftingRecipe.class, 
            EnchantmentRecipeManager::onRecipeLoad
        );

        // Note: We no longer remove Item assets on load so that existing scrolls don't become 'Invalid Items'.
        // Disabled logic is handled via hide-from-creative (ScrollItemGenerator), name prefix, and usage block.

        plugin.getEventRegistry().register(
            LoadedAssetsEvent.class,
            BlockType.class,
            EnchantmentRecipeManager::onBlockTypeLoad
        );
        
        // Register CraftRecipeEvent to cancel disabled recipes
        plugin.getEventRegistry().registerGlobal(
            com.hypixel.hytale.server.core.event.events.ecs.CraftRecipeEvent.Pre.class,
            EnchantmentRecipeManager::onCraft
        );
        
        LOGGER.atInfo().log("EnchantmentRecipeManager registered event listener");
        LOGGER.atInfo().log("Disabled enchantment scroll items: " + DISABLED_SCROLL_ITEM_IDS);
    }
    
    /**
     * Builds the set of disabled scroll item IDs based on current config.
     */
    private static void buildDisabledScrollSet() {
        DISABLED_SCROLL_ITEM_IDS.clear();
        
        if (plugin == null) {
            return;
        }
        
        EnchantingConfig config = plugin.getConfigManager().getConfig();

        // If all crafting is disabled, add ALL scrolls and return
        if (config.disableEnchantmentCrafting) {
            for (List<String> scrollIds : ENCHANTMENT_SCROLL_ITEMS.values()) {
                DISABLED_SCROLL_ITEM_IDS.addAll(scrollIds);
            }
            LOGGER.atInfo().log("All enchantment crafting disabled by config. All scroll recipes will be removed.");
            return;
        }

        // Check for missing optional dependencies
        boolean hasPerfectParries = plugin.isPerfectParriesModPresent();
        LOGGER.atInfo().log("Perfect Parries mod present: " + hasPerfectParries);
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
                    LOGGER.atInfo().log("Enchantment '" + type.getId() + "' is disabled, will filter scrolls: " + scrollItemIds);
                }
            }
        }
    }
    
    /**
     * Event handler called when recipes are loaded.
     * Removes recipes for disabled enchantment scrolls.
     */
    private static void onRecipeLoad(LoadedAssetsEvent<String, CraftingRecipe, DefaultAssetMap<String, CraftingRecipe>> event) {
        if (plugin == null) {
            return;
        }
        
        EnchantingConfig config = plugin.getConfigManager().getConfig();
        Map<String, List<ConfigIngredient>> recipeOverrides = config.scrollRecipes;

        List<String> recipeIdsToRemove = new ArrayList<>();
        Map<String, CraftingRecipe> newRecipes = new HashMap<>();
        
        for (Map.Entry<String, CraftingRecipe> entry : event.getLoadedAssets().entrySet()) {
            String recipeId = entry.getKey();
            CraftingRecipe recipe = entry.getValue();
            
            // Check if this recipe is for a disabled scroll
            // Recipe IDs are like "Scroll_Sharpness_I_Recipe_Generated_0"
            
            // Handle Enchanting Table recipe
            if (recipeId.startsWith("Enchanting_Table")) {
                // If all enchanting crafting is disabled, remove the enchanting table recipe
                if (config.disableEnchantmentCrafting) {
                    recipeIdsToRemove.add(recipeId);
                    LOGGER.atInfo().log("Marking for removal (all enchanting crafting disabled): " + recipeId);
                    continue; // Skip further processing for this recipe
                }

                 List<ConfigIngredient> overrideIngredients = null;
                 Integer overrideTier = null;
                 
                 // Check ingredients
                 if (config.enchantingTableRecipe != null && !config.enchantingTableRecipe.isEmpty()) {
                     if (!doesRecipeMatch(recipe, config.enchantingTableRecipe)) {
                         overrideIngredients = config.enchantingTableRecipe;
                     }
                 }
                 
                 // Check tier
                 int currentTier = getBenchTier(recipe, "Workbench");
                 if (currentTier != -1 && currentTier != config.enchantingTableCraftingTier) {
                     overrideTier = config.enchantingTableCraftingTier;
                 }
                 
                 if (overrideIngredients != null || overrideTier != null) {
                     LOGGER.atInfo().log("Applying overrides for Enchanting Table (Asset ID: " + recipeId + ")");
                     CraftingRecipe newRecipe = applyModifications(recipeId, recipe, overrideIngredients, overrideTier, "Workbench");
                     newRecipes.put(recipeId, newRecipe);
                     continue;
                 }
            }
            
            // Check overrides first (Scrolls)
            String scrollItemId = null;
            // Try to find if this recipe corresponds to a scroll
            // 1. Check if configured in ingredients overrides
            for (String key : recipeOverrides.keySet()) {
                if (recipeId.startsWith(key + "_Recipe_Generated_")) {
                    scrollItemId = key;
                    break;
                }
            }
            // 2. If not found, check output (more robust)
            if (scrollItemId == null && recipe.getPrimaryOutput() != null) {
                String outId = recipe.getPrimaryOutput().getItemId();
                if (outId != null && outId.startsWith("Scroll_")) {
                     scrollItemId = outId;
                }
            }

            if (scrollItemId != null) {
                // Check if disabled first
                if (DISABLED_SCROLL_ITEM_IDS.contains(scrollItemId)) {
                    recipeIdsToRemove.add(recipeId);
                    LOGGER.atInfo().log("Marking for removal (disabled scroll): " + recipeId);
                    continue; // Skip further processing
                }

                List<ConfigIngredient> overrideIngredients = null;
                Integer overrideTier = null;

                if (recipeOverrides.containsKey(scrollItemId)) {
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
                }
                
                // Check if current tier matches override tier from list
                if (overrideTier != null) {
                     int current = getBenchTier(recipe, "Enchantingbench");
                     if (current != -1 && current == overrideTier) {
                         overrideTier = null; // No change needed
                     }
                }

                if (overrideIngredients != null || overrideTier != null) {
                     LOGGER.atInfo().log("Applying overrides for " + scrollItemId + " on recipe " + recipeId);
                     CraftingRecipe newRecipe = applyModifications(recipeId, recipe, overrideIngredients, overrideTier, "Enchantingbench");
                     newRecipes.put(recipeId, newRecipe);
                }
                
                // If we found it was a scroll, we processed it (either removed or checked overrides). 
                // Continue to next recipe in main loop to avoid double processing
                continue; 
            }

            // Fallback for ID-based detection if output is somehow null or weird (legacy support for generated IDs)
            if (recipeIdsToRemove.contains(recipeId)) continue; // Already marked

            for (String disabledScrollId : DISABLED_SCROLL_ITEM_IDS) {
                 if (recipeId.startsWith(disabledScrollId + "_Recipe_Generated_")) {
                    recipeIdsToRemove.add(recipeId);
                    LOGGER.atInfo().log("Marking for removal (ID match): " + recipeId);
                    break;
                 }
            }
        }
        
        // Apply overrides
        if (!newRecipes.isEmpty()) {
            try {
                // Use loadAssets to properly register/overwrite the recipes in the store
                CraftingRecipe.getAssetStore().loadAssets("SimpleEnchanting:ConfigOverrides", new ArrayList<>(newRecipes.values()));
                LOGGER.atInfo().log("Applied " + newRecipes.size() + " recipe overrides");
            } catch (Exception e) {
                 LOGGER.atSevere().log("Failed to apply recipe overrides: " + e.toString());
                 e.printStackTrace();
            }
        }

        if (!recipeIdsToRemove.isEmpty()) {
            // Remove the disabled recipes from the asset store
            try {
                // If we have overrides for disabled items, we still remove them?
                // Yes, logic: Disabled takes precedence.
                CraftingRecipe.getAssetStore().removeAssets(recipeIdsToRemove);
                LOGGER.atInfo().log("Removed " + recipeIdsToRemove.size() + " disabled enchantment recipes");
            } catch (Exception e) {
                LOGGER.atSevere().log("Failed to remove disabled recipes: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }



    private static int getBenchTier(CraftingRecipe recipe, String benchId) {
        if (recipe.getBenchRequirement() == null) return -1;
        for (BenchRequirement br : recipe.getBenchRequirement()) {
            if (br.id != null && br.id.equals(benchId)) {
                return br.requiredTierLevel;
            }
        }
        return -1;
    }
    
    // Replaces applyRecipeOverride
    private static CraftingRecipe applyModifications(String recipeId, CraftingRecipe original, List<ConfigIngredient> ingredients, Integer tier, String benchId) {
        MaterialQuantity[] newInputs = original.getInput();
        
        // Apply ingredient override if present
        if (ingredients != null) {
            newInputs = new MaterialQuantity[ingredients.size()];
            for (int i = 0; i < ingredients.size(); i++) {
                ConfigIngredient ci = ingredients.get(i);
                newInputs[i] = new MaterialQuantity(ci.item, null, null, ci.amount, null);
            }
        }
        
        // Deep copy bench requirements to modify safe
        BenchRequirement[] newRequirements = original.getBenchRequirement();
        if (tier != null && newRequirements != null) {
            newRequirements = new BenchRequirement[original.getBenchRequirement().length];
            for (int i = 0; i < original.getBenchRequirement().length; i++) {
                BenchRequirement origReq = original.getBenchRequirement()[i];
                newRequirements[i] = origReq.clone(); // Use clone method of BenchRequirement
                if (benchId.equals(newRequirements[i].id)) {
                    newRequirements[i].requiredTierLevel = tier;
                }
            }
        }
        
        // Create new recipe copying original properties but with new inputs
        CraftingRecipe newRecipe = new CraftingRecipe(
            newInputs,
            original.getPrimaryOutput(),
            original.getOutputs(), // extra outputs
            original.getPrimaryOutput().getQuantity(), // Assume primary output quantity is what matters
            newRequirements,
            original.getTimeSeconds(),
            original.isKnowledgeRequired(),
            original.getRequiredMemoriesLevel()
        );
        
        // Set the ID via reflection
        setRecipeId(newRecipe, recipeId);
        
        return newRecipe;
    }
    
    /* Old applyRecipeOverride removed */

    private static boolean doesRecipeMatch(CraftingRecipe recipe, List<ConfigIngredient> configuredIngredients) {
        MaterialQuantity[] currentInputs = recipe.getInput();
        
        if (currentInputs.length != configuredIngredients.size()) {
            return false;
        }

        Map<String, Integer> currentMap = new HashMap<>();
        for (MaterialQuantity mq : currentInputs) {
            currentMap.merge(mq.getItemId(), mq.getQuantity(), Integer::sum);
        }
        
        Map<String, Integer> configMap = new HashMap<>();
        for (ConfigIngredient ci : configuredIngredients) {
            configMap.merge(ci.item, ci.amount, Integer::sum);
        }
        
        return currentMap.equals(configMap);
    }
    
    private static void setRecipeId(CraftingRecipe recipe, String id) {
        try {
            java.lang.reflect.Field idField = CraftingRecipe.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(recipe, id);
        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to set recipe ID for " + id + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Gets the enchantment ID from a scroll item ID.
     * e.g., "Scroll_Sharpness_I" -> "sharpness"
     */
    private static String getEnchantmentIdFromScrollItemId(String scrollItemId) {
        for (Map.Entry<String, List<String>> entry : ENCHANTMENT_SCROLL_ITEMS.entrySet()) {
            if (entry.getValue().contains(scrollItemId)) {
                return entry.getKey();
            }
        }
        return null;
    }
    
    /**
     * Enables (re-adds) recipes for a specific enchantment.
     * Only works if the recipes were previously disabled and cached.
     * 
     * @param enchantmentId The enchantment ID (e.g., "sharpness")
     */
    /**
     * Enables (re-adds) recipes for a specific enchantment.
     * With event cancellation strategy, this just requires updating the disabled set,
     * which happens via reload/buildDisabledScrollSet.
     * 
     * @param enchantmentId The enchantment ID (e.g., "sharpness")
     */
    public static void enableEnchantmentRecipes(@Nonnull String enchantmentId) {
        // No-op for now, handled by state refresh in reload()
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

    /* Removed redundant Item listener logic as CraftingRecipe interception handles it. */

    
    private static void onBlockTypeLoad(LoadedAssetsEvent<String, BlockType, DefaultAssetMap<String, BlockType>> event) {
        if (plugin == null) return;
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
            java.util.Collection<org.herolias.plugin.api.CraftingCategoryDefinition> allDefs =
                    org.herolias.plugin.api.CraftingCategoryDefinition.values();

            // Filter to only addon (non-built-in) categories
            java.util.List<org.herolias.plugin.api.CraftingCategoryDefinition> addonCategories =
                    allDefs.stream().filter(d -> !d.isBuiltIn()).collect(java.util.stream.Collectors.toList());

            if (addonCategories.isEmpty()) return;

            Field benchField = BlockType.class.getDeclaredField("bench");
            benchField.setAccessible(true);
            Object bench = benchField.get(block);
            if (bench == null) return;

            // CraftingBench extends Bench, categories field is on CraftingBench
            Class<?> craftingBenchClass = bench.getClass();
            Field categoriesField = craftingBenchClass.getDeclaredField("categories");
            categoriesField.setAccessible(true);

            Object[] existingCategories = (Object[]) categoriesField.get(bench);
            if (existingCategories == null) existingCategories = new Object[0];

            // Check which addon categories aren't already present
            java.util.Set<String> existingIds = new java.util.HashSet<>();
            for (Object cat : existingCategories) {
                java.lang.reflect.Method getId = cat.getClass().getMethod("getId");
                existingIds.add((String) getId.invoke(cat));
            }

            java.util.List<Object> newCategories = new java.util.ArrayList<>(java.util.Arrays.asList(existingCategories));
            Class<?> benchCategoryClass = Class.forName(
                    "com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.CraftingBench$BenchCategory");

            for (org.herolias.plugin.api.CraftingCategoryDefinition def : addonCategories) {
                if (existingIds.contains(def.getCategoryId())) continue;

                // Create BenchCategory(id, name, icon, itemCategories)
                Object benchCategory = benchCategoryClass.getDeclaredConstructor(
                        String.class, String.class, String.class, 
                        Class.forName("[Lcom.hypixel.hytale.server.core.asset.type.blocktype.config.bench.CraftingBench$BenchItemCategory;")
                ).newInstance(
                        def.getCategoryId(),
                        "server.benchCategories." + def.getCategoryId(),
                        def.getIconPath(),
                        null
                );

                newCategories.add(benchCategory);

                // Register the display name translation
                plugin.getLanguageManager().putTranslation(
                        "benchCategories." + def.getCategoryId(), def.getDisplayName());

                LOGGER.atInfo().log("Injected addon crafting category tab: " + def.getCategoryId()
                        + " (" + def.getDisplayName() + ")");
            }

            // Write back the expanded array
            Object newArray = java.lang.reflect.Array.newInstance(benchCategoryClass, newCategories.size());
            for (int i = 0; i < newCategories.size(); i++) {
                java.lang.reflect.Array.set(newArray, i, newCategories.get(i));
            }
            categoriesField.set(bench, newArray);

        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to inject addon crafting categories: " + e.getMessage());
            e.printStackTrace();
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
             LOGGER.atSevere().log("Failed to apply block upgrades: " + e.getMessage());
             e.printStackTrace();
        }
    }

    private static void updateTier(BenchTierLevel[] tiers, int index, List<ConfigIngredient> ingredients) {
        if (index >= tiers.length || ingredients == null) return;
        
        try {
            BenchTierLevel tier = tiers[index];
            if (tier == null) return;
            
            MaterialQuantity[] materials = new MaterialQuantity[ingredients.size()];
            for(int i=0; i<ingredients.size(); i++) {
                ConfigIngredient ci = ingredients.get(i);
                materials[i] = new MaterialQuantity(ci.item, null, null, ci.amount, null);
            }
            
            BenchUpgradeRequirement newReq = new BenchUpgradeRequirement(materials, 5.0f);
            
            Field reqField = BenchTierLevel.class.getDeclaredField("upgradeRequirement");
            reqField.setAccessible(true);
            reqField.set(tier, newReq);
            
        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to update tier " + index + ": " + e.getMessage());
        }
    }

    /**
     * Former onItemLoad logic removed to prevent disabled scrolls from becoming 'Invalid Items'.
     */
}
