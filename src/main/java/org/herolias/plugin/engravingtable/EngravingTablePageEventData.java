package org.herolias.plugin.engravingtable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

public class EngravingTablePageEventData {
    public static final BuilderCodec<EngravingTablePageEventData> CODEC = BuilderCodec.builder(
            EngravingTablePageEventData.class,
            EngravingTablePageEventData::new)
            .append(new KeyedCodec<>("Close", Codec.STRING),
                    (entry, s) -> entry.close = s, entry -> entry.close).add()
            .append(new KeyedCodec<>("Take", Codec.STRING),
                    (entry, s) -> entry.take = s, entry -> entry.take).add()
            .append(new KeyedCodec<>("InventorySelect", Codec.STRING),
                    (entry, s) -> entry.inventorySelect = s, entry -> entry.inventorySelect).add()
            .append(new KeyedCodec<>("ClearPrimary", Codec.STRING),
                    (entry, s) -> entry.clearPrimary = s, entry -> entry.clearPrimary).add()
            .append(new KeyedCodec<>("ClearSecondary", Codec.STRING),
                    (entry, s) -> entry.clearSecondary = s, entry -> entry.clearSecondary).add()
            .append(new KeyedCodec<>("NameColor", Codec.STRING),
                    (entry, s) -> entry.nameColor = s, entry -> entry.nameColor).add()
            .append(new KeyedCodec<>("GlowColor", Codec.STRING),
                    (entry, s) -> entry.glowColor = s, entry -> entry.glowColor).add()
            .append(new KeyedCodec<>("NameInput", Codec.STRING),
                    (entry, s) -> entry.nameInputTrigger = s, entry -> entry.nameInputTrigger).add()
            .append(new KeyedCodec<>("@NameInput", Codec.STRING),
                    (entry, s) -> entry.nameInput = s, entry -> entry.nameInput).add()
            .build();

    public String close;
    public String take;
    public String inventorySelect;
    public String clearPrimary;
    public String clearSecondary;
    public String nameColor;
    public String glowColor;
    public String nameInputTrigger;
    public String nameInput;

    public EngravingTablePageEventData() {
    }
}
