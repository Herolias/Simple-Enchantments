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
import org.herolias.plugin.enchantment.EnchantmentType;
import org.herolias.plugin.config.UserSettingsManager;
import org.herolias.plugin.lang.LanguageManager;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * Interactive UI page corresponding to the /enchanting command.
 * Provides a Walkthrough and User Settings (Glow toggle, Language selection).
 */
public class EnchantingPage extends InteractiveCustomUIPage<EnchantingPageEventData> {
    private static final Value<String> BUTTON_STYLE = Value.ref("Pages/BasicTextButton.ui", "LabelStyle");
    private static final Value<String> BUTTON_STYLE_SELECTED = Value.ref("Pages/BasicTextButton.ui",
            "SelectedLabelStyle");
    private static final Value<String> DEFAULT_LABEL_STYLE = Value.ref("Pages/EnchantingLanguageSettings.ui",
            "DefaultLabelStyle");
    private static final Value<String> GOLD_LABEL_STYLE = Value.ref("Pages/EnchantingLanguageSettings.ui",
            "GoldLabelStyle");
    private static final Value<String> GOLD_TEXTBUTTON_STYLE = Value.ref("Pages/EnchantingWalkthroughSidebar.ui",
            "GoldButtonStyleOverride");

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

    private static final Map<String, String> NATIVE_LANGUAGE_NAMES = Map.ofEntries(
            Map.entry("default", "Default"),
            Map.entry("en-US", "English"),
            Map.entry("de-DE", "Deutsch"),
            Map.entry("es-ES", "Español"),
            Map.entry("fr-FR", "Français"),
            Map.entry("id-ID", "Bahasa Indonesia"),
            Map.entry("it-IT", "Italiano"),
            Map.entry("nl-NL", "Nederlands"),
            Map.entry("pt-BR", "Português (BR)"),
            Map.entry("ru-RU", "Русский"),
            Map.entry("sv-SE", "Svenska"),
            Map.entry("uk-UA", "Українська"));

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

