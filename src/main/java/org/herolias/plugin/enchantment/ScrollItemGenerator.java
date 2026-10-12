package org.herolias.plugin.enchantment;

import com.hypixel.hytale.assetstore.AssetLoadResult;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.RawAsset;
import com.hypixel.hytale.assetstore.codec.ContainedAssetCodec;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.modules.interaction.interaction.UnarmedInteractions;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.api.ScrollDefinition;
import org.herolias.plugin.config.EnchantingConfig;
import org.herolias.plugin.config.EnchantingConfig.ConfigIngredient;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates the scroll {@link Item} assets (and, through the item document,
 * their {@code RootInteraction}/{@code Interaction} children and crafting
 * recipe) at runtime for every registered enchantment.
 * <p>
 * Each scroll is described as a JSON document with exactly the shape of a
 * hand-written item file (see {@code Server/Item/Items/Scrolls/Scroll_Cleansing.json})
 * and is pushed through the public asset-store pipeline
 * ({@link com.hypixel.hytale.assetstore.AssetStore#loadBuffersWithKeys}). That
 * pipeline runs the {@code Item} codec including
 * {@code afterDecode(Item::processConfig)}, registers the contained
 * interactions, and lets the vanilla crafting plugin register the
 * {@code <ItemId>_Recipe_Generated_0} recipe, exactly as it does for items
 * loaded from disk.
 * <p>
 * Generation runs once, as soon as the mod's own item assets are present
 * (core {@code Hytale} packs are always loaded before third-party packs, so
 * the engine assets {@code processConfig()} needs - item qualities, unarmed
 * interactions - are available by then). On a plugin reload, where the asset
 * map is already populated during {@code setup()}, generation runs
 * immediately.
 * <p>
 * All generated assets are registered under the plugin's own asset-pack key
 * ({@code org.herolias:SimpleEnchantments}), so the server removes them
 * together with the pack when the plugin is unloaded.
 * <p>
 * The Cleansing scroll is excluded - it keeps its own JSON file.
 */
public class ScrollItemGenerator {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String DEFAULT_ICON = "Icons/ItemsGenerated/Scroll.png";
    private static final String DEFAULT_MODEL = "Items/Scrolls/EnchantmentScroll.blockymodel";
    private static final String DEFAULT_TEXTURE = "Items/Scrolls/EnchScroll.png";
    private static final String DEFAULT_PLAYER_ANIMATIONS_ID = "Item";
    private static final int DEFAULT_MAX_STACK = 10;
    private static final double RECIPE_TIME_SECONDS = 2.0;
    private static final String CREATIVE_SCROLL_CATEGORY = CreativeCategoryInjector.SCROLL_CATEGORY_PATH;
    private static final String ENCHANTING_BENCH_ID = "Enchantingbench";
    private static final String SCROLL_PAGE_ID = "EnchantScroll";

    /**
     * An item shipped in this mod's own asset pack. Once it is present in the item
     * map, the mod pack (and, because core packs load first, every engine asset)
     * has been loaded and generation is safe.
     */
    private static final String READINESS_MARKER_ITEM = "Scroll_Cleansing";

    private static final ScrollDefinition.IconProperties DEFAULT_ICON_PROPERTIES = new ScrollDefinition.IconProperties(
            0.84f, -35f, -18f, 180f, 90f, -5f);

    private static SimpleEnchanting plugin;

    /** True once generation has been attempted for this plugin lifetime. */
    private static volatile boolean generated = false;
    /** Re-entrance guard: loading assets fires nested LoadedAssetsEvents. */
    private static volatile boolean generating = false;
    /** IDs of the items we generated (for diagnostics / consumers). */
    private static final Set<String> generatedItemIds = ConcurrentHashMap.newKeySet();

    /**
     * Registers the asset listener. Called during plugin {@code setup()}. If the
     * item assets are already loaded (plugin reload), generation runs right away.
     */
    public static void registerEventListener(@Nonnull SimpleEnchanting pluginInstance) {
        plugin = pluginInstance;
        generated = false;
        generating = false;
        generatedItemIds.clear();

        plugin.getEventRegistry().register(
                LoadedAssetsEvent.class,
                Item.class,
                ScrollItemGenerator::onItemsLoaded);

        LOGGER.atInfo().log("ScrollItemGenerator: event listener registered");

        if (isReadyToGenerate()) {
            LOGGER.atInfo().log(
                    "ScrollItemGenerator: item assets are already loaded (plugin reload) - generating scroll items now");
            generateOnce();
        }
    }

    /**
     * Resets all static state. Call from plugin {@code shutdown()}. The generated
     * assets themselves are removed by the server together with the plugin's
     * asset pack.
     */
    public static void unload() {
        generated = false;
        generating = false;
        generatedItemIds.clear();
        plugin = null;
    }

    /** @return true once the scroll items have been generated. */
    public static boolean isGenerated() {
        return generated;
    }

    /** @return the IDs of the generated scroll items (unmodifiable). */
    @Nonnull
    public static Set<String> getGeneratedItemIds() {
        return Collections.unmodifiableSet(generatedItemIds);
    }

    /** The asset-pack key every generated asset is registered under. */
    @Nonnull
    public static String getPackKey() {
        return plugin.getIdentifier().toString();
    }

    private static boolean isReadyToGenerate() {
        try {
            return Item.getAssetMap().getAsset(READINESS_MARKER_ITEM) != null;
        } catch (Exception e) {
            // Asset store not registered yet
            return false;
        }
    }

    private static void onItemsLoaded(LoadedAssetsEvent<String, Item, DefaultAssetMap<String, Item>> event) {
        if (generating || generated || plugin == null) {
            return;
        }
        if (!isReadyToGenerate()) {
            LOGGER.atFine().log("ScrollItemGenerator: waiting for the mod's own item assets before generating scrolls");
            return;
        }
        generateOnce();
    }

    private static synchronized void generateOnce() {
        if (generated || generating) {
            return;
        }
        generating = true;
        generated = true; // never retry in a loop; regeneration requires unload()/registerEventListener()
        try {
            generateAndRegisterItems();
        } catch (Exception e) {
            LOGGER.atSevere().withCause(e).log("ScrollItemGenerator: failed to generate scroll items");
        } finally {
            generating = false;
        }
    }

    // ───────────────────── Generation ─────────────────────

    private static void generateAndRegisterItems() {
        EnchantingConfig config = plugin.getConfigManager().getConfig();
        Map<String, List<ConfigIngredient>> scrollRecipes = config.scrollRecipes;

        if (scrollRecipes == null || scrollRecipes.isEmpty()) {
            LOGGER.atWarning().log("ScrollItemGenerator: No scroll recipes in config. Skipping.");
            return;
        }

        if (UnarmedInteractions.getAssetMap().getAsset(DEFAULT_PLAYER_ANIMATIONS_ID) == null) {
            LOGGER.atWarning().log(
                    "ScrollItemGenerator: engine UnarmedInteractions '%s' is not loaded yet; generated scrolls will use default interaction fallbacks",
                    DEFAULT_PLAYER_ANIMATIONS_ID);
        }

        String packKey = getPackKey();
        List<RawAsset<String>> rawAssets = new ArrayList<>();
        int withRecipe = 0;

        for (EnchantmentType type : EnchantmentType.values()) {
            if ("cleansing".equals(type.getId())) {
                continue;
            }

            String scrollBaseName = type.getScrollBaseName();
            List<ScrollDefinition> addonScrollDefs = type.getScrollDefinitions();
            boolean isAddon = addonScrollDefs != null && !addonScrollDefs.isEmpty();
            boolean enabled = isScrollEnabled(type);

            for (int level = 1; level <= type.getMaxLevel(); level++) {
                String scrollItemId = scrollBaseName + "_" + EnchantmentType.toRoman(level);

                int craftingTier;
                List<ConfigIngredient> ingredients = new ArrayList<>();
                String quality;
                int itemLevel;
                String[] craftingCategories;
                String icon = null;
                String model = null;
                String texture = null;
                ScrollDefinition.IconProperties iconProps = null;

                if (isAddon) {
                    // ─── Use ScrollDefinition from the builder API ───
                    final int lvl = level;
                    ScrollDefinition def = addonScrollDefs.stream()
                            .filter(d -> d.getLevel() == lvl)
                            .findFirst().orElse(null);
                    if (def == null) {
                        LOGGER.atWarning().log("ScrollItemGenerator: No ScrollDefinition for %s (level %d)",
                                scrollItemId, level);
                        continue;
                    }

                    craftingTier = def.getCraftingTier();
                    quality = def.getQuality() != null ? def.getQuality() : "Uncommon";
                    itemLevel = level;

                    String cat = def.getCraftingCategory();
                    if (cat == null)
                        cat = type.getCraftingCategory();
                    if (cat == null)
                        cat = guessCraftingCategory(type);
                    craftingCategories = new String[] { cat };

                    icon = def.getIcon();
                    model = def.getModel();
                    texture = def.getTexture();
                    iconProps = def.getIconProperties();

                    for (ScrollDefinition.Ingredient ing : def.getRecipe()) {
                        ConfigIngredient ci = new ConfigIngredient();
                        ci.item = ing.getItemId();
                        ci.amount = ing.getQuantity();
                        ingredients.add(ci);
                    }
                } else {
                    // ─── Use config.scrollRecipes + BuiltinScrolls (built-in enchantments) ───
                    List<ConfigIngredient> configRecipe = scrollRecipes.get(scrollItemId);
                    if (configRecipe == null) {
                        LOGGER.atWarning().log("ScrollItemGenerator: No recipe for %s", scrollItemId);
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

                if (craftingCategories == null || craftingCategories.length == 0) {
                    craftingCategories = new String[] { guessCraftingCategory(type) };
                }
                if (iconProps == null) {
                    iconProps = DEFAULT_ICON_PROPERTIES;
                }
                if (texture == null)
                    texture = getTextureForEnchantment(type.getId());
                if (icon == null)
                    icon = getIconForEnchantment(type.getId());
                if (model == null)
                    model = DEFAULT_MODEL;

                BsonDocument doc = buildScrollDocument(scrollItemId, type.getId(), level, itemLevel, quality,
                        icon, iconProps, model, texture, enabled, ingredients, craftingTier, craftingCategories);
                if (doc.containsKey("Recipe")) {
                    withRecipe++;
                }

                rawAssets.add(new RawAsset<>((Path) null, scrollItemId, null, 0,
                        doc.toJson().toCharArray(), null, ContainedAssetCodec.Mode.NONE));

                // Register translations for addon scroll items
                if (isAddon) {
                    try {
                        org.herolias.plugin.lang.LanguageManager langMgr = plugin.getLanguageManager();
                        String roman = EnchantmentType.toRoman(level);
                        langMgr.putTranslation("items." + scrollItemId + ".name",
                                "Scroll of " + type.getDisplayName() + " " + roman);
                        langMgr.putTranslation("items." + scrollItemId + ".description", type.getDescription());
                    } catch (Exception e) {
                        LOGGER.atWarning().withCause(e)
                                .log("ScrollItemGenerator: Failed to register translations for %s", scrollItemId);
                    }
                }
            }
        }

        if (rawAssets.isEmpty()) {
            LOGGER.atWarning().log("ScrollItemGenerator: nothing to generate");
            return;
        }

        // Full asset pipeline: Item codec (incl. processConfig), validation, contained
        // RootInteraction/Interaction children, and the vanilla item->recipe generation.
        // forceLoadAll=true makes re-generation idempotent (skips duplicate detection).
        AssetLoadResult<String, Item> result = Item.getAssetStore()
                .loadBuffersWithKeys(packKey, rawAssets, AssetUpdateQuery.DEFAULT, true);

        generatedItemIds.addAll(result.getLoadedAssets().keySet());

        LOGGER.atInfo().log(
                "ScrollItemGenerator: generated %d scroll items (%d with recipes) through the Item codec under pack '%s'",
                result.getLoadedAssets().size(), withRecipe, packKey);
        if (!result.getFailedToLoadKeys().isEmpty()) {
            LOGGER.atSevere().log("ScrollItemGenerator: %d generated scroll items failed to load: %s",
                    result.getFailedToLoadKeys().size(), result.getFailedToLoadKeys());
        }
    }

    /**
     * Whether the scroll should be visible/usable. Uses the manager's cached view
     * (which also accounts for the Perfect Parries dependency) when available; the
     * manager may not exist yet if generation runs during {@code setup()}.
     */
    private static boolean isScrollEnabled(EnchantmentType type) {
        EnchantmentManager manager = plugin.getEnchantmentManager();
        if (manager != null) {
            return manager.isEnchantmentEnabled(type);
        }
        EnchantingConfig config = plugin.getConfigManager().getConfig();
        if (config.disabledEnchantments != null && config.disabledEnchantments.getOrDefault(type.getId(), false)) {
            return false;
        }
        if ((type == EnchantmentType.RIPOSTE || type == EnchantmentType.COUP_DE_GRACE)
                && !plugin.isPerfectParriesModPresent()) {
            return false;
        }
        return true;
    }

    // ───────────────────── Item document ─────────────────────

    /**
     * Builds the item JSON document. Field names mirror the {@code Item} codec
     * (compare {@code Scroll_Cleansing.json}).
     */
    @Nonnull
    static BsonDocument buildScrollDocument(String itemId, String enchantmentId, int level, int itemLevel,
            @Nullable String quality, String icon, ScrollDefinition.IconProperties iconProps, String model,
            String texture, boolean enabled, List<ConfigIngredient> ingredients, int craftingTier,
            String[] craftingCategories) {
        BsonDocument doc = new BsonDocument();

        doc.append("TranslationProperties", new BsonDocument()
                .append("Name", new BsonString("server.items." + itemId + ".name"))
                .append("Description", new BsonString("server.items." + itemId + ".description")));
        doc.append("Icon", new BsonString(icon != null ? icon : DEFAULT_ICON));

        // Disabled scrolls get no creative category so they stay out of the creative menu
        if (enabled) {
            doc.append("Categories", new BsonArray(List.of(new BsonString(CREATIVE_SCROLL_CATEGORY))));
        }

        doc.append("Model", new BsonString(model != null ? model : DEFAULT_MODEL));
        doc.append("Texture", new BsonString(texture != null ? texture : DEFAULT_TEXTURE));

        // Inline root interactions -> contained RootInteraction + OpenCustomUI Interaction
        BsonDocument page = new BsonDocument()
                .append("Id", new BsonString(SCROLL_PAGE_ID))
                .append("EnchantmentId", new BsonString(enchantmentId))
                .append("Level", new BsonInt32(level));
        BsonDocument interaction = new BsonDocument()
                .append("Type", new BsonString("OpenCustomUI"))
                .append("Page", page);
        doc.append("Interactions", new BsonDocument()
                .append("Primary", new BsonDocument("Interactions", new BsonArray(List.of(interaction.clone()))))
                .append("Secondary", new BsonDocument("Interactions", new BsonArray(List.of(interaction.clone())))));

        // Inline recipe -> the crafting plugin registers "<itemId>_Recipe_Generated_0"
        BsonArray input = new BsonArray();
        for (ConfigIngredient ci : ingredients) {
            if (ci == null || ci.UnlocksAtTier != null) {
                continue;
            }
            BsonDocument mq = new BsonDocument();
            if (ci.isResourceType()) {
                mq.append("ResourceTypeId", new BsonString(ci.resourceType));
            } else if (ci.item != null && !ci.item.isEmpty()) {
                mq.append("ItemId", new BsonString(ci.item));
            } else {
                LOGGER.atWarning().log(
                        "ScrollItemGenerator: skipping ingredient of %s with neither 'item' nor 'resourceType'",
                        itemId);
                continue;
            }
            mq.append("Quantity", new BsonInt32(ci.amount != null ? ci.amount : 1));
            input.add(mq);
        }
        if (input.isEmpty()) {
            LOGGER.atWarning().log("ScrollItemGenerator: %s has no valid ingredients; no recipe generated", itemId);
        } else {
            BsonArray categories = new BsonArray();
            for (String c : craftingCategories) {
                categories.add(new BsonString(c));
            }
            BsonDocument benchRequirement = new BsonDocument()
                    .append("Id", new BsonString(ENCHANTING_BENCH_ID))
                    .append("Type", new BsonString("Crafting"))
                    .append("Categories", categories)
                    .append("RequiredTierLevel", new BsonInt32(craftingTier));
            doc.append("Recipe", new BsonDocument()
                    .append("TimeSeconds", new BsonDouble(RECIPE_TIME_SECONDS))
                    .append("Input", input)
                    .append("OutputQuantity", new BsonInt32(1))
                    .append("BenchRequirement", new BsonArray(List.of(benchRequirement))));
        }

        doc.append("PlayerAnimationsId", new BsonString(DEFAULT_PLAYER_ANIMATIONS_ID));
        doc.append("IconProperties", new BsonDocument()
                .append("Scale", new BsonDouble(iconProps.getScale()))
                .append("Translation", new BsonArray(List.of(
                        new BsonDouble(iconProps.getTranslationX()),
                        new BsonDouble(iconProps.getTranslationY()))))
                .append("Rotation", new BsonArray(List.of(
                        new BsonDouble(iconProps.getRotationX()),
                        new BsonDouble(iconProps.getRotationY()),
                        new BsonDouble(iconProps.getRotationZ())))));
        doc.append("MaxStack", new BsonInt32(DEFAULT_MAX_STACK));
        doc.append("ItemLevel", new BsonInt32(itemLevel));
        if (quality != null) {
            doc.append("Quality", new BsonString(quality));
        }
        return doc;
    }

    // ───────────────────── Lookups ─────────────────────

    private static String guessCraftingCategory(EnchantmentType type) {
        Set<ItemCategory> cats = type.getApplicableCategories();
        if (cats.contains(ItemCategory.MELEE_WEAPON))
            return "Enchanting_Melee";
        if (cats.contains(ItemCategory.RANGED_WEAPON))
            return "Enchanting_Ranged";
        if (cats.contains(ItemCategory.ARMOR) || cats.contains(ItemCategory.HELMET)
                || cats.contains(ItemCategory.LEGS) || cats.contains(ItemCategory.GLOVES))
            return "Enchanting_Armor";
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

    private static String getTextureForEnchantment(String enchantmentId) {
        String base = "Items/Scrolls/";
        switch (enchantmentId) {
            case "sharpness":
                return base + "EnchScrollSharp.png";
            case "life_leech":
                return base + "EnchScrollLifeLe.png";
            case "durability":
                return base + "EnchScrollDurabi.png";
            case "sturdy":
                return base + "EnchScrollSturd.png";
            case "dexterity":
                return base + "EnchScrollDextiri.png";
            case "protection":
                return base + "EnchScrollProtec.png";
            case "efficiency":
                return base + "EnchScrollEffin.png";
            case "fortune":
                return base + "EnchScrollLoot.png";
            case "smelting":
                return base + "EnchScrollSmelt.png";
            case "strength":
                return base + "EnchScrollPow.png";
            case "eagles_eye":
                return base + "EnchScrollEagle.png";
            case "looting":
                return base + "EnchScrollLooting.png";
            case "feather_falling":
                return base + "EnchScrollFeatherFall.png";
            case "waterbreathing":
                return base + "EnchScrollWaterBreath.png";
            case "burn":
                return base + "EnchScrollBur.png";
            case "freeze":
                return base + "EnchScrollFree.png";
            case "eternal_shot":
                return base + "EnchScrollEternalSh.png";
            case "pick_perfect":
                return base + "EnchScrollPickPer.png";
            case "thrift":
                return base + "EnchScrollThri.png";
            case "elemental_heart":
                return base + "EnchScrollElementalH.png";
            case "knockback":
                return base + "EnchScrollKnock.png";
            case "reflection":
                return base + "EnchScrollReflect.png";
            case "absorption":
                return base + "EnchScrollAbsorb.png";
            case "fast_swim":
                return base + "EnchScrollSwiftSwi.png";
            case "night_vision":
                return base + "EnchScrollNightVis.png";
            case "ranged_protection":
                return base + "EnchScrollRangedProtec.png";
            case "frenzy":
                return base + "EnchScrollFren.png";
            case "riposte":
                return base + "EnchScrollRipo.png";
            case "coup_de_grace":
                return base + "EnchScrollCoupDeGr.png";
            case "poison":
                return base + "EnchScrollPoi.png";
            case "environmental_protection":
                return base + "EnchScrollEnviromentProtec.png";
            case "regeneration":
                return base + "EnchScrollHeal.png";
            case "second_stomach":
                return base + "EnchScrollSecondS.png";
            default:
                return DEFAULT_TEXTURE;
        }
    }

    private static String getIconForEnchantment(String enchantmentId) {
        String base = "Icons/ItemsGenerated/";
        switch (enchantmentId) {
            case "sharpness": return base + "Sharpness.png";
            case "life_leech": return base + "LifeLeach.png";
            case "durability": return base + "Durability.png";
            case "sturdy": return base + "Sturdy.png";
            case "dexterity": return base + "Dexterity.png";
            case "protection": return base + "Protection.png";
            case "efficiency": return base + "Efficiency.png";
            case "fortune": return base + "Fortune.png";
            case "smelting": return base + "Smelting.png";
            case "strength": return base + "Strength.png";
            case "eagles_eye": return base + "EaglesEye.png";
            case "looting": return base + "Looting.png";
            case "feather_falling": return base + "FeatherFalling.png";
            case "waterbreathing": return base + "Waterbreathing.png";
            case "burn": return base + "Burn.png";
            case "freeze": return base + "Freeze.png";
            case "eternal_shot": return base + "EternalShot.png";
            case "pick_perfect": return base + "PickPerfect.png";
            case "thrift": return base + "Thrift.png";
            case "elemental_heart": return base + "ElementalHeart.png";
            case "knockback": return base + "Knockback.png";
            case "reflection": return base + "Reflection.png";
            case "absorption": return base + "Absorption.png";
            case "fast_swim": return base + "SwiftSwim.png";
            case "night_vision": return base + "NightVision.png";
            case "ranged_protection": return base + "RangedProtection.png";
            case "frenzy": return base + "Frenzy.png";
            case "riposte": return base + "Riposte.png";
            case "coup_de_grace": return base + "CoupDeGrace.png";
            case "poison": return base + "Poison.png";
            case "environmental_protection": return base + "Environment Protection.png";
            case "regeneration": return base + "Regeneration.png";
            case "second_stomach": return base + "SecondStomach.png";
            default: return DEFAULT_ICON;
        }
    }
}
