package org.herolias.plugin.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * Event data for the EnchantConfigPage UI interactions.
 * Handles tab switching, value changes, toggles, and save/cancel actions.
 */
public class EnchantConfigPageEventData {

    // Event keys
    static final String KEY_TAB_SWITCH = "TabSwitch";
    static final String KEY_SETTING_KEY = "SettingKey";
    static final String KEY_SETTING_VALUE = "SettingValue";
    static final String KEY_TOGGLE_ENCHANTMENT = "ToggleEnchantment";
    static final String KEY_SAVE_CONFIG = "SaveConfig";
    static final String KEY_CANCEL_CONFIG = "CancelConfig";
    static final String KEY_SELECT_RECIPE = "SelectRecipe";
    static final String KEY_BACK_TO_LIST = "BackToList";
    static final String KEY_INGREDIENT_INDEX = "IngredientIndex";
    static final String KEY_INGREDIENT_VALUE = "IngredientValue";
    static final String KEY_TIER_VALUE = "TierValue";
    static final String KEY_RESET_VALUE = "ResetValue";

    // Item search event keys
    static final String KEY_OPEN_SEARCH = "OpenSearch";
    static final String KEY_SEARCH_QUERY = "SearchQuery";
    static final String KEY_SELECT_ITEM = "SelectItem";
    static final String KEY_CANCEL_SEARCH = "CancelSearch";
    static final String KEY_UPDATE_AMOUNT = "UpdateAmount";

    // Table/Upgrade recipe editing
    static final String KEY_EDIT_RECIPE_TYPE = "EditRecipeType";
    static final String KEY_BACK_FROM_RECIPE_EDIT = "BackFromRecipeEdit";
    static final String KEY_RESET_RECIPE = "ResetRecipe";

    // Remove and reorder ingredients
    static final String KEY_REMOVE_INGREDIENT = "RemoveIngredient";
    static final String KEY_MOVE_INGREDIENT_UP = "MoveIngredientUp";
    static final String KEY_MOVE_INGREDIENT_DOWN = "MoveIngredientDown";

    // Global reset
    static final String KEY_RESET_ALL_CONFIG = "ResetAllConfig";
    static final String KEY_CONFIRM_RESET_ALL = "ConfirmResetAll";

    static final String KEY_INPUT_VALUE = "@InputValue";
    static final String KEY_SEARCH_INPUT = "@SearchInput";

