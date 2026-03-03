package org.herolias.plugin.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.logger.HytaleLogger;
import org.herolias.plugin.config.ConfigManager;
import org.herolias.plugin.config.EnchantingConfig;
import org.herolias.plugin.config.UserSettingsManager;
import org.herolias.plugin.lang.LanguageManager;
import org.herolias.plugin.enchantment.EnchantmentType;
import org.herolias.plugin.SimpleEnchanting;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;

/**
 * Interactive UI page for in-game configuration editing.
 * Supports three tabs: General, Enchantments, Recipes.
 */
public class EnchantConfigPage extends InteractiveCustomUIPage<EnchantConfigPageEventData> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    
    private static final Value<String> BUTTON_STYLE = Value.ref("Pages/BasicTextButton.ui", "LabelStyle");
    private static final Value<String> BUTTON_STYLE_SELECTED = Value.ref("Pages/BasicTextButton.ui", "SelectedLabelStyle");
    
    private static final String TAB_GENERAL = "general";
    private static final String TAB_ENCHANTMENTS = "enchantments";
    private static final String TAB_RECIPES = "recipes";
    
    private final ConfigManager configManager;
    private final UserSettingsManager userSettingsManager;
    private final LanguageManager languageManager;
    private final String lang;
    private final EnchantingConfig workingConfig;
    
    private String currentTab = TAB_GENERAL;
    @Nullable
    private String selectedRecipe = null;
    
    // Item search state
    private int searchingIngredientIndex = -1;  // Index of ingredient being edited (-1 = not searching)
    private String currentSearchQuery = "";      // Current search filter text
    
    // Recipe editing mode - null=not editing, "table"=table recipe, "upgrade_X"=upgrade X
    @Nullable
    private String editingRecipeType = null;
    
    // Track unsaved changes and UI feedback state
    private boolean hasUnsavedChanges = false;
    private boolean showSaveFeedback = false;
    private boolean showResetConfirmation = false;
    
    // Mapping of enchantment types to their enchantment ID keys in enchantmentMultipliers map
    // Enchantments with defaultMultiplierPerLevel > 0 get a multiplier row in the UI
    private static final Map<EnchantmentType, String> ENCHANTMENT_MULTIPLIERS = new LinkedHashMap<>();
    // Secondary multipliers for enchantments with multiple configurable effects (legacy fields)
    private static final Map<EnchantmentType, String> ENCHANTMENT_SECONDARY_MULTIPLIERS = new LinkedHashMap<>();
    private static final Map<String, String> SECONDARY_MULTIPLIER_LABELS = new LinkedHashMap<>();
    static {
        // Populate from registry: only enchantments with a non-zero default multiplier get a UI row
        for (EnchantmentType type : EnchantmentType.values()) {
            if (type.getDefaultMultiplierPerLevel() > 0) {
                ENCHANTMENT_MULTIPLIERS.put(type, type.getId());
            }
        }

        // Secondary multipliers for enchantments with multiple effects (still legacy fields on config)
        ENCHANTMENT_SECONDARY_MULTIPLIERS.put(EnchantmentType.LOOTING, "lootingQuantityMultiplierPerLevel");
        SECONDARY_MULTIPLIER_LABELS.put("lootingQuantityMultiplierPerLevel", "config.secondary.quantity_bonus");

        ENCHANTMENT_SECONDARY_MULTIPLIERS.put(EnchantmentType.BURN, "burnDuration");
        SECONDARY_MULTIPLIER_LABELS.put("burnDuration", "config.secondary.burn_duration");

        ENCHANTMENT_SECONDARY_MULTIPLIERS.put(EnchantmentType.FREEZE, "freezeDuration");
        SECONDARY_MULTIPLIER_LABELS.put("freezeDuration", "config.secondary.freeze_duration");
    }
    
    // Default config instance for reset functionality - uses values from EnchantingConfig.java
    private static final EnchantingConfig DEFAULT_CONFIG = EnchantingConfig.createDefault();
    
    /**
     * Calculates the step size based on the current value's decimal precision.
     * A value of 0.1 gets step 0.01, a value of 1 gets step 0.1, etc.
     */
    private static double calculateStep(double value) {
        if (value == 0) return 0.1;
        double absValue = Math.abs(value);
        
        // Find the magnitude of the number
        if (absValue >= 10) return 1.0;
        if (absValue >= 1) return 0.1;
        if (absValue >= 0.1) return 0.01;
        return 0.001;
    }

    public EnchantConfigPage(@Nonnull PlayerRef playerRef, @Nonnull ConfigManager configManager, 
                             @Nonnull UserSettingsManager userSettingsManager, @Nonnull LanguageManager languageManager) {
        super(playerRef, CustomPageLifetime.CanDismiss, EnchantConfigPageEventData.CODEC);
        this.configManager = configManager;
        this.userSettingsManager = userSettingsManager;
        this.languageManager = languageManager;
        this.lang = userSettingsManager.getLanguage(playerRef.getUuid());
        // Create a working copy of the config for editing
        this.workingConfig = cloneConfig(configManager.getConfig());
    }
    
    private EnchantingConfig cloneConfig(EnchantingConfig original) {
        EnchantingConfig copy = new EnchantingConfig();
        copy.configVersion = original.configVersion;
        copy.maxEnchantmentsPerItem = original.maxEnchantmentsPerItem;
        copy.showEnchantmentBanner = original.showEnchantmentBanner;
        copy.hasAutoDisabledBanner = original.hasAutoDisabledBanner;
        copy.enableEnchantmentGlow = original.enableEnchantmentGlow;
        copy.allowSameScrollUpgrades = original.allowSameScrollUpgrades;
        
        // Clone enchantment multipliers map
        copy.enchantmentMultipliers = new LinkedHashMap<>(original.enchantmentMultipliers);
        
        // Clone legacy secondary fields (still used for looting quantity etc.)
        copy.strengthRangeMultiplierPerLevel = original.strengthRangeMultiplierPerLevel;
        copy.lootingQuantityMultiplierPerLevel = original.lootingQuantityMultiplierPerLevel;
        copy.burnDuration = original.burnDuration;
        copy.freezeDuration = original.freezeDuration;

        copy.disableEnchantmentCrafting = original.disableEnchantmentCrafting;
        copy.returnEnchantmentOnCleanse = original.returnEnchantmentOnCleanse;
        copy.salvagerYieldsScroll = original.salvagerYieldsScroll;
        copy.enchantingTableCraftingTier = original.enchantingTableCraftingTier;
        copy.showWelcomeMessage = original.showWelcomeMessage;
        
        copy.disabledEnchantments = new LinkedHashMap<>(original.disabledEnchantments);
        copy.scrollRecipes = new LinkedHashMap<>();
        for (var entry : original.scrollRecipes.entrySet()) {
            copy.scrollRecipes.put(entry.getKey(), new java.util.ArrayList<>(entry.getValue()));
        }
        
        // Copy enchanting table recipe (initialize from defaults if null)
        EnchantingConfig defaults = EnchantingConfig.createDefault();
        if (original.enchantingTableRecipe != null) {
            copy.enchantingTableRecipe = new java.util.ArrayList<>(original.enchantingTableRecipe);
        } else {
            copy.enchantingTableRecipe = new java.util.ArrayList<>(defaults.enchantingTableRecipe);
        }
        
        // Copy upgrades (initialize from defaults if null)
        copy.enchantingTableUpgrades = new LinkedHashMap<>();
        if (original.enchantingTableUpgrades != null) {
            for (var entry : original.enchantingTableUpgrades.entrySet()) {
                copy.enchantingTableUpgrades.put(entry.getKey(), new java.util.ArrayList<>(entry.getValue()));
            }
        } else {
            for (var entry : defaults.enchantingTableUpgrades.entrySet()) {
                copy.enchantingTableUpgrades.put(entry.getKey(), new java.util.ArrayList<>(entry.getValue()));
            }
        }
        
        return copy;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder commandBuilder, 
                      @Nonnull UIEventBuilder eventBuilder, @Nonnull Store<EntityStore> store) {
        commandBuilder.append("Pages/EnchantConfigPage.ui");
        
        commandBuilder.set("#PageTitle.TextSpans", languageManager.getMessage("customUI.enchantConfigPage.title", lang, this.playerRef.getLanguage()));
        
        // Tab buttons
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#TabGeneral", 
            EventData.of("TabSwitch", TAB_GENERAL));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#TabEnchantments", 
            EventData.of("TabSwitch", TAB_ENCHANTMENTS));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#TabRecipes", 
            EventData.of("TabSwitch", TAB_RECIPES));
        
        // Action buttons
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#SaveButton", 
            EventData.of("SaveConfig", "true"));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#CancelButton", 
            EventData.of("CancelConfig", "true"));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ResetAllButton", 
            EventData.of("ResetAllConfig", "true"));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ConfirmResetButton", 
            EventData.of("ConfirmResetAll", "true"));
        
        // Build initial tab content
        buildTabContent(commandBuilder, eventBuilder);
        updateTabStyles(commandBuilder);
        updateActionBarIndicators(commandBuilder);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, 
                                 @Nonnull EnchantConfigPageEventData data) {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        
        if (data.tabSwitch != null) {
            this.currentTab = data.tabSwitch;
            this.selectedRecipe = null;
            buildTabContent(commandBuilder, eventBuilder);
            updateTabStyles(commandBuilder);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        } else if (data.saveConfig != null) {
            saveConfig(ref, store);
            buildTabContent(commandBuilder, eventBuilder);
            updateActionBarIndicators(commandBuilder);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        } else if (data.cancelConfig != null) {
            closeWithoutSaving(ref, store);
        } else if (data.settingValue != null) {
            // Handle setting value - can be from +/- buttons (with key:value format) or from ValueChanged (key only with inputValue)
            String key;
            String value;
            
            if (data.inputValue != null) {
                // ValueChanged from NumberField - settingValue is just the key, value comes from inputValue
                key = data.settingValue;
                value = String.valueOf(data.inputValue);
                
                // Don't rebuild tab content for input values to preserve focus
                updateSetting(key, value);
                updateActionBarIndicators(commandBuilder);
                this.sendUpdate(commandBuilder, eventBuilder, false);
            } else if (data.settingValue.contains(":")) {
                // Button click - format is "key:value"
                String[] parts = data.settingValue.split(":", 2);
                key = parts[0];
                value = parts[1];
                
                updateSetting(key, value);
                buildTabContent(commandBuilder, eventBuilder);
                updateActionBarIndicators(commandBuilder);
                this.sendUpdate(commandBuilder, eventBuilder, false);
            } else {
                // Invalid format
                LOGGER.atWarning().log("Invalid setting format: " + data.settingValue);
                return;
            }
        } else if (data.toggleEnchantment != null) {
            toggleEnchantment(data.toggleEnchantment);
            buildTabContent(commandBuilder, eventBuilder);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        } else if (data.selectRecipe != null) {
            this.selectedRecipe = data.selectRecipe;
            buildTabContent(commandBuilder, eventBuilder);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        } else if (data.backToList != null) {
            this.selectedRecipe = null;
            buildTabContent(commandBuilder, eventBuilder);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        } else if (data.tierValue != null && this.selectedRecipe != null) {
            updateRecipeTier(this.selectedRecipe, data.tierValue);
            buildTabContent(commandBuilder, eventBuilder);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        } else if (data.resetValue != null) {
            // Reset value to default
            resetToDefault(data.resetValue);
            buildTabContent(commandBuilder, eventBuilder);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        } else if (data.openSearch != null && (this.selectedRecipe != null || this.editingRecipeType != null)) {
            // Open item search for ingredient at given index
            try {
                this.searchingIngredientIndex = Integer.parseInt(data.openSearch);
                this.currentSearchQuery = "";
                buildTabContent(commandBuilder, eventBuilder);
                this.sendUpdate(commandBuilder, eventBuilder, false);
            } catch (NumberFormatException e) {
                LOGGER.atWarning().log("Invalid ingredient index: " + data.openSearch);
            }
        } else if (data.searchInput != null && this.searchingIngredientIndex >= 0) {
            // Update search filter - only update search results, not the entire overlay
            this.currentSearchQuery = data.searchInput;
            updateSearchResults(commandBuilder, eventBuilder);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        } else if (data.selectItem != null && this.searchingIngredientIndex >= 0 && (this.selectedRecipe != null || this.editingRecipeType != null)) {
            // Replace ingredient with selected item
            updateIngredient(this.searchingIngredientIndex, data.selectItem);
            this.searchingIngredientIndex = -1;
            this.currentSearchQuery = "";
            buildTabContent(commandBuilder, eventBuilder);
            updateActionBarIndicators(commandBuilder);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        } else if (data.cancelSearch != null) {
            // Close search without selecting
            this.searchingIngredientIndex = -1;
            this.currentSearchQuery = "";
            buildTabContent(commandBuilder, eventBuilder);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        } else if (data.updateAmount != null && (this.selectedRecipe != null || this.editingRecipeType != null)) {
            // Update ingredient amount - format: updateAmount = index, inputValue = new amount (for ValueChanged)
            // or format: updateAmount = "index:amount" (for +/- buttons)
            String indexStr;
            int amount;
            
            if (data.inputValue != null) {
                // ValueChanged from NumberField - updateAmount is just the index, value comes from inputValue
                indexStr = data.updateAmount;
                amount = data.inputValue.intValue();
                
                try {
                    int index = Integer.parseInt(indexStr);
                    updateIngredientAmount(index, amount);
                    // Don't rebuild tab to preserve focus
                    updateActionBarIndicators(commandBuilder);
                    this.sendUpdate(commandBuilder, eventBuilder, false);
                } catch (NumberFormatException e) {
                    LOGGER.atWarning().log("Invalid index in amount update: " + indexStr);
                }
            } else if (data.updateAmount.contains(":")) {
                // Button click - format is "index:amount"
                String[] parts = data.updateAmount.split(":", 2);
                indexStr = parts[0];
                amount = Integer.parseInt(parts[1]);
                
                try {
                    int index = Integer.parseInt(indexStr);
                    updateIngredientAmount(index, amount);
                    buildTabContent(commandBuilder, eventBuilder);
                    updateActionBarIndicators(commandBuilder);
                    this.sendUpdate(commandBuilder, eventBuilder, false);
                } catch (NumberFormatException e) {
                    LOGGER.atWarning().log("Invalid index in amount update: " + indexStr);
                }
            } else {
                LOGGER.atWarning().log("Invalid amount format: " + data.updateAmount);
                return;
            }
        } else if (data.settingKey != null && data.settingKey.equals("AddIngredient")) {
            // Add a new ingredient to the current recipe (scroll or table/upgrade)
            addNewIngredient();
            buildTabContent(commandBuilder, eventBuilder);
            updateActionBarIndicators(commandBuilder);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        } else if (data.editRecipeType != null) {
            // Open table/upgrade recipe edit screen
            this.editingRecipeType = data.editRecipeType;
            this.searchingIngredientIndex = -1;
            this.currentSearchQuery = "";
            buildTabContent(commandBuilder, eventBuilder);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        } else if (data.backFromRecipeEdit != null) {
            // Exit table/upgrade recipe edit mode
            this.editingRecipeType = null;
            this.searchingIngredientIndex = -1;
            this.currentSearchQuery = "";
            buildTabContent(commandBuilder, eventBuilder);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        } else if (data.resetRecipe != null) {
            // Reset recipe to default
            resetRecipeToDefault(data.resetRecipe);
            markUnsavedChange();
            buildTabContent(commandBuilder, eventBuilder);
            updateActionBarIndicators(commandBuilder);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        } else if (data.resetAllConfig != null) {
            // Show confirmation or reset
            if (showResetConfirmation) {
                // Already showing confirmation, this means they confirmed (if we use a two-step button) 
                // BUT we will implement a proper dialog overlay
                resetAllToDefaults();
                showResetConfirmation = false;
            } else {
                 showResetConfirmation = true; // Toggle confirmation overlay
            }
            buildTabContent(commandBuilder, eventBuilder);
            updateActionBarIndicators(commandBuilder);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        } else if (data.confirmResetAll != null) {
            // Confirmed via dialog
            resetAllToDefaults();
            showResetConfirmation = false;
            buildTabContent(commandBuilder, eventBuilder);
            updateActionBarIndicators(commandBuilder);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        } else if (data.removeIngredient != null) {
            try {
                int index = Integer.parseInt(data.removeIngredient);
                removeIngredient(index);
                buildTabContent(commandBuilder, eventBuilder);
                updateActionBarIndicators(commandBuilder);
                this.sendUpdate(commandBuilder, eventBuilder, false);
            } catch (NumberFormatException e) {
                 LOGGER.atWarning().log("Invalid ingredient index for removal: " + data.removeIngredient);
            }
        } else if (data.moveIngredientUp != null) {
            try {
                int index = Integer.parseInt(data.moveIngredientUp);
                moveIngredient(index, -1);
                buildTabContent(commandBuilder, eventBuilder);
                updateActionBarIndicators(commandBuilder);
                this.sendUpdate(commandBuilder, eventBuilder, false);
            } catch (NumberFormatException e) {
                 LOGGER.atWarning().log("Invalid ingredient index for move up: " + data.moveIngredientUp);
            }
        } else if (data.moveIngredientDown != null) {
            try {
                int index = Integer.parseInt(data.moveIngredientDown);
                moveIngredient(index, 1);
                buildTabContent(commandBuilder, eventBuilder);
                updateActionBarIndicators(commandBuilder);
                this.sendUpdate(commandBuilder, eventBuilder, false);
            } catch (NumberFormatException e) {
                 LOGGER.atWarning().log("Invalid ingredient index for move down: " + data.moveIngredientDown);
            }
        }
    }
    
    private void buildTabContent(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        commandBuilder.clear("#ContentArea");
        commandBuilder.clear("#ItemSearchSidebarContainer");
        
        switch (currentTab) {
            case TAB_GENERAL -> {
                if (editingRecipeType != null) {
                    buildRecipeDetail(commandBuilder, eventBuilder);
                } else {
                    buildGeneralTab(commandBuilder, eventBuilder);
                }
            }
            case TAB_ENCHANTMENTS -> buildEnchantmentsTab(commandBuilder, eventBuilder);
            case TAB_RECIPES -> buildRecipesTab(commandBuilder, eventBuilder);
        }
    }
    
    private void updateTabStyles(@Nonnull UICommandBuilder commandBuilder) {
        commandBuilder.set("#TabGeneral.Style", TAB_GENERAL.equals(currentTab) ? BUTTON_STYLE_SELECTED : BUTTON_STYLE);
        commandBuilder.set("#TabEnchantments.Style", TAB_ENCHANTMENTS.equals(currentTab) ? BUTTON_STYLE_SELECTED : BUTTON_STYLE);
        commandBuilder.set("#TabRecipes.Style", TAB_RECIPES.equals(currentTab) ? BUTTON_STYLE_SELECTED : BUTTON_STYLE);
        
        commandBuilder.set("#TabGeneral.TextSpans", languageManager.getMessage("customUI.enchantConfigPage.tabGeneral", lang, this.playerRef.getLanguage()));
        commandBuilder.set("#TabEnchantments.TextSpans", languageManager.getMessage("customUI.enchantConfigPage.tabEnchantments", lang, this.playerRef.getLanguage()));
        commandBuilder.set("#TabRecipes.TextSpans", languageManager.getMessage("customUI.enchantConfigPage.tabRecipes", lang, this.playerRef.getLanguage()));
        
        commandBuilder.set("#SaveButton.TextSpans", languageManager.getMessage("customUI.enchantConfigPage.save", lang, this.playerRef.getLanguage()));
        commandBuilder.set("#CancelButton.TextSpans", languageManager.getMessage("customUI.enchantConfigPage.cancel", lang, this.playerRef.getLanguage()));
        commandBuilder.set("#ResetAllButton.TextSpans", languageManager.getMessage("config.button.reset_all", lang, this.playerRef.getLanguage()));
        commandBuilder.set("#ConfirmResetButton.TextSpans", languageManager.getMessage("config.button.confirm_reset", lang, this.playerRef.getLanguage()));
    }
    
    private void buildGeneralTab(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        int index = 0;
        
        // Max Enchantments Per Item - using NumberField for direct input
        int maxEnchants = workingConfig.maxEnchantmentsPerItem;
        int maxEnchantsStep = 1;  // Integer value, step by 1
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigItem.ui");
        commandBuilder.set("#ContentArea[" + index + "] #SettingName.TextSpans", languageManager.getMessage("config.general.max_enchantments", lang, this.playerRef.getLanguage()));
        commandBuilder.set("#ContentArea[" + index + "] #SettingInput.Value", (double) maxEnchants);
        commandBuilder.set("#ContentArea[" + index + "] #SettingInput.Value", (double) maxEnchants);
        commandBuilder.set("#ContentArea[" + index + "] #ResetBtn.TextSpans", languageManager.getMessage("config.button.reset", lang, this.playerRef.getLanguage()));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #ResetBtn",
            EventData.of("ResetValue", "maxEnchantmentsPerItem"));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #DecreaseBtn",
            EventData.of("SettingValue", "maxEnchantmentsPerItem:" + Math.max(1, maxEnchants - maxEnchantsStep)));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #IncreaseBtn",
            EventData.of("SettingValue", "maxEnchantmentsPerItem:" + (maxEnchants + maxEnchantsStep)));
        eventBuilder.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ContentArea[" + index + "] #SettingInput",
            EventData.of("SettingValue", "maxEnchantmentsPerItem").append("@InputValue", "#ContentArea[" + index + "] #SettingInput.Value"), false);
        index++;
        
        // Show Enchantment Banner
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigToggle.ui");
        commandBuilder.set("#ContentArea[" + index + "] #SettingName.TextSpans", languageManager.getMessage("config.general.show_banner", lang, this.playerRef.getLanguage()));
        commandBuilder.set("#ContentArea[" + index + "] #ToggleButton.TextSpans", 
            languageManager.getMessage(workingConfig.showEnchantmentBanner ? "config.common.enabled" : "config.common.disabled", lang, this.playerRef.getLanguage()));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #ToggleButton",
            EventData.of("SettingValue", "showEnchantmentBanner:" + !workingConfig.showEnchantmentBanner));
        index++;
        
        // Enable Enchantment Glow
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigToggle.ui");
        commandBuilder.set("#ContentArea[" + index + "] #SettingName.TextSpans", languageManager.getMessage("config.general.enable_glow", lang, this.playerRef.getLanguage()));
        commandBuilder.set("#ContentArea[" + index + "] #ToggleButton.TextSpans", 
            languageManager.getMessage(workingConfig.enableEnchantmentGlow ? "config.common.enabled" : "config.common.disabled", lang, this.playerRef.getLanguage()));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #ToggleButton",
            EventData.of("SettingValue", "enableEnchantmentGlow:" + !workingConfig.enableEnchantmentGlow));
        index++;
        
        // Allow Same Scroll Upgrades
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigToggle.ui");
        commandBuilder.set("#ContentArea[" + index + "] #SettingName.TextSpans", languageManager.getMessage("config.general.allow_same_scroll_upgrades", lang, this.playerRef.getLanguage()));
        commandBuilder.set("#ContentArea[" + index + "] #ToggleButton.TextSpans", 
            languageManager.getMessage(workingConfig.allowSameScrollUpgrades ? "config.common.enabled" : "config.common.disabled", lang, this.playerRef.getLanguage()));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #ToggleButton",
            EventData.of("SettingValue", "allowSameScrollUpgrades:" + !workingConfig.allowSameScrollUpgrades));
        index++;
        
        // Enchanting Table Crafting Tier
        int craftingTier = workingConfig.enchantingTableCraftingTier;
        int craftingTierStep = 1;  // Integer value, step by 1
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigItem.ui");
        commandBuilder.set("#ContentArea[" + index + "] #SettingName.TextSpans", languageManager.getMessage("config.general.table_tier", lang, this.playerRef.getLanguage()));
        commandBuilder.set("#ContentArea[" + index + "] #SettingInput.Value", (double) craftingTier);
        commandBuilder.set("#ContentArea[" + index + "] #SettingInput.Value", (double) craftingTier);
        commandBuilder.set("#ContentArea[" + index + "] #ResetBtn.TextSpans", languageManager.getMessage("config.button.reset", lang, this.playerRef.getLanguage()));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #ResetBtn",
            EventData.of("ResetValue", "enchantingTableCraftingTier"));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #DecreaseBtn",
            EventData.of("SettingValue", "enchantingTableCraftingTier:" + Math.max(1, craftingTier - craftingTierStep)));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #IncreaseBtn",
            EventData.of("SettingValue", "enchantingTableCraftingTier:" + (craftingTier + craftingTierStep)));
        eventBuilder.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ContentArea[" + index + "] #SettingInput",
            EventData.of("SettingValue", "enchantingTableCraftingTier").append("@InputValue", "#ContentArea[" + index + "] #SettingInput.Value"), false);
        index++;
        
        // Disable Enchantment Crafting toggle
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigToggle.ui");
        commandBuilder.set("#ContentArea[" + index + "] #SettingName.TextSpans", languageManager.getMessage("config.general.disable_crafting", lang, this.playerRef.getLanguage()));
        commandBuilder.set("#ContentArea[" + index + "] #ToggleButton.TextSpans", 
            languageManager.getMessage(workingConfig.disableEnchantmentCrafting ? "config.common.enabled" : "config.common.disabled", lang, this.playerRef.getLanguage()));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #ToggleButton",
            EventData.of("SettingValue", "disableEnchantmentCrafting:" + !workingConfig.disableEnchantmentCrafting));
        index++;
        
        // Return Enchantment On Cleanse toggle
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigToggle.ui");
        commandBuilder.set("#ContentArea[" + index + "] #SettingName.TextSpans", languageManager.getMessage("config.general.return_cleanse", lang, this.playerRef.getLanguage()));
        commandBuilder.set("#ContentArea[" + index + "] #ToggleButton.TextSpans", 
            languageManager.getMessage(workingConfig.returnEnchantmentOnCleanse ? "config.common.enabled" : "config.common.disabled", lang, this.playerRef.getLanguage()));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #ToggleButton",
            EventData.of("SettingValue", "returnEnchantmentOnCleanse:" + !workingConfig.returnEnchantmentOnCleanse));
        index++;
        
        // Salvager Yields Scroll toggle
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigToggle.ui");
        commandBuilder.set("#ContentArea[" + index + "] #SettingName.TextSpans", languageManager.getMessage("config.general.salvager_yield", lang, this.playerRef.getLanguage()));
        commandBuilder.set("#ContentArea[" + index + "] #ToggleButton.TextSpans", 
            languageManager.getMessage(workingConfig.salvagerYieldsScroll ? "config.common.enabled" : "config.common.disabled", lang, this.playerRef.getLanguage()));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #ToggleButton",
            EventData.of("SettingValue", "salvagerYieldsScroll:" + !workingConfig.salvagerYieldsScroll));
        index++;
        
        // Edit Table Recipe button
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigRecipeButton.ui");
        commandBuilder.set("#ContentArea[" + index + "] #RecipeButtonLabel.TextSpans", languageManager.getMessage("config.general.table_recipe", lang, this.playerRef.getLanguage()));
        commandBuilder.set("#ContentArea[" + index + "] #EditRecipeBtn.TextSpans", languageManager.getMessage("config.button.edit", lang, this.playerRef.getLanguage()));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #EditRecipeBtn",
            EventData.of("EditRecipeType", "table"));
        index++;
        
        // Show Welcome Message toggle
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigToggle.ui");
        commandBuilder.set("#ContentArea[" + index + "] #SettingName.TextSpans", languageManager.getMessage("config.general.show_welcome", lang, this.playerRef.getLanguage()));
        commandBuilder.set("#ContentArea[" + index + "] #ToggleButton.TextSpans", 
            languageManager.getMessage(workingConfig.showWelcomeMessage ? "config.common.enabled" : "config.common.disabled", lang, this.playerRef.getLanguage()));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #ToggleButton",
            EventData.of("SettingValue", "showWelcomeMessage:" + !workingConfig.showWelcomeMessage));
        index++;
        
        // Edit Upgrade 1 button
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigRecipeButton.ui");
        commandBuilder.set("#ContentArea[" + index + "] #RecipeButtonLabel.TextSpans", languageManager.getMessage("config.general.upgrade_1", lang, this.playerRef.getLanguage()));
        commandBuilder.set("#ContentArea[" + index + "] #EditRecipeBtn.TextSpans", languageManager.getMessage("config.button.edit", lang, this.playerRef.getLanguage()));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #EditRecipeBtn",
            EventData.of("EditRecipeType", "Upgrade_1"));
        index++;
        
        // Edit Upgrade 2 button
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigRecipeButton.ui");
        commandBuilder.set("#ContentArea[" + index + "] #RecipeButtonLabel.TextSpans", languageManager.getMessage("config.general.upgrade_2", lang, this.playerRef.getLanguage()));
        commandBuilder.set("#ContentArea[" + index + "] #EditRecipeBtn.TextSpans", languageManager.getMessage("config.button.edit", lang, this.playerRef.getLanguage()));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #EditRecipeBtn",
            EventData.of("EditRecipeType", "Upgrade_2"));
        index++;
        
        // Edit Upgrade 3 button
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigRecipeButton.ui");
        commandBuilder.set("#ContentArea[" + index + "] #RecipeButtonLabel.TextSpans", languageManager.getMessage("config.general.upgrade_3", lang, this.playerRef.getLanguage()));
        commandBuilder.set("#ContentArea[" + index + "] #EditRecipeBtn.TextSpans", languageManager.getMessage("config.button.edit", lang, this.playerRef.getLanguage()));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #EditRecipeBtn",
            EventData.of("EditRecipeType", "Upgrade_3"));
    }
    
    /**
     * Builds the recipe edit screen for table/upgrade recipes on the General tab.
     */
    private void buildTableRecipeEdit(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        List<EnchantingConfig.ConfigIngredient> ingredients = getEditingIngredients();
        if (ingredients == null) return;
        
        // Back button
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigBackButton.ui");
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[0] #BackBtn",
            EventData.of("BackFromRecipeEdit", "true"));
        
        // Title and Reset button
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigRecipeTitle.ui");
        String titleKey = getEditingRecipeTitleKey();
        String translatedTitleString = languageManager.getRawMessage(titleKey, lang, this.playerRef.getLanguage());
        String rawActiveTitle = languageManager.getRawMessage("config.recipe.active_title", lang, this.playerRef.getLanguage());
        commandBuilder.set("#ContentArea[1].TextSpans", Message.raw(rawActiveTitle.replace("{0}", translatedTitleString)));
        
        int index = 2;
        int ingredientDataIndex = 0;
        
        for (EnchantingConfig.ConfigIngredient ingredient : ingredients) {
            if (ingredient.item != null) {
                int currentAmount = ingredient.amount != null ? ingredient.amount : 0;
                final int dataIdx = ingredientDataIndex;
                
                commandBuilder.append("#ContentArea", "Pages/EnchantConfigIngredient.ui");
                commandBuilder.set("#ContentArea[" + index + "] #IngredientIcon.ItemId", ingredient.item);
                commandBuilder.set("#ContentArea[" + index + "] #IngredientName.TextSpans", languageManager.getMessage(formatItemName(ingredient.item), lang, this.playerRef.getLanguage()));
                commandBuilder.set("#ContentArea[" + index + "] #AmountInput.Value", (double) currentAmount);
                commandBuilder.set("#ContentArea[" + index + "] #ChangeBtn.TextSpans", languageManager.getMessage("config.button.change", lang, this.playerRef.getLanguage()));
                
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #ChangeBtn",
                    EventData.of("OpenSearch", String.valueOf(dataIdx)));
                
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #RemoveBtn",
                    EventData.of("RemoveIngredient", String.valueOf(dataIdx)));
                
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #MoveUpBtn",
                    EventData.of("MoveIngredientUp", String.valueOf(dataIdx)));
                
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #MoveDownBtn",
                    EventData.of("MoveIngredientDown", String.valueOf(dataIdx)));
                
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #DecreaseAmountBtn",
                    EventData.of("UpdateAmount", dataIdx + ":" + Math.max(1, currentAmount - 1)));
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #IncreaseAmountBtn",
                    EventData.of("UpdateAmount", dataIdx + ":" + (currentAmount + 1)));
                eventBuilder.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ContentArea[" + index + "] #AmountInput",
                    EventData.of("UpdateAmount", String.valueOf(dataIdx)).append("@InputValue", "#ContentArea[" + index + "] #AmountInput.Value"), false);
                
                index++;
                ingredientDataIndex++;
            }
        }
        
        // Add Ingredient button
        if (searchingIngredientIndex < 0) {
            commandBuilder.append("#ContentArea", "Pages/EnchantConfigAddIngredient.ui");
            commandBuilder.set("#ContentArea[" + index + "] #AddIngredientBtn.TextSpans", languageManager.getMessage("config.button.add_ingredient", lang, this.playerRef.getLanguage()));
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #AddIngredientBtn",
                EventData.of("SettingKey", "AddIngredient"));
            index++;
            
            // Reset Recipe button
            commandBuilder.append("#ContentArea", "Pages/EnchantConfigResetRecipe.ui");
            commandBuilder.set("#ContentArea[" + index + "] #ResetRecipeBtn.TextSpans", languageManager.getMessage("config.button.reset_recipe", lang, this.playerRef.getLanguage()));
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #ResetRecipeBtn",
                EventData.of("ResetRecipe", editingRecipeType));
        }
        
        // Show item search overlay if searching
        if (searchingIngredientIndex >= 0) {
            buildItemSearchOverlay(commandBuilder, eventBuilder);
        }
    }
    
    private List<EnchantingConfig.ConfigIngredient> getEditingIngredients() {
        if (editingRecipeType == null) return null;
        if (editingRecipeType.equals("table")) {
            return workingConfig.enchantingTableRecipe;
        } else if (editingRecipeType.startsWith("Upgrade_")) {
            return workingConfig.enchantingTableUpgrades.get(editingRecipeType);
        }
        return null;
    }
    
    private String getEditingRecipeTitleKey() {
        if (editingRecipeType == null) return "config.recipe.title";
        
        // Handle generic keys
        if (editingRecipeType.equals("table")) return "config.general.table_recipe";
        if (editingRecipeType.equals("Upgrade_1")) return "config.general.upgrade_1";
        if (editingRecipeType.equals("Upgrade_2")) return "config.general.upgrade_2";
        if (editingRecipeType.equals("Upgrade_3")) return "config.general.upgrade_3";

        // Try to derive the scroll title
        String name = editingRecipeType;
        if (name.startsWith("Scroll_")) {
            name = name.substring("Scroll_".length());
            // It will be something like "Lightning_Strike_I", replace underscores with spaces
            name = name.replace("_", " ");
            return "Scroll of " + name; // Fallback plain text if there's no specific key
        }

        return "config.recipe.title";
    }
    
    private void buildEnchantmentsTab(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        int index = 0;
        boolean hasPerfectParries = SimpleEnchanting.getInstance().isPerfectParriesModPresent();
        
        for (EnchantmentType type : EnchantmentType.values()) {
            // Skip Riposte and Coup de Grâce if Perfect Parries is missing
            if (!hasPerfectParries && (type == EnchantmentType.RIPOSTE || type == EnchantmentType.COUP_DE_GRACE)) {
                continue;
            }

            commandBuilder.append("#ContentArea", "Pages/EnchantConfigEnchantment.ui");
            
            // Enchantment name
            commandBuilder.set("#ContentArea[" + index + "] #EnchantName.TextSpans", languageManager.getMessage(type.getNameKey(), lang, this.playerRef.getLanguage()));
            
            // Multiplier value (if this enchantment has one)
            String multiplierField = ENCHANTMENT_MULTIPLIERS.get(type);
            if (multiplierField != null) {
                double value = getMultiplierValue(multiplierField);
                double step = calculateStep(value);
                commandBuilder.set("#ContentArea[" + index + "] #MultiplierInput.Value", value);
                commandBuilder.set("#ContentArea[" + index + "] #MultiplierInput.Value", value);
                commandBuilder.set("#ContentArea[" + index + "] #MultiplierSection.Visible", true);
                commandBuilder.set("#ContentArea[" + index + "] #ResetMultiplier.TextSpans", languageManager.getMessage("config.button.reset", lang, this.playerRef.getLanguage()));
                // Reset button
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #ResetMultiplier",
                    EventData.of("ResetValue", multiplierField));
                // Decrease/Increase by dynamic step
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #MultiplierDecrease",
                    EventData.of("SettingValue", multiplierField + ":" + String.format("%.3f", Math.max(0, value - step))));
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #MultiplierIncrease",
                    EventData.of("SettingValue", multiplierField + ":" + String.format("%.3f", value + step)));
                // Direct input via ValueChanged
                eventBuilder.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ContentArea[" + index + "] #MultiplierInput",
                    EventData.of("SettingValue", multiplierField).append("@InputValue", "#ContentArea[" + index + "] #MultiplierInput.Value"), false);
            } else {
                commandBuilder.set("#ContentArea[" + index + "] #MultiplierSection.Visible", false);
            }
            
            // Toggle enabled/disabled
            boolean isDisabled = workingConfig.disabledEnchantments.getOrDefault(type.getId(), false);
            commandBuilder.set("#ContentArea[" + index + "] #EnableToggle.TextSpans", 
                languageManager.getMessage(isDisabled ? "config.common.disabled" : "config.common.enabled", lang, this.playerRef.getLanguage()));
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #EnableToggle",
                EventData.of("ToggleEnchantment", type.getId()));
            
            index++;
            
            // Secondary multiplier row (e.g., Strength range bonus, Looting quantity bonus)
            String secondaryField = ENCHANTMENT_SECONDARY_MULTIPLIERS.get(type);
            if (secondaryField != null) {
                String getOrDefault = SECONDARY_MULTIPLIER_LABELS.getOrDefault(secondaryField, secondaryField);
                double secValue = getMultiplierValue(secondaryField);
                double secStep = calculateStep(secValue);
                
                commandBuilder.append("#ContentArea", "Pages/EnchantConfigItem.ui");
                commandBuilder.set("#ContentArea[" + index + "] #SettingName.TextSpans", languageManager.getMessage(getOrDefault, lang, this.playerRef.getLanguage()));
                commandBuilder.set("#ContentArea[" + index + "] #SettingName.TextSpans", languageManager.getMessage(getOrDefault, lang, this.playerRef.getLanguage()));
                commandBuilder.set("#ContentArea[" + index + "] #SettingInput.Value", secValue);
                commandBuilder.set("#ContentArea[" + index + "] #ResetBtn.TextSpans", languageManager.getMessage("config.button.reset", lang, this.playerRef.getLanguage()));
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #ResetBtn",
                    EventData.of("ResetValue", secondaryField));
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #DecreaseBtn",
                    EventData.of("SettingValue", secondaryField + ":" + String.format("%.3f", Math.max(0, secValue - secStep))));
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #IncreaseBtn",
                    EventData.of("SettingValue", secondaryField + ":" + String.format("%.3f", secValue + secStep)));
                eventBuilder.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ContentArea[" + index + "] #SettingInput",
                    EventData.of("SettingValue", secondaryField).append("@InputValue", "#ContentArea[" + index + "] #SettingInput.Value"), false);
                
                index++;
            }
        }
    }
    
    private void buildRecipesTab(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        if (selectedRecipe != null) {
            buildRecipeDetail(commandBuilder, eventBuilder);
            return;
        }
        
        int index = 0;
        boolean hasPerfectParries = SimpleEnchanting.getInstance().isPerfectParriesModPresent();

        Set<String> allRecipeNames = new LinkedHashSet<>();
        allRecipeNames.addAll(workingConfig.scrollRecipes.keySet());

        for (EnchantmentType type : EnchantmentType.values()) {
            if (!type.isBuiltIn() && type.getScrollDefinitions() != null) {
                for (org.herolias.plugin.api.ScrollDefinition def : type.getScrollDefinitions()) {
                    allRecipeNames.add(type.getScrollBaseName() + "_" + EnchantmentType.toRoman(def.getLevel()));
                }
            }
        }

        for (String recipeName : allRecipeNames) {
            // Filter out Riposte and Coup de Grâce recipes if the mod is missing
            if (!hasPerfectParries) {
                if (recipeName.toLowerCase().contains("riposte") || recipeName.toLowerCase().contains("coup_de_grace")) {
                    continue;
                }
            }

            // Verify that this recipe corresponds to a registered enchantment or is the cleansing scroll
            boolean isValidRecipe = false;
            if (recipeName.startsWith("Scroll_Cleansing")) {
                isValidRecipe = true;
            } else {
                for (EnchantmentType type : EnchantmentType.values()) {
                    if (recipeName.startsWith(type.getScrollBaseName() + "_")) {
                        isValidRecipe = true;
                        break;
                    }
                }
            }

            if (!isValidRecipe) {
                continue; // Skip orphan recipes from uninstalled addons
            }

            List<EnchantingConfig.ConfigIngredient> ingredients = workingConfig.scrollRecipes.get(recipeName);
            if (ingredients == null) {
                ingredients = getDefaultAddonRecipe(recipeName);
            }
            if (ingredients == null) continue;
            
            // Find tier
            int tier = 1;
            for (EnchantingConfig.ConfigIngredient ing : ingredients) {
                if (ing.UnlocksAtTier != null) {
                    tier = ing.UnlocksAtTier;
                    break;
                }
            }
            
            final int currentTier = tier;
            final String recipeKey = recipeName;
            
            commandBuilder.append("#ContentArea", "Pages/EnchantConfigRecipeItem.ui");
            commandBuilder.set("#ContentArea[" + index + "] #RecipeName.TextSpans", languageManager.getMessage(getRecipeNameKey(recipeName), lang, this.playerRef.getLanguage()));
            commandBuilder.set("#ContentArea[" + index + "] #TierInput.Value", (double) tier);
            commandBuilder.set("#ContentArea[" + index + "] #TierLabel.TextSpans", languageManager.getMessage("config.recipe.tier_label", lang, this.playerRef.getLanguage()));
            commandBuilder.set("#ContentArea[" + index + "] #TierReset.TextSpans", languageManager.getMessage("config.button.reset", lang, this.playerRef.getLanguage()));
            commandBuilder.set("#ContentArea[" + index + "] #SelectBtn.TextSpans", languageManager.getMessage("config.button.edit_recipe", lang, this.playerRef.getLanguage()));
            
            // Tier controls with reset button
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #TierReset",
                EventData.of("ResetValue", "recipeTier:" + recipeKey));
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #TierDecrease",
                EventData.of("SettingValue", "recipeTier:" + recipeKey + ":" + Math.max(1, currentTier - 1)));
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #TierIncrease",
                EventData.of("SettingValue", "recipeTier:" + recipeKey + ":" + (currentTier + 1)));
            // Direct input via ValueChanged
            eventBuilder.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ContentArea[" + index + "] #TierInput",
                EventData.of("SettingValue", "recipeTier:" + recipeKey).append("@InputValue", "#ContentArea[" + index + "] #TierInput.Value"), false);
            
            // View button
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #SelectBtn",
                EventData.of("SelectRecipe", recipeName));
            
            index++;
        }
    }
    
    private void buildRecipeDetail(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        // Get ingredients based on whether we're editing scroll or table/upgrade recipe
        List<EnchantingConfig.ConfigIngredient> ingredients = getCurrentEditingIngredients();
        if (ingredients == null) return;
        
        // Determine title and reset target
        Message title;
        String resetTarget;
        boolean isScrollRecipe = selectedRecipe != null && editingRecipeType == null;
        
        if (selectedRecipe != null) {
            String nameKey = getRecipeNameKey(selectedRecipe);
            String translatedNameStr = languageManager.getRawMessage(nameKey, lang, this.playerRef.getLanguage());
            String rawActiveTitle = languageManager.getRawMessage("config.recipe.active_title", lang, this.playerRef.getLanguage());
            title = Message.raw(rawActiveTitle.replace("{0}", translatedNameStr));
            resetTarget = selectedRecipe;
        } else if (editingRecipeType != null) {
            // Editing generic recipe type (e.g. "Scroll of Sharpness I")
            String titleKey = getEditingRecipeTitleKey();
            String translatedNameStr = languageManager.getRawMessage(titleKey, lang, this.playerRef.getLanguage());
            String rawActiveTitle = languageManager.getRawMessage("config.recipe.active_title", lang, this.playerRef.getLanguage());
            title = Message.raw(rawActiveTitle.replace("{0}", translatedNameStr));
            resetTarget = editingRecipeType;
        } else {
            // Fallback if neither selectedRecipe nor editingRecipeType is set
            String rawActiveTitle = languageManager.getRawMessage("config.recipe.active_title", lang, this.playerRef.getLanguage());
            title = Message.raw(rawActiveTitle.replace("{0}", "Unknown Recipe"));
            resetTarget = null; // Or some default/error value
        }
        
        // Back button
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigBackButton.ui");
        commandBuilder.set("#ContentArea[0] #BackBtn.TextSpans", languageManager.getMessage("config.button.back", lang, this.playerRef.getLanguage()));
        if (isScrollRecipe) {
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[0] #BackBtn",
                EventData.of("BackToList", "true"));
        } else {
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[0] #BackBtn",
                EventData.of("BackFromRecipeEdit", "true"));
        }
        
        // Recipe title
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigRecipeTitle.ui");
        commandBuilder.set("#ContentArea[1].TextSpans", title);
        
        int index = 2;
        int ingredientDataIndex = 0;  // Track actual ingredient index in list (excluding tier entries)
        
        for (EnchantingConfig.ConfigIngredient ingredient : ingredients) {
            // Skip tier entry - tier is now editable in the list view
            if (ingredient.UnlocksAtTier != null) {
                continue;
            }
            
            if (ingredient.item != null) {
                int currentAmount = ingredient.amount != null ? ingredient.amount : 0;
                final int dataIdx = ingredientDataIndex;
                
                // Ingredient row with editable controls
                String displayName = formatItemName(ingredient.item);
                // Try to resolve item to get translated name
                Item item = Item.getAssetMap().getAssetMap().get(ingredient.item);
                if (item != null) {
                    displayName = getItemDisplayName(item);
                }
                
                commandBuilder.append("#ContentArea", "Pages/EnchantConfigIngredient.ui");
                commandBuilder.set("#ContentArea[" + index + "] #IngredientIcon.ItemId", ingredient.item);
                commandBuilder.set("#ContentArea[" + index + "] #IngredientName.TextSpans", languageManager.getMessage(displayName, lang, this.playerRef.getLanguage()));
                commandBuilder.set("#ContentArea[" + index + "] #AmountInput.Value", (double) currentAmount);
                commandBuilder.set("#ContentArea[" + index + "] #ChangeBtn.TextSpans", languageManager.getMessage("config.button.change", lang, this.playerRef.getLanguage()));
                
                // Change button to open item search
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #ChangeBtn",
                    EventData.of("OpenSearch", String.valueOf(dataIdx)));
                
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #RemoveBtn",
                    EventData.of("RemoveIngredient", String.valueOf(dataIdx)));
                
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #MoveUpBtn",
                    EventData.of("MoveIngredientUp", String.valueOf(dataIdx)));
                
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #MoveDownBtn",
                    EventData.of("MoveIngredientDown", String.valueOf(dataIdx)));
                
                // Amount controls
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #DecreaseAmountBtn",
                    EventData.of("UpdateAmount", dataIdx + ":" + Math.max(1, currentAmount - 1)));
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #IncreaseAmountBtn",
                    EventData.of("UpdateAmount", dataIdx + ":" + (currentAmount + 1)));
                eventBuilder.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ContentArea[" + index + "] #AmountInput",
                    EventData.of("UpdateAmount", String.valueOf(dataIdx)).append("@InputValue", "#ContentArea[" + index + "] #AmountInput.Value"), false);
                
                index++;
                ingredientDataIndex++;
            }
        }
        
        // Add Ingredient button (only show when not searching)
        if (searchingIngredientIndex < 0) {
            commandBuilder.append("#ContentArea", "Pages/EnchantConfigAddIngredient.ui");
            commandBuilder.set("#ContentArea[" + index + "] #AddIngredientBtn.TextSpans", languageManager.getMessage("config.button.add_ingredient", lang, this.playerRef.getLanguage()));
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #AddIngredientBtn",
                EventData.of("SettingKey", "AddIngredient"));
            index++;
            
            // Reset Recipe button
            commandBuilder.append("#ContentArea", "Pages/EnchantConfigResetRecipe.ui");
            commandBuilder.set("#ContentArea[" + index + "] #ResetRecipeBtn.TextSpans", languageManager.getMessage("config.button.reset_recipe", lang, this.playerRef.getLanguage()));
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #ResetRecipeBtn",
                EventData.of("ResetRecipe", resetTarget));
        }
        
        // Show item search overlay if searching
        if (searchingIngredientIndex >= 0) {
            buildItemSearchOverlay(commandBuilder, eventBuilder);
        }
    }
    
    /**
     * Builds the item search overlay for selecting a replacement ingredient.
     */
    private void buildItemSearchOverlay(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        // Append search overlay to sidebar container
        commandBuilder.append("#ItemSearchSidebarContainer", "Pages/EnchantConfigItemSearch.ui");
        
        // Restore the current search query value so it doesn't get cleared
        if (currentSearchQuery != null && !currentSearchQuery.isEmpty()) {
            commandBuilder.set("#ItemSearchSidebarContainer #ItemSearchInput.Value", currentSearchQuery);
        }
        
        // Cancel button
        commandBuilder.set("#ItemSearchSidebarContainer #CancelSearchBtn.TextSpans", languageManager.getMessage("config.button.cancel", lang, this.playerRef.getLanguage()));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ItemSearchSidebarContainer #CancelSearchBtn",
            EventData.of("CancelSearch", "true"));
        
        // Search input - listen for text changes using .Value property
        eventBuilder.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ItemSearchSidebarContainer #ItemSearchInput",
            EventData.of("SearchInput", "").append("@SearchInput", "#ItemSearchSidebarContainer #ItemSearchInput.Value"), false);
        
        // Get filtered items and display them
        List<Item> filteredItems = getFilteredItems(currentSearchQuery);
        
        int itemIndex = 0;
        int maxResults = 50;  // Limit results to avoid performance issues
        
        for (Item item : filteredItems) {
            if (itemIndex >= maxResults) break;
            
            String itemId = item.getId();
            String displayName = getItemDisplayName(item);
            
            commandBuilder.append("#ItemSearchSidebarContainer #ItemSearchResults", "Pages/EnchantConfigSearchItem.ui");
            commandBuilder.set("#ItemSearchSidebarContainer #ItemSearchResults[" + itemIndex + "] #ItemIcon.ItemId", itemId);
            commandBuilder.set("#ItemSearchSidebarContainer #ItemSearchResults[" + itemIndex + "] #ItemDisplayName.TextSpans", languageManager.getMessage(displayName, lang, this.playerRef.getLanguage()));
            
            // Clicking the item selects it
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ItemSearchSidebarContainer #ItemSearchResults[" + itemIndex + "] #ItemSelectBtn",
                EventData.of("SelectItem", itemId));
            
            itemIndex++;
        }
    }
    
    /**
     * Updates only the search results list without rebuilding the entire overlay.
     * This preserves focus on the search input field.
     */
    private void updateSearchResults(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        // Clear only the search results, not the entire overlay
        commandBuilder.clear("#ItemSearchSidebarContainer #ItemSearchResults");
        
        // Get filtered items and display them
        List<Item> filteredItems = getFilteredItems(currentSearchQuery);
        
        int itemIndex = 0;
        int maxResults = 50;
        
        for (Item item : filteredItems) {
            if (itemIndex >= maxResults) break;
            
            String itemId = item.getId();
            String displayName = getItemDisplayName(item);
            
            commandBuilder.append("#ItemSearchSidebarContainer #ItemSearchResults", "Pages/EnchantConfigSearchItem.ui");
            commandBuilder.set("#ItemSearchSidebarContainer #ItemSearchResults[" + itemIndex + "] #ItemIcon.ItemId", itemId);
            commandBuilder.set("#ItemSearchSidebarContainer #ItemSearchResults[" + itemIndex + "] #ItemDisplayName.TextSpans", languageManager.getMessage(displayName, lang, this.playerRef.getLanguage()));
            
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ItemSearchSidebarContainer #ItemSearchResults[" + itemIndex + "] #ItemSelectBtn",
                EventData.of("SelectItem", itemId));
            
            itemIndex++;
        }
    }
    
    /**
     * Adds a new empty ingredient to the current recipe.
     */
    private void addNewIngredient() {
        List<EnchantingConfig.ConfigIngredient> ingredients = getCurrentEditingIngredients(true);
        if (ingredients == null) return;
        
        // Add a new ingredient with a default item
        EnchantingConfig.ConfigIngredient newIngredient = new EnchantingConfig.ConfigIngredient("Resource_Iron_Ingot", 1);
        ingredients.add(newIngredient);
        markUnsavedChange();
    }

    /**
     * Filters all game items by the search query (matches ID or display name).
     */
    private List<Item> getFilteredItems(String query) {
        List<Item> result = new ArrayList<>();
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        
        for (Item item : Item.getAssetMap().getAssetMap().values()) {
            if (item == null) continue;
            
            String itemId = item.getId();
            if (itemId == null) continue;
            
            // Match by ID or display name
            String displayName = getItemDisplayName(item);
            if (itemId.toLowerCase(Locale.ROOT).contains(lowerQuery) ||
                displayName.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                result.add(item);
            }
        }
        
        return result;
    }
    
    /**
     * Gets a display-friendly name for an item.
     * Uses the item's translation key if available, otherwise formats the ID.
     */
    private String getItemDisplayName(Item item) {
        String translationKey = item.getTranslationKey();
        if (translationKey != null && !translationKey.isEmpty()) {
            return translationKey;
        }
        // Fall back to formatted ID
        return formatItemName(item.getId());
    }

    
    /**
     * Helper to reliably fetch the default definition for an addon recipe.
     */
    private List<EnchantingConfig.ConfigIngredient> getDefaultAddonRecipe(String scrollItemId) {
        for (EnchantmentType type : EnchantmentType.values()) {
            if (!type.isBuiltIn() && type.getScrollDefinitions() != null) {
                for (org.herolias.plugin.api.ScrollDefinition def : type.getScrollDefinitions()) {
                    String id = type.getScrollBaseName() + "_" + org.herolias.plugin.enchantment.EnchantmentType.toRoman(def.getLevel());
                    if (id.equals(scrollItemId)) {
                        List<EnchantingConfig.ConfigIngredient> list = new ArrayList<>();
                        for (org.herolias.plugin.api.ScrollDefinition.Ingredient ing : def.getRecipe()) {
                            list.add(new EnchantingConfig.ConfigIngredient(ing.getItemId(), ing.getQuantity()));
                        }
                        list.add(new EnchantingConfig.ConfigIngredient(def.getCraftingTier()));
                        return list;
                    }
                }
            }
        }
        return null;
    }

    private List<EnchantingConfig.ConfigIngredient> getCurrentEditingIngredients() {
        return getCurrentEditingIngredients(false);
    }

    /**
     * Gets the ingredient list currently being edited (scroll, table, or upgrade).
     */
    private List<EnchantingConfig.ConfigIngredient> getCurrentEditingIngredients(boolean createIfMissing) {
        // First check table/upgrade editing (priority)
        if (editingRecipeType != null) {
            return getEditingIngredients();
        }
        // Fall back to scroll recipe editing
        if (selectedRecipe != null) {
            if (workingConfig.scrollRecipes.containsKey(selectedRecipe)) {
                return workingConfig.scrollRecipes.get(selectedRecipe);
            }
            List<EnchantingConfig.ConfigIngredient> def = getDefaultAddonRecipe(selectedRecipe);
            if (def != null) {
                if (createIfMissing) {
                    workingConfig.scrollRecipes.put(selectedRecipe, def);
                }
                return def;
            }
        }
        return null;
    }
    
    /**
     * Updates an ingredient's item ID at the specified index.
     */
    private void updateIngredient(int ingredientIndex, String newItemId) {
        List<EnchantingConfig.ConfigIngredient> ingredients = getCurrentEditingIngredients(true);
        if (ingredients == null) return;
        
        int dataIndex = 0;
        for (EnchantingConfig.ConfigIngredient ingredient : ingredients) {
            if (ingredient.UnlocksAtTier != null) continue;
            
            if (dataIndex == ingredientIndex) {
                ingredient.item = newItemId;
                markUnsavedChange();
                return;
            }
            dataIndex++;
        }
    }
    
    /**
     * Updates an ingredient's amount at the specified index.
     */
    private void updateIngredientAmount(int ingredientIndex, int newAmount) {
        List<EnchantingConfig.ConfigIngredient> ingredients = getCurrentEditingIngredients(true);
        if (ingredients == null) return;
        
        int dataIndex = 0;
        for (EnchantingConfig.ConfigIngredient ingredient : ingredients) {
            if (ingredient.UnlocksAtTier != null) continue;
            
            if (dataIndex == ingredientIndex) {
                ingredient.amount = Math.max(1, newAmount);
                markUnsavedChange();
                return;
            }
            dataIndex++;
        }
    }
    
    /**
     * Resets a recipe to its default from EnchantingConfig defaults.
     */
    private void resetRecipeToDefault(String recipeType) {
        EnchantingConfig defaultConfig = EnchantingConfig.createDefault();
        
        if (recipeType.equals("table")) {
            workingConfig.enchantingTableRecipe = new ArrayList<>(defaultConfig.enchantingTableRecipe);
        } else if (recipeType.startsWith("Upgrade_")) {
            List<EnchantingConfig.ConfigIngredient> defaultUpgrade = defaultConfig.enchantingTableUpgrades.get(recipeType);
            if (defaultUpgrade != null) {
                workingConfig.enchantingTableUpgrades.put(recipeType, new ArrayList<>(defaultUpgrade));
            }
        } else {
            // Scroll recipe
            List<EnchantingConfig.ConfigIngredient> defaultRecipe = defaultConfig.scrollRecipes.get(recipeType);
            if (defaultRecipe != null) {
                workingConfig.scrollRecipes.put(recipeType, new ArrayList<>(defaultRecipe));
            } else {
                List<EnchantingConfig.ConfigIngredient> addonDef = getDefaultAddonRecipe(recipeType);
                if (addonDef != null) {
                    workingConfig.scrollRecipes.remove(recipeType);
                }
            }
        }
    }
    
    private String formatItemName(String itemId) {
        // Convert Item IDs like "Resource_Iron_Ingot" to "Iron Ingot"
        String name = itemId;
        // Remove common prefixes
        for (String prefix : new String[]{"Resource_", "Material_", "Item_", "Misc_"}) {
            if (name.startsWith(prefix)) {
                name = name.substring(prefix.length());
                break;
            }
        }
        return name.replace("_", " ");
    }
    
    private String getRecipeNameKey(String name) {
        return "items." + name + ".name";
    }
    
    private double getMultiplierValue(String key) {
        // First check enchantmentMultipliers map (handles all enchantment IDs)
        if (workingConfig.enchantmentMultipliers.containsKey(key)) {
            return workingConfig.enchantmentMultipliers.getOrDefault(key, 0.0);
        }
        // Legacy secondary multiplier fields
        return switch (key) {
            case "strengthRangeMultiplierPerLevel" -> workingConfig.strengthRangeMultiplierPerLevel != null ? workingConfig.strengthRangeMultiplierPerLevel : 0.15;
            case "lootingQuantityMultiplierPerLevel" -> workingConfig.lootingQuantityMultiplierPerLevel != null ? workingConfig.lootingQuantityMultiplierPerLevel : 0.25;
            case "burnDuration" -> workingConfig.burnDuration;
            case "freezeDuration" -> workingConfig.freezeDuration;
            default -> 0.0;
        };
    }
    
    private void updateSetting(String key, String value) {
        // Handle recipe tier updates (format: recipeTier:recipeName:newTier)
        if (key.equals("recipeTier") && value.contains(":")) {
            String[] parts = value.split(":", 2);
            if (parts.length == 2) {
                updateRecipeTier(parts[0], parts[1]);
            }
            return;
        }
        
        // Ignore empty values (e.g. while deleting text) without logging warning
        if (value == null || value.trim().isEmpty()) {
            markUnsavedChange();
            return;
        }
        
        try {
            // Check if key is an enchantment ID in the multipliers map
            if (workingConfig.enchantmentMultipliers.containsKey(key)) {
                workingConfig.enchantmentMultipliers.put(key, Double.parseDouble(value));
            } else {
                // General settings and legacy secondary fields
                switch (key) {
                    case "maxEnchantmentsPerItem" -> workingConfig.maxEnchantmentsPerItem = Math.max(1, Integer.parseInt(value));
                    case "showEnchantmentBanner" -> workingConfig.showEnchantmentBanner = Boolean.parseBoolean(value);
                    case "enableEnchantmentGlow" -> workingConfig.enableEnchantmentGlow = Boolean.parseBoolean(value);
                    case "allowSameScrollUpgrades" -> workingConfig.allowSameScrollUpgrades = Boolean.parseBoolean(value);
                    case "disableEnchantmentCrafting" -> workingConfig.disableEnchantmentCrafting = Boolean.parseBoolean(value);
                    case "returnEnchantmentOnCleanse" -> workingConfig.returnEnchantmentOnCleanse = Boolean.parseBoolean(value);
                    case "enchantingTableCraftingTier" -> workingConfig.enchantingTableCraftingTier = Math.max(1, Integer.parseInt(value));
                    case "strengthRangeMultiplierPerLevel" -> workingConfig.strengthRangeMultiplierPerLevel = Double.parseDouble(value);
                    case "lootingQuantityMultiplierPerLevel" -> workingConfig.lootingQuantityMultiplierPerLevel = Double.parseDouble(value);
                    case "burnDuration" -> workingConfig.burnDuration = Double.parseDouble(value);
                    case "freezeDuration" -> workingConfig.freezeDuration = Double.parseDouble(value);
                    case "salvagerYieldsScroll" -> workingConfig.salvagerYieldsScroll = Boolean.parseBoolean(value);
                    case "showWelcomeMessage" -> workingConfig.showWelcomeMessage = Boolean.parseBoolean(value);
                }
            }
        } catch (NumberFormatException e) {
            LOGGER.atWarning().log("Failed to parse setting value: " + key + " = " + value);
        }
        markUnsavedChange();
    }
    
    private void toggleEnchantment(String enchantmentId) {
        boolean currentlyDisabled = workingConfig.disabledEnchantments.getOrDefault(enchantmentId, false);
        workingConfig.disabledEnchantments.put(enchantmentId, !currentlyDisabled);
        markUnsavedChange();
    }
    
    private void updateRecipeTier(String recipeName, String tierValue) {
        List<EnchantingConfig.ConfigIngredient> ingredients = workingConfig.scrollRecipes.get(recipeName);
        if (ingredients == null) {
            ingredients = getDefaultAddonRecipe(recipeName);
            if (ingredients != null) {
                workingConfig.scrollRecipes.put(recipeName, ingredients);
            }
        }
        if (ingredients == null) return;
        
        try {
            int newTier = Integer.parseInt(tierValue);
            for (EnchantingConfig.ConfigIngredient ing : ingredients) {
                if (ing.UnlocksAtTier != null) {
                    ing.UnlocksAtTier = newTier;
                    break;
                }
            }
        } catch (NumberFormatException e) {
            LOGGER.atWarning().log("Failed to parse tier value: " + tierValue);
        }
        markUnsavedChange();
    }
    
    /**
     * Resets a setting to its default value.
     * For regular settings, uses DEFAULT_CONFIG from EnchantingConfig.createDefault().
     * For recipe tiers, looks up original tier from the default config's recipes.
     */
    private void resetToDefault(String key) {
        // Handle recipe tier reset
        if (key.startsWith("recipeTier:")) {
            String recipeName = key.substring("recipeTier:".length());
            // Get original tier from the default config's recipes
            List<EnchantingConfig.ConfigIngredient> defaultIngredients = DEFAULT_CONFIG.scrollRecipes.get(recipeName);
            if (defaultIngredients != null) {
                int defaultTier = 1;
                for (EnchantingConfig.ConfigIngredient ing : defaultIngredients) {
                    if (ing.UnlocksAtTier != null) {
                        defaultTier = ing.UnlocksAtTier;
                        break;
                    }
                }
                updateRecipeTier(recipeName, String.valueOf(defaultTier));
            } else {
                List<EnchantingConfig.ConfigIngredient> addonDef = getDefaultAddonRecipe(recipeName);
                if (addonDef != null) {
                    workingConfig.scrollRecipes.remove(recipeName);
                    markUnsavedChange();
                }
            }
            return;
        }
        
        // Handle regular settings - get default value from DEFAULT_CONFIG
        String defaultValue = getDefaultValue(key);
        if (defaultValue != null) {
            updateSetting(key, defaultValue);
        }
    }
    
    /**
     * Gets the default value for a setting from DEFAULT_CONFIG.
     */
    private String getDefaultValue(String key) {
        // Check enchantment multipliers map first
        if (DEFAULT_CONFIG.enchantmentMultipliers.containsKey(key)) {
            return String.valueOf(DEFAULT_CONFIG.enchantmentMultipliers.get(key));
        }
        return switch (key) {
            case "maxEnchantmentsPerItem" -> String.valueOf(DEFAULT_CONFIG.maxEnchantmentsPerItem);
            case "allowSameScrollUpgrades" -> String.valueOf(DEFAULT_CONFIG.allowSameScrollUpgrades);
            case "enchantingTableCraftingTier" -> String.valueOf(DEFAULT_CONFIG.enchantingTableCraftingTier);
            case "strengthRangeMultiplierPerLevel" -> String.valueOf(DEFAULT_CONFIG.strengthRangeMultiplierPerLevel != null ? DEFAULT_CONFIG.strengthRangeMultiplierPerLevel : 0.15);
            case "lootingQuantityMultiplierPerLevel" -> String.valueOf(DEFAULT_CONFIG.lootingQuantityMultiplierPerLevel != null ? DEFAULT_CONFIG.lootingQuantityMultiplierPerLevel : 0.25);
            case "burnDuration" -> String.valueOf(DEFAULT_CONFIG.burnDuration);
            case "freezeDuration" -> String.valueOf(DEFAULT_CONFIG.freezeDuration);
            case "returnEnchantmentOnCleanse" -> String.valueOf(DEFAULT_CONFIG.returnEnchantmentOnCleanse);
            case "disableEnchantmentCrafting" -> String.valueOf(DEFAULT_CONFIG.disableEnchantmentCrafting);
            case "salvagerYieldsScroll" -> String.valueOf(DEFAULT_CONFIG.salvagerYieldsScroll);
            case "showWelcomeMessage" -> String.valueOf(DEFAULT_CONFIG.showWelcomeMessage);
            default -> null;
        };
    }
    
    private void saveConfig(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        // Copy working config to actual config
        EnchantingConfig actualConfig = configManager.getConfig();
        actualConfig.maxEnchantmentsPerItem = workingConfig.maxEnchantmentsPerItem;
        actualConfig.showEnchantmentBanner = workingConfig.showEnchantmentBanner;
        actualConfig.hasAutoDisabledBanner = workingConfig.hasAutoDisabledBanner;
        actualConfig.enableEnchantmentGlow = workingConfig.enableEnchantmentGlow;
        actualConfig.allowSameScrollUpgrades = workingConfig.allowSameScrollUpgrades;
        actualConfig.enchantingTableCraftingTier = workingConfig.enchantingTableCraftingTier;
        
        // Copy enchantment multipliers map
        actualConfig.enchantmentMultipliers = new LinkedHashMap<>(workingConfig.enchantmentMultipliers);
        
        // Legacy secondary fields
        actualConfig.strengthRangeMultiplierPerLevel = workingConfig.strengthRangeMultiplierPerLevel;
        actualConfig.lootingQuantityMultiplierPerLevel = workingConfig.lootingQuantityMultiplierPerLevel;
        actualConfig.burnDuration = workingConfig.burnDuration;
        actualConfig.freezeDuration = workingConfig.freezeDuration;
        
        actualConfig.returnEnchantmentOnCleanse = workingConfig.returnEnchantmentOnCleanse;
        actualConfig.disableEnchantmentCrafting = workingConfig.disableEnchantmentCrafting;
        actualConfig.salvagerYieldsScroll = workingConfig.salvagerYieldsScroll;
        actualConfig.showWelcomeMessage = workingConfig.showWelcomeMessage;
        actualConfig.disabledEnchantments = new LinkedHashMap<>(workingConfig.disabledEnchantments);
        actualConfig.scrollRecipes = new LinkedHashMap<>();
        for (var entry : workingConfig.scrollRecipes.entrySet()) {
            actualConfig.scrollRecipes.put(entry.getKey(), new java.util.ArrayList<>(entry.getValue()));
        }
        
        // Save enchanting table recipe and upgrades
        if (workingConfig.enchantingTableRecipe != null) {
            actualConfig.enchantingTableRecipe = new java.util.ArrayList<>(workingConfig.enchantingTableRecipe);
        }
        if (workingConfig.enchantingTableUpgrades != null) {
            actualConfig.enchantingTableUpgrades = new LinkedHashMap<>();
            for (var entry : workingConfig.enchantingTableUpgrades.entrySet()) {
                actualConfig.enchantingTableUpgrades.put(entry.getKey(), new java.util.ArrayList<>(entry.getValue()));
            }
        }
        
        // Save to disk
        configManager.saveConfig();
        LOGGER.atInfo().log("Configuration saved via in-game editor");

        // Invalidate enchantment cache FIRST so that refresh systems see the new state
        org.herolias.plugin.SimpleEnchanting.getInstance().getEnchantmentManager().invalidateEnabledCache();

        // Force-refresh all players' tooltips so changes are visible immediately.
        // TooltipBridge isolates all DynamicTooltipsLib references; only call it
        // when we know the lib was loaded successfully.
        if (org.herolias.plugin.SimpleEnchanting.getInstance().isTooltipsEnabled()) {
            try {
                org.herolias.plugin.enchantment.TooltipBridge.refreshAllPlayers();
            } catch (NoClassDefFoundError e) {
                // Safety net — should never happen if isTooltipsEnabled() is true
            }
        }
        
        // Broadcast global scroll translation updates (for Crafting UI etc)
        org.herolias.plugin.enchantment.ScrollDescriptionManager.broadcastUpdatePacket();
        
        // Refresh recipes based on new config settings (e.g. invalidates disabled recipes)
        org.herolias.plugin.enchantment.EnchantmentRecipeManager.reload();
        
        // Reset unsaved changes state
        hasUnsavedChanges = false;
        showSaveFeedback = true;
        
        // If editing a recipe detail, go back to overview or previous screen
        if (editingRecipeType != null) {
            editingRecipeType = null;
            searchingIngredientIndex = -1;
            currentSearchQuery = "";
        } else if (selectedRecipe != null) {
            selectedRecipe = null;
            searchingIngredientIndex = -1;
            currentSearchQuery = "";
        }
    }
    
    private void resetAllToDefaults() {
        // Reset the working config to a fresh default
        EnchantingConfig defaults = EnchantingConfig.createDefault();
        
        workingConfig.maxEnchantmentsPerItem = defaults.maxEnchantmentsPerItem;
        
        // Smart default for banner: verify if tooltips are enabled
        if (SimpleEnchanting.getInstance().isTooltipsEnabled()) {
            workingConfig.showEnchantmentBanner = false;
            workingConfig.hasAutoDisabledBanner = true;
        } else {
            workingConfig.showEnchantmentBanner = defaults.showEnchantmentBanner;
            workingConfig.hasAutoDisabledBanner = false;
        }
        
        workingConfig.enableEnchantmentGlow = defaults.enableEnchantmentGlow;
        workingConfig.enchantingTableCraftingTier = defaults.enchantingTableCraftingTier;
        
        // Reset enchantment multipliers map
        workingConfig.enchantmentMultipliers = new LinkedHashMap<>(defaults.enchantmentMultipliers);
        
        // Reset legacy secondary fields
        workingConfig.strengthRangeMultiplierPerLevel = defaults.strengthRangeMultiplierPerLevel;
        workingConfig.lootingQuantityMultiplierPerLevel = defaults.lootingQuantityMultiplierPerLevel;
        workingConfig.burnDuration = defaults.burnDuration;
        workingConfig.freezeDuration = defaults.freezeDuration;
        
        workingConfig.returnEnchantmentOnCleanse = defaults.returnEnchantmentOnCleanse;
        workingConfig.disableEnchantmentCrafting = defaults.disableEnchantmentCrafting;
        workingConfig.salvagerYieldsScroll = defaults.salvagerYieldsScroll;
        workingConfig.showWelcomeMessage = defaults.showWelcomeMessage;
        workingConfig.allowSameScrollUpgrades = defaults.allowSameScrollUpgrades;

        
        // Reset maps
        workingConfig.disabledEnchantments = new LinkedHashMap<>(); // Defaults are empty usually
        
        workingConfig.scrollRecipes = new LinkedHashMap<>();
        for (var entry : defaults.scrollRecipes.entrySet()) {
            workingConfig.scrollRecipes.put(entry.getKey(), new java.util.ArrayList<>(entry.getValue()));
        }
        
        if (defaults.enchantingTableRecipe != null) {
            workingConfig.enchantingTableRecipe = new java.util.ArrayList<>(defaults.enchantingTableRecipe);
        }
        
        workingConfig.enchantingTableUpgrades = new LinkedHashMap<>();
        for (var entry : defaults.enchantingTableUpgrades.entrySet()) {
            workingConfig.enchantingTableUpgrades.put(entry.getKey(), new java.util.ArrayList<>(entry.getValue()));
        }
        
        // Mark as unsaved
        markUnsavedChange();
    }
    
    private void removeIngredient(int ingredientIndex) {
        List<EnchantingConfig.ConfigIngredient> ingredients = getCurrentEditingIngredients(true);
        if (ingredients == null) return;
        
        int dataIndex = 0;
        int removeIndex = -1;
        
        // Find the actual list index for this data index (skipping tiers)
        for (int i = 0; i < ingredients.size(); i++) {
            EnchantingConfig.ConfigIngredient ingredient = ingredients.get(i);
            if (ingredient.UnlocksAtTier != null) continue;
            
            if (dataIndex == ingredientIndex) {
                removeIndex = i;
                break;
            }
            dataIndex++;
        }
        
        if (removeIndex >= 0) {
            ingredients.remove(removeIndex);
            markUnsavedChange();
        }
    }
    
    private void moveIngredient(int ingredientIndex, int direction) {
        List<EnchantingConfig.ConfigIngredient> ingredients = getCurrentEditingIngredients();
        if (ingredients == null) return;
        
        int dataIndex = 0;
        int listIndex = -1;
        
        // Find the actual list index
        for (int i = 0; i < ingredients.size(); i++) {
            EnchantingConfig.ConfigIngredient ingredient = ingredients.get(i);
            if (ingredient.UnlocksAtTier != null) continue;
            
            if (dataIndex == ingredientIndex) {
                listIndex = i;
                break;
            }
            dataIndex++;
        }
        
        if (listIndex >= 0) {
            int swapIndex = -1;
            // Find the previous/next ingredient to swap with (skipping tiers)
            if (direction < 0) { // Up
                for (int i = listIndex - 1; i >= 0; i--) {
                    if (ingredients.get(i).UnlocksAtTier == null) {
                        swapIndex = i;
                        break;
                    }
                }
            } else { // Down
                for (int i = listIndex + 1; i < ingredients.size(); i++) {
                    if (ingredients.get(i).UnlocksAtTier == null) {
                        swapIndex = i;
                        break;
                    }
                }
            }
            
            if (swapIndex >= 0) {
                EnchantingConfig.ConfigIngredient temp = ingredients.get(listIndex);
                ingredients.set(listIndex, ingredients.get(swapIndex));
                ingredients.set(swapIndex, temp);
                markUnsavedChange();
            }
        }
    }
    
    private void updateActionBarIndicators(@Nonnull UICommandBuilder commandBuilder) {
        // Update Unsaved Changes Indicator
        commandBuilder.set("#UnsavedIndicator.Visible", hasUnsavedChanges);
        if (hasUnsavedChanges) {
            commandBuilder.set("#UnsavedIndicator.TextSpans", languageManager.getMessage("config.message.unsaved", lang, this.playerRef.getLanguage()));
        }
        
        // Update Save Feedback
        commandBuilder.set("#SaveFeedback.Visible", showSaveFeedback);
        if (showSaveFeedback) {
            commandBuilder.set("#SaveFeedback.TextSpans", languageManager.getMessage("config.message.saved", lang, this.playerRef.getLanguage()));
            // Auto-hide feedback could be handled by a delayed task, but for now it stays until next action clears it
            // Or we could perform a clear on next event
        }
        
        // Update Reset All Button to show confirmation state
        // Update Reset All Button to show confirmation state
        if (showResetConfirmation) {
            commandBuilder.set("#ResetAllButton.Visible", false);
            commandBuilder.set("#ConfirmResetButton.Visible", true);
        } else {
            commandBuilder.set("#ResetAllButton.Visible", true);
            commandBuilder.set("#ConfirmResetButton.Visible", false);
        }
    }
    
    private void markUnsavedChange() {
        if (!hasUnsavedChanges) {
            hasUnsavedChanges = true;
            showSaveFeedback = false; // clear success message on new change
        }
    }
    
    private void closeWithoutSaving(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        Player playerComponent = store.getComponent(ref, Player.getComponentType());
        if (playerComponent != null) {
            playerComponent.getPageManager().setPage(ref, store, Page.None);
        }
    }
}
