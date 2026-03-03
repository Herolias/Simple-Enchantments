package org.herolias.plugin.api;

import org.herolias.plugin.enchantment.EnchantmentType;
import org.herolias.plugin.enchantment.EnchantmentRegistry;
import org.herolias.plugin.enchantment.ItemCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

import javax.annotation.Nonnull;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Fluent builder for registering addon enchantments with Simple Enchantments.
 * <p>
 * Example usage:
 * <pre>{@code
 * EnchantmentApi api = EnchantmentApiProvider.get();
 * EnchantmentType lightning = api.registerEnchantment("my_mod:lightning", "Lightning Strike")
 *     .description("Chance to strike enemies with lightning")
 *     .maxLevel(3)
 *     .multiplierPerLevel(0.15)
 *     .scale(ScaleType.DIMINISHING)
 *     .bonusDescription("Lightning strike chance: {amount}%")
 *     .appliesTo(ItemCategory.MELEE_WEAPON, ItemCategory.RANGED_WEAPON)
 *     .build();
 * }</pre>
 * <p>
 * Minimum required: ID (namespaced) and display name (provided via
 * {@link EnchantmentApi#registerEnchantment(String, String)}).
 */
public class EnchantmentBuilder {

    private final String id;
    private final String displayName;

    // Defaults
    private String description = "";
    private int maxLevel = 1;
    private boolean requiresDurability = false;
    private boolean isLegendary = false;
    private double multiplierPerLevel = 0.0;
    private String bonusDescriptionTemplate = "";
    private String craftingCategory = null; // null = auto-derive from item categories
    private IntToDoubleFunction scaleFunction = null; // null = linear (default)
    private String walkthroughText = null; // null = fallback to bonusDescription
    private String modDisplayName = null; // null = fallback to ownerModId
    private final Set<ItemCategory> categories = new LinkedHashSet<>();
    private final List<ScrollDefinition> scrollDefinitions = new ArrayList<>();

    /**
     * Creates a new builder. Use {@link EnchantmentApi#registerEnchantment(String, String)} instead.
     *
     * @param id          Namespaced enchantment ID (e.g. "my_mod:lightning")
     * @param displayName Human-readable name (e.g. "Lightning Strike")
     */
    EnchantmentBuilder(@Nonnull String id, @Nonnull String displayName) {
        if (!id.contains(":")) {
            throw new IllegalArgumentException(
                    "Addon enchantment IDs must be namespaced (e.g. 'my_mod:lightning'), got: '" + id + "'");
        }
        this.id = id;
        this.displayName = displayName;
    }

    /** Sets the mod display name shown in scroll descriptions and the walkthrough. */
    public EnchantmentBuilder modDisplayName(@Nonnull String modDisplayName) {
        this.modDisplayName = modDisplayName;
        return this;
    }

    /** Sets the enchantment description. */
    public EnchantmentBuilder description(@Nonnull String description) {
        this.description = description;
        return this;
    }

    /** Sets the maximum level (default: 1). */
    public EnchantmentBuilder maxLevel(int maxLevel) {
        if (maxLevel < 1) throw new IllegalArgumentException("maxLevel must be >= 1, got: " + maxLevel);
        this.maxLevel = maxLevel;
        return this;
    }

    /** Sets whether this enchantment requires the item to have durability (default: false). */
    public EnchantmentBuilder requiresDurability(boolean requiresDurability) {
        this.requiresDurability = requiresDurability;
        return this;
    }

    /** Marks this enchantment as legendary — only one per item (default: false). */
    public EnchantmentBuilder legendary(boolean isLegendary) {
        this.isLegendary = isLegendary;
        return this;
    }

    /**
     * Sets the effect multiplier per level (default: 0.0).
     * <p>
     * When > 0, the enchantment gets a multiplier slider in the config UI.
     * When 0, the enchantment is treated as binary (on/off).
     */
    public EnchantmentBuilder multiplierPerLevel(double multiplier) {
        this.multiplierPerLevel = multiplier;
        return this;
    }

    /**
     * Sets the scaling curve using a predefined {@link ScaleType}.
     * <p>
     * If not called, defaults to {@link ScaleType#LINEAR} ({@code level * multiplierPerLevel}).
     *
     * @param scaleType the predefined scaling curve
     * @see ScaleType
     */
    public EnchantmentBuilder scale(@Nonnull ScaleType scaleType) {
        // Deferred: resolved in build() after multiplierPerLevel is finalized
        this.scaleFunction = null;
        this.deferredScaleType = scaleType;
        this.deferredScalePower = Double.NaN;
        return this;
    }

    /**
     * Sets the scaling curve using a power exponent.
     * The formula becomes: {@code level^exponent * multiplierPerLevel}.
     * <p>
     * Examples:
     * <ul>
     *   <li>{@code .scale(1.0)} — linear (default)</li>
     *   <li>{@code .scale(2.0)} — quadratic</li>
     *   <li>{@code .scale(0.5)} — diminishing (square root)</li>
     * </ul>
     *
     * @param exponent the power exponent applied to the level
     */
    public EnchantmentBuilder scale(double exponent) {
        this.scaleFunction = null;
        this.deferredScaleType = null;
        this.deferredScalePower = exponent;
        return this;
    }

    /**
     * Sets a fully custom scaling function.
     * The function receives the enchantment level and must return the total
     * scaled multiplier for that level (not per-level — the full amount).
     * <p>
     * Example: {@code .scale(level -> level * level * 0.05)}
     *
     * @param function custom scaling function (level → total multiplier)
     */
    public EnchantmentBuilder scale(@Nonnull IntToDoubleFunction function) {
        this.scaleFunction = function;
        this.deferredScaleType = null;
        this.deferredScalePower = Double.NaN;
        return this;
    }

    // Internal: deferred scale resolution (ScaleType and power need multiplierPerLevel)
    private ScaleType deferredScaleType = null;
    private double deferredScalePower = Double.NaN;

    /**
     * Sets the bonus description template shown in tooltips/walkthrough.
     * Use {@code {amount}} as a placeholder for the calculated value.
     * <p>
     * Example: {@code "Damage increased by {amount}%"}
     */
    public EnchantmentBuilder bonusDescription(@Nonnull String template) {
        this.bonusDescriptionTemplate = template;
        return this;
    }

    /**
     * Sets custom walkthrough text for the {@code /enchanting} page.
     * <p>
     * Use {@code {amount}} as a placeholder for the calculated per-level value.
     * If not set, defaults to the bonus description template.
     *
     * @param text The walkthrough description text
     */
    public EnchantmentBuilder walkthrough(@Nonnull String text) {
        this.walkthroughText = text;
        return this;
    }

    /**
     * Sets the crafting category (tab) in the Enchanting Table for this enchantment's scrolls.
     * <p>
     * Built-in categories: "Enchanting_Melee", "Enchanting_Ranged",
     * "Enchanting_Armor", "Enchanting_Shield", "Enchanting_Staff", "Enchanting_Tools"
     * <p>
     * Custom categories can be registered via {@link EnchantmentApi#registerCraftingCategory(String, String, String)}.
     * If not set, auto-derived from the primary item category.
     */
    public EnchantmentBuilder craftingCategory(@Nonnull String category) {
        this.craftingCategory = category;
        return this;
    }

    /**
     * Starts building a scroll definition for a specific level.
     * Returns a {@link ScrollBuilder} that chains back via {@code .end()}.
     * <p>
     * Example:
     * <pre>{@code
     * builder.scroll(1)
     *     .quality("Uncommon")
     *     .craftingTier(1)
     *     .ingredient("My_Crystal", 3)
     *     .end()
     * }</pre>
     *
     * @param level The scroll level (1-based)
     */
    @Nonnull
    public ScrollBuilder scroll(int level) {
        return new ScrollBuilder(level, this);
    }

    /**
     * Called by ScrollBuilder.end() to add the completed definition.
     */
    void addScrollDefinition(@Nonnull ScrollDefinition definition) {
        scrollDefinitions.add(definition);
    }

    /**
     * Adds item categories this enchantment can be applied to.
     * Use constants from {@link ItemCategory} (e.g. {@code ItemCategory.MELEE_WEAPON}).
     */
    public EnchantmentBuilder appliesTo(@Nonnull ItemCategory... categories) {
        for (ItemCategory cat : categories) {
            if (cat == null) throw new IllegalArgumentException("ItemCategory must not be null");
            this.categories.add(cat);
        }
        return this;
    }

    /**
     * Builds and registers the enchantment.
     * <p>
     * After calling this method, the enchantment is:
     * <ul>
     *   <li>Registered in the global {@link EnchantmentRegistry}</li>
     *   <li>Available via {@code /enchant} command</li>
     *   <li>Visible in the config UI with enable/disable toggle</li>
     *   <li>Configurable multiplier slider (if {@code multiplierPerLevel > 0})</li>
     *   <li>Scroll items auto-generated</li>
     * </ul>
     *
     * @return The registered {@link EnchantmentType} instance
     * @throws IllegalStateException if no categories were specified
     * @throws IllegalStateException if an enchantment with this ID already exists
     */
    /**
     * Gets the scroll definitions configured via {@link #scroll(int)}.
     */
    @Nonnull
    public List<ScrollDefinition> getScrollDefinitions() {
        return List.copyOf(scrollDefinitions);
    }

    /**
     * Gets the crafting category, or null if auto-derived.
     */
    @javax.annotation.Nullable
    public String getCraftingCategory() {
        return craftingCategory;
    }

    @Nonnull
    public EnchantmentType build() {
        if (categories.isEmpty()) {
            throw new IllegalStateException(
                    "At least one item category is required. Call appliesTo() before build().");
        }

        // Extract namespace as the owner mod ID
        String ownerModId = id.substring(0, id.indexOf(':'));

        // Resolve crafting category if not explicitly set
        String resolvedCategory = craftingCategory;
        if (resolvedCategory == null) {
            resolvedCategory = deriveDefaultCraftingCategory();
        }

        EnchantmentType type = new EnchantmentType(
                id, displayName, description, maxLevel,
                requiresDurability, isLegendary,
                multiplierPerLevel, bonusDescriptionTemplate,
                ownerModId, modDisplayName, categories
        );

        // Store scroll definitions and crafting category on the type
        type.setScrollDefinitions(scrollDefinitions);
        type.setCraftingCategory(resolvedCategory);

        // Store custom walkthrough text if provided
        if (walkthroughText != null) {
            type.setWalkthroughText(walkthroughText);
        }

        // Resolve and store scale function
        IntToDoubleFunction resolvedScale = resolveScaleFunction();
        if (resolvedScale != null) {
            type.setScaleFunction(resolvedScale);
        }

        // Register in the global registry
        EnchantmentRegistry.getInstance().register(type);

        // Ensure the config has a multiplier entry for this enchantment
        try {
            org.herolias.plugin.config.EnchantingConfig config =
                    org.herolias.plugin.SimpleEnchanting.getInstance().getConfigManager().getConfig();
            config.enchantmentMultipliers.putIfAbsent(id, multiplierPerLevel);
        } catch (Exception e) {
            // Plugin not yet initialized — will be populated on next config load
        }

        // Register translations so the name/description displays correctly in UIs
        try {
            org.herolias.plugin.lang.LanguageManager langMgr = org.herolias.plugin.SimpleEnchanting.getInstance().getLanguageManager();
            if (langMgr != null) {
                langMgr.putTranslation(type.getNameKey(), displayName);
                langMgr.putTranslation("enchantment." + ownerModId + "_" + id.substring(id.indexOf(':') + 1) + ".description", description);
                if (bonusDescriptionTemplate != null && !bonusDescriptionTemplate.isEmpty()) {
                    langMgr.putTranslation("enchantment." + ownerModId + "_" + id.substring(id.indexOf(':') + 1) + ".bonus", bonusDescriptionTemplate);
                }
            }
        } catch (Exception e) {
            // LanguageManager not available yet or something went wrong
        }

        return type;
    }

    /**
     * Auto-derives the Enchanting Table category from item categories.
     */
    private String deriveDefaultCraftingCategory() {
        if (categories.contains(ItemCategory.MELEE_WEAPON)) return "Enchanting_Melee";
        if (categories.contains(ItemCategory.RANGED_WEAPON)) return "Enchanting_Ranged";
        if (categories.contains(ItemCategory.ARMOR) || categories.contains(ItemCategory.BOOTS)) return "Enchanting_Armor";
        if (categories.contains(ItemCategory.SHIELD)) return "Enchanting_Shield";
        if (categories.contains(ItemCategory.STAFF) || categories.contains(ItemCategory.STAFF_MANA) || categories.contains(ItemCategory.STAFF_ESSENCE)) return "Enchanting_Staff";
        if (categories.contains(ItemCategory.PICKAXE) || categories.contains(ItemCategory.AXE) || categories.contains(ItemCategory.SHOVEL) || categories.contains(ItemCategory.TOOL)) return "Enchanting_Tools";
        return "Enchanting_Melee"; // fallback
    }

    /**
     * Resolves the scale function from deferred settings.
     * Returns null for linear (default) to avoid storing a no-op function.
     */
    private IntToDoubleFunction resolveScaleFunction() {
        if (scaleFunction != null) {
            return scaleFunction;
        }
        if (deferredScaleType != null && deferredScaleType != ScaleType.LINEAR) {
            return deferredScaleType.toFunction(multiplierPerLevel);
        }
        if (!Double.isNaN(deferredScalePower) && deferredScalePower != 1.0) {
            double exponent = deferredScalePower;
            double mult = multiplierPerLevel;
            return level -> Math.pow(level, exponent) * mult;
        }
        return null; // linear default
    }
}
