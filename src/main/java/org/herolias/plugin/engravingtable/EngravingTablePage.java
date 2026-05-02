package org.herolias.plugin.engravingtable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.enchantment.EnchantmentData;
import org.herolias.plugin.enchantment.EnchantmentManager;
import org.herolias.plugin.enchantment.TooltipBridge;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;

public class EngravingTablePage extends InteractiveCustomUIPage<EngravingTablePageEventData> {
    private static final String PAGE_PATH = "Pages/EngravingTablePage.ui";
    private static final String INVENTORY_ENTRY_PATH = "Pages/EngravingTableInventoryEntry.ui";
    private static final String INVENTORY_ROW_UI = "Group { LayoutMode: LeftCenterWrap; Anchor: (Height: 54, Bottom: 2); }";
    private static final long DUPLICATE_SELECTION_WINDOW_NANOS = 100_000_000L;
    private static final int HOTBAR_SLOTS_PER_ROW = 9;
    private static final int INVENTORY_SLOTS_PER_ROW = 18;
    private static final String COLOR_MUTED = "#9d9d9d";
    private static final String COLOR_NEUTRAL = "#d7cfbe";
    private static final String COLOR_SUCCESS = "#88d488";
    private static final String COLOR_ERROR = "#ff8a8a";
    private static final Map<String, Map<String, String>> ASSET_LANGUAGE_CACHE = new ConcurrentHashMap<>();

    private final SimpleEnchanting plugin;
    private final EnchantmentManager enchantmentManager;

    private String inputStateKey = "";
    private String selectedPrimaryKey;
    private String selectedSecondaryKey;
    private String editableName = "";
    private EngravingTableColorOption selectedNameColor = EngravingTableColorOption.DEFAULT_NAME_COLOR;
    private EngravingTableColorOption selectedGlowColor = EngravingTableColorOption.DEFAULT_GLOW_COLOR;
    private String lastInventorySelectionKey;
    private long lastInventorySelectionNanos;

    public EngravingTablePage(
            @Nonnull PlayerRef playerRef,
            @Nonnull SimpleEnchanting plugin,
            @Nonnull EnchantmentManager enchantmentManager) {
        super(playerRef, CustomPageLifetime.CanDismiss, EngravingTablePageEventData.CODEC);
        this.plugin = plugin;
        this.enchantmentManager = enchantmentManager;
    }

    @Override
    public void build(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder commandBuilder,
            @Nonnull UIEventBuilder eventBuilder,
            @Nonnull Store<EntityStore> store) {
        this.syncEditorState(true);
        commandBuilder.append(PAGE_PATH);
        this.updateDynamicState(commandBuilder);
        this.bindEvents(eventBuilder);
    }

    @Override
    public void handleDataEvent(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull EngravingTablePageEventData data) {
        if (data.close != null) {
            this.closePage(ref, store);
            return;
        }
        if (data.take != null) {
            this.takePreview(ref, store);
            return;
        }
        if (data.inventorySelect != null) {
            this.handleInventorySelection(ref, store, data.inventorySelect);
            return;
        }
        if (data.clearPrimary != null) {
            this.selectedPrimaryKey = null;
            this.selectedSecondaryKey = null;
            this.syncEditorState(true);
            this.sendDynamicUpdate();
            return;
        }
        if (data.clearSecondary != null) {
            this.selectedSecondaryKey = null;
            this.syncEditorState(true);
            this.sendDynamicUpdate();
            return;
        }
        if (data.nameColor != null) {
            this.selectedNameColor = EngravingTableColorOption.fromIdOrDefault(data.nameColor,
                    EngravingTableColorOption.DEFAULT_NAME_COLOR);
            this.sendDynamicUpdate();
            return;
        }
        if (data.glowColor != null) {
            this.selectedGlowColor = EngravingTableColorOption.fromIdOrDefault(data.glowColor,
                    EngravingTableColorOption.DEFAULT_GLOW_COLOR);
            this.sendDynamicUpdate();
            return;
        }
        if (data.nameInput != null) {
            this.editableName = data.nameInput;
            this.sendDynamicUpdate();
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
    }

    private void closePage(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            player.getPageManager().setPage(ref, store, Page.None);
        }
    }

    private void syncEditorState(boolean force) {
        String currentStateKey = this.buildInputStateKey();
        if (!force && currentStateKey.equals(this.inputStateKey)) {
            return;
        }
        this.inputStateKey = currentStateKey;

        ItemStack primary = this.getPrimaryInput();
        if (ItemStack.isEmpty(primary)) {
            this.editableName = "";
            this.selectedNameColor = EngravingTableColorOption.DEFAULT_NAME_COLOR;
            this.selectedGlowColor = EngravingTableColorOption.DEFAULT_GLOW_COLOR;
            return;
        }

        EngravingTableCustomizationData customization = EngravingTableCustomizationData.fromItemStack(primary);
        this.editableName = this.resolveCurrentDisplayName(primary, customization);
        this.selectedNameColor = customization.getNameColorOrDefault();
        this.selectedGlowColor = customization.getGlowColorOrDefault();
    }

    private void sendDynamicUpdate() {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        this.updateDynamicState(commandBuilder);
        this.bindEvents(eventBuilder);
        this.sendUpdate(commandBuilder, eventBuilder, false);
    }