        buildTabContent(commandBuilder, eventBuilder, true);
        updateTabStyles(commandBuilder);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull EnchantingPageEventData data) {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();

        if (data.tabSwitch != null) {
            this.currentTab = data.tabSwitch;
            buildTabContent(commandBuilder, eventBuilder, true);
            updateTabStyles(commandBuilder);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        } else if (data.walkthroughAction != null) {
            int maxPages = 5 + org.herolias.plugin.enchantment.EnchantmentType.values().length;
            if ("next".equals(data.walkthroughAction) && currentWalkthroughPage < maxPages) {
                currentWalkthroughPage++;
            } else if ("prev".equals(data.walkthroughAction) && currentWalkthroughPage > 0) {
                currentWalkthroughPage--;
            }
            buildTabContent(commandBuilder, eventBuilder, false);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        } else if (data.walkthroughPageSelect != null) {
            try {
                int page = Integer.parseInt(data.walkthroughPageSelect);
                int maxPages = 5 + org.herolias.plugin.enchantment.EnchantmentType.values().length;
                if (page >= 0 && page <= maxPages) {
                    currentWalkthroughPage = page;
                }
            } catch (NumberFormatException ignored) {
            }
            buildTabContent(commandBuilder, eventBuilder, false);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        } else if (data.toggleSetting != null) {
            if (data.toggleSetting.startsWith("lang:")) {
                String nextLang = data.toggleSetting.substring(5);
                userSettingsManager.setLanguage(this.playerRef.getUuid(), nextLang);
                languageManager.sendUpdatePacket(this.playerRef, nextLang);

                // Also trigger scroll descriptions
                org.herolias.plugin.enchantment.ScrollDescriptionManager.sendUpdatePacket(this.playerRef);

                // Also refresh dynamic tooltips in inventory
                org.herolias.plugin.enchantment.TooltipBridge.refreshPlayer(this.playerRef.getUuid());
            } else if ("glow".equals(data.toggleSetting)) {
                boolean current = userSettingsManager.getEnableEnchantmentGlow(this.playerRef.getUuid());
                userSettingsManager.setEnableEnchantmentGlow(this.playerRef.getUuid(), !current);
            }

            buildTabContent(commandBuilder, eventBuilder, true);
            updateTabStyles(commandBuilder);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        } else if ("true".equals(data.openDiscord)) {
            closeWithoutSaving(ref, store);
            this.playerRef.sendMessage(Message.raw("Click here to join the Simple Enchantments Discord! ")
                    .insert(Message.raw("[Discord Link]").link("https://discord.gg/7XQAnUfQfM").color("#5865F2"))
                    .bold(true));
            return;
        } else if ("true".equals(data.close)) {
            closeWithoutSaving(ref, store);
        }
    }

    private void buildTabContent(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder,
            boolean rebuildSidebar) {
        commandBuilder.clear("#ContentArea");
        if (rebuildSidebar) {
            commandBuilder.clear("#WalkthroughSidebarContainer");
        }

        switch (currentTab) {
            case TAB_WALKTHROUGH -> buildWalkthroughTab(commandBuilder, eventBuilder, rebuildSidebar);
            case TAB_SETTINGS -> buildSettingsTab(commandBuilder, eventBuilder);
        }
    }

    private void updateTabStyles(@Nonnull UICommandBuilder commandBuilder) {
        String lang = userSettingsManager.getLanguage(this.playerRef.getUuid());
        commandBuilder.set("#PageTitle.TextSpans",
                languageManager.getMessage("customUI.enchantingPage.title", lang, this.playerRef.getLanguage()));

        commandBuilder.set("#TabWalkthrough.Style",
                TAB_WALKTHROUGH.equals(currentTab) ? BUTTON_STYLE_SELECTED : BUTTON_STYLE);
        commandBuilder.set("#TabWalkthrough.TextSpans", languageManager
                .getMessage("customUI.enchantingPage.tabWalkthrough", lang, this.playerRef.getLanguage()));

        commandBuilder.set("#TabSettings.Style",
                TAB_SETTINGS.equals(currentTab) ? BUTTON_STYLE_SELECTED : BUTTON_STYLE);
        commandBuilder.set("#TabSettings.TextSpans",
                languageManager.getMessage("customUI.enchantingPage.tabSettings", lang, this.playerRef.getLanguage()));

        commandBuilder.set("#CloseButton.TextSpans",
                languageManager.getMessage("config.button.cancel", lang, this.playerRef.getLanguage()));
    }

    private void buildWalkthroughTab(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder,
            boolean rebuildSidebar) {
        if (rebuildSidebar) {
            commandBuilder.append("#WalkthroughSidebarContainer", "Pages/EnchantingWalkthroughSidebar.ui");
        }
        commandBuilder.append("#ContentArea", "Pages/EnchantingWalkthrough_Page.ui");

        String lang = userSettingsManager.getLanguage(this.playerRef.getUuid());

        Message nextMsg = languageManager.getMessage("customUI.walkthrough.next", lang, this.playerRef.getLanguage());
        Message prevMsg = languageManager.getMessage("customUI.walkthrough.prev", lang, this.playerRef.getLanguage());

        commandBuilder.set("#ContentArea[0] #WalkthroughNext.TextSpans", nextMsg);
        commandBuilder.set("#ContentArea[0] #WalkthroughPrev.TextSpans", prevMsg);

        if (currentWalkthroughPage == 0) {
            commandBuilder.set("#ContentArea[0] #WalkthroughPrev.Visible", false);
        }

        EnchantmentType[] enchantments = org.herolias.plugin.enchantment.EnchantmentType.values();
        int maxPages = 5 + enchantments.length;

        if (currentWalkthroughPage == maxPages) {
            commandBuilder.set("#ContentArea[0] #WalkthroughNext.Visible", false);
        }

        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[0] #WalkthroughNext",
                EventData.of("WalkthroughAction", "next"));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[0] #WalkthroughPrev",
                EventData.of("WalkthroughAction", "prev"));

        // Setup sidebar buttons
        for (int i = 0; i <= 5; i++) {
            String selector = "#WalkthroughSidebarContainer #WalkthroughP" + (i + 1);
            commandBuilder.set(selector + ".Style", currentWalkthroughPage == i ? GOLD_TEXTBUTTON_STYLE : BUTTON_STYLE);

            if (rebuildSidebar) {
                String titleKey = switch (i) {
                    case 0 -> "customUI.walkthrough.welcome.title";
                    case 1 -> "customUI.walkthrough.table.title";
                    case 2 -> "customUI.walkthrough.scrolls.title";
                    case 3 -> "customUI.walkthrough.removing.title";
                    case 4 -> "customUI.walkthrough.salvaging.title";
                    case 5 -> "customUI.walkthrough.enchantments.title";
                    default -> "unknown";
                };
                commandBuilder.set(selector + ".TextSpans",
                        languageManager.getMessage(titleKey, lang, this.playerRef.getLanguage()));
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, selector,
                        EventData.of("WalkthroughPageSelect", String.valueOf(i)));
            }
        }

        // Setup enchantment specific sub-buttons
        for (int j = 0; j < enchantments.length; j++) {
            int pageIndex = 6 + j;
            boolean isSel = currentWalkthroughPage == pageIndex;

            if (rebuildSidebar) {
                commandBuilder.append("#WalkthroughSidebarContainer #EnchantmentList",
                        "Pages/EnchantingWalkthroughSidebarItem.ui");
            }

            String selector = "#WalkthroughSidebarContainer #EnchantmentList[" + j + "] #SubBtn";
            commandBuilder.set(selector + ".Style", isSel ? GOLD_TEXTBUTTON_STYLE : BUTTON_STYLE);

            String prefix = isSel ? " » " : " • ";
            String prefixColor = isSel ? "#ffd700" : "#888888";

            Message subName = Message.raw(prefix).color(prefixColor)
                    .insert(languageManager.getMessage(enchantments[j].getNameKey(), lang, this.playerRef.getLanguage())
                            .color(isSel ? "#ffd700" : "#ffffff"));

            commandBuilder.set(selector + ".TextSpans", subName);

            if (rebuildSidebar) {
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, selector,
                        EventData.of("WalkthroughPageSelect", String.valueOf(pageIndex)));
            }
        }

        // Setup language specific strings for the page content
        setupWalkthroughTranslations(commandBuilder, lang, currentWalkthroughPage, enchantments);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[0] #DiscordBtn",
                EventData.of("OpenDiscord", "true"));
    }

    private void setupWalkthroughTranslations(@Nonnull UICommandBuilder commandBuilder, String lang, int page,
            EnchantmentType[] enchantments) {
        if (page < 6) {
            switch (page) {
                case 0 -> {
                    commandBuilder.set("#ContentArea[0] #Title.TextSpans", languageManager
                            .getMessage("customUI.walkthrough.welcome.title", lang, this.playerRef.getLanguage()));
                    commandBuilder.set("#ContentArea[0] #Desc.TextSpans", languageManager
                            .getMessage("customUI.walkthrough.welcome.desc", lang, this.playerRef.getLanguage()));
                    commandBuilder.set("#ContentArea[0] #DiscordBtn.Visible", true);
                    commandBuilder.set("#ContentArea[0] #DiscordBtn.TextSpans",
                            Message.raw("Join Discord").color("#ffffff"));
                }
                case 1 -> {
                    commandBuilder.set("#ContentArea[0] #Title.TextSpans", languageManager
                            .getMessage("customUI.walkthrough.table.title", lang, this.playerRef.getLanguage()));
                    commandBuilder.set("#ContentArea[0] #Desc.TextSpans", languageManager
                            .getMessage("customUI.walkthrough.table.desc", lang, this.playerRef.getLanguage()));
                    commandBuilder.set("#ContentArea[0] #DiscordBtn.Visible", false);
                }
                case 2 -> {
                    commandBuilder.set("#ContentArea[0] #Title.TextSpans", languageManager
                            .getMessage("customUI.walkthrough.scrolls.title", lang, this.playerRef.getLanguage()));
                    commandBuilder.set("#ContentArea[0] #Desc.TextSpans", languageManager
                            .getMessage("customUI.walkthrough.scrolls.desc", lang, this.playerRef.getLanguage()));
                    commandBuilder.set("#ContentArea[0] #DiscordBtn.Visible", false);
                }
                case 3 -> {
                    commandBuilder.set("#ContentArea[0] #Title.TextSpans", languageManager
                            .getMessage("customUI.walkthrough.removing.title", lang, this.playerRef.getLanguage()));
                    // Resolve {returnScroll} placeholder from config
                    String removingDesc = languageManager.getRawMessage("customUI.walkthrough.removing.desc", lang,
                            this.playerRef.getLanguage());
                    boolean returnOnCleanse = plugin.getConfigManager().getConfig().returnEnchantmentOnCleanse;
                    String scrollReplacement = languageManager.getRawMessage(
                            returnOnCleanse ? "customUI.walkthrough.returnScroll.yes"
                                    : "customUI.walkthrough.returnScroll.no",
                            lang, this.playerRef.getLanguage());
                    removingDesc = removingDesc.replace("{returnScroll}", scrollReplacement);
                    commandBuilder.set("#ContentArea[0] #Desc.TextSpans", Message.raw(removingDesc));
                    commandBuilder.set("#ContentArea[0] #DiscordBtn.Visible", false);
                }
                case 4 -> {
                    commandBuilder.set("#ContentArea[0] #Title.TextSpans", languageManager
                            .getMessage("customUI.walkthrough.salvaging.title", lang, this.playerRef.getLanguage()));
                    commandBuilder.set("#ContentArea[0] #Desc.TextSpans", languageManager
                            .getMessage("customUI.walkthrough.salvaging.desc", lang, this.playerRef.getLanguage()));
                    commandBuilder.set("#ContentArea[0] #DiscordBtn.Visible", false);
                }
                case 5 -> {
                    commandBuilder.set("#ContentArea[0] #Title.TextSpans", languageManager
                            .getMessage("customUI.walkthrough.enchantments.title", lang, this.playerRef.getLanguage()));
                    // Resolve {maxEnchantments} placeholder from config
                    String enchDesc = languageManager.getRawMessage("customUI.walkthrough.enchantments.desc", lang,
                            this.playerRef.getLanguage());
                    int maxEnch = plugin.getConfigManager().getConfig().maxEnchantmentsPerItem;
                    enchDesc = enchDesc.replace("{maxEnchantments}", String.valueOf(maxEnch));
                    commandBuilder.set("#ContentArea[0] #Desc.TextSpans", Message.raw(enchDesc));
                    commandBuilder.set("#ContentArea[0] #DiscordBtn.Visible", false);
                }
            }
        } else {
            int enchantmentIndex = page - 6;
            if (enchantmentIndex >= 0 && enchantmentIndex < enchantments.length) {
                EnchantmentType type = enchantments[enchantmentIndex];

                // Set the title
                commandBuilder.set("#ContentArea[0] #Title.TextSpans",
                        languageManager.getMessage(type.getNameKey(), lang, this.playerRef.getLanguage()));

                // Use walkthrough description with dynamic config values
                String walkthroughDesc = type.getWalkthroughDescription(lang, this.playerRef.getLanguage());
                commandBuilder.set("#ContentArea[0] #Desc.TextSpans", Message.raw(walkthroughDesc));
                commandBuilder.set("#ContentArea[0] #DiscordBtn.Visible", false);

                if (type.getOwnerModId() != null || type.getOwnerModName() != null) {
                    String modDisplay = type.getOwnerModName() != null ? type.getOwnerModName() : type.getOwnerModId();
                    commandBuilder.set("#ContentArea[0] #ModNameLabel.Visible", true);
                    commandBuilder.set("#ContentArea[0] #ModNameLabel.TextSpans",
                            Message.raw("Added by " + modDisplay));
                } else {
                    commandBuilder.set("#ContentArea[0] #ModNameLabel.Visible", false);
                }

                // Set server enabled Label
                boolean isEnabled = plugin.getEnchantmentManager().isEnchantmentEnabled(type);
                String enabledKey = isEnabled
                        ? "config.common.enabled"
                        : "config.common.disabled";
                String enabledPrefix = languageManager.getRawMessage("customUI.walkthrough.enabledOnServer", lang,
                        this.playerRef.getLanguage());

                String stateStr = languageManager.getRawMessage(enabledKey, lang, this.playerRef.getLanguage());
                String color = isEnabled ? "#55FF55" : "#FF5555";

                Message enabledLabel = Message.raw(enabledPrefix).insert(Message.raw(stateStr).color(color));
                commandBuilder.set("#ContentArea[0] #ServerEnabledLabel.Visible", true);
                commandBuilder.set("#ContentArea[0] #ServerEnabledLabel.TextSpans", enabledLabel);
            }
        }
    }

    private void buildSettingsTab(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        String lang = userSettingsManager.getLanguage(this.playerRef.getUuid());
        boolean glowEnabled = userSettingsManager.getEnableEnchantmentGlow(this.playerRef.getUuid());

        int index = 0;

        // Setup Language Toggle -> Grid
        commandBuilder.append("#ContentArea", "Pages/EnchantingLanguageSettings.ui");
        commandBuilder.set("#ContentArea[" + index + "] #SettingName.TextSpans",
                languageManager.getMessage("customUI.enchantingPage.language", lang, this.playerRef.getLanguage()));

        int langIndex = 0;
        int rowIndex = -1;
        for (String langOpt : LANGUAGE_OPTIONS) {
            if (langIndex % 4 == 0) {
                commandBuilder.append("#ContentArea[" + index + "] #LanguageGrid", "Pages/EnchantingLanguageRow.ui");
                rowIndex++;
            }
            String btnSelector = "#ContentArea[" + index + "] #LanguageGrid[" + rowIndex + "] #Lang"
                    + ((langIndex % 4) + 1);
            commandBuilder.set(btnSelector + " #Text.Style",
                    langOpt.equals(lang) ? GOLD_LABEL_STYLE : DEFAULT_LABEL_STYLE);

            Message btnText = "default".equals(langOpt)
                    ? languageManager.getMessage("customUI.enchantingPage.lang.default", lang,
                            this.playerRef.getLanguage())
                    : Message.raw(NATIVE_LANGUAGE_NAMES.get(langOpt));
            commandBuilder.set(btnSelector + " #Text.TextSpans", btnText);

            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, btnSelector,
                    EventData.of("ToggleSetting", "lang:" + langOpt));

            langIndex++;
        }
        index++;

        // Setup Glow Toggle
        commandBuilder.append("#ContentArea", "Pages/EnchantingSettings.ui");
        commandBuilder.set("#ContentArea[" + index + "] #SettingName.TextSpans",
                languageManager.getMessage("config.general.enable_glow", lang, this.playerRef.getLanguage()));
        commandBuilder.set("#ContentArea[" + index + "] #ToggleButton.TextSpans",
                languageManager.getMessage(glowEnabled ? "config.common.enabled" : "config.common.disabled", lang,
                        this.playerRef.getLanguage()));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #ToggleButton",
                EventData.of("ToggleSetting", "glow"));
        index++;

        // Add Admin Config Note
        commandBuilder.append("#ContentArea", "Pages/EnchantingSettingsNote.ui");
        commandBuilder.set("#ContentArea[" + index + "] #NoteText.TextSpans",
                languageManager.getMessage("customUI.enchantingPage.adminNote", lang, this.playerRef.getLanguage()));
    }

    private void closeWithoutSaving(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        Player playerComponent = store.getComponent(ref, Player.getComponentType());
        if (playerComponent != null) {
            playerComponent.getPageManager().setPage(ref, store, Page.None);
        }
    }
}
