package org.herolias.plugin.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.entity.entities.Player;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.config.UserSettingsManager;
import org.herolias.plugin.lang.LanguageManager;

import javax.annotation.Nonnull;

/**
 * Interactive UI page corresponding to the /enchanting command.
 * Provides a Walkthrough and User Settings (Glow, Banner toggles).
 */
public class EnchantingPage extends InteractiveCustomUIPage<EnchantingPageEventData> {

    private static final Value<String> BUTTON_STYLE = Value.ref("Pages/BasicTextButton.ui", "LabelStyle");
    private static final Value<String> BUTTON_STYLE_SELECTED = Value.ref("Pages/BasicTextButton.ui", "SelectedLabelStyle");

    private static final String TAB_WALKTHROUGH = "walkthrough";
    private static final String TAB_SETTINGS = "settings";

    private final SimpleEnchanting plugin;
    private final UserSettingsManager userSettingsManager;
    private final LanguageManager languageManager;
    private final PlayerRef playerRef;
    private String currentTab = TAB_WALKTHROUGH;
    private int currentWalkthroughPage = 0;
    private static final int MAX_WALKTHROUGH_PAGES = 4; // 0 to 4

    private static final String[] LANGUAGE_OPTIONS = {
        "default", "en-US", "de-DE", "es-ES", "fr-FR", "id-ID", "it-IT", 
        "nl-NL", "pt-BR", "ru-RU", "sv-SE", "uk-UA"
    };