    public static final BuilderCodec<EnchantConfigPageEventData> CODEC = BuilderCodec.builder(
            EnchantConfigPageEventData.class,
            EnchantConfigPageEventData::new)
            .addField(new KeyedCodec<>(KEY_TAB_SWITCH, Codec.STRING),
                    (entry, s) -> entry.tabSwitch = s, entry -> entry.tabSwitch)
            .addField(new KeyedCodec<>(KEY_SETTING_KEY, Codec.STRING),
                    (entry, s) -> entry.settingKey = s, entry -> entry.settingKey)
            .addField(new KeyedCodec<>(KEY_SETTING_VALUE, Codec.STRING),
                    (entry, s) -> entry.settingValue = s, entry -> entry.settingValue)
            .addField(new KeyedCodec<>(KEY_INPUT_VALUE, Codec.DOUBLE),
                    (entry, d) -> entry.inputValue = d, entry -> entry.inputValue)
            .addField(new KeyedCodec<>(KEY_TOGGLE_ENCHANTMENT, Codec.STRING),
                    (entry, s) -> entry.toggleEnchantment = s, entry -> entry.toggleEnchantment)
            .addField(new KeyedCodec<>(KEY_SAVE_CONFIG, Codec.STRING),
                    (entry, s) -> entry.saveConfig = s, entry -> entry.saveConfig)
            .addField(new KeyedCodec<>(KEY_CANCEL_CONFIG, Codec.STRING),
                    (entry, s) -> entry.cancelConfig = s, entry -> entry.cancelConfig)
            .addField(new KeyedCodec<>(KEY_SELECT_RECIPE, Codec.STRING),
                    (entry, s) -> entry.selectRecipe = s, entry -> entry.selectRecipe)
            .addField(new KeyedCodec<>(KEY_BACK_TO_LIST, Codec.STRING),
                    (entry, s) -> entry.backToList = s, entry -> entry.backToList)
            .addField(new KeyedCodec<>(KEY_INGREDIENT_INDEX, Codec.STRING),
                    (entry, s) -> entry.ingredientIndex = s, entry -> entry.ingredientIndex)
            .addField(new KeyedCodec<>(KEY_INGREDIENT_VALUE, Codec.STRING),
                    (entry, s) -> entry.ingredientValue = s, entry -> entry.ingredientValue)
            .addField(new KeyedCodec<>(KEY_TIER_VALUE, Codec.STRING),
                    (entry, s) -> entry.tierValue = s, entry -> entry.tierValue)
            .addField(new KeyedCodec<>(KEY_RESET_VALUE, Codec.STRING),
                    (entry, s) -> entry.resetValue = s, entry -> entry.resetValue)
            // Item search events
            .addField(new KeyedCodec<>(KEY_OPEN_SEARCH, Codec.STRING),
                    (entry, s) -> entry.openSearch = s, entry -> entry.openSearch)
            .addField(new KeyedCodec<>(KEY_SEARCH_QUERY, Codec.STRING),
                    (entry, s) -> entry.searchQuery = s, entry -> entry.searchQuery)
            .addField(new KeyedCodec<>(KEY_SEARCH_INPUT, Codec.STRING),
                    (entry, s) -> entry.searchInput = s, entry -> entry.searchInput)
            .addField(new KeyedCodec<>(KEY_SELECT_ITEM, Codec.STRING),
                    (entry, s) -> entry.selectItem = s, entry -> entry.selectItem)
            .addField(new KeyedCodec<>(KEY_CANCEL_SEARCH, Codec.STRING),
                    (entry, s) -> entry.cancelSearch = s, entry -> entry.cancelSearch)
            .addField(new KeyedCodec<>(KEY_UPDATE_AMOUNT, Codec.STRING),
                    (entry, s) -> entry.updateAmount = s, entry -> entry.updateAmount)
            // Table/Upgrade recipe editing
            .addField(new KeyedCodec<>(KEY_EDIT_RECIPE_TYPE, Codec.STRING),
                    (entry, s) -> entry.editRecipeType = s, entry -> entry.editRecipeType)
            .addField(new KeyedCodec<>(KEY_BACK_FROM_RECIPE_EDIT, Codec.STRING),
                    (entry, s) -> entry.backFromRecipeEdit = s, entry -> entry.backFromRecipeEdit)
            .addField(new KeyedCodec<>(KEY_RESET_RECIPE, Codec.STRING),
                    (entry, s) -> entry.resetRecipe = s, entry -> entry.resetRecipe)
            // Remove and reorder ingredients
            .addField(new KeyedCodec<>(KEY_REMOVE_INGREDIENT, Codec.STRING),
                    (entry, s) -> entry.removeIngredient = s, entry -> entry.removeIngredient)
            .addField(new KeyedCodec<>(KEY_MOVE_INGREDIENT_UP, Codec.STRING),
                    (entry, s) -> entry.moveIngredientUp = s, entry -> entry.moveIngredientUp)
            .addField(new KeyedCodec<>(KEY_MOVE_INGREDIENT_DOWN, Codec.STRING),
                    (entry, s) -> entry.moveIngredientDown = s, entry -> entry.moveIngredientDown)
            // Global reset
            .addField(new KeyedCodec<>(KEY_RESET_ALL_CONFIG, Codec.STRING),
                    (entry, s) -> entry.resetAllConfig = s, entry -> entry.resetAllConfig)
            .addField(new KeyedCodec<>(KEY_CONFIRM_RESET_ALL, Codec.STRING),
                    (entry, s) -> entry.confirmResetAll = s, entry -> entry.confirmResetAll)
            .build();

    // Tab navigation
    String tabSwitch;

    // General/multiplier setting changes
    String settingKey;
    String settingValue;
    Double inputValue; // Direct input from NumberField via ValueChanged

    // Enchantment toggle
    String toggleEnchantment;

    // Actions
    String saveConfig;
    String cancelConfig;

    // Recipe navigation
    String selectRecipe;
    String backToList;

    // Recipe editing
    String ingredientIndex;
    String ingredientValue;
    String tierValue;

    // Reset to default
    String resetValue;

    // Item search
    String openSearch; // Ingredient index to open search for
    String searchQuery; // Search filter text
    String searchInput; // Text field input
    String selectItem; // Selected item ID
    String cancelSearch; // Cancel search action
    String updateAmount; // Format: "index:amount"

    // Table/Upgrade recipe editing
    String editRecipeType; // "table" or "Upgrade_X"
    String backFromRecipeEdit; // Exit recipe edit mode
    String resetRecipe; // Reset recipe to default

    // Remove and reorder ingredients
    String removeIngredient; // Index of ingredient to remove
    String moveIngredientUp; // Index of ingredient to move up
    String moveIngredientDown; // Index of ingredient to move down

    // Global reset
    String resetAllConfig; // Trigger reset all confirmation
    String confirmResetAll; // Confirm reset all

    public EnchantConfigPageEventData() {
        // Default constructor for codec
    }
}