    private void updateDynamicState(@Nonnull UICommandBuilder commandBuilder) {
        ItemStack primary = this.getPrimaryInput();
        ItemStack secondary = this.getSecondaryInput();
        ItemStack preview = this.getPreviewResultItem();
        boolean showSecondRow = this.isScrollMode();
        boolean showCustomization = this.isCustomizationMode();
        boolean showGlow = showCustomization && this.canEditGlow(primary);
        boolean canTake = this.canTakePreview();

        commandBuilder.set("#PageTitle.TextSpans", Message.raw("Engraving Table"));
        commandBuilder.set("#WindowHint.Visible", false);

        this.setGridSlot(commandBuilder, "#PrimaryDropGrid", "#InputPlaceholder", primary, "Item/Scroll");
        commandBuilder.set("#ClearPrimaryButton.Visible", !ItemStack.isEmpty(primary));
        commandBuilder.set("#ClearPrimaryButton.TextSpans", Message.raw("Clear"));

        this.setGridSlot(commandBuilder, "#PreviewGrid", "#PreviewPlaceholder", preview, "Result");
        commandBuilder.set("#PreviewTakeButton.Visible", !ItemStack.isEmpty(preview));
        commandBuilder.set("#PreviewTakeButton.Disabled", !canTake);
        commandBuilder.set("#PreviewTakeButton.TextSpans", Message.raw(canTake ? "Take" : "Locked"));

        commandBuilder.set("#MergePlus.Visible", showSecondRow);
        commandBuilder.set("#SecondSlotRow.Visible", showSecondRow);
        this.setGridSlot(commandBuilder, "#SecondaryDropGrid", "#SecondInputPlaceholder",
                secondary,
                "Scroll");
        commandBuilder.set("#ClearSecondaryButton.Visible", showSecondRow && !ItemStack.isEmpty(secondary));
        commandBuilder.set("#ClearSecondaryButton.TextSpans", Message.raw("Clear"));

        commandBuilder.set("#DetailsRow.Visible", showCustomization);
        commandBuilder.set("#NameSection.Visible", showCustomization);
        commandBuilder.set("#NamePreviewValue.TextSpans", this.buildNamePreviewMessage());
        commandBuilder.set("#NameInput.Value", this.editableName);
        commandBuilder.set("#NameHint.TextSpans",
                Message.raw("Name color: 1 matching petal."));
        this.updateColorButtons(commandBuilder, true);

        commandBuilder.set("#GlowSection.Visible", showGlow);
        commandBuilder.set("#GlowPreviewValue.TextSpans", this.buildGlowPreviewMessage());
        commandBuilder.set("#GlowHint.TextSpans",
                Message.raw("Glow color: 1 matching crystal."));
        this.updateColorButtons(commandBuilder, false);

        commandBuilder.set("#StatusTextGroup.Visible", !ItemStack.isEmpty(primary));
        commandBuilder.set("#CostValue.TextSpans", this.buildCostMessage());
        commandBuilder.set("#StatusValue.TextSpans", this.buildStatusMessage());
        commandBuilder.set("#CloseButton.TextSpans", Message.raw("Close"));

        this.updateInventoryGrid(commandBuilder);
    }

