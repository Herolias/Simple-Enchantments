package org.herolias.plugin.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * Codec class for handling events from the EnchantingPage (Walkthrough and Settings).
 */
public class EnchantingPageEventData {
    public static final BuilderCodec<EnchantingPageEventData> CODEC = BuilderCodec.builder(
            EnchantingPageEventData.class, 
            EnchantingPageEventData::new
        )
        .addField(new KeyedCodec<>("TabSwitch", Codec.STRING), 
            (entry, s) -> entry.tabSwitch = s, entry -> entry.tabSwitch)
        .addField(new KeyedCodec<>("ToggleSetting", Codec.STRING), 
            (entry, s) -> entry.toggleSetting = s, entry -> entry.toggleSetting)
        .addField(new KeyedCodec<>("Close", Codec.STRING), 
            (entry, s) -> entry.close = s, entry -> entry.close)
        .addField(new KeyedCodec<>("WalkthroughAction", Codec.STRING), 
            (entry, s) -> entry.walkthroughAction = s, entry -> entry.walkthroughAction)
        .build();

    public String tabSwitch;
    public String toggleSetting;
    public String close;
    public String walkthroughAction;

    public EnchantingPageEventData() {
    }
}
