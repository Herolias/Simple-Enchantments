package org.herolias.plugin.enchantment;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.BenchRequirement;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.item.config.AssetIconProperties;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemEntityConfig;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemTranslationProperties;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.InteractionConfiguration;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.protocol.Vector2f;
import com.hypixel.hytale.protocol.Vector3f;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.config.EnchantingConfig;
import org.herolias.plugin.config.EnchantingConfig.ConfigIngredient;
import org.herolias.plugin.ui.EnchantScrollPageSupplier;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Generates scroll {@link Item}, {@link CraftingRecipe}, {@link Interaction},
 * and {@link RootInteraction} assets at runtime for all registered
 * enchantments.
 * and {@link RootInteraction} assets at runtime for all registered
 * enchantments.
 * <p>
 * Uses a two-phase approach because mod assets load BEFORE engine
 * {@code /Server}
 * Uses a two-phase approach because mod assets load BEFORE engine
 * {@code /Server}
 * assets. Phase 1 registers items with safe defaults. Phase 2 calls
 * {@code processConfig()} after engine assets (ItemQuality,
 * UnarmedInteractions)
 * {@code processConfig()} after engine assets (ItemQuality,
 * UnarmedInteractions)
 * are available.
 * <p>
 * The Cleansing scroll is excluded — it keeps its own JSON file.
 */
public class ScrollItemGenerator {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String DEFAULT_ICON = "Icons/ItemsGenerated/Scroll.png";
    private static final String DEFAULT_MODEL = "Items/Scrolls/EnchantmentScroll.blockymodel";
    private static final String DEFAULT_TEXTURE = "Items/Scrolls/EnchScroll.png";
    private static final String DEFAULT_PLAYER_ANIMATIONS_ID = "Item";
    private static final int DEFAULT_MAX_STACK = 10;
    private static final String SOURCE_ID = "SimpleEnchanting:ScrollGen";

    private static SimpleEnchanting plugin;

    /** Phase 1 done: items registered with safe defaults */
    private static boolean itemsRegistered = false;
    /** Phase 2 done: processConfig called after engine assets loaded */
    private static boolean itemsProcessed = false;
    /**
     * Re-entrance guard: true while we are inside
     * generateAndRegisterItems/processRegisteredItems
     */
    /**
     * Re-entrance guard: true while we are inside
     * generateAndRegisterItems/processRegisteredItems
     */
    private static boolean generating = false;
    /** Items we generated, kept for Phase 2 processConfig */
    private static final List<Item> generatedItems = new ArrayList<>();

    /**
     * Registers event listeners. Called during plugin setup().
     */
    public static void registerEventListener(@Nonnull SimpleEnchanting pluginInstance) {
        plugin = pluginInstance;

        plugin.getEventRegistry().register(
                LoadedAssetsEvent.class,
                Item.class,
                ScrollItemGenerator::onItemsLoaded);
                LoadedAssetsEvent.class,
                Item.class,
                ScrollItemGenerator::onItemsLoaded);