    private void bindEvents(@Nonnull UIEventBuilder eventBuilder) {
        ItemStack primary = this.getPrimaryInput();
        ItemStack secondary = this.getSecondaryInput();
        ItemStack preview = this.getPreviewResultItem();
        boolean showCustomization = this.isCustomizationMode();
        boolean showGlow = showCustomization && this.canEditGlow(primary);
        boolean canTake = !ItemStack.isEmpty(preview) && this.canTakePreview();

        if (canTake) {
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#PreviewTakeButton",
                    EventData.of("Take", "true"));
        }

        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                EventData.of("Close", "true"));

        if (!ItemStack.isEmpty(primary)) {
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ClearPrimaryButton",
                    EventData.of("ClearPrimary", "true"));
        }

        if (!ItemStack.isEmpty(secondary)) {
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#ClearSecondaryButton",
                    EventData.of("ClearSecondary", "true"));
        }

        if (showCustomization) {
            eventBuilder.addEventBinding(CustomUIEventBindingType.ValueChanged, "#NameInput",
                    EventData.of("NameInput", "").append("@NameInput", "#NameInput.Value"),
                    false);

            for (EngravingTableColorOption colorOption : EngravingTableColorOption.values()) {
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating,
                        "#NameColor" + colorOption.getAssetSuffix(),
                        EventData.of("NameColor", colorOption.getId()));
            }
        }

        if (showGlow) {
            for (EngravingTableColorOption colorOption : EngravingTableColorOption.values()) {
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating,
                        "#GlowColor" + colorOption.getAssetSuffix(),
                        EventData.of("GlowColor", colorOption.getId()));
            }
        }

        Ref<EntityStore> ref = this.playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Player player = ref.getStore().getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }

        this.bindInventorySlotEvents(eventBuilder, player);
    }

    private void updateColorButtons(@Nonnull UICommandBuilder commandBuilder, boolean nameButtons) {
        EngravingTableColorOption selected = nameButtons ? this.selectedNameColor : this.selectedGlowColor;
        for (EngravingTableColorOption colorOption : EngravingTableColorOption.values()) {
            String selector = (nameButtons ? "#NameColor" : "#GlowColor") + colorOption.getAssetSuffix();
            String label = colorOption == selected
                    ? "[" + colorOption.getDisplayName() + "]"
                    : colorOption.getDisplayName();
            commandBuilder.set(selector + ".TextSpans",
                    Message.raw(label).color(this.getButtonTextColor(colorOption)));
        }
    }

    private void setGridSlot(
            @Nonnull UICommandBuilder commandBuilder,
            @Nonnull String gridSelector,
            @Nonnull String placeholderSelector,
            @Nullable ItemStack itemStack,
            @Nonnull String placeholderText) {
        boolean hasItem = !ItemStack.isEmpty(itemStack);
        commandBuilder.set(gridSelector + ".Slots", new ItemGridSlot[] { this.createGridSlot(itemStack) });
        commandBuilder.set(placeholderSelector + ".Visible", !hasItem);
        commandBuilder.set(placeholderSelector + ".TextSpans", Message.raw(placeholderText).color(COLOR_MUTED));
    }

    @Nonnull
    private ItemGridSlot createGridSlot(@Nullable ItemStack itemStack) {
        if (ItemStack.isEmpty(itemStack)) {
            return new ItemGridSlot();
        }
        ItemStack displayStack = itemStack.getQuantity() > 1 ? itemStack.withQuantity(1) : itemStack;
        if (displayStack == null) {
            displayStack = itemStack;
        }
        if (!this.hasDynamicCustomUiTooltipData(displayStack)) {
            displayStack = displayStack.withMetadata((BsonDocument) null);
        }
        return new ItemGridSlot(displayStack);
    }

    private boolean hasDynamicCustomUiTooltipData(@Nonnull ItemStack itemStack) {
        BsonDocument metadata = itemStack.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        return TooltipBridge.hasDynamicTooltip(itemStack.getItemId(), metadata.toJson(), this.getTooltipLanguage())
                || this.hasSimpleEnchantingTooltipData(metadata);
    }

    private boolean hasSimpleEnchantingTooltipData(@Nonnull BsonDocument metadata) {
        return this.hasEnchantmentTooltipData(metadata) || this.hasEngravingTooltipData(metadata);
    }

    @Nullable
    private String getTooltipLanguage() {
        String configuredLanguage = this.plugin.getUserSettingsManager().getLanguage(this.playerRef.getUuid());
        if (configuredLanguage != null && !configuredLanguage.isBlank()
                && !"default".equalsIgnoreCase(configuredLanguage)) {
            return configuredLanguage;
        }
        return this.playerRef.getLanguage();
    }

    private boolean hasEnchantmentTooltipData(@Nonnull BsonDocument metadata) {
        BsonValue enchantments = metadata.get(EnchantmentData.METADATA_KEY);
        if (enchantments == null || !enchantments.isDocument()) {
            return false;
        }
        try {
            return !EnchantmentData.fromBson(enchantments.asDocument()).isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasEngravingTooltipData(@Nonnull BsonDocument metadata) {
        EngravingTableCustomizationData customization = EngravingTableCustomizationData.fromMetadataDocument(metadata);
        return customization.getCustomName() != null || customization.getNameColor() != null;
    }

    private void updateInventoryGrid(@Nonnull UICommandBuilder commandBuilder) {
        commandBuilder.clear("#HotbarSlots");
        commandBuilder.clear("#StorageSlots");
        commandBuilder.clear("#BackpackSlots");

        Ref<EntityStore> ref = this.playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            commandBuilder.set("#InventoryEmptyLabel.Visible", true);
            return;
        }
        Player player = ref.getStore().getComponent(ref, Player.getComponentType());
        if (player == null) {
            commandBuilder.set("#InventoryEmptyLabel.Visible", true);
            return;
        }

        Inventory inventory = player.getInventory();
        boolean hasHotbar = this.appendInventorySection(commandBuilder, inventory.getHotbar(),
                "#HotbarSection", "#HotbarSlots", "Hotbar", InventoryComponent.HOTBAR_SECTION_ID);
        boolean hasStorage = this.appendInventorySection(commandBuilder, inventory.getStorage(),
                "#StorageSection", "#StorageSlots", "Storage", InventoryComponent.STORAGE_SECTION_ID);
        boolean hasBackpack = this.appendInventorySection(commandBuilder, inventory.getBackpack(),
                "#BackpackSection", "#BackpackSlots", "Backpack", InventoryComponent.BACKPACK_SECTION_ID);

        commandBuilder.set("#InventoryEmptyLabel.Visible", !(hasHotbar || hasStorage || hasBackpack));
    }

    private boolean appendInventorySection(
            @Nonnull UICommandBuilder commandBuilder,
            @Nullable ItemContainer container,
            @Nonnull String sectionSelector,
            @Nonnull String slotsSelector,
            @Nonnull String sectionLabel,
            int sectionId) {
        commandBuilder.set(sectionSelector + ".Visible", container != null);
        if (container == null) {
            return false;
        }

        int slotsPerRow = sectionId == InventoryComponent.HOTBAR_SECTION_ID
                ? HOTBAR_SLOTS_PER_ROW
                : INVENTORY_SLOTS_PER_ROW;
        boolean hasAnyItem = false;
        for (short slot = 0; slot < container.getCapacity(); slot++) {
            int rowIndex = slot / slotsPerRow;
            int slotInRow = slot % slotsPerRow;
            if (slotInRow == 0) {
                commandBuilder.appendInline(slotsSelector, INVENTORY_ROW_UI);
            }

            String rowSelector = slotsSelector + "[" + rowIndex + "]";
            commandBuilder.append(rowSelector, INVENTORY_ENTRY_PATH);
            String selector = rowSelector + "[" + slotInRow + "]";
            ItemStack itemStack = container.getItemStack(slot);
            boolean hasItem = !ItemStack.isEmpty(itemStack);
            hasAnyItem |= hasItem;

            commandBuilder.set(selector + " #SlotButton.Disabled", !hasItem);
            commandBuilder.set(selector + " #Icon.Visible", hasItem);
            commandBuilder.set(selector + " #EmptySlot.Visible", !hasItem);
            commandBuilder.set(selector + " #SelectedOverlayPrimary.Visible", false);
            commandBuilder.set(selector + " #SelectedOverlaySecondary.Visible", false);
            commandBuilder.set(selector + " #SelectedOverlayBoth.Visible", false);
            commandBuilder.set(selector + " #SelectedBottomLine.Visible", false);
            commandBuilder.set(selector + " #SelectionBadge.Visible", false);
            commandBuilder.set(selector + " #QuantityLabel.TextSpans", Message.raw(""));

            if (!hasItem) {
                continue;
            }

            commandBuilder.set(selector + " #Icon.Slots", new ItemGridSlot[] { this.createGridSlot(itemStack) });
            InventorySource source = new InventorySource(
                    this.buildInventoryKey(sectionId, slot, itemStack),
                    container,
                    slot,
                    itemStack,
                    sectionLabel);
            boolean primarySelected = source.key().equals(this.selectedPrimaryKey);
            boolean secondarySelected = source.key().equals(this.selectedSecondaryKey);
            boolean selected = primarySelected || secondarySelected;
            commandBuilder.set(selector + " #SelectedOverlayPrimary.Visible", primarySelected && !secondarySelected);
            commandBuilder.set(selector + " #SelectedOverlaySecondary.Visible", !primarySelected && secondarySelected);
            commandBuilder.set(selector + " #SelectedOverlayBoth.Visible", primarySelected && secondarySelected);
            commandBuilder.set(selector + " #SelectedBottomLine.Visible", selected);
            commandBuilder.set(selector + " #SelectionBadge.Visible", selected);
            if (selected) {
                String accentColor = primarySelected && secondarySelected
                        ? "#9ee08f"
                        : (secondarySelected ? "#6bbde0" : "#e0bd6b");
                commandBuilder.set(selector + " #SelectedBottomLine.Background", accentColor);
                commandBuilder.set(selector + " #SelectionBadge.Background", accentColor);
                commandBuilder.set(selector + " #SelectionBadge.TextSpans",
                        Message.raw(primarySelected && secondarySelected ? "2x" : (primarySelected ? "1" : "2")));
            }
            if (itemStack.getQuantity() > 1) {
                commandBuilder.set(selector + " #QuantityLabel.TextSpans",
                        Message.raw(String.valueOf(itemStack.getQuantity())));
            }
        }
        return hasAnyItem;
    }

    private void bindInventorySlotEvents(@Nonnull UIEventBuilder eventBuilder, @Nonnull Player player) {
        Inventory inventory = player.getInventory();
        this.bindInventorySectionEvents(eventBuilder, inventory.getHotbar(), "#HotbarSlots", "Hotbar",
                InventoryComponent.HOTBAR_SECTION_ID);
        this.bindInventorySectionEvents(eventBuilder, inventory.getStorage(), "#StorageSlots", "Storage",
                InventoryComponent.STORAGE_SECTION_ID);
        this.bindInventorySectionEvents(eventBuilder, inventory.getBackpack(), "#BackpackSlots", "Backpack",
                InventoryComponent.BACKPACK_SECTION_ID);
    }

    private void bindInventorySectionEvents(
            @Nonnull UIEventBuilder eventBuilder,
            @Nullable ItemContainer container,
            @Nonnull String slotsSelector,
            @Nonnull String sectionLabel,
            int sectionId) {
        if (container == null) {
            return;
        }
        int slotsPerRow = sectionId == InventoryComponent.HOTBAR_SECTION_ID
                ? HOTBAR_SLOTS_PER_ROW
                : INVENTORY_SLOTS_PER_ROW;
        for (short slot = 0; slot < container.getCapacity(); slot++) {
            ItemStack itemStack = container.getItemStack(slot);
            if (ItemStack.isEmpty(itemStack)) {
                continue;
            }
            int rowIndex = slot / slotsPerRow;
            int slotInRow = slot % slotsPerRow;
            String selector = slotsSelector + "[" + rowIndex + "][" + slotInRow + "] #SlotButton";
            String innerSelector = slotsSelector + "[" + rowIndex + "][" + slotInRow + "] #SlotInner";
            String overlaySelector = slotsSelector + "[" + rowIndex + "][" + slotInRow + "] #ClickOverlay";
            String iconSelector = slotsSelector + "[" + rowIndex + "][" + slotInRow + "] #Icon";
            String key = this.buildInventoryKey(sectionId, slot, itemStack);

            EventData selectEvent = EventData.of("InventorySelect", key);
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, selector,
                    selectEvent, false);
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, innerSelector,
                    selectEvent, false);
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, overlaySelector,
                    selectEvent, false);
            eventBuilder.addEventBinding(CustomUIEventBindingType.SlotClicking, iconSelector,
                    selectEvent, false);
        }
    }

    @Nonnull
    private Message buildNamePreviewMessage() {
        ItemStack preview = this.getPreviewResultItem();
        ItemStack reference = !ItemStack.isEmpty(preview) ? preview : this.getPrimaryInput();
        if (ItemStack.isEmpty(reference)) {
            return Message.raw("No preview").color(COLOR_MUTED);
        }

        EngravingTableCustomizationData customization = EngravingTableCustomizationData.fromItemStack(reference);
        String name = this.resolveCurrentDisplayName(reference, customization);
        Message message = Message.raw(name);
        if (customization.getNameColor() != null) {
            message = message.color(customization.getNameColor().getHexColor());
        }
        return message;
    }

    @Nonnull
    private Message buildGlowPreviewMessage() {
        ItemStack primary = this.getPrimaryInput();
        if (!this.canEditGlow(primary)) {
            return Message.raw("No enchantment glow available").color(COLOR_MUTED);
        }

        EngravingTableColorOption previewColor = this.hasPendingGlowChange(primary)
                ? this.selectedGlowColor
                : EngravingTableCustomizationData.fromItemStack(primary).getGlowColorOrDefault();
        return Message.raw(previewColor.getDisplayName()).color(previewColor.getHexColor());
    }

    @Nonnull
    private Message buildCostMessage() {
        if (this.isMergeMode()) {
            return Message.raw("Cost: free scroll merge").color(COLOR_SUCCESS);
        }

        List<PendingCost> costs = this.buildRequiredCosts();
        if (costs.isEmpty()) {
            return Message.raw("Cost: none").color(COLOR_MUTED);
        }

        StringJoiner joiner = new StringJoiner(" + ");
        ItemContainer playerInventory = this.getCurrentPlayerInventoryContainer();
        for (PendingCost cost : costs) {
            joiner.add(cost.displayText() + " (have "
                    + this.countMatchingItems(playerInventory, cost.itemStack()) + ")");
        }
        boolean affordable = this.canAfford(costs);
        return Message.raw((affordable ? "Cost: " : "Missing: ") + joiner)
                .color(affordable ? COLOR_NEUTRAL : COLOR_ERROR);
    }

    @Nonnull
    private Message buildStatusMessage() {
        StatusInfo statusInfo = this.buildStatusInfo();
        return Message.raw(statusInfo.text()).color(statusInfo.color());
    }

    @Nonnull
    private StatusInfo buildStatusInfo() {
        ItemStack primary = this.getPrimaryInput();
        if (ItemStack.isEmpty(primary)) {
            return new StatusInfo("Select an item from the inventory.", COLOR_MUTED);
        }

        if (this.isBlockedBySecondaryItem()) {
            return new StatusInfo("The second slot is reserved for scroll merging.", COLOR_ERROR);
        }

        if (this.isMergeMode()) {
            String inputAvailabilityError = this.getMergeInputAvailabilityError();
            if (inputAvailabilityError != null) {
                return new StatusInfo(inputAvailabilityError, COLOR_ERROR);
            }
            ScrollMergeHelper.MergeResult mergeResult = this.getMergeResult();
            if (!mergeResult.isSuccessful()) {
                return new StatusInfo(mergeResult.errorMessage(), COLOR_ERROR);
            }
            return new StatusInfo("Ready to merge scrolls.", COLOR_SUCCESS);
        }

        if (this.isCustomizationMode()) {
            if (this.editableName.trim().isEmpty()) {
                return new StatusInfo("Name cannot be empty.", COLOR_ERROR);
            }
            boolean nameChanged = this.hasPendingNameChange(primary);
            boolean glowChanged = this.hasPendingGlowChange(primary);
            if (!nameChanged && !glowChanged) {
                if (ScrollMergeHelper.isScroll(primary)) {
                    return new StatusInfo("Scroll ready for rename or merge.", COLOR_MUTED);
                }
                return new StatusInfo("No pending engravingTable changes.", COLOR_MUTED);
            }

            List<PendingCost> costs = this.buildRequiredCosts();
            if (!this.canAfford(costs)) {
                return new StatusInfo("You do not have the required petals or crystals.", COLOR_ERROR);
            }
            return new StatusInfo("Ready to apply the result.", COLOR_SUCCESS);
        }

        return new StatusInfo("Select an item from the inventory.", COLOR_MUTED);
    }

    @Nullable
    private ItemStack getPreviewResultItem() {
        if (this.isMergeMode()) {
            ScrollMergeHelper.MergeResult mergeResult = this.getMergeResult();
            return mergeResult.isSuccessful() ? mergeResult.result() : null;
        }
        if (!this.isCustomizationMode()) {
            return null;
        }

        ItemStack primary = this.getPrimaryInput();
        if (ItemStack.isEmpty(primary)) {
            return null;
        }
        boolean nameChanged = this.hasPendingNameChange(primary);
        boolean glowChanged = this.hasPendingGlowChange(primary);
        if (!nameChanged && !glowChanged) {
            return null;
        }
        if (this.editableName.trim().isEmpty()) {
            return null;
        }

        FinalCustomization finalCustomization = this.buildFinalCustomization(primary);
        ItemStack result = EngravingTableCustomizationData.applyToItem(
                primary,
                finalCustomization.customName(),
                finalCustomization.nameColor(),
                finalCustomization.glowColor());
        return result != null && result.getQuantity() > 1 ? result.withQuantity(1) : result;
    }

    private boolean canTakePreview() {
        if (ItemStack.isEmpty(this.getPreviewResultItem())) {
            return false;
        }
        if (this.isMergeMode() && this.getMergeInputAvailabilityError() != null) {
            return false;
        }
        return this.canAfford(this.buildRequiredCosts());
    }

    @Nonnull
    private FinalCustomization buildFinalCustomization(@Nonnull ItemStack primary) {
        EngravingTableCustomizationData existing = EngravingTableCustomizationData.fromItemStack(primary);
        String defaultName = this.resolveBaseItemName(primary);
        String trimmedName = this.editableName.trim();

        String finalCustomName = trimmedName.equals(defaultName) ? null : trimmedName;
        EngravingTableColorOption finalNameColor = this.selectedNameColor == EngravingTableColorOption.DEFAULT_NAME_COLOR
                ? null
                : this.selectedNameColor;
        EngravingTableColorOption finalGlowColor = this.canEditGlow(primary)
                ? (this.selectedGlowColor == EngravingTableColorOption.DEFAULT_GLOW_COLOR ? null
                        : this.selectedGlowColor)
                : existing.getGlowColor();

        return new FinalCustomization(finalCustomName, finalNameColor, finalGlowColor);
    }

    private boolean hasPendingNameChange(@Nonnull ItemStack primary) {
        return this.hasPendingNameTextChange(primary) || this.hasPendingNameColorChange(primary);
    }

    private boolean hasPendingNameTextChange(@Nonnull ItemStack primary) {
        if (!this.isCustomizationMode() || ItemStack.isEmpty(primary)) {
            return false;
        }
        String trimmedName = this.editableName.trim();
        if (trimmedName.isEmpty()) {
            return false;
        }

        EngravingTableCustomizationData customization = EngravingTableCustomizationData.fromItemStack(primary);
        String currentDisplayName = this.resolveCurrentDisplayName(primary, customization);
        return !trimmedName.equals(currentDisplayName);
    }

    private boolean hasPendingNameColorChange(@Nonnull ItemStack primary) {
        if (!this.isCustomizationMode() || ItemStack.isEmpty(primary)) {
            return false;
        }
        EngravingTableColorOption currentColor = EngravingTableCustomizationData.fromItemStack(primary)
                .getNameColorOrDefault();
        return this.selectedNameColor != currentColor;
    }

    private boolean hasPendingGlowChange(@Nonnull ItemStack primary) {
        if (!this.canEditGlow(primary)) {
            return false;
        }
        EngravingTableColorOption currentGlowColor = EngravingTableCustomizationData.fromItemStack(primary)
                .getGlowColorOrDefault();
        return this.selectedGlowColor != currentGlowColor;
    }

    private boolean canEditGlow(@Nullable ItemStack itemStack) {
        return this.isCustomizationMode()
                && ScrollMergeHelper.hasStoredEnchantments(itemStack, this.enchantmentManager);
    }

    @Nonnull
    private List<PendingCost> buildRequiredCosts() {
        List<PendingCost> costs = new ArrayList<>(2);
        ItemStack primary = this.getPrimaryInput();
        if (!this.isCustomizationMode() || ItemStack.isEmpty(primary)) {
            return costs;
        }

        if (this.hasPendingNameColorChange(primary)) {
            costs.add(new PendingCost(
                    new ItemStack(this.selectedNameColor.getPetalItemId(), 1),
                    "1 " + this.selectedNameColor.getDisplayName() + " Petal"));
        }
        if (this.hasPendingGlowChange(primary)) {
            costs.add(new PendingCost(
                    new ItemStack(this.selectedGlowColor.getCrystalItemId(), 1),
                    "1 " + this.selectedGlowColor.getDisplayName() + " Crystal"));
        }
        return costs;
    }

    private boolean canAfford(@Nonnull List<PendingCost> costs) {
        if (costs.isEmpty()) {
            return true;
        }
        Ref<EntityStore> ref = this.playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return false;
        }
        Store<EntityStore> store = ref.getStore();
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return false;
        }
        ItemContainer playerInventory = this.getPlayerInventoryContainer(player);
        return playerInventory != null && playerInventory.canRemoveItemStacks(this.toCostItemStacks(costs), true, true);
    }

    @Nullable
    private ItemContainer getCurrentPlayerInventoryContainer() {
        Ref<EntityStore> ref = this.playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return null;
        }
        Player player = ref.getStore().getComponent(ref, Player.getComponentType());
        return player != null ? this.getPlayerInventoryContainer(player) : null;
    }

    private int countMatchingItems(@Nullable ItemContainer inventory, @Nonnull ItemStack requiredItemStack) {
        if (inventory == null) {
            return 0;
        }
        return inventory.countItemStacks(requiredItemStack::isStackableWith);
    }

    private void takePreview(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        ItemStack previewResult = this.getPreviewResultItem();
        if (ItemStack.isEmpty(previewResult)) {
            this.playerRef.sendMessage(Message.raw(this.buildStatusInfo().text()));
            return;
        }

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }

        List<PendingCost> costs = this.buildRequiredCosts();
        ItemContainer playerInventory = this.getPlayerInventoryContainer(player);
        if (playerInventory == null || !this.canAfford(costs)) {
            this.playerRef.sendMessage(Message.raw("You do not have the required materials."));
            this.sendDynamicUpdate();
            return;
        }

        boolean mergeMode = this.isMergeMode();
        if (mergeMode) {
            String inputAvailabilityError = this.getMergeInputAvailabilityError();
            if (inputAvailabilityError != null) {
                this.playerRef.sendMessage(Message.raw(inputAvailabilityError));
                this.sendDynamicUpdate();
                return;
            }
        }

        List<ItemStack> costStacks = this.toCostItemStacks(costs);
        if (!costStacks.isEmpty() && !playerInventory.removeItemStacks(costStacks, true, true).succeeded()) {
            this.playerRef.sendMessage(Message.raw("Failed to consume the required materials."));
            this.sendDynamicUpdate();
            return;
        }

        if (!this.consumePreviewInputs(player)) {
            if (!costStacks.isEmpty()) {
                SimpleItemContainer.addOrDropItemStacks(store, ref, playerInventory, costStacks);
            }
            this.playerRef.sendMessage(Message.raw("The selected item changed before the result could be taken."));
            this.sendDynamicUpdate();
            return;
        }

        SimpleItemContainer.addOrDropItemStack(store, ref, playerInventory, previewResult);
        TooltipBridge.refreshPlayer(this.playerRef.getUuid());
        this.playerRef.sendMessage(Message.raw(mergeMode
                ? "Merged the scrolls."
                : "Applied the engravingTable changes."));
        player.getPageManager().setPage(ref, store, Page.None);
    }

    private boolean consumePreviewInputs(@Nonnull Player player) {
        InventorySource primary = this.resolveSelectedSource(player, this.selectedPrimaryKey);
        if (primary == null || ItemStack.isEmpty(primary.itemStack())) {
            return false;
        }

        if (this.isMergeMode()) {
            InventorySource secondary = this.resolveSelectedSource(player, this.selectedSecondaryKey);
            if (secondary == null || ItemStack.isEmpty(secondary.itemStack())) {
                return false;
            }

            if (primary.key().equals(secondary.key())) {
                return primary.itemStack().getQuantity() >= 2
                        && primary.container()
                                .removeItemStackFromSlot(primary.slot(), 2)
                                .succeeded();
            }

            ItemStackSlotTransaction removePrimary = primary.container()
                    .removeItemStackFromSlot(primary.slot(), 1);
            if (!removePrimary.succeeded()) {
                return false;
            }

            ItemStackSlotTransaction removeSecondary = secondary.container()
                    .removeItemStackFromSlot(secondary.slot(), 1);
            if (!removeSecondary.succeeded()) {
                primary.container().setItemStackForSlot(primary.slot(), primary.itemStack());
                return false;
            }
            return true;
        }

        return primary.container()
                .removeItemStackFromSlot(primary.slot(), 1)
                .succeeded();
    }

    @Nonnull
    private List<ItemStack> toCostItemStacks(@Nonnull List<PendingCost> costs) {
        List<ItemStack> itemStacks = new ArrayList<>(costs.size());
        for (PendingCost cost : costs) {
            itemStacks.add(cost.itemStack());
        }
        return itemStacks;
    }

    @Nullable
    private ItemContainer getPlayerInventoryContainer(@Nonnull Player player) {
        ItemContainer combined = player.getInventory().getCombinedBackpackStorageHotbarFirst();
        if (combined != null) {
            return combined;
        }
        return player.getInventory().getCombinedHotbarFirst();
    }

    @Nonnull
    private String resolveBaseItemName(@Nonnull ItemStack itemStack) {
        String itemNameKey = itemStack.getItem().getTranslationKey();
        String lang = this.plugin.getUserSettingsManager().getLanguage(this.playerRef.getUuid());
        String clientLang = this.playerRef.getLanguage();

        String resolved = this.plugin.getLanguageManager().getRawMessage(itemNameKey, lang, clientLang);
        if ((resolved == null || resolved.isBlank() || resolved.equals(itemNameKey))
                && itemNameKey.startsWith("server.")) {
            resolved = this.plugin.getLanguageManager().getRawMessage(itemNameKey.substring(7), lang, clientLang);
        }
        if (this.isResolvedName(resolved, itemNameKey)) {
            return resolved;
        }

        I18nModule i18nModule = I18nModule.get();
        if (i18nModule != null) {
            String preferredLang = (lang == null || lang.isBlank() || "default".equalsIgnoreCase(lang))
                    ? clientLang
                    : lang;

            resolved = this.resolveI18nName(i18nModule, preferredLang, itemNameKey);
            if (this.isResolvedName(resolved, itemNameKey)) {
                return resolved;
            }

            if (clientLang != null && !clientLang.equalsIgnoreCase(preferredLang)) {
                resolved = this.resolveI18nName(i18nModule, clientLang, itemNameKey);
                if (this.isResolvedName(resolved, itemNameKey)) {
                    return resolved;
                }
            }

            resolved = this.resolveI18nName(i18nModule, "en-US", itemNameKey);
            if (this.isResolvedName(resolved, itemNameKey)) {
                return resolved;
            }
        }

        resolved = this.resolveAssetLanguageName(lang, clientLang, itemNameKey);
        if (this.isResolvedName(resolved, itemNameKey)) {
            return resolved;
        }

        return itemStack.getItemId();
    }

    @Nullable
    private String resolveI18nName(@Nonnull I18nModule i18nModule, @Nullable String language,
            @Nonnull String itemNameKey) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String resolved = i18nModule.getMessage(language, itemNameKey);
        if (!this.isResolvedName(resolved, itemNameKey) && itemNameKey.startsWith("server.")) {
            resolved = i18nModule.getMessage(language, itemNameKey.substring(7));
        }
        return resolved;
    }

    @Nullable
    private String resolveAssetLanguageName(@Nullable String lang, @Nullable String clientLang,
            @Nonnull String itemNameKey) {
        String preferredLang = (lang == null || lang.isBlank() || "default".equalsIgnoreCase(lang))
                ? clientLang
                : lang;

        String resolved = this.resolveAssetLanguageName(preferredLang, itemNameKey);
        if (this.isResolvedName(resolved, itemNameKey)) {
            return resolved;
        }
        if (clientLang != null && !clientLang.equalsIgnoreCase(preferredLang)) {
            resolved = this.resolveAssetLanguageName(clientLang, itemNameKey);
            if (this.isResolvedName(resolved, itemNameKey)) {
                return resolved;
            }
        }
        return this.resolveAssetLanguageName("en-US", itemNameKey);
    }

    @Nullable
    private String resolveAssetLanguageName(@Nullable String language, @Nonnull String itemNameKey) {
        if (language == null || language.isBlank()) {
            return null;
        }
        Map<String, String> messages = ASSET_LANGUAGE_CACHE.computeIfAbsent(language,
                EngravingTablePage::loadAssetLanguage);
        String resolved = messages.get(itemNameKey);
        if (resolved == null && itemNameKey.startsWith("server.")) {
            resolved = messages.get(itemNameKey.substring(7));
        }
        return resolved;
    }

    @Nonnull
    private static Map<String, String> loadAssetLanguage(@Nonnull String language) {
        Map<String, String> messages = new HashMap<>();
        for (Path assetRoot : getLanguageAssetRoots()) {
            loadAssetLanguageFile(messages, assetRoot.resolve("Server").resolve("Languages").resolve(language)
                    .resolve("server.lang"));
        }
        return messages;
    }

    @Nonnull
    private static List<Path> getLanguageAssetRoots() {
        List<Path> roots = new ArrayList<>();
        try {
            AssetModule assetModule = AssetModule.get();
            if (assetModule != null) {
                for (AssetPack pack : assetModule.getAssetPacks()) {
                    if (pack.getRoot() != null) {
                        roots.add(pack.getRoot());
                    }
                }
            }
        } catch (Exception ignored) {
            // AssetModule may be unavailable in some test/bootstrap contexts.
        }
        roots.add(Path.of("Assets"));
        roots.add(Path.of("../Assets"));
        roots.add(Path.of("../../Assets"));
        return roots;
    }

    private static void loadAssetLanguageFile(@Nonnull Map<String, String> messages, @Nonnull Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.replace("\uFEFF", "").trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int separatorIndex = line.indexOf('=');
                if (separatorIndex <= 0) {
                    continue;
                }

                String key = line.substring(0, separatorIndex).trim();
                String value = line.substring(separatorIndex + 1).trim()
                        .replace("\\n", "\n")
                        .replace("\\t", "\t")
                        .replace("[TMP] ", "")
                        .replace("[TMP]", "");
                messages.putIfAbsent(key, value);
                messages.putIfAbsent("server." + key, value);
            }
        } catch (IOException ignored) {
            // Missing/unreadable fallback language files should not break the UI.
        }
    }

    private boolean isResolvedName(@Nullable String resolved, @Nonnull String itemNameKey) {
        if (resolved == null || resolved.isBlank() || resolved.equals(itemNameKey)) {
            return false;
        }
        if (itemNameKey.startsWith("server.") && resolved.equals(itemNameKey.substring(7))) {
            return false;
        }
        if (!itemNameKey.startsWith("server.") && resolved.equals("server." + itemNameKey)) {
            return false;
        }
        return !this.looksLikeTranslationKey(resolved);
    }

    @Nonnull
    private String resolveCurrentDisplayName(@Nonnull ItemStack itemStack,
            @Nonnull EngravingTableCustomizationData customization) {
        if (customization.getCustomName() != null && !customization.getCustomName().isBlank()) {
            String customName = customization.getCustomName();
            if (this.looksLikeTranslationKey(customName)) {
                String resolved = this.resolveTranslationKey(customName);
                if (this.isResolvedName(resolved, customName)) {
                    return resolved;
                }
            }
            return customName;
        }
        return this.resolveBaseItemName(itemStack);
    }

    @Nullable
    private String resolveTranslationKey(@Nonnull String translationKey) {
        String lang = this.plugin.getUserSettingsManager().getLanguage(this.playerRef.getUuid());
        String clientLang = this.playerRef.getLanguage();

        String resolved = this.plugin.getLanguageManager().getRawMessage(translationKey, lang, clientLang);
        if (!this.isResolvedName(resolved, translationKey) && translationKey.startsWith("server.")) {
            resolved = this.plugin.getLanguageManager().getRawMessage(translationKey.substring(7), lang, clientLang);
        }
        if (this.isResolvedName(resolved, translationKey)) {
            return resolved;
        }

        I18nModule i18nModule = I18nModule.get();
        if (i18nModule != null) {
            String preferredLang = (lang == null || lang.isBlank() || "default".equalsIgnoreCase(lang))
                    ? clientLang
                    : lang;
            resolved = this.resolveI18nName(i18nModule, preferredLang, translationKey);
            if (this.isResolvedName(resolved, translationKey)) {
                return resolved;
            }
            if (clientLang != null && !clientLang.equalsIgnoreCase(preferredLang)) {
                resolved = this.resolveI18nName(i18nModule, clientLang, translationKey);
                if (this.isResolvedName(resolved, translationKey)) {
                    return resolved;
                }
            }
            resolved = this.resolveI18nName(i18nModule, "en-US", translationKey);
            if (this.isResolvedName(resolved, translationKey)) {
                return resolved;
            }
        }

        return this.resolveAssetLanguageName(lang, clientLang, translationKey);
    }

    private boolean looksLikeTranslationKey(@Nonnull String value) {
        return value.startsWith("server.items.") || value.startsWith("items.");
    }

    private boolean isCustomizationMode() {
        return !ItemStack.isEmpty(this.getPrimaryInput()) && !this.isScrollMode();
    }

    private boolean isMergeMode() {
        return this.isScrollMode() && !ItemStack.isEmpty(this.getSecondaryInput());
    }

    private boolean isBlockedBySecondaryItem() {
        return !this.isScrollMode() && !ItemStack.isEmpty(this.getSecondaryInput());
    }

    private boolean isScrollMode() {
        return ScrollMergeHelper.isScroll(this.getPrimaryInput());
    }

    @Nonnull
    private ScrollMergeHelper.MergeResult getMergeResult() {
        return ScrollMergeHelper.merge(this.getPrimaryInput(), this.getSecondaryInput(), this.enchantmentManager);
    }

    @Nullable
    private ItemStack getPrimaryInput() {
        return this.getSelectedItemStack(this.selectedPrimaryKey);
    }

    @Nullable
    private ItemStack getSecondaryInput() {
        return this.getSelectedItemStack(this.selectedSecondaryKey);
    }

    @Nonnull
    private String buildInputStateKey() {
        return String.valueOf(this.getPrimaryInput()) + "|" + String.valueOf(this.getSecondaryInput());
    }

    @Nonnull
    private String getButtonTextColor(@Nonnull EngravingTableColorOption colorOption) {
        return switch (colorOption) {
            case RED, BLUE, PURPLE -> "#ffffff";
            default -> "#1c1c1c";
        };
    }

    private void handleInventorySelection(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull String selectionKey) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }

        InventorySource selection = this.resolveSelectedSource(player, selectionKey);
        if (selection == null) {
            this.playerRef.sendMessage(Message.raw("That item is no longer in your inventory."));
            this.syncEditorState(true);
            this.sendDynamicUpdate();
            return;
        }
        if (this.isDuplicateSelectionEvent(selection.key())) {
            return;
        }

        ItemStack currentPrimary = this.getPrimaryInput();
        if (ItemStack.isEmpty(currentPrimary)) {
            this.selectedPrimaryKey = selection.key();
        } else if (this.isScrollMode() && ScrollMergeHelper.isScroll(selection.itemStack())) {
            if (!selection.key().equals(this.selectedPrimaryKey) || selection.itemStack().getQuantity() >= 2) {
                this.selectedSecondaryKey = selection.key();
            } else {
                this.playerRef.sendMessage(Message.raw("Choose another scroll, or a stack with at least 2 scrolls."));
            }
        } else {
            this.selectedPrimaryKey = selection.key();
            this.selectedSecondaryKey = null;
        }

        this.syncEditorState(true);
        this.sendDynamicUpdate();
    }

    private boolean isDuplicateSelectionEvent(@Nonnull String selectionKey) {
        long now = System.nanoTime();
        if (selectionKey.equals(this.lastInventorySelectionKey)
                && now - this.lastInventorySelectionNanos < DUPLICATE_SELECTION_WINDOW_NANOS) {
            return true;
        }
        this.lastInventorySelectionKey = selectionKey;
        this.lastInventorySelectionNanos = now;
        return false;
    }

    @Nullable
    private ItemStack getSelectedItemStack(@Nullable String selectionKey) {
        Ref<EntityStore> ref = this.playerRef.getReference();
        if (selectionKey == null || ref == null || !ref.isValid()) {
            return null;
        }
        Player player = ref.getStore().getComponent(ref, Player.getComponentType());
        if (player == null) {
            return null;
        }
        InventorySource source = this.resolveSelectedSource(player, selectionKey);
        return source != null ? source.itemStack() : null;
    }

    @Nullable
    private InventorySource resolveSelectedSource(@Nonnull Player player, @Nullable String selectionKey) {
        if (selectionKey == null || selectionKey.isBlank()) {
            return null;
        }
        for (InventorySource source : this.getInventorySources(player)) {
            if (selectionKey.equals(source.key())) {
                return source;
            }
        }
        return null;
    }

    @Nonnull
    private List<InventorySource> getInventorySources(@Nonnull Player player) {
        List<InventorySource> sources = new ArrayList<>();
        Inventory inventory = player.getInventory();
        this.appendInventorySources(sources, inventory.getHotbar(), "Hotbar", InventoryComponent.HOTBAR_SECTION_ID);
        this.appendInventorySources(sources, inventory.getStorage(), "Storage", InventoryComponent.STORAGE_SECTION_ID);
        this.appendInventorySources(sources, inventory.getBackpack(), "Backpack",
                InventoryComponent.BACKPACK_SECTION_ID);
        return sources;
    }

    private void appendInventorySources(
            @Nonnull List<InventorySource> sources,
            @Nullable ItemContainer container,
            @Nonnull String sectionLabel,
            int sectionId) {
        if (container == null) {
            return;
        }
        for (short slot = 0; slot < container.getCapacity(); slot++) {
            ItemStack itemStack = container.getItemStack(slot);
            if (ItemStack.isEmpty(itemStack)) {
                continue;
            }
            sources.add(new InventorySource(
                    this.buildInventoryKey(sectionId, slot, itemStack),
                    container,
                    slot,
                    itemStack,
                    sectionLabel));
        }
    }

    @Nullable
    private String getMergeInputAvailabilityError() {
        if (!this.isMergeMode()) {
            return null;
        }
        if (this.selectedPrimaryKey == null || !this.selectedPrimaryKey.equals(this.selectedSecondaryKey)) {
            return null;
        }

        ItemStack primary = this.getPrimaryInput();
        if (ItemStack.isEmpty(primary)) {
            return "The selected scroll is no longer available.";
        }
        return primary.getQuantity() >= 2
                ? null
                : "That stack needs at least 2 scrolls to merge with itself.";
    }

    @Nonnull
    private String buildInventoryKey(int sectionId, short slot, @Nonnull ItemStack itemStack) {
        return sectionId + ":" + slot + ":" + Integer.toHexString(itemStack.hashCode());
    }

    private record FinalCustomization(
            @Nullable String customName,
            @Nullable EngravingTableColorOption nameColor,
            @Nullable EngravingTableColorOption glowColor) {
    }

    private record PendingCost(@Nonnull ItemStack itemStack, @Nonnull String displayText) {
    }

    private record StatusInfo(@Nonnull String text, @Nonnull String color) {
    }

    private record InventorySource(
            @Nonnull String key,
            @Nonnull ItemContainer container,
            short slot,
            @Nonnull ItemStack itemStack,
            @Nonnull String sectionLabel) {
    }
}
