package org.herolias.plugin.anvil;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class AnvilCustomizationData {
    public static final String METADATA_KEY = "SimpleEnchantingAnvil";
    private static final String NAME_KEY = "Name";
    private static final String NAME_COLOR_KEY = "NameColor";
    private static final String GLOW_COLOR_KEY = "GlowColor";

    public static final AnvilCustomizationData EMPTY = new AnvilCustomizationData(null, null, null);

    @Nullable
    private final String customName;
    @Nullable
    private final AnvilColorOption nameColor;
    @Nullable
    private final AnvilColorOption glowColor;

    public AnvilCustomizationData(
            @Nullable String customName,
            @Nullable AnvilColorOption nameColor,
            @Nullable AnvilColorOption glowColor) {
        this.customName = normalizeName(customName);
        this.nameColor = nameColor;
        this.glowColor = glowColor;
    }

    @Nullable
    public String getCustomName() {
        return this.customName;
    }

    @Nullable
    public AnvilColorOption getNameColor() {
        return this.nameColor;
    }

    @Nullable
    public AnvilColorOption getGlowColor() {
        return this.glowColor;
    }

    public boolean isEmpty() {
        return this.customName == null && this.nameColor == null && this.glowColor == null;
    }

    @Nonnull
    public AnvilColorOption getNameColorOrDefault() {
        return this.nameColor != null ? this.nameColor : AnvilColorOption.DEFAULT_NAME_COLOR;
    }

    @Nonnull
    public AnvilColorOption getGlowColorOrDefault() {
        return this.glowColor != null ? this.glowColor : AnvilColorOption.DEFAULT_GLOW_COLOR;
    }

    public void appendHashInput(@Nonnull StringBuilder hashBuilder) {
        if (this.customName != null) {
            hashBuilder.append("|anvilName:").append(this.customName);
        }
        if (this.nameColor != null) {
            hashBuilder.append("|anvilNameColor:").append(this.nameColor.getId());
        }
        if (this.glowColor != null) {
            hashBuilder.append("|anvilGlowColor:").append(this.glowColor.getId());
        }
    }

    @Nonnull
    public static AnvilCustomizationData fromItemStack(@Nullable ItemStack itemStack) {
        if (ItemStack.isEmpty(itemStack)) {
            return EMPTY;
        }
        BsonDocument metadata = itemStack.getMetadata();
        return fromMetadataDocument(metadata);
    }

    @Nonnull
    public static AnvilCustomizationData fromMetadataDocument(@Nullable BsonDocument metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return EMPTY;
        }
        BsonValue rawValue = metadata.get(METADATA_KEY);
        if (rawValue == null || !rawValue.isDocument()) {
            return EMPTY;
        }

        BsonDocument customDocument = rawValue.asDocument();
        String customName = getString(customDocument, NAME_KEY);
        AnvilColorOption nameColor = AnvilColorOption.fromId(getString(customDocument, NAME_COLOR_KEY));
        AnvilColorOption glowColor = AnvilColorOption.fromId(getString(customDocument, GLOW_COLOR_KEY));

        if (customName == null && nameColor == null && glowColor == null) {
            return EMPTY;
        }
        return new AnvilCustomizationData(customName, nameColor, glowColor);
    }

    @Nonnull
    public static ItemStack applyToItem(
            @Nonnull ItemStack itemStack,
            @Nullable String customName,
            @Nullable AnvilColorOption nameColor,
            @Nullable AnvilColorOption glowColor) {
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
