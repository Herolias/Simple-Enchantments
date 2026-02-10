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
        if (metadata == null || metadata.isEmpty()) return null;

        // Quick string-contains check to avoid JSON parsing for items without enchantments
        if (!metadata.contains("\"" + EnchantmentData.METADATA_KEY + "\"")) return null;

        EnchantmentData data = parseEnchantments(metadata);
        if (data == null || data.isEmpty()) return null;

        // Build the formatted enchantment lines
        List<String> lines = buildEnchantmentLines(data);
        if (lines.isEmpty()) return null;

        // Use the stable hash from EnchantmentData for virtual ID generation
        String stableHash = data.computeStableHash();

        return TooltipData.builder()
                .addLine("<color is=\"" + HEADER_COLOR + "\">Enchantments:</color>")
                .addLines(lines)
                .hashInput(stableHash)
                .build();
    }

    /**
     * Builds formatted lines for each enchantment on the item.
     * <p>
     * Each line uses Hytale's rich-text format:
     * <pre>
     * &lt;color is="#AA55FF"&gt;• Sharpness III&lt;/color&gt; &lt;color is="#AAAAAA"&gt;+30% melee damage&lt;/color&gt;
     * </pre>
     */
    @Nonnull
    private List<String> buildEnchantmentLines(@Nonnull EnchantmentData data) {
        List<String> lines = new ArrayList<>();

        for (Map.Entry<EnchantmentType, Integer> entry : data.getAllEnchantments().entrySet()) {
            EnchantmentType type = entry.getKey();
            int level = entry.getValue();

            if (!enchantmentManager.isEnchantmentEnabled(type)) continue;

            StringBuilder sb = new StringBuilder();
            String color = type.isLegendary() ? LEGENDARY_COLOR : ENCHANTMENT_COLOR;
            sb.append("<color is=\"").append(color).append("\">");
            sb.append(ENCHANT_SYMBOL);
            sb.append(type.getFormattedName(level));
            sb.append("</color>");

            String bonus = type.getBonusDescription(level);
            if (bonus != null && !bonus.isEmpty()) {
                sb.append(" <color is=\"").append(BONUS_COLOR).append("\">");
                sb.append(bonus);
                sb.append("</color>");
            }

            lines.add(sb.toString());
        }

        return lines;
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
