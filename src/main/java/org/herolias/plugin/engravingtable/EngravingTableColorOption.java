package org.herolias.plugin.engravingtable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public enum EngravingTableColorOption {
    RED(1, "red", "Red", "Red", "#ff5a5a", "Plant_Petals_Red", "Ingredient_Crystal_Red"),
    BLUE(2, "blue", "Blue", "Blue", "#5aa6ff", "Plant_Petals_Blue", "Ingredient_Crystal_Blue"),
    GREEN(3, "green", "Green", "Green", "#58d27d", "Plant_Petals_Green", "Ingredient_Crystal_Green"),
    YELLOW(4, "yellow", "Yellow", "Yellow", "#ffd65a", "Plant_Petals_Yellow", "Ingredient_Crystal_Yellow"),
    PURPLE(5, "purple", "Purple", "Purple", "#b088ff", "Plant_Petals_Purple", "Ingredient_Crystal_Purple"),
    PINK(6, "pink", "Pink", "Pink", "#ff8ed6", "Plant_Petals_Pink", "Ingredient_Crystal_Pink"),
    CYAN(7, "cyan", "Cyan", "Cyan", "#63ecff", "Plant_Petals_Cyan", "Ingredient_Crystal_Cyan"),
    WHITE(8, "white", "White", "White", "#ffffff", "Plant_Petals_White", "Ingredient_Crystal_White");

    public static final EngravingTableColorOption DEFAULT_NAME_COLOR = WHITE;
    public static final EngravingTableColorOption DEFAULT_GLOW_COLOR = PURPLE;

    private final int glowIndex;
    private final String id;
    private final String displayName;
    private final String assetSuffix;
    private final String hexColor;
    private final String petalItemId;
    private final String crystalItemId;

    EngravingTableColorOption(
            int glowIndex,
            @Nonnull String id,
            @Nonnull String displayName,
            @Nonnull String assetSuffix,
            @Nonnull String hexColor,
            @Nonnull String petalItemId,
            @Nonnull String crystalItemId) {
        this.glowIndex = glowIndex;
        this.id = id;
        this.displayName = displayName;
        this.assetSuffix = assetSuffix;
        this.hexColor = hexColor;
        this.petalItemId = petalItemId;
        this.crystalItemId = crystalItemId;
    }

    public int getGlowIndex() {
        return this.glowIndex;
    }

    @Nonnull
    public String getId() {
        return this.id;
    }

    @Nonnull
    public String getDisplayName() {
        return this.displayName;
    }

    @Nonnull
    public String getAssetSuffix() {
        return this.assetSuffix;
    }

    @Nonnull
    public String getHexColor() {
        return this.hexColor;
    }

    @Nonnull
    public String getPetalItemId() {
        return this.petalItemId;
    }

    @Nonnull
    public String getCrystalItemId() {
        return this.crystalItemId;
    }

    public float getGlowStatValue(boolean singleGlow) {
        return singleGlow ? 100.0f + this.glowIndex : (float) this.glowIndex;
    }

    @Nonnull
    public String getGlowVfxId(boolean smallGlow, boolean singleGlow) {
        if (singleGlow) {
            return "Enchantment_Glow_Single_" + this.assetSuffix;
        }
        return (smallGlow ? "Enchantment_Glow_Small_" : "Enchantment_Glow_") + this.assetSuffix;
    }

    @Nullable
    public static EngravingTableColorOption fromId(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (EngravingTableColorOption option : values()) {
            if (option.id.equalsIgnoreCase(id)) {
                return option;
            }
        }
        return null;
    }

    @Nullable
    public static EngravingTableColorOption fromHexColor(@Nullable String hexColor) {
        if (hexColor == null || hexColor.isBlank()) {
            return null;
        }
        for (EngravingTableColorOption option : values()) {
            if (option.hexColor.equalsIgnoreCase(hexColor)) {
                return option;
            }
        }
        return null;
    }

    @Nonnull
    public static EngravingTableColorOption fromIdOrDefault(@Nullable String id, @Nonnull EngravingTableColorOption fallback) {
        EngravingTableColorOption option = fromId(id);
        return option != null ? option : fallback;
    }
}
