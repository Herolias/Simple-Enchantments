package org.herolias.plugin.engravingtable;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.herolias.plugin.enchantment.NativeTooltipManager;

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
        EngravingTableCustomizationData metadataData = fromMetadataDocument(itemStack.getMetadata());
        ItemDisplayMetadata displayMetadata = itemStack.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC);
        if (displayMetadata == null || displayMetadata.getName() == null) {
            return metadataData;
        }

        Message name = displayMetadata.getName();
        String customName = normalizeName(name.getRawText());
        EngravingTableColorOption nameColor = EngravingTableColorOption.fromHexColor(name.getColor());
        if (customName == null && nameColor == null) {
            return metadataData;
        }
        return new EngravingTableCustomizationData(
                customName != null ? customName : metadataData.getCustomName(),
                nameColor != null ? nameColor : metadataData.getNameColor(),
                metadataData.getGlowColor());
    }

    @Nonnull
    public static EngravingTableCustomizationData fromMetadataDocument(@Nullable BsonDocument metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return EMPTY;
        }
        String customName = null;
        EngravingTableColorOption nameColor = null;
        EngravingTableColorOption glowColor = null;

        BsonValue rawValue = metadata.get(METADATA_KEY);
        if (rawValue != null && rawValue.isDocument()) {
            BsonDocument customDocument = rawValue.asDocument();
            customName = getString(customDocument, NAME_KEY);
            nameColor = EngravingTableColorOption.fromId(getString(customDocument, NAME_COLOR_KEY));
            glowColor = EngravingTableColorOption.fromId(getString(customDocument, GLOW_COLOR_KEY));
        }

        BsonValue displayValue = metadata.get(ItemDisplayMetadata.KEY);
        if (displayValue != null && displayValue.isDocument()) {
            BsonValue nameValue = displayValue.asDocument().get("Name");
            if (nameValue != null && nameValue.isDocument()) {
                BsonDocument nameDocument = nameValue.asDocument();
                String nativeName = getString(nameDocument, "RawText");
                EngravingTableColorOption nativeColor = EngravingTableColorOption.fromHexColor(
                        getString(nameDocument, "Color"));
                if (nativeName != null) {
                    customName = nativeName;
                }
                if (nativeColor != null) {
                    nameColor = nativeColor;
                }
            }
        }

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
        ItemDisplayMetadata existingDisplay = itemStack.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC);
        Message description = existingDisplay != null ? existingDisplay.getDescription() : null;
        Message nativeName = createNativeName(itemStack, customName, nameColor);
        ItemStack updated = itemStack;
        if (nativeName != null || description != null) {
            updated = updated.withMetadata(ItemDisplayMetadata.KEYED_CODEC,
                    new ItemDisplayMetadata(nativeName, description));
        } else {
            updated = updated.withMetadata(ItemDisplayMetadata.KEYED_CODEC, null);
        }

        BsonDocument customDocument = new BsonDocument();
        if (nameColor != null) {
            customDocument.put(NAME_COLOR_KEY, new BsonString(nameColor.getId()));
        }
        if (glowColor != null) {
            customDocument.put(GLOW_COLOR_KEY, new BsonString(glowColor.getId()));
        }

        if (customDocument.isEmpty()) {
            return NativeTooltipManager.apply(updated.withMetadata(METADATA_KEY, (BsonValue) null));
        }
        return NativeTooltipManager.apply(updated.withMetadata(METADATA_KEY, customDocument));
    }

    @Nullable
    private static Message createNativeName(
            @Nonnull ItemStack itemStack,
            @Nullable String customName,
            @Nullable EngravingTableColorOption nameColor) {
        String normalizedName = normalizeName(customName);
        Message message = normalizedName != null ? Message.raw(normalizedName)
                : (nameColor != null ? itemStack.getItem().getTranslationMessage() : null);
        if (message != null && nameColor != null) {
            message = message.color(nameColor.getHexColor());
        }
        return message;
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
