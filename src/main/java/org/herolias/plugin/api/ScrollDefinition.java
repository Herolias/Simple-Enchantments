package org.herolias.plugin.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Defines the properties of a single scroll level for an enchantment.
 * Created via {@link ScrollBuilder}.
 * <p>
 * Each enchantment level gets its own scroll item in-game. This class holds
 * the per-level configuration: recipe ingredients, rarity, crafting tier,
 * and optional visual overrides (model, texture, icon).
 */
public class ScrollDefinition {

    private final int level;
    private final String quality; // "Common", "Uncommon", "Rare", "Epic", "Legendary"
    private final int craftingTier; // RequiredTierLevel in JSON (1-4)
    private final String craftingCategory; // e.g. "Enchanting_Melee", or custom category ID
    private final List<Ingredient> recipe;

    // Visual overrides (null = use SE defaults)
    private final String icon; // e.g. "Icons/ItemsGenerated/Scroll.png"
    private final String model; // e.g. "Items/Scrolls/EnchantmentScroll.blockymodel"
    private final String texture; // e.g. "Items/Scrolls/EnchScroll.png"

    private final IconProperties iconProperties;

    ScrollDefinition(int level, String quality, int craftingTier, String craftingCategory,
            List<Ingredient> recipe, String icon, String model, String texture,
            IconProperties iconProperties) {
        this.level = level;
        this.quality = quality;
        this.craftingTier = craftingTier;
        this.craftingCategory = craftingCategory;
        this.recipe = List.copyOf(recipe);
        this.icon = icon;
        this.model = model;
        this.texture = texture;
        this.iconProperties = iconProperties;
    }

    public int getLevel() {
        return level;
    }

    public String getQuality() {
        return quality;
    }

    public int getCraftingTier() {
        return craftingTier;
    }

    public String getCraftingCategory() {
        return craftingCategory;
    }

    public List<Ingredient> getRecipe() {
        return recipe;
    }

    @Nullable
    public String getIcon() {
        return icon;
    }

    @Nullable
    public String getModel() {
        return model;
    }

    @Nullable
    public String getTexture() {
        return texture;
    }

    @Nonnull
    public IconProperties getIconProperties() {
        return iconProperties;
    }

    /**
     * A single crafting ingredient (item ID + quantity).
     */
    public static class Ingredient {
        private final String itemId;
        private final int quantity;

        public Ingredient(@Nonnull String itemId, int quantity) {
            this.itemId = Objects.requireNonNull(itemId);
            if (quantity < 1)
                throw new IllegalArgumentException("Quantity must be >= 1");
            this.quantity = quantity;
        }

        public String getItemId() {
            return itemId;
        }

        public int getQuantity() {
            return quantity;
        }
    }

    /**
     * Holds the icon properties for a scroll item.
     */
    public static class IconProperties {
        private final float scale;
        private final float translationX;
        private final float translationY;
        private final float rotationX;
        private final float rotationY;
        private final float rotationZ;

        public IconProperties(float scale, float translationX, float translationY,
                float rotationX, float rotationY, float rotationZ) {
            this.scale = scale;
            this.translationX = translationX;
            this.translationY = translationY;
            this.rotationX = rotationX;
            this.rotationY = rotationY;
            this.rotationZ = rotationZ;
        }

        public float getScale() {
            return scale;
        }

        public float getTranslationX() {
            return translationX;
        }

        public float getTranslationY() {
            return translationY;
        }

        public float getRotationX() {
            return rotationX;
        }

        public float getRotationY() {
            return rotationY;
        }

        public float getRotationZ() {
            return rotationZ;
        }
    }
}
