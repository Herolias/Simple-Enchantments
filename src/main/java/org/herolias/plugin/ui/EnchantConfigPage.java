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
import org.herolias.plugin.enchantment.EnchantmentType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    
    // Mapping of enchantment types to their config field names for multipliers
    private static final Map<EnchantmentType, String> ENCHANTMENT_MULTIPLIERS = new LinkedHashMap<>();
    // Secondary multipliers for enchantments with multiple configurable effects
    private static final Map<EnchantmentType, String> ENCHANTMENT_SECONDARY_MULTIPLIERS = new LinkedHashMap<>();
    // Display labels for secondary multipliers
    private static final Map<String, String> SECONDARY_MULTIPLIER_LABELS = new LinkedHashMap<>();
    static {
        ENCHANTMENT_MULTIPLIERS.put(EnchantmentType.SHARPNESS, "sharpnessDamageMultiplierPerLevel");
        ENCHANTMENT_MULTIPLIERS.put(EnchantmentType.LIFE_LEECH, "lifeLeechPercentage");
        ENCHANTMENT_MULTIPLIERS.put(EnchantmentType.DURABILITY, "durabilityReductionPerLevel");
        ENCHANTMENT_MULTIPLIERS.put(EnchantmentType.DEXTERITY, "dexterityStaminaReductionPerLevel");
        ENCHANTMENT_MULTIPLIERS.put(EnchantmentType.PROTECTION, "protectionDamageReductionPerLevel");
        ENCHANTMENT_MULTIPLIERS.put(EnchantmentType.EFFICIENCY, "efficiencyMiningSpeedPerLevel");
        ENCHANTMENT_MULTIPLIERS.put(EnchantmentType.FORTUNE, "fortuneRollChancePerLevel");
        ENCHANTMENT_MULTIPLIERS.put(EnchantmentType.STRENGTH, "strengthDamageMultiplierPerLevel");
        ENCHANTMENT_MULTIPLIERS.put(EnchantmentType.EAGLES_EYE, "eaglesEyeDistanceBonusPerLevel");
        ENCHANTMENT_MULTIPLIERS.put(EnchantmentType.LOOTING, "lootingChanceMultiplierPerLevel");
        ENCHANTMENT_MULTIPLIERS.put(EnchantmentType.FEATHER_FALLING, "featherFallingReductionPerLevel");
        ENCHANTMENT_MULTIPLIERS.put(EnchantmentType.WATERBREATHING, "waterBreathingReductionPerLevel");
        ENCHANTMENT_MULTIPLIERS.put(EnchantmentType.KNOCKBACK, "knockbackStrengthPerLevel");
        ENCHANTMENT_MULTIPLIERS.put(EnchantmentType.REFLECTION, "reflectionDamagePercentagePerLevel");
        ENCHANTMENT_MULTIPLIERS.put(EnchantmentType.ABSORPTION, "absorptionHealPercentagePerLevel");
        ENCHANTMENT_MULTIPLIERS.put(EnchantmentType.FAST_SWIM, "fastSwimSpeedBonusPerLevel");
        ENCHANTMENT_MULTIPLIERS.put(EnchantmentType.THRIFT, "thriftRestoreAmountPerLevel");
        ENCHANTMENT_MULTIPLIERS.put(EnchantmentType.ELEMENTAL_HEART, "elementalHeartSaveChancePerLevel");

        // Secondary multipliers for enchantments with multiple effects
        ENCHANTMENT_SECONDARY_MULTIPLIERS.put(EnchantmentType.STRENGTH, "strengthRangeMultiplierPerLevel");
        ENCHANTMENT_SECONDARY_MULTIPLIERS.put(EnchantmentType.LOOTING, "lootingQuantityMultiplierPerLevel");

        SECONDARY_MULTIPLIER_LABELS.put("strengthRangeMultiplierPerLevel", "Range/Speed Bonus");
        SECONDARY_MULTIPLIER_LABELS.put("lootingQuantityMultiplierPerLevel", "Quantity Bonus");
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

    public EnchantConfigPage(@Nonnull PlayerRef playerRef, @Nonnull ConfigManager configManager) {
        super(playerRef, CustomPageLifetime.CanDismiss, EnchantConfigPageEventData.CODEC);
        this.configManager = configManager;
        // Create a working copy of the config for editing
        this.workingConfig = cloneConfig(configManager.getConfig());
    }
    
    private EnchantingConfig cloneConfig(EnchantingConfig original) {
        EnchantingConfig copy = new EnchantingConfig();
        copy.configVersion = original.configVersion;
        copy.maxEnchantmentsPerItem = original.maxEnchantmentsPerItem;
        copy.showEnchantmentBanner = original.showEnchantmentBanner;
        copy.enableEnchantmentGlow = original.enableEnchantmentGlow;
        copy.sharpnessDamageMultiplierPerLevel = original.sharpnessDamageMultiplierPerLevel;
        copy.lifeLeechPercentage = original.lifeLeechPercentage;
        copy.durabilityReductionPerLevel = original.durabilityReductionPerLevel;
        copy.dexterityStaminaReductionPerLevel = original.dexterityStaminaReductionPerLevel;
        copy.protectionDamageReductionPerLevel = original.protectionDamageReductionPerLevel;
        copy.efficiencyMiningSpeedPerLevel = original.efficiencyMiningSpeedPerLevel;
        copy.fortuneRollChancePerLevel = original.fortuneRollChancePerLevel;
        copy.strengthDamageMultiplierPerLevel = original.strengthDamageMultiplierPerLevel;
        copy.strengthRangeMultiplierPerLevel = original.strengthRangeMultiplierPerLevel;
        copy.eaglesEyeDistanceBonusPerLevel = original.eaglesEyeDistanceBonusPerLevel;
        copy.lootingChanceMultiplierPerLevel = original.lootingChanceMultiplierPerLevel;
        copy.lootingQuantityMultiplierPerLevel = original.lootingQuantityMultiplierPerLevel;
        copy.featherFallingReductionPerLevel = original.featherFallingReductionPerLevel;
        copy.waterBreathingReductionPerLevel = original.waterBreathingReductionPerLevel;
        copy.knockbackStrengthPerLevel = original.knockbackStrengthPerLevel;
        copy.reflectionDamagePercentagePerLevel = original.reflectionDamagePercentagePerLevel;
        copy.absorptionHealPercentagePerLevel = original.absorptionHealPercentagePerLevel;
        copy.fastSwimSpeedBonusPerLevel = original.fastSwimSpeedBonusPerLevel;
        copy.thriftRestoreAmountPerLevel = original.thriftRestoreAmountPerLevel;
        copy.elementalHeartSaveChancePerLevel = original.elementalHeartSaveChancePerLevel;
        copy.returnEnchantmentOnCleanse = original.returnEnchantmentOnCleanse;
        copy.enchantingTableCraftingTier = original.enchantingTableCraftingTier;
        
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
    }
    
    private void buildGeneralTab(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        int index = 0;
        
        // Max Enchantments Per Item - using NumberField for direct input
        int maxEnchants = workingConfig.maxEnchantmentsPerItem;
        int maxEnchantsStep = 1;  // Integer value, step by 1
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigItem.ui");
        commandBuilder.set("#ContentArea[" + index + "] #SettingName.TextSpans", Message.raw("Max Enchantments Per Item"));
        commandBuilder.set("#ContentArea[" + index + "] #SettingInput.Value", (double) maxEnchants);
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
        commandBuilder.set("#ContentArea[" + index + "] #SettingName.TextSpans", Message.raw("Show Enchantment Banner"));
        commandBuilder.set("#ContentArea[" + index + "] #ToggleButton.TextSpans", 
            Message.raw(workingConfig.showEnchantmentBanner ? "Enabled" : "Disabled"));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #ToggleButton",
            EventData.of("SettingValue", "showEnchantmentBanner:" + !workingConfig.showEnchantmentBanner));
        index++;
        
        // Enable Enchantment Glow
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigToggle.ui");
        commandBuilder.set("#ContentArea[" + index + "] #SettingName.TextSpans", Message.raw("Enable Enchantment Glow"));
        commandBuilder.set("#ContentArea[" + index + "] #ToggleButton.TextSpans", 
            Message.raw(workingConfig.enableEnchantmentGlow ? "Enabled" : "Disabled"));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #ToggleButton",
            EventData.of("SettingValue", "enableEnchantmentGlow:" + !workingConfig.enableEnchantmentGlow));
        index++;
        
        // Enchanting Table Crafting Tier
        int craftingTier = workingConfig.enchantingTableCraftingTier;
        int craftingTierStep = 1;  // Integer value, step by 1
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigItem.ui");
        commandBuilder.set("#ContentArea[" + index + "] #SettingName.TextSpans", Message.raw("Enchanting Table Crafting Tier"));
        commandBuilder.set("#ContentArea[" + index + "] #SettingInput.Value", (double) craftingTier);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #ResetBtn",
            EventData.of("ResetValue", "enchantingTableCraftingTier"));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #DecreaseBtn",
            EventData.of("SettingValue", "enchantingTableCraftingTier:" + Math.max(1, craftingTier - craftingTierStep)));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #IncreaseBtn",
            EventData.of("SettingValue", "enchantingTableCraftingTier:" + (craftingTier + craftingTierStep)));
        eventBuilder.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ContentArea[" + index + "] #SettingInput",
            EventData.of("SettingValue", "enchantingTableCraftingTier").append("@InputValue", "#ContentArea[" + index + "] #SettingInput.Value"), false);
        index++;
        
        // Return Enchantment On Cleanse toggle
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigToggle.ui");
        commandBuilder.set("#ContentArea[" + index + "] #SettingName.TextSpans", Message.raw("Return Enchantment On Cleanse"));
        commandBuilder.set("#ContentArea[" + index + "] #ToggleButton.TextSpans", 
            Message.raw(workingConfig.returnEnchantmentOnCleanse ? "Enabled" : "Disabled"));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #ToggleButton",
            EventData.of("SettingValue", "returnEnchantmentOnCleanse:" + !workingConfig.returnEnchantmentOnCleanse));
        index++;
        
        // Edit Table Recipe button
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigRecipeButton.ui");
        commandBuilder.set("#ContentArea[" + index + "] #RecipeButtonLabel.TextSpans", Message.raw("Enchanting Table Recipe"));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #EditRecipeBtn",
            EventData.of("EditRecipeType", "table"));
        index++;
        
        // Edit Upgrade 1 button
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigRecipeButton.ui");
        commandBuilder.set("#ContentArea[" + index + "] #RecipeButtonLabel.TextSpans", Message.raw("Upgrade 1 Cost (Tier 1 -> 2)"));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #EditRecipeBtn",
            EventData.of("EditRecipeType", "Upgrade_1"));
        index++;
        
        // Edit Upgrade 2 button
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigRecipeButton.ui");
        commandBuilder.set("#ContentArea[" + index + "] #RecipeButtonLabel.TextSpans", Message.raw("Upgrade 2 Cost (Tier 2 -> 3)"));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #EditRecipeBtn",
            EventData.of("EditRecipeType", "Upgrade_2"));
        index++;
        
        // Edit Upgrade 3 button
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigRecipeButton.ui");
        commandBuilder.set("#ContentArea[" + index + "] #RecipeButtonLabel.TextSpans", Message.raw("Upgrade 3 Cost (Tier 3 -> 4)"));
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
        String title = getEditingRecipeTitle();
        commandBuilder.set("#ContentArea[1].TextSpans", Message.raw(title + " - Ingredients"));
        
        int index = 2;
        int ingredientDataIndex = 0;
        
        for (EnchantingConfig.ConfigIngredient ingredient : ingredients) {
            if (ingredient.item != null) {
                int currentAmount = ingredient.amount != null ? ingredient.amount : 0;
                final int dataIdx = ingredientDataIndex;
                
                commandBuilder.append("#ContentArea", "Pages/EnchantConfigIngredient.ui");
                commandBuilder.set("#ContentArea[" + index + "] #IngredientIcon.ItemId", ingredient.item);
                commandBuilder.set("#ContentArea[" + index + "] #IngredientName.TextSpans", Message.raw(formatItemName(ingredient.item)));
                commandBuilder.set("#ContentArea[" + index + "] #AmountInput.Value", (double) currentAmount);
                
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
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #AddIngredientBtn",
                EventData.of("SettingKey", "AddIngredient"));
            index++;
            
            // Reset Recipe button
            commandBuilder.append("#ContentArea", "Pages/EnchantConfigResetRecipe.ui");
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
    
    private String getEditingRecipeTitle() {
        if (editingRecipeType == null) return "Recipe";
        return switch (editingRecipeType) {
            case "table" -> "Enchanting Table Recipe";
            case "Upgrade_1" -> "Upgrade 1 Cost";
            case "Upgrade_2" -> "Upgrade 2 Cost";
            case "Upgrade_3" -> "Upgrade 3 Cost";
            default -> "Recipe";
        };
    }
    
    private void buildEnchantmentsTab(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        int index = 0;
        
        for (EnchantmentType type : EnchantmentType.values()) {
            commandBuilder.append("#ContentArea", "Pages/EnchantConfigEnchantment.ui");
            
            // Enchantment name
            commandBuilder.set("#ContentArea[" + index + "] #EnchantName.TextSpans", Message.translation(type.getNameKey()));
            
            // Multiplier value (if this enchantment has one)
            String multiplierField = ENCHANTMENT_MULTIPLIERS.get(type);
            if (multiplierField != null) {
                double value = getMultiplierValue(multiplierField);
                double step = calculateStep(value);
                commandBuilder.set("#ContentArea[" + index + "] #MultiplierInput.Value", value);
                commandBuilder.set("#ContentArea[" + index + "] #MultiplierSection.Visible", true);
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
                Message.raw(isDisabled ? "Disabled" : "Enabled"));
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #EnableToggle",
                EventData.of("ToggleEnchantment", type.getId()));
            
            index++;
            
            // Secondary multiplier row (e.g., Strength range bonus, Looting quantity bonus)
            String secondaryField = ENCHANTMENT_SECONDARY_MULTIPLIERS.get(type);
            if (secondaryField != null) {
                String label = SECONDARY_MULTIPLIER_LABELS.getOrDefault(secondaryField, secondaryField);
                double secValue = getMultiplierValue(secondaryField);
                double secStep = calculateStep(secValue);
                
                commandBuilder.append("#ContentArea", "Pages/EnchantConfigItem.ui");
                commandBuilder.set("#ContentArea[" + index + "] #SettingName.TextSpans", Message.raw("  " + label));
                commandBuilder.set("#ContentArea[" + index + "] #SettingInput.Value", secValue);
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
        for (String recipeName : workingConfig.scrollRecipes.keySet()) {
            List<EnchantingConfig.ConfigIngredient> ingredients = workingConfig.scrollRecipes.get(recipeName);
            
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
            commandBuilder.set("#ContentArea[" + index + "] #RecipeName.TextSpans", Message.raw(formatRecipeName(recipeName)));
            commandBuilder.set("#ContentArea[" + index + "] #TierInput.Value", (double) tier);
            
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
        String title;
        String resetTarget;
        boolean isScrollRecipe = selectedRecipe != null && editingRecipeType == null;
        
        if (isScrollRecipe) {
            title = formatRecipeName(selectedRecipe) + " - Ingredients";
            resetTarget = selectedRecipe;
        } else {
            title = getEditingRecipeTitle() + " - Ingredients";
            resetTarget = editingRecipeType;
        }
        
        // Back button
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigBackButton.ui");
        if (isScrollRecipe) {
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[0] #BackBtn",
                EventData.of("BackToList", "true"));
        } else {
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[0] #BackBtn",
                EventData.of("BackFromRecipeEdit", "true"));
        }
        
        // Recipe title
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigRecipeTitle.ui");
        commandBuilder.set("#ContentArea[1].TextSpans", Message.raw(title));
        
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
                commandBuilder.set("#ContentArea[" + index + "] #IngredientName.TextSpans", Message.raw(displayName));
                commandBuilder.set("#ContentArea[" + index + "] #AmountInput.Value", (double) currentAmount);
                
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
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ContentArea[" + index + "] #AddIngredientBtn",
                EventData.of("SettingKey", "AddIngredient"));
            index++;
            
            // Reset Recipe button
            commandBuilder.append("#ContentArea", "Pages/EnchantConfigResetRecipe.ui");
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
        // Append search overlay to content area
        commandBuilder.append("#ContentArea", "Pages/EnchantConfigItemSearch.ui");
        
        // Restore the current search query value so it doesn't get cleared
        if (currentSearchQuery != null && !currentSearchQuery.isEmpty()) {
            commandBuilder.set("#ItemSearchInput.Value", currentSearchQuery);
        }
        
        // Cancel button
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#CancelSearchBtn",
            EventData.of("CancelSearch", "true"));
        
        // Search input - listen for text changes using .Value property
        eventBuilder.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ItemSearchInput",
            EventData.of("SearchInput", "").append("@SearchInput", "#ItemSearchInput.Value"), false);
        
        // Get filtered items and display them
        List<Item> filteredItems = getFilteredItems(currentSearchQuery);
        
        int itemIndex = 0;
        int maxResults = 50;  // Limit results to avoid performance issues
        
        for (Item item : filteredItems) {
            if (itemIndex >= maxResults) break;
            
            String itemId = item.getId();
            String displayName = getItemDisplayName(item);
            
            commandBuilder.append("#ItemSearchResults", "Pages/EnchantConfigSearchItem.ui");
            commandBuilder.set("#ItemSearchResults[" + itemIndex + "] #ItemIcon.ItemId", itemId);
            commandBuilder.set("#ItemSearchResults[" + itemIndex + "] #ItemDisplayName.TextSpans", Message.raw(displayName));
            
            // Clicking the item selects it
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ItemSearchResults[" + itemIndex + "] #ItemSelectBtn",
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
        commandBuilder.clear("#ItemSearchResults");
        
        // Get filtered items and display them
        List<Item> filteredItems = getFilteredItems(currentSearchQuery);
        
        int itemIndex = 0;
        int maxResults = 50;
        
        for (Item item : filteredItems) {
            if (itemIndex >= maxResults) break;
            
            String itemId = item.getId();
            String displayName = getItemDisplayName(item);
            
            commandBuilder.append("#ItemSearchResults", "Pages/EnchantConfigSearchItem.ui");
            commandBuilder.set("#ItemSearchResults[" + itemIndex + "] #ItemIcon.ItemId", itemId);
            commandBuilder.set("#ItemSearchResults[" + itemIndex + "] #ItemDisplayName.TextSpans", Message.raw(displayName));
            
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ItemSearchResults[" + itemIndex + "] #ItemSelectBtn",
                EventData.of("SelectItem", itemId));
            
            itemIndex++;
        }
    }
    
    /**
     * Adds a new empty ingredient to the current recipe.
     */
    private void addNewIngredient() {
        List<EnchantingConfig.ConfigIngredient> ingredients = getCurrentEditingIngredients();
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
            // Get translated message from I18n
            String translated = I18nModule.get().getMessage("en-US", translationKey);
            if (translated != null && !translated.equals(translationKey)) {
                return translated;
            }
        }
        // Fall back to formatted ID
        return formatItemName(item.getId());
    }
    
    /**
     * Gets the ingredient list currently being edited (scroll, table, or upgrade).
     */
    private List<EnchantingConfig.ConfigIngredient> getCurrentEditingIngredients() {
        // First check table/upgrade editing (priority)
        if (editingRecipeType != null) {
            return getEditingIngredients();
        }
        // Fall back to scroll recipe editing
        if (selectedRecipe != null) {
            return workingConfig.scrollRecipes.get(selectedRecipe);
        }
        return null;
    }
    
    /**
     * Updates an ingredient's item ID at the specified index.
     */
    private void updateIngredient(int ingredientIndex, String newItemId) {
        List<EnchantingConfig.ConfigIngredient> ingredients = getCurrentEditingIngredients();
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
        List<EnchantingConfig.ConfigIngredient> ingredients = getCurrentEditingIngredients();
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
    
    private String formatRecipeName(String name) {
        // Convert Scroll_Sharpness_I to Sharpness I
        String formatted = name.replace("Scroll_", "").replace("_", " ");
        // Explicit rename for FastSwim -> Swift Swim
        return formatted.replace("FastSwim", "Swift Swim");
    }
    
    private double getMultiplierValue(String fieldName) {
        return switch (fieldName) {
            case "sharpnessDamageMultiplierPerLevel" -> workingConfig.sharpnessDamageMultiplierPerLevel;
            case "lifeLeechPercentage" -> workingConfig.lifeLeechPercentage;
            case "durabilityReductionPerLevel" -> workingConfig.durabilityReductionPerLevel;
            case "dexterityStaminaReductionPerLevel" -> workingConfig.dexterityStaminaReductionPerLevel;
            case "protectionDamageReductionPerLevel" -> workingConfig.protectionDamageReductionPerLevel;
            case "efficiencyMiningSpeedPerLevel" -> workingConfig.efficiencyMiningSpeedPerLevel;
            case "fortuneRollChancePerLevel" -> workingConfig.fortuneRollChancePerLevel;
            case "strengthDamageMultiplierPerLevel" -> workingConfig.strengthDamageMultiplierPerLevel;
            case "strengthRangeMultiplierPerLevel" -> workingConfig.strengthRangeMultiplierPerLevel;
            case "eaglesEyeDistanceBonusPerLevel" -> workingConfig.eaglesEyeDistanceBonusPerLevel;
            case "lootingChanceMultiplierPerLevel" -> workingConfig.lootingChanceMultiplierPerLevel;
            case "lootingQuantityMultiplierPerLevel" -> workingConfig.lootingQuantityMultiplierPerLevel;
            case "featherFallingReductionPerLevel" -> workingConfig.featherFallingReductionPerLevel;
            case "waterBreathingReductionPerLevel" -> workingConfig.waterBreathingReductionPerLevel;
            case "knockbackStrengthPerLevel" -> workingConfig.knockbackStrengthPerLevel;
            case "reflectionDamagePercentagePerLevel" -> workingConfig.reflectionDamagePercentagePerLevel;
            case "thriftRestoreAmountPerLevel" -> workingConfig.thriftRestoreAmountPerLevel;
            case "absorptionHealPercentagePerLevel" -> workingConfig.absorptionHealPercentagePerLevel;
            case "fastSwimSpeedBonusPerLevel" -> workingConfig.fastSwimSpeedBonusPerLevel;
            case "elementalHeartSaveChancePerLevel" -> workingConfig.elementalHeartSaveChancePerLevel;
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
            markUnsavedChange(); // Still mark as unsaved because user touched it, even if invalid state
            return;
        }
        
        try {
            switch (key) {
                case "maxEnchantmentsPerItem" -> workingConfig.maxEnchantmentsPerItem = Math.max(1, Integer.parseInt(value));
                case "showEnchantmentBanner" -> workingConfig.showEnchantmentBanner = Boolean.parseBoolean(value);
                case "enableEnchantmentGlow" -> workingConfig.enableEnchantmentGlow = Boolean.parseBoolean(value);
                case "returnEnchantmentOnCleanse" -> workingConfig.returnEnchantmentOnCleanse = Boolean.parseBoolean(value);
                case "enchantingTableCraftingTier" -> workingConfig.enchantingTableCraftingTier = Math.max(1, Integer.parseInt(value));
                case "sharpnessDamageMultiplierPerLevel" -> workingConfig.sharpnessDamageMultiplierPerLevel = Double.parseDouble(value);
                case "lifeLeechPercentage" -> workingConfig.lifeLeechPercentage = Double.parseDouble(value);
                case "durabilityReductionPerLevel" -> workingConfig.durabilityReductionPerLevel = Double.parseDouble(value);
                case "dexterityStaminaReductionPerLevel" -> workingConfig.dexterityStaminaReductionPerLevel = Double.parseDouble(value);
                case "protectionDamageReductionPerLevel" -> workingConfig.protectionDamageReductionPerLevel = Double.parseDouble(value);
                case "efficiencyMiningSpeedPerLevel" -> workingConfig.efficiencyMiningSpeedPerLevel = Double.parseDouble(value);
                case "fortuneRollChancePerLevel" -> workingConfig.fortuneRollChancePerLevel = Double.parseDouble(value);
                case "strengthDamageMultiplierPerLevel" -> workingConfig.strengthDamageMultiplierPerLevel = Double.parseDouble(value);
                case "strengthRangeMultiplierPerLevel" -> workingConfig.strengthRangeMultiplierPerLevel = Double.parseDouble(value);
                case "eaglesEyeDistanceBonusPerLevel" -> workingConfig.eaglesEyeDistanceBonusPerLevel = Double.parseDouble(value);
                case "lootingChanceMultiplierPerLevel" -> workingConfig.lootingChanceMultiplierPerLevel = Double.parseDouble(value);
                case "lootingQuantityMultiplierPerLevel" -> workingConfig.lootingQuantityMultiplierPerLevel = Double.parseDouble(value);
                case "featherFallingReductionPerLevel" -> workingConfig.featherFallingReductionPerLevel = Double.parseDouble(value);
                case "waterBreathingReductionPerLevel" -> workingConfig.waterBreathingReductionPerLevel = Double.parseDouble(value);
                case "knockbackStrengthPerLevel" -> workingConfig.knockbackStrengthPerLevel = Double.parseDouble(value);
                case "reflectionDamagePercentagePerLevel" -> workingConfig.reflectionDamagePercentagePerLevel = Double.parseDouble(value);
                case "thriftRestoreAmountPerLevel" -> workingConfig.thriftRestoreAmountPerLevel = Double.parseDouble(value);
                case "absorptionHealPercentagePerLevel" -> workingConfig.absorptionHealPercentagePerLevel = Double.parseDouble(value);
                case "fastSwimSpeedBonusPerLevel" -> workingConfig.fastSwimSpeedBonusPerLevel = Double.parseDouble(value);
                case "elementalHeartSaveChancePerLevel" -> workingConfig.elementalHeartSaveChancePerLevel = Double.parseDouble(value);
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
        return switch (key) {
            case "maxEnchantmentsPerItem" -> String.valueOf(DEFAULT_CONFIG.maxEnchantmentsPerItem);
            case "enchantingTableCraftingTier" -> String.valueOf(DEFAULT_CONFIG.enchantingTableCraftingTier);
            case "sharpnessDamageMultiplierPerLevel" -> String.valueOf(DEFAULT_CONFIG.sharpnessDamageMultiplierPerLevel);
            case "lifeLeechPercentage" -> String.valueOf(DEFAULT_CONFIG.lifeLeechPercentage);
            case "durabilityReductionPerLevel" -> String.valueOf(DEFAULT_CONFIG.durabilityReductionPerLevel);
            case "dexterityStaminaReductionPerLevel" -> String.valueOf(DEFAULT_CONFIG.dexterityStaminaReductionPerLevel);
            case "protectionDamageReductionPerLevel" -> String.valueOf(DEFAULT_CONFIG.protectionDamageReductionPerLevel);
            case "efficiencyMiningSpeedPerLevel" -> String.valueOf(DEFAULT_CONFIG.efficiencyMiningSpeedPerLevel);
            case "fortuneRollChancePerLevel" -> String.valueOf(DEFAULT_CONFIG.fortuneRollChancePerLevel);
            case "strengthDamageMultiplierPerLevel" -> String.valueOf(DEFAULT_CONFIG.strengthDamageMultiplierPerLevel);
            case "strengthRangeMultiplierPerLevel" -> String.valueOf(DEFAULT_CONFIG.strengthRangeMultiplierPerLevel);
            case "eaglesEyeDistanceBonusPerLevel" -> String.valueOf(DEFAULT_CONFIG.eaglesEyeDistanceBonusPerLevel);
            case "lootingChanceMultiplierPerLevel" -> String.valueOf(DEFAULT_CONFIG.lootingChanceMultiplierPerLevel);
            case "lootingQuantityMultiplierPerLevel" -> String.valueOf(DEFAULT_CONFIG.lootingQuantityMultiplierPerLevel);
            case "featherFallingReductionPerLevel" -> String.valueOf(DEFAULT_CONFIG.featherFallingReductionPerLevel);
            case "waterBreathingReductionPerLevel" -> String.valueOf(DEFAULT_CONFIG.waterBreathingReductionPerLevel);
            case "knockbackStrengthPerLevel" -> String.valueOf(DEFAULT_CONFIG.knockbackStrengthPerLevel);
            case "reflectionDamagePercentagePerLevel" -> String.valueOf(DEFAULT_CONFIG.reflectionDamagePercentagePerLevel);
            case "thriftRestoreAmountPerLevel" -> String.valueOf(DEFAULT_CONFIG.thriftRestoreAmountPerLevel);
            case "absorptionHealPercentagePerLevel" -> String.valueOf(DEFAULT_CONFIG.absorptionHealPercentagePerLevel);
            case "fastSwimSpeedBonusPerLevel" -> String.valueOf(DEFAULT_CONFIG.fastSwimSpeedBonusPerLevel);
            case "elementalHeartSaveChancePerLevel" -> String.valueOf(DEFAULT_CONFIG.elementalHeartSaveChancePerLevel);
            case "returnEnchantmentOnCleanse" -> String.valueOf(DEFAULT_CONFIG.returnEnchantmentOnCleanse);
            default -> null;
        };
    }
    
    private void saveConfig(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        // Copy working config to actual config
        EnchantingConfig actualConfig = configManager.getConfig();
        actualConfig.maxEnchantmentsPerItem = workingConfig.maxEnchantmentsPerItem;
        actualConfig.showEnchantmentBanner = workingConfig.showEnchantmentBanner;
        actualConfig.enableEnchantmentGlow = workingConfig.enableEnchantmentGlow;
        actualConfig.enchantingTableCraftingTier = workingConfig.enchantingTableCraftingTier;
        actualConfig.sharpnessDamageMultiplierPerLevel = workingConfig.sharpnessDamageMultiplierPerLevel;
        actualConfig.lifeLeechPercentage = workingConfig.lifeLeechPercentage;
        actualConfig.durabilityReductionPerLevel = workingConfig.durabilityReductionPerLevel;
        actualConfig.dexterityStaminaReductionPerLevel = workingConfig.dexterityStaminaReductionPerLevel;
        actualConfig.protectionDamageReductionPerLevel = workingConfig.protectionDamageReductionPerLevel;
        actualConfig.efficiencyMiningSpeedPerLevel = workingConfig.efficiencyMiningSpeedPerLevel;
        actualConfig.fortuneRollChancePerLevel = workingConfig.fortuneRollChancePerLevel;
        actualConfig.strengthDamageMultiplierPerLevel = workingConfig.strengthDamageMultiplierPerLevel;
        actualConfig.strengthRangeMultiplierPerLevel = workingConfig.strengthRangeMultiplierPerLevel;
        actualConfig.eaglesEyeDistanceBonusPerLevel = workingConfig.eaglesEyeDistanceBonusPerLevel;
        actualConfig.lootingChanceMultiplierPerLevel = workingConfig.lootingChanceMultiplierPerLevel;
        actualConfig.lootingQuantityMultiplierPerLevel = workingConfig.lootingQuantityMultiplierPerLevel;
        actualConfig.featherFallingReductionPerLevel = workingConfig.featherFallingReductionPerLevel;
        actualConfig.waterBreathingReductionPerLevel = workingConfig.waterBreathingReductionPerLevel;
        actualConfig.knockbackStrengthPerLevel = workingConfig.knockbackStrengthPerLevel;
        actualConfig.reflectionDamagePercentagePerLevel = workingConfig.reflectionDamagePercentagePerLevel;
        actualConfig.thriftRestoreAmountPerLevel = workingConfig.thriftRestoreAmountPerLevel;
        actualConfig.absorptionHealPercentagePerLevel = workingConfig.absorptionHealPercentagePerLevel;
        actualConfig.fastSwimSpeedBonusPerLevel = workingConfig.fastSwimSpeedBonusPerLevel;
        actualConfig.elementalHeartSaveChancePerLevel = workingConfig.elementalHeartSaveChancePerLevel;
        actualConfig.returnEnchantmentOnCleanse = workingConfig.returnEnchantmentOnCleanse;
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
        workingConfig.showEnchantmentBanner = defaults.showEnchantmentBanner;
        workingConfig.enableEnchantmentGlow = defaults.enableEnchantmentGlow;
        workingConfig.enchantingTableCraftingTier = defaults.enchantingTableCraftingTier;
        workingConfig.sharpnessDamageMultiplierPerLevel = defaults.sharpnessDamageMultiplierPerLevel;
        workingConfig.lifeLeechPercentage = defaults.lifeLeechPercentage;
        workingConfig.durabilityReductionPerLevel = defaults.durabilityReductionPerLevel;
        workingConfig.dexterityStaminaReductionPerLevel = defaults.dexterityStaminaReductionPerLevel;
        workingConfig.protectionDamageReductionPerLevel = defaults.protectionDamageReductionPerLevel;
        workingConfig.efficiencyMiningSpeedPerLevel = defaults.efficiencyMiningSpeedPerLevel;
        workingConfig.fortuneRollChancePerLevel = defaults.fortuneRollChancePerLevel;
        workingConfig.strengthDamageMultiplierPerLevel = defaults.strengthDamageMultiplierPerLevel;
        workingConfig.strengthRangeMultiplierPerLevel = defaults.strengthRangeMultiplierPerLevel;
        workingConfig.eaglesEyeDistanceBonusPerLevel = defaults.eaglesEyeDistanceBonusPerLevel;
        workingConfig.lootingChanceMultiplierPerLevel = defaults.lootingChanceMultiplierPerLevel;
        workingConfig.lootingQuantityMultiplierPerLevel = defaults.lootingQuantityMultiplierPerLevel;
        workingConfig.featherFallingReductionPerLevel = defaults.featherFallingReductionPerLevel;
        workingConfig.waterBreathingReductionPerLevel = defaults.waterBreathingReductionPerLevel;
        workingConfig.knockbackStrengthPerLevel = defaults.knockbackStrengthPerLevel;
        workingConfig.reflectionDamagePercentagePerLevel = defaults.reflectionDamagePercentagePerLevel;
        workingConfig.thriftRestoreAmountPerLevel = defaults.thriftRestoreAmountPerLevel;
        workingConfig.absorptionHealPercentagePerLevel = defaults.absorptionHealPercentagePerLevel;
        workingConfig.fastSwimSpeedBonusPerLevel = defaults.fastSwimSpeedBonusPerLevel;
        workingConfig.elementalHeartSaveChancePerLevel = defaults.elementalHeartSaveChancePerLevel;
        workingConfig.returnEnchantmentOnCleanse = defaults.returnEnchantmentOnCleanse;
        
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
        List<EnchantingConfig.ConfigIngredient> ingredients = getCurrentEditingIngredients();
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
            commandBuilder.set("#UnsavedIndicator.TextSpans", Message.raw("[!] Unsaved Changes"));
        }
        
        // Update Save Feedback
        commandBuilder.set("#SaveFeedback.Visible", showSaveFeedback);
        if (showSaveFeedback) {
            commandBuilder.set("#SaveFeedback.TextSpans", Message.raw("Saved! Most changes require a restart."));
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