        LOGGER.atInfo().log("ScrollItemGenerator: Event listener registered");
    }

    /**
     * Called on EVERY LoadedAssetsEvent&lt;Item&gt;.
     * Phase 1 (first call): Generate and register items with safe defaults.
     * Phase 2 (subsequent call): Call processConfig() now that engine assets are
     * loaded.
     * Phase 2 (subsequent call): Call processConfig() now that engine assets are
     * loaded.
     */
    private static void onItemsLoaded(LoadedAssetsEvent<String, Item, DefaultAssetMap<String, Item>> event) {
        // Guard: AssetStore.loadAssets() fires nested LoadedAssetsEvent<Item>
        // synchronously.
        // Without this guard, Phase 1's loadAssets triggers Phase 2 prematurely (or
        // infinite recursion).
        if (generating)
            return;
        // Guard: AssetStore.loadAssets() fires nested LoadedAssetsEvent<Item>
        // synchronously.
        // Without this guard, Phase 1's loadAssets triggers Phase 2 prematurely (or
        // infinite recursion).
        if (generating)
            return;

        if (!itemsRegistered) {
            // Phase 1: Generate and register items (mod loads first, before /Server)
            itemsRegistered = true;
            generating = true;
            try {
                LOGGER.atInfo().log("ScrollItemGenerator: Phase 1 — Generating scroll items...");
                generateAndRegisterItems();
            } finally {
                generating = false;
            }
        } else if (!itemsProcessed) {
            // Phase 2: Engine /Server assets are now loaded — call processConfig
            itemsProcessed = true;
            generating = true;
            try {
                LOGGER.atInfo()
                        .log("ScrollItemGenerator: Phase 2 — Running processConfig (engine assets now available)...");
                LOGGER.atInfo()
                        .log("ScrollItemGenerator: Phase 2 — Running processConfig (engine assets now available)...");
                processRegisteredItems();
            } finally {
                generating = false;
            }
        }
    }

    // ───────────────────── Phase 1: Generate & Register ─────────────────────

    private static void generateAndRegisterItems() {
        EnchantingConfig config = plugin.getConfigManager().getConfig();
        Map<String, List<ConfigIngredient>> scrollRecipes = config.scrollRecipes;

        if (scrollRecipes == null || scrollRecipes.isEmpty()) {
            LOGGER.atWarning().log("ScrollItemGenerator: No scroll recipes in config. Skipping.");
            return;
        }

        List<CraftingRecipe> recipesToRegister = new ArrayList<>();
        List<Interaction> interactionsToRegister = new ArrayList<>();
        List<RootInteraction> rootInteractionsToRegister = new ArrayList<>();

        for (EnchantmentType type : EnchantmentType.values()) {
            if ("cleansing".equals(type.getId()))
                continue;
            if ("cleansing".equals(type.getId()))
                continue;

            String scrollBaseName = type.getScrollBaseName();

            // Check if this enchantment has addon ScrollDefinitions
            List<org.herolias.plugin.api.ScrollDefinition> addonScrollDefs = type.getScrollDefinitions();
            boolean isAddon = addonScrollDefs != null && !addonScrollDefs.isEmpty();

            for (int level = 1; level <= type.getMaxLevel(); level++) {
                String scrollItemId = scrollBaseName + "_" + EnchantmentType.toRoman(level);

                int craftingTier;
                List<ConfigIngredient> ingredients = new ArrayList<>();
                String quality;
                int itemLevel;
                String[] craftingCategories;

                if (isAddon) {
                    // ─── Use ScrollDefinition from the builder API ───
                    final int lvl = level;
                    org.herolias.plugin.api.ScrollDefinition def = addonScrollDefs.stream()
                            .filter(d -> d.getLevel() == lvl)
                            .findFirst().orElse(null);
                    if (def == null) {
                        LOGGER.atWarning().log("ScrollItemGenerator: No ScrollDefinition for " + scrollItemId
                                + " (level " + level + ")");
                        LOGGER.atWarning().log("ScrollItemGenerator: No ScrollDefinition for " + scrollItemId
                                + " (level " + level + ")");
                        continue;
                    }

                    craftingTier = def.getCraftingTier();
                    quality = def.getQuality() != null ? def.getQuality() : "Uncommon";
                    itemLevel = level;

                    // Resolve crafting categories: use the definition's category, fall back to the
                    // type's category
                    // Resolve crafting categories: use the definition's category, fall back to the
                    // type's category
                    String cat = def.getCraftingCategory();
                    if (cat == null)
                        cat = type.getCraftingCategory();
                    if (cat == null)
                        cat = guessCraftingCategory(type);
                    craftingCategories = new String[] { cat };
                    if (cat == null)
                        cat = type.getCraftingCategory();
                    if (cat == null)
                        cat = guessCraftingCategory(type);
                    craftingCategories = new String[] { cat };

                    org.herolias.plugin.api.ScrollDefinition.IconProperties iconProps = def.getIconProperties();

                    for (org.herolias.plugin.api.ScrollDefinition.Ingredient ing : def.getRecipe()) {
                        ConfigIngredient ci = new ConfigIngredient();
                        ci.item = ing.getItemId();
                        ci.amount = ing.getQuantity();
                        ingredients.add(ci);
                    }
                } else {
                    // ─── Use config.scrollRecipes + BuiltinScrolls (built-in enchantments) ───
                    List<ConfigIngredient> configRecipe = scrollRecipes.get(scrollItemId);
                    if (configRecipe == null) {
                        LOGGER.atWarning().log("ScrollItemGenerator: No recipe for " + scrollItemId);
                        continue;
                    }

                    craftingTier = 1;
                    for (ConfigIngredient ci : configRecipe) {
                        if (ci.UnlocksAtTier != null) {
                            craftingTier = ci.UnlocksAtTier;
                        } else {
                            ingredients.add(ci);
                        }
                    }

                    quality = BuiltinScrolls.getQuality(scrollItemId);
                    itemLevel = BuiltinScrolls.getItemLevel(scrollItemId);
                    craftingCategories = BuiltinScrolls.getCraftingCategories(scrollItemId);
                }

                // 1. Interaction
                String interactionId = "SE_Interaction_" + scrollItemId;
                OpenCustomUIInteraction interaction = createScrollInteraction(interactionId, type.getId(), level);
                if (interaction != null)
                    interactionsToRegister.add(interaction);
                if (interaction != null)
                    interactionsToRegister.add(interaction);

                // 2. RootInteractions
                String rootPrimaryId = "SE_Root_Primary_" + scrollItemId;
                String rootSecondaryId = "SE_Root_Secondary_" + scrollItemId;
                rootInteractionsToRegister.add(new RootInteraction(rootPrimaryId, interactionId));
                rootInteractionsToRegister.add(new RootInteraction(rootSecondaryId, interactionId));

                // 3. Item (with safe defaults — processConfig runs in Phase 2)
                org.herolias.plugin.api.ScrollDefinition.IconProperties iconProps = null;
                if (isAddon) {
                    final int lvl = level;
                    org.herolias.plugin.api.ScrollDefinition def = addonScrollDefs.stream()
                            .filter(d -> d.getLevel() == lvl).findFirst().orElse(null);
                    if (def != null) {
                        iconProps = def.getIconProperties();
                    }
                }
                if (iconProps == null) {
                    // Default fallback properties for built-in or if addon def is missing
                    iconProps = new org.herolias.plugin.api.ScrollDefinition.IconProperties(0.84f, 31f, 18f, 0f, 90f,
                            -5f);
                }

                Item item = createScrollItem(scrollItemId, itemLevel, quality, rootPrimaryId, rootSecondaryId,
                        iconProps);
                if (item != null)
                    generatedItems.add(item);
                Item item = createScrollItem(scrollItemId, itemLevel, quality, rootPrimaryId, rootSecondaryId,
                        iconProps);
                if (item != null)
                    generatedItems.add(item);

                // 3b. Register translations for addon scroll items
                if (isAddon && item != null) {
                    try {
                        org.herolias.plugin.lang.LanguageManager langMgr = plugin.getLanguageManager();
                        String roman = EnchantmentType.toRoman(level);
                        String scrollName = "Scroll of " + type.getDisplayName() + " " + roman;
                        String scrollDesc = type.getDescription();
                        langMgr.putTranslation("items." + scrollItemId + ".name", scrollName);
                        langMgr.putTranslation("items." + scrollItemId + ".description", scrollDesc);
                    } catch (Exception e) {
                        LOGGER.atWarning()
                                .log("ScrollItemGenerator: Failed to register translations for " + scrollItemId);
                        LOGGER.atWarning()
                                .log("ScrollItemGenerator: Failed to register translations for " + scrollItemId);
                    }
                }

                // 4. Recipe
                CraftingRecipe recipe = createScrollRecipe(scrollItemId, ingredients, craftingTier, craftingCategories);
                if (recipe != null)
                    recipesToRegister.add(recipe);
                if (recipe != null)
                    recipesToRegister.add(recipe);
            }
        }

        // Register in dependency order
        try {
            if (!interactionsToRegister.isEmpty()) {
                Interaction.getAssetStore().loadAssets(SOURCE_ID, interactionsToRegister);
                LOGGER.atInfo()
                        .log("ScrollItemGenerator: Registered " + interactionsToRegister.size() + " interactions");
                LOGGER.atInfo()
                        .log("ScrollItemGenerator: Registered " + interactionsToRegister.size() + " interactions");
            }
            if (!rootInteractionsToRegister.isEmpty()) {
                RootInteraction.getAssetStore().loadAssets(SOURCE_ID, rootInteractionsToRegister);
                LOGGER.atInfo().log(
                        "ScrollItemGenerator: Registered " + rootInteractionsToRegister.size() + " root interactions");
                LOGGER.atInfo().log(
                        "ScrollItemGenerator: Registered " + rootInteractionsToRegister.size() + " root interactions");
            }
            if (!generatedItems.isEmpty()) {
                Item.getAssetStore().loadAssets(SOURCE_ID, generatedItems);
                LOGGER.atInfo().log("ScrollItemGenerator: Registered " + generatedItems.size() + " scroll items");
            }
            if (!recipesToRegister.isEmpty()) {
                CraftingRecipe.getAssetStore().loadAssets(SOURCE_ID, recipesToRegister);
                LOGGER.atInfo().log("ScrollItemGenerator: Registered " + recipesToRegister.size() + " recipes");
            }
        } catch (Exception e) {
            LOGGER.atSevere().log("ScrollItemGenerator: Failed to register assets: " + e.getMessage());
            e.printStackTrace();
        }

        LOGGER.atInfo().log("ScrollItemGenerator: Phase 1 complete. " +
                generatedItems.size() + " items, " + recipesToRegister.size() + " recipes.");
    }

    // ───────────────────── Phase 2: processConfig ─────────────────────

    /**
     * Calls processConfig() on all generated items now that engine assets
     * (ItemQuality, UnarmedInteractions, etc.) are loaded.
     * Also clears cached packets so toPacket() regenerates with resolved values.
     */
    private static void processRegisteredItems() {
        int success = 0;
        int failed = 0;

        for (Item item : generatedItems) {
            try {
                Method processConfig = Item.class.getDeclaredMethod("processConfig");
                processConfig.setAccessible(true);
                processConfig.invoke(item);

                // Check if this scroll's enchantment is disabled in the config
                try {
                    String itemId = item.getId();
                    boolean isDisabled = false;
                    for (EnchantmentType type : EnchantmentType.values()) {
                        if (itemId.startsWith(type.getScrollBaseName() + "_")) {
                            EnchantingConfig config = plugin.getConfigManager().getConfig();
                            if (config.disabledEnchantments.getOrDefault(type.getId(), false)) {
                                isDisabled = true;
                                break;
                            }
                        }
                    }
                    if (isDisabled) {
                        // Clear categories to immediately hide it from the creative menu if disabled
                        setField(Item.class, item, "categories", new String[0]);
                    }
                } catch (Exception ignore) {
                }
                } catch (Exception ignore) {
                }

                // Clear cached packet so toPacket() regenerates with new values
                Field cachedPacketField = Item.class.getDeclaredField("cachedPacket");
                cachedPacketField.setAccessible(true);
                cachedPacketField.set(item, null);

                success++;
            } catch (Exception e) {
                LOGGER.atSevere().log("ScrollItemGenerator: processConfig failed for "
                        + item.getId() + ": " + e.getMessage());
                e.printStackTrace();
                failed++;
            }
        }

    LOGGER.atInfo().log("ScrollItemGenerator: Phase 2 complete. processConfig: "+success+" ok, "+failed+" failed.");

    }

    // ───────────────────── Item & Asset Creation ─────────────────────

    private static OpenCustomUIInteraction createScrollInteraction(
            String interactionId, String enchantmentId, int level) {
        try {
            OpenCustomUIInteraction interaction = new OpenCustomUIInteraction();

            Field idField = Interaction.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(interaction, interactionId);

            EnchantScrollPageSupplier supplier = new EnchantScrollPageSupplier();
            Field enchIdField = EnchantScrollPageSupplier.class.getDeclaredField("enchantmentId");
            enchIdField.setAccessible(true);
            enchIdField.set(supplier, enchantmentId);

            Field levelField = EnchantScrollPageSupplier.class.getDeclaredField("level");
            levelField.setAccessible(true);
            levelField.set(supplier, level);

            Field supplierField = OpenCustomUIInteraction.class.getDeclaredField("customPageSupplier");
            supplierField.setAccessible(true);
            supplierField.set(interaction, supplier);

            Field dataField = Interaction.class.getDeclaredField("data");
            dataField.setAccessible(true);
            dataField.set(interaction, new AssetExtraInfo.Data(Interaction.class, interactionId, null));

            return interaction;
        } catch (Exception e) {
            LOGGER.atSevere()
                    .log("ScrollItemGenerator: Failed to create interaction " + interactionId + ": " + e.getMessage());
            LOGGER.atSevere()
                    .log("ScrollItemGenerator: Failed to create interaction " + interactionId + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Creates a scroll Item with safe defaults for interactionConfig and
     * itemEntityConfig.
     * processConfig() will be called later in Phase 2 to resolve quality,
     * interaction
     * Creates a scroll Item with safe defaults for interactionConfig and
     * itemEntityConfig.
     * processConfig() will be called later in Phase 2 to resolve quality,
     * interaction
     * fallbacks, etc.
     */
    private static Item createScrollItem(String itemId, int level, String quality,
            String rootPrimaryId, String rootSecondaryId,
            org.herolias.plugin.api.ScrollDefinition.IconProperties iconProps) {
            String rootPrimaryId, String rootSecondaryId,
            org.herolias.plugin.api.ScrollDefinition.IconProperties iconProps) {
        try {
            Item item = new Item(itemId);

            setField(Item.class, item, "icon", DEFAULT_ICON);

            // Create Hytale's internal AssetIconProperties object using the provided
            // iconProps
            // Create Hytale's internal AssetIconProperties object using the provided
            // iconProps
            if (iconProps != null) {
                AssetIconProperties aip = new AssetIconProperties(
                        iconProps.getScale(),
                        new Vector2f(iconProps.getTranslationX(), iconProps.getTranslationY()),
                        new Vector3f(iconProps.getRotationX(), iconProps.getRotationY(), iconProps.getRotationZ()));
                        new Vector3f(iconProps.getRotationX(), iconProps.getRotationY(), iconProps.getRotationZ()));
                setField(Item.class, item, "iconProperties", aip);
            }

            setField(Item.class, item, "model", DEFAULT_MODEL);
            setField(Item.class, item, "texture", DEFAULT_TEXTURE);
            setField(Item.class, item, "playerAnimationsId", DEFAULT_PLAYER_ANIMATIONS_ID);
            setField(Item.class, item, "maxStack", DEFAULT_MAX_STACK);
            setField(Item.class, item, "itemLevel", level);

            if (quality != null) {
                setField(Item.class, item, "qualityId", quality);
            }

            String nameKey = "server.items." + itemId + ".name";
            String descKey = "server.items." + itemId + ".description";
            setField(Item.class, item, "translationProperties",
                    new ItemTranslationProperties(nameKey, descKey));

            setField(Item.class, item, "categories", new String[] { "Items.Magic" });
            setField(Item.class, item, "categories", new String[] { "Items.Magic" });

            // Safe defaults — processConfig() will override in Phase 2
            setField(Item.class, item, "interactionConfig", InteractionConfiguration.DEFAULT);
            setField(Item.class, item, "itemEntityConfig", ItemEntityConfig.DEFAULT);

            Map<InteractionType, String> interactions = new EnumMap<>(InteractionType.class);
            interactions.put(InteractionType.Primary, rootPrimaryId);
            interactions.put(InteractionType.Secondary, rootSecondaryId);
            setField(Item.class, item, "interactions", interactions);

            return item;
        } catch (Exception e) {
            LOGGER.atSevere().log("ScrollItemGenerator: Failed to create item " + itemId + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private static CraftingRecipe createScrollRecipe(String scrollItemId,
            List<ConfigIngredient> ingredients,
            int craftingTier,
            String[] craftingCategories) {
            List<ConfigIngredient> ingredients,
            int craftingTier,
            String[] craftingCategories) {
        try {
            MaterialQuantity[] input = new MaterialQuantity[ingredients.size()];
            for (int i = 0; i < ingredients.size(); i++) {
                ConfigIngredient ci = ingredients.get(i);
                input[i] = new MaterialQuantity(ci.item, null, null, ci.amount, null);
            }

            MaterialQuantity primaryOutput = new MaterialQuantity(scrollItemId, null, null, 1, null);

            BenchRequirement benchReq = new BenchRequirement();
            benchReq.id = "Enchantingbench";
            benchReq.type = BenchType.Crafting;
            benchReq.categories = craftingCategories;
            benchReq.requiredTierLevel = craftingTier;

            CraftingRecipe recipe = new CraftingRecipe(
                    input, primaryOutput,
                    new MaterialQuantity[] { primaryOutput }, 1,
                    new BenchRequirement[] { benchReq },
                    2.0f, false, 1);
                    new MaterialQuantity[] { primaryOutput }, 1,
                    new BenchRequirement[] { benchReq },
                    2.0f, false, 1);

            String recipeId = scrollItemId + "_Recipe_Generated_0";
            setField(CraftingRecipe.class, recipe, "id", recipeId);

            return recipe;
        } catch (Exception e) {
            LOGGER.atSevere()
                    .log("ScrollItemGenerator: Failed to create recipe for " + scrollItemId + ": " + e.getMessage());
            LOGGER.atSevere()
                    .log("ScrollItemGenerator: Failed to create recipe for " + scrollItemId + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private static String guessCraftingCategory(EnchantmentType type) {
        Set<ItemCategory> cats = type.getApplicableCategories();
        if (cats.contains(ItemCategory.MELEE_WEAPON))
            return "Enchanting_Melee";
        if (cats.contains(ItemCategory.RANGED_WEAPON))
            return "Enchanting_Ranged";
        if (cats.contains(ItemCategory.MELEE_WEAPON))
            return "Enchanting_Melee";
        if (cats.contains(ItemCategory.RANGED_WEAPON))
            return "Enchanting_Ranged";
        if (cats.contains(ItemCategory.ARMOR) || cats.contains(ItemCategory.HELMET)
                || cats.contains(ItemCategory.BOOTS) || cats.contains(ItemCategory.GLOVES))
            return "Enchanting_Armor";
        if (cats.contains(ItemCategory.SHIELD))
            return "Enchanting_Shield";
        if (cats.contains(ItemCategory.SHIELD))
            return "Enchanting_Shield";
        if (cats.contains(ItemCategory.STAFF) || cats.contains(ItemCategory.STAFF_MANA)
                || cats.contains(ItemCategory.STAFF_ESSENCE))
            return "Enchanting_Staff";
        if (cats.contains(ItemCategory.PICKAXE) || cats.contains(ItemCategory.AXE)
                || cats.contains(ItemCategory.SHOVEL) || cats.contains(ItemCategory.TOOL))
            return "Enchanting_Tools";
        return "Enchanting_Melee";
    }

    private static void setField(Class<?> clazz, Object obj, String fieldName, Object value) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }
}
