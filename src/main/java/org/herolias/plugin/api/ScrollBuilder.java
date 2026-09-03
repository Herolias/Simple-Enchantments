package org.herolias.plugin.api;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemQuality;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Fluent builder for configuring a single scroll level.
 * <p>
 * Example usage:
 * 
 * <pre>{@code
 * ScrollDefinition scroll = new ScrollBuilder(1)
 *         .quality("Uncommon")
 *         .craftingTier(1)
 *         .craftingCategory("Enchanting_Melee")
 *         .ingredient("Ingredient_Fabric_Scrap_Cindercloth", 5)
 *         .ingredient("Ingredient_Void_Essence", 5)
 *         .build();
 * }</pre>
 * <p>
 * For addon mods, use via {@link EnchantmentBuilder#scroll(int)}:
 * 
 * <pre>{@code
 * api.registerEnchantment("my_mod:lightning", "Lightning Strike")
 *         .scroll(1)
 *         .quality("Uncommon")
 *         .craftingTier(1)
 *         .craftingCategory("Enchanting_Melee")
 *         .ingredient("My_Crystal", 3)
 *         .end()
 *         .scroll(2)
 *         .quality("Rare")
 *         .craftingTier(2)
 *         .ingredient("My_Crystal", 8)
 *         .end()
 *         .build();
 * }</pre>
 */
public class ScrollBuilder {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Quality ids shipped with the game (Assets/Server/Item/Qualities). Used as a
     * fallback whitelist while the ItemQuality asset store is not loaded yet.
     */
    private static final Set<String> VANILLA_QUALITIES = Set.of(
            "Common", "Uncommon", "Rare", "Epic", "Legendary",
            "Junk", "Tool", "Debug", "Developer", "Technical", "Template");

    private final int level;
    private final EnchantmentBuilder parent; // null if standalone

    private String quality = "Uncommon";
    private int craftingTier = 1;
    private String craftingCategory = null; // null = inherit from enchantment
    private final List<ScrollDefinition.Ingredient> ingredients = new ArrayList<>();

    // Visual overrides (null = use SE defaults)
    private String icon = null;
    private String model = null;
    private String texture = null;

    // Default scroll icon properties
    private ScrollDefinition.IconProperties iconProperties = new ScrollDefinition.IconProperties(0.84f, 5f, 15f, 90f,
            45f, 0f);

    /**
     * Creates a standalone ScrollBuilder (not chained from EnchantmentBuilder).
     */
    public ScrollBuilder(int level) {
        this(level, null);
    }

    /**
     * Creates a ScrollBuilder chained from an EnchantmentBuilder.
     */
    ScrollBuilder(int level, @Nullable EnchantmentBuilder parent) {
        if (level < 1)
            throw new IllegalArgumentException("Scroll level must be >= 1, got: " + level);
        this.level = level;
        this.parent = parent;
    }

    /**
     * Sets the rarity/quality of this scroll level.
     * <p>
     * Valid values are ItemQuality asset ids: "Common", "Uncommon", "Rare",
     * "Epic", "Legendary" (plus any quality added by other asset packs).
     *
     * @param quality The quality id (an {@code ItemQuality} asset)
     * @throws IllegalArgumentException if the quality is blank, or is not a
     *                                  registered ItemQuality while the asset
     *                                  store is already loaded
     */
    public ScrollBuilder quality(@Nonnull String quality) {
        this.quality = validateQuality(quality);
        return this;
    }

    /**
     * Validates a quality id against the server's ItemQuality assets. When the
     * asset store is not loaded yet (addons usually register during setup), the
     * id is checked against the vanilla list and unknown ids only produce a
     * warning, since another asset pack may still add them.
     */
    @Nonnull
    static String validateQuality(@Nullable String quality) {
        if (quality == null || quality.isBlank()) {
            throw new IllegalArgumentException("Scroll quality must not be blank");
        }
        String trimmed = quality.trim();
        Boolean known = isLoadedQuality(trimmed);
        if (known != null) {
            if (!known) {
                throw new IllegalArgumentException(
                        "Unknown scroll quality '" + trimmed + "'. Expected an ItemQuality asset id such as "
                                + "Common, Uncommon, Rare, Epic or Legendary.");
            }
        } else if (!VANILLA_QUALITIES.contains(trimmed)) {
            LOGGER.atWarning().log(
                    "Scroll quality '%s' is not a vanilla ItemQuality; make sure an asset pack defines it or the scroll will not load",
                    trimmed);
        }
        return trimmed;
    }

    /**
     * @return true/false when the ItemQuality asset store is loaded, null when it
     *         is not available yet
     */
    @Nullable
    private static Boolean isLoadedQuality(@Nonnull String quality) {
        try {
            var assetMap = ItemQuality.getAssetMap();
            if (assetMap == null || assetMap.getAssetMap().isEmpty()) {
                return null;
            }
            return assetMap.getAsset(quality) != null;
        } catch (Exception e) {
            // Asset registry not initialised yet
            return null;
        }
    }

    /**
     * Sets the crafting tier required to craft this scroll.
     * Corresponds to the Enchanting Table upgrade level (1-4).
     */
    public ScrollBuilder craftingTier(int tier) {
        if (tier < 1 || tier > 4)
            throw new IllegalArgumentException("Crafting tier must be 1-4, got: " + tier);
        this.craftingTier = tier;
        return this;
    }

    /**
     * Sets which tab this scroll appears under in the Enchanting Table.
     * <p>
     * Built-in categories: "Enchanting_Melee", "Enchanting_Ranged",
     * "Enchanting_Armor", "Enchanting_Shield", "Enchanting_Staff",
     * "Enchanting_Tools"
     * <p>
     * Custom categories can be registered via
     * {@link EnchantmentApi#registerCraftingCategory(String, String, String)}.
     * If not set, defaults to the enchantment's primary item category mapping.
     */
    public ScrollBuilder craftingCategory(@Nonnull String category) {
        this.craftingCategory = category;
        return this;
    }

    /**
     * Adds a crafting ingredient for this scroll level.
     * <p>
     * The item id is not required to exist yet (items from other packs may load
     * later); {@link EnchantmentBuilder#build()} logs a warning for ids that are
     * unknown at that point.
     *
     * @param itemId   The item ID (e.g. "Ingredient_Crystal_Blue")
     * @param quantity The quantity required (>= 1)
     * @throws IllegalArgumentException if the item id is blank or the quantity is
     *                                  below 1
     */
    public ScrollBuilder ingredient(@Nonnull String itemId, int quantity) {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("Ingredient item id must not be blank (scroll level " + level + ")");
        }
        ingredients.add(new ScrollDefinition.Ingredient(itemId.trim(), quantity));
        return this;
    }

    /**
     * Overrides the scroll icon for this level.
     * If not set, uses the default SE scroll icon.
     *
     * @param iconPath Path relative to the mod's assets (e.g. "Icons/MyScroll.png")
     */
    public ScrollBuilder icon(@Nonnull String iconPath) {
        this.icon = iconPath;
        return this;
    }

    /**
     * Overrides the scroll 3D model for this level.
     * If not set, uses the default SE scroll model.
     *
     * @param modelPath Path relative to the mod's assets (e.g.
     *                  "Items/MyScroll.blockymodel")
     */
    public ScrollBuilder model(@Nonnull String modelPath) {
        this.model = modelPath;
        return this;
    }

    /**
     * Overrides the scroll texture for this level.
     * If not set, uses the default SE scroll texture.
     *
     * @param texturePath Path relative to the mod's assets (e.g.
     *                    "Items/MyScroll.png")
     */
    public ScrollBuilder texture(@Nonnull String texturePath) {
        this.texture = texturePath;
        return this;
    }

    /**
     * Overrides the scroll icon properties for this level.
     * If not set, uses the default SE scroll properties:
     * scale: 0.84, translation: [5, 15], rotation: [90, 45, 0].
     */
    public ScrollBuilder iconProperties(float scale, float translationX, float translationY,
            float rotationX, float rotationY, float rotationZ) {
        this.iconProperties = new ScrollDefinition.IconProperties(
                scale, translationX, translationY, rotationX, rotationY, rotationZ);
        return this;
    }

    /**
     * Returns to the parent EnchantmentBuilder (when chained).
     * Only available when created via {@link EnchantmentBuilder#scroll(int)}.
     *
     * @throws IllegalStateException if this builder was created standalone
     */
    @Nonnull
    public EnchantmentBuilder end() {
        if (parent == null) {
            throw new IllegalStateException(
                    "end() can only be called on chained ScrollBuilders. Use build() for standalone.");
        }
        parent.addScrollDefinition(buildDefinition());
        return parent;
    }

    /**
     * Builds the scroll definition (standalone usage).
     */
    @Nonnull
    public ScrollDefinition build() {
        return buildDefinition();
    }

    @Nonnull
    ScrollDefinition buildDefinition() {
        return new ScrollDefinition(level, quality, craftingTier, craftingCategory,
                ingredients, icon, model, texture, iconProperties);
    }
}