    public EnchantingPage(@Nonnull PlayerRef playerRef, @Nonnull SimpleEnchanting plugin) {
        super(playerRef, CustomPageLifetime.CanDismiss, EnchantingPageEventData.CODEC);
        this.playerRef = playerRef;
        this.plugin = plugin;
        this.userSettingsManager = plugin.getUserSettingsManager();
        this.languageManager = plugin.getLanguageManager();
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder, @Nonnull Store<EntityStore> store) {
        
        commandBuilder.append("Pages/EnchantingPage.ui");

        // Tab switches
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#TabWalkthrough",
            EventData.of("TabSwitch", TAB_WALKTHROUGH));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#TabSettings",
            EventData.of("TabSwitch", TAB_SETTINGS));

        // Action button
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
            EventData.of("Close", "true"));

        buildTabContent(commandBuilder, eventBuilder);
        updateTabStyles(commandBuilder);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull EnchantingPageEventData data) {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();

        if (data.tabSwitch != null) {
            this.currentTab = data.tabSwitch;
            buildTabContent(commandBuilder, eventBuilder);
            updateTabStyles(commandBuilder);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        } else if (data.walkthroughAction != null) {
            if ("next".equals(data.walkthroughAction) && currentWalkthroughPage < MAX_WALKTHROUGH_PAGES) {
                currentWalkthroughPage++;
            } else if ("prev".equals(data.walkthroughAction) && currentWalkthroughPage > 0) {
                currentWalkthroughPage--;
            }
            buildTabContent(commandBuilder, eventBuilder);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        } else if (data.toggleSetting != null) {
            if ("lang".equals(data.toggleSetting)) {
                String currentLang = userSettingsManager.getLanguage(this.playerRef.getUuid());
                int idx = 0;
                for (int i = 0; i < LANGUAGE_OPTIONS.length; i++) {
                    if (LANGUAGE_OPTIONS[i].equals(currentLang)) {
                        idx = i;
                        break;
                    }
                }
                String nextLang = LANGUAGE_OPTIONS[(idx + 1) % LANGUAGE_OPTIONS.length];
                userSettingsManager.setLanguage(this.playerRef.getUuid(), nextLang);
                languageManager.sendUpdatePacket(this.playerRef, nextLang);
                
                // Also trigger scroll descriptions
                org.herolias.plugin.enchantment.ScrollDescriptionManager.sendUpdatePacket(this.playerRef);
                
                // Also refresh dynamic tooltips in inventory
                org.herolias.plugin.enchantment.TooltipBridge.refreshPlayer(this.playerRef.getUuid());
            } else if ("glow".equals(data.toggleSetting)) {
                boolean current = userSettingsManager.getEnableEnchantmentGlow(this.playerRef.getUuid());
                userSettingsManager.setEnableEnchantmentGlow(this.playerRef.getUuid(), !current);
            } else if ("banner".equals(data.toggleSetting)) {
                boolean current = userSettingsManager.getShowEnchantmentBanner(this.playerRef.getUuid());
                userSettingsManager.setShowEnchantmentBanner(this.playerRef.getUuid(), !current);
            }
            
            buildTabContent(commandBuilder, eventBuilder);
            updateTabStyles(commandBuilder);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        } else if ("true".equals(data.close)) {
            closeWithoutSaving(ref, store);
        }
    }

    private void buildTabContent(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        commandBuilder.clear("#ContentArea");

        switch (currentTab) {
            case TAB_WALKTHROUGH -> buildWalkthroughTab(commandBuilder, eventBuilder);
            case TAB_SETTINGS -> buildSettingsTab(commandBuilder, eventBuilder);
        }
    }

    private void updateTabStyles(@Nonnull UICommandBuilder commandBuilder) {
        String lang = userSettingsManager.getLanguage(this.playerRef.getUuid());
        commandBuilder.set("#PageTitle.TextSpans", languageManager.getMessage("customUI.enchantingPage.title", lang, this.playerRef.getLanguage()));
        
        commandBuilder.set("#TabWalkthrough.Style", TAB_WALKTHROUGH.equals(currentTab) ? BUTTON_STYLE_SELECTED : BUTTON_STYLE);
        commandBuilder.set("#TabWalkthrough.TextSpans", languageManager.getMessage("customUI.enchantingPage.tabWalkthrough", lang, this.playerRef.getLanguage()));
        
        commandBuilder.set("#TabSettings.Style", TAB_SETTINGS.equals(currentTab) ? BUTTON_STYLE_SELECTED : BUTTON_STYLE);
        commandBuilder.set("#TabSettings.TextSpans", languageManager.getMessage("customUI.enchantingPage.tabSettings", lang, this.playerRef.getLanguage()));

        commandBuilder.set("#CloseButton.TextSpans", languageManager.getMessage("config.button.cancel", lang, this.playerRef.getLanguage()));
    }

    private void buildWalkthroughTab(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        commandBuilder.append("#ContentArea", "Pages/EnchantingWalkthrough_Page.ui");
        
        String lang = userSettingsManager.getLanguage(this.playerRef.getUuid());
        
        Message nextMsg = languageManager.getMessage("customUI.walkthrough.next", lang, this.playerRef.getLanguage());
        Message prevMsg = languageManager.getMessage("customUI.walkthrough.prev", lang, this.playerRef.getLanguage());

        commandBuilder.set("#ContentArea[0] #WalkthroughNext.TextSpans", nextMsg);
        commandBuilder.set("#ContentArea[0] #WalkthroughPrev.TextSpans", prevMsg);

        if (currentWalkthroughPage == 0) {
            commandBuilder.set("#ContentArea[0] #WalkthroughPrev.Visible", false);
        }
        if (currentWalkthroughPage == MAX_WALKTHROUGH_PAGES) {
            commandBuilder.set("#ContentArea[0] #WalkthroughNext.Visible", false);
        }

        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[0] #WalkthroughNext",
            EventData.of("WalkthroughAction", "next"));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[0] #WalkthroughPrev",
            EventData.of("WalkthroughAction", "prev"));
            
        // Setup language specific strings for the page content
        setupWalkthroughTranslations(commandBuilder, lang, currentWalkthroughPage);
    }
    
    private void setupWalkthroughTranslations(@Nonnull UICommandBuilder commandBuilder, String lang, int page) {
        switch (page) {
            case 0 -> {
                commandBuilder.set("#ContentArea[0] #Title.TextSpans", languageManager.getMessage("customUI.walkthrough.welcome.title", lang, this.playerRef.getLanguage()));
                commandBuilder.set("#ContentArea[0] #Desc.TextSpans", languageManager.getMessage("customUI.walkthrough.welcome.desc", lang, this.playerRef.getLanguage()));
            }
            case 1 -> {
                commandBuilder.set("#ContentArea[0] #Title.TextSpans", languageManager.getMessage("customUI.walkthrough.table.title", lang, this.playerRef.getLanguage()));
                commandBuilder.set("#ContentArea[0] #Desc.TextSpans", languageManager.getMessage("customUI.walkthrough.table.desc", lang, this.playerRef.getLanguage()));
            }
            case 2 -> {
                commandBuilder.set("#ContentArea[0] #Title.TextSpans", languageManager.getMessage("customUI.walkthrough.scrolls.title", lang, this.playerRef.getLanguage()));
                commandBuilder.set("#ContentArea[0] #Desc.TextSpans", languageManager.getMessage("customUI.walkthrough.scrolls.desc", lang, this.playerRef.getLanguage()));
            }
            case 3 -> {
                commandBuilder.set("#ContentArea[0] #Title.TextSpans", languageManager.getMessage("customUI.walkthrough.removing.title", lang, this.playerRef.getLanguage()));
                commandBuilder.set("#ContentArea[0] #Desc.TextSpans", languageManager.getMessage("customUI.walkthrough.removing.desc", lang, this.playerRef.getLanguage()));
            }
            case 4 -> {
                commandBuilder.set("#ContentArea[0] #Title.TextSpans", languageManager.getMessage("customUI.walkthrough.salvaging.title", lang, this.playerRef.getLanguage()));
                commandBuilder.set("#ContentArea[0] #Desc.TextSpans", languageManager.getMessage("customUI.walkthrough.salvaging.desc", lang, this.playerRef.getLanguage()));
            }
        }
    }

    private void buildSettingsTab(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        String lang = userSettingsManager.getLanguage(this.playerRef.getUuid());
        boolean glowEnabled = userSettingsManager.getEnableEnchantmentGlow(this.playerRef.getUuid());
        boolean bannerEnabled = userSettingsManager.getShowEnchantmentBanner(this.playerRef.getUuid());

        int index = 0;
        
        // Setup Language Toggle
        commandBuilder.append("#ContentArea", "Pages/EnchantingSettings.ui");
        commandBuilder.set("#ContentArea[" + index + "] #SettingName.TextSpans", languageManager.getMessage("customUI.enchantingPage.language", lang, this.playerRef.getLanguage()));
        commandBuilder.set("#ContentArea[" + index + "] #ToggleButton.TextSpans", 
            "default".equals(lang) ? languageManager.getMessage("customUI.enchantingPage.lang.default", lang, this.playerRef.getLanguage()) : Message.raw(lang));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #ToggleButton",
            EventData.of("ToggleSetting", "lang"));
        index++;

        // Setup Glow Toggle
        commandBuilder.append("#ContentArea", "Pages/EnchantingSettings.ui");
        commandBuilder.set("#ContentArea[" + index + "] #SettingName.TextSpans", languageManager.getMessage("config.general.enable_glow", lang, this.playerRef.getLanguage()));
        commandBuilder.set("#ContentArea[" + index + "] #ToggleButton.TextSpans",
            languageManager.getMessage(glowEnabled ? "config.common.enabled" : "config.common.disabled", lang, this.playerRef.getLanguage()));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #ToggleButton",
            EventData.of("ToggleSetting", "glow"));
        index++;

        // Setup Banner Toggle
        commandBuilder.append("#ContentArea", "Pages/EnchantingSettings.ui");
        commandBuilder.set("#ContentArea[" + index + "] #SettingName.TextSpans", languageManager.getMessage("config.general.show_banner", lang, this.playerRef.getLanguage()));
        commandBuilder.set("#ContentArea[" + index + "] #ToggleButton.TextSpans",
            languageManager.getMessage(bannerEnabled ? "config.common.enabled" : "config.common.disabled", lang, this.playerRef.getLanguage()));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #ToggleButton",
            EventData.of("ToggleSetting", "banner"));
    }

    private void closeWithoutSaving(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        Player playerComponent = store.getComponent(ref, Player.getComponentType());
        if (playerComponent != null) {
            playerComponent.getPageManager().setPage(ref, store, Page.None);
        }
    }
}
