package org.herolias.plugin.enchantment;

import com.hypixel.hytale.logger.HytaleLogger;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.herolias.tooltips.api.TooltipData;
import org.herolias.tooltips.api.TooltipProvider;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link TooltipProvider} implementation that displays enchantment information
 * on item tooltips via DynamicTooltipsLib.
 * <p>
 * This provider parses the item's metadata BSON to extract {@link EnchantmentData}
 * and produces formatted tooltip lines using the same rich-text style that was
 * previously baked into the old {@code VirtualItemRegistry}.
 */
public class EnchantmentTooltipProvider implements TooltipProvider {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String PROVIDER_ID = "simple-enchantments:enchantments";
    private static final int PRIORITY = 100; // Default priority — other mods can go above or below

    // ── Formatting constants (matches original VirtualItemRegistry style) ──
    private static final String HEADER_COLOR = "#C8A2FF";
    private static final String ENCHANTMENT_COLOR = "#AA55FF";
    private static final String LEGENDARY_COLOR = "#FFAA00";
    private static final String BONUS_COLOR = "#AAAAAA";
    private static final String ENCHANT_SYMBOL = "\u2022 "; // Bullet point "• "

    private final EnchantmentManager enchantmentManager;

    public EnchantmentTooltipProvider(@Nonnull EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
    }

    @Nonnull
    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Nullable
    @Override
    public TooltipData getTooltipData(@Nonnull String itemId, @Nullable String metadata) {
        return getTooltipData(itemId, metadata, "en-US");
    }

    @Nullable
    @Override
    public TooltipData getTooltipData(@Nonnull String itemId, @Nullable String metadata,
                                      @Nullable String locale) {
        if (metadata == null || metadata.isEmpty()) return null;

        // Quick string-contains check to avoid JSON parsing for items without enchantments
        if (!metadata.contains("\"" + EnchantmentData.METADATA_KEY + "\"")) return null;

        EnchantmentData data = parseEnchantments(metadata);
        if (data == null || data.isEmpty()) return null;

        // Use the stable hash from EnchantmentData for virtual ID generation
        String stableHash = data.computeStableHash();

        // Build additive lines in the player's locale.
        // The locale comes from processSection → compose → getTooltipData.
        String effectiveLocale = (locale != null && !locale.isEmpty()) ? locale : "en-US";
        TooltipData.Builder builder = TooltipData.builder()
                .hashInput(stableHash);

        List<String> lines = buildEnchantmentLines(data, effectiveLocale);
        for (String line : lines) {
            builder.addLine(line);
        }

        return builder.build();
    }

    /**
     * Builds formatted enchantment lines for the tooltip.
     * Each entry is a separate additive line with Hytale markup.
     *
     * @param data   the enchantment data to format
     * @param locale the locale for translation resolution
     * @return list of formatted lines (header + one per enchantment)
     */
    @Nonnull
    public List<String> buildEnchantmentLines(@Nonnull EnchantmentData data, @Nonnull String locale) {
        List<String> lines = new ArrayList<>();

        // Header line
        lines.add("<color is=\"" + HEADER_COLOR + "\">Enchantments:</color>");

        for (Map.Entry<EnchantmentType, Integer> entry : data.getAllEnchantments().entrySet()) {
            EnchantmentType type = entry.getKey();
            int level = entry.getValue();

            if (!enchantmentManager.isEnchantmentEnabled(type)) continue;

            StringBuilder line = new StringBuilder();
            String color = type.isLegendary() ? LEGENDARY_COLOR : ENCHANTMENT_COLOR;
            line.append("<color is=\"").append(color).append("\">");
            line.append(ENCHANT_SYMBOL);

            // Resolve the enchantment name via I18n
            String nameKey = type.getNameKey();
            String name = resolveTranslation(nameKey, locale, type.getDisplayName());
            line.append(name).append(" ").append(EnchantmentType.toRoman(level));
            line.append("</color>");

            // Append bonus description
            String bonus = type.getBonusDescription(level, locale);
            if (bonus != null && !bonus.isEmpty()) {
                line.append(" <color is=\"").append(BONUS_COLOR).append("\">");
                line.append(bonus);
                line.append("</color>");
            }

            lines.add(line.toString());
        }

        return lines;
    }

    /**
     * Resolves a translation key for the given locale, falling back to the default value.
     */
    @Nonnull
    private static String resolveTranslation(@Nonnull String key, @Nonnull String locale, @Nonnull String defaultValue) {
        try {
            com.hypixel.hytale.server.core.modules.i18n.I18nModule i18n =
                    com.hypixel.hytale.server.core.modules.i18n.I18nModule.get();
            if (i18n != null) {
                String resolved = i18n.getMessage(locale, key);
                if (resolved != null) return resolved;
            }
        } catch (Exception ignored) {}
        return defaultValue;
    }

    /**
     * Parses enchantment data from the packet's metadata JSON string.
     * Same logic as the old {@code InventoryPacketAdapter.parseEnchantmentsFromPacket}.
     */
    @Nullable
    private static EnchantmentData parseEnchantments(@Nonnull String metadataJson) {
        try {
            BsonDocument doc = BsonDocument.parse(metadataJson);
            BsonValue enchBson = doc.get(EnchantmentData.METADATA_KEY);
            if (enchBson == null || !enchBson.isDocument()) return null;

            EnchantmentData data = EnchantmentData.fromBson(enchBson.asDocument());
            return data.isEmpty() ? null : data;
        } catch (Exception e) {
            return null;
        }
    }
}
