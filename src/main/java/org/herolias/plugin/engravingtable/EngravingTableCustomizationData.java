package org.herolias.plugin.engravingtable;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class EngravingTableCustomizationData {
    public static final String METADATA_KEY = "SimpleEnchantingEngravingTable";
    private static final String NAME_KEY = "Name";
    private static final String NAME_COLOR_KEY = "NameColor";
    private static final String GLOW_COLOR_KEY = "GlowColor";

    public static final EngravingTableCustomizationData EMPTY = new EngravingTableCustomizationData(null, null, null);

    @Nullable
    private final String customName;
    @Nullable
    private final EngravingTableColorOption nameColor;
    @Nullable
    private final EngravingTableColorOption glowColor;

    public EngravingTableCustomizationData(
            @Nullable String customName,
            @Nullable EngravingTableColorOption nameColor,
            @Nullable EngravingTableColorOption glowColor) {
        this.customName = normalizeName(customName);
        this.nameColor = nameColor;
        this.glowColor = glowColor;
    }

    @Nullable
    public String getCustomName() {
        return this.customName;
    }

    @Nullable
    public EngravingTableColorOption getNameColor() {
        return this.nameColor;
    }

    @Nullable
    public EngravingTableColorOption getGlowColor() {
        return this.glowColor;
    }

    public boolean isEmpty() {
        return this.customName == null && this.nameColor == null && this.glowColor == null;
    }

    @Nonnull
    public EngravingTableColorOption getNameColorOrDefault() {
        return this.nameColor != null ? this.nameColor : EngravingTableColorOption.DEFAULT_NAME_COLOR;
    }

    @Nonnull
    public EngravingTableColorOption getGlowColorOrDefault() {
        return this.glowColor != null ? this.glowColor : EngravingTableColorOption.DEFAULT_GLOW_COLOR;
    }

    public void appendHashInput(@Nonnull StringBuilder hashBuilder) {
        if (this.customName != null) {
            hashBuilder.append("|engravingTableName:").append(this.customName);
        }
        if (this.nameColor != null) {
            hashBuilder.append("|engravingTableNameColor:").append(this.nameColor.getId());
        }
        if (this.glowColor != null) {
            hashBuilder.append("|engravingTableGlowColor:").append(this.glowColor.getId());
        }
    }

    @Nonnull
    public static EngravingTableCustomizationData fromItemStack(@Nullable ItemStack itemStack) {
        if (ItemStack.isEmpty(itemStack)) {
            return EMPTY;
        }
        BsonDocument metadata = itemStack.getMetadata();
        return fromMetadataDocument(metadata);
    }

    @Nonnull
    public static EngravingTableCustomizationData fromMetadataDocument(@Nullable BsonDocument metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return EMPTY;
        }
        BsonValue rawValue = metadata.get(METADATA_KEY);
        if (rawValue == null || !rawValue.isDocument()) {
            return EMPTY;
        }

        BsonDocument customDocument = rawValue.asDocument();
        String customName = getString(customDocument, NAME_KEY);
        EngravingTableColorOption nameColor = EngravingTableColorOption.fromId(getString(customDocument, NAME_COLOR_KEY));
        EngravingTableColorOption glowColor = EngravingTableColorOption.fromId(getString(customDocument, GLOW_COLOR_KEY));

        if (customName == null && nameColor == null && glowColor == null) {
            return EMPTY;
        }
        return new EngravingTableCustomizationData(customName, nameColor, glowColor);
    }

    @Nonnull
    public static ItemStack applyToItem(
            @Nonnull ItemStack itemStack,
            @Nullable String customName,
            @Nullable EngravingTableColorOption nameColor,
            @Nullable EngravingTableColorOption glowColor) {
        BsonDocument customDocument = new BsonDocument();
        String normalizedName = normalizeName(customName);
        if (normalizedName != null) {
            customDocument.put(NAME_KEY, new BsonString(normalizedName));
        }
        if (nameColor != null) {
            customDocument.put(NAME_COLOR_KEY, new BsonString(nameColor.getId()));
        }
        if (glowColor != null) {
            customDocument.put(GLOW_COLOR_KEY, new BsonString(glowColor.getId()));
        }

        if (customDocument.isEmpty()) {
            return itemStack.withMetadata(METADATA_KEY, (BsonValue) null);
        }
        return itemStack.withMetadata(METADATA_KEY, customDocument);
    }

    @Nullable
    private static String getString(@Nonnull BsonDocument document, @Nonnull String key) {
        BsonValue value = document.get(key);
        if (value == null || !value.isString()) {
            return null;
        }
        return normalizeName(value.asString().getValue());
    }

    @Nullable
    private static String normalizeName(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
