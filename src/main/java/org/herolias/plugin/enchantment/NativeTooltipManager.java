package org.herolias.plugin.enchantment;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.lang.LanguageManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

/**
 * Applies Simple Enchantments tooltip text through Hytale's native per-stack
 * {@link ItemDisplayMetadata}. The manager preserves existing native names and
 * descriptions by treating the current description as a base and appending only
 * the enchantment block owned by this mod.
 */
public final class NativeTooltipManager {
    public static final String METADATA_KEY = "SimpleEnchantingNativeTooltip";

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String BASE_DESCRIPTION_KEY = "BaseDescription";
    private static final String LAST_DESCRIPTION_KEY = "LastDescription";
    private static final String LAST_HASH_KEY = "LastHash";
    private static final String MANAGED_KEY = "Managed";

    private static final String HEADER_COLOR = "#C8A2FF";
    private static final String ENCHANTMENT_COLOR = "#AA55FF";
    private static final String LEGENDARY_COLOR = "#FFAA00";
    private static final String BONUS_COLOR = "#AAAAAA";
    private static final String ENCHANT_SYMBOL = "\u2022 ";

    private NativeTooltipManager() {
    }

    @Nonnull
    public static ItemStack withEnchantments(@Nonnull ItemStack itemStack, @Nullable EnchantmentData data) {
        return withEnchantments(itemStack, data, resolveEnchantmentManager());
    }

    @Nonnull
    public static ItemStack withEnchantments(
            @Nonnull ItemStack itemStack,
            @Nullable EnchantmentData data,
            @Nullable EnchantmentManager enchantmentManager) {
        BsonDocument bson = (data == null || data.isEmpty()) ? null : data.toBson();
        return apply(itemStack.withMetadata(EnchantmentData.METADATA_KEY, Codec.BSON_DOCUMENT, bson),
                enchantmentManager);
    }

    @Nonnull
    public static ItemStack withEnchantments(@Nonnull ItemStack itemStack, @Nullable BsonDocument enchantmentBson) {
        return withEnchantments(itemStack, enchantmentBson, resolveEnchantmentManager());
    }

    @Nonnull
    public static ItemStack withEnchantments(
            @Nonnull ItemStack itemStack,
            @Nullable BsonDocument enchantmentBson,
            @Nullable EnchantmentManager enchantmentManager) {
        return apply(itemStack.withMetadata(EnchantmentData.METADATA_KEY, Codec.BSON_DOCUMENT, enchantmentBson),
                enchantmentManager);
    }

    @Nonnull
    public static ItemStack apply(@Nonnull ItemStack itemStack) {
        return apply(itemStack, resolveEnchantmentManager());
    }

    @Nonnull
    public static ItemStack apply(@Nonnull ItemStack itemStack, @Nullable EnchantmentManager enchantmentManager) {
        if (ItemStack.isEmpty(itemStack)) {
            return itemStack;
        }

        ItemDisplayMetadata displayMetadata = itemStack.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC);
        Message currentName = displayMetadata != null ? displayMetadata.getName() : null;
        Message currentDescription = displayMetadata != null ? displayMetadata.getDescription() : null;
        BsonDocument state = getState(itemStack);
        EnchantmentData data = getEnchantmentData(itemStack);

        if (data.isEmpty() && state == null) {
            return itemStack;
        }

        Message baseDescription = getBaseDescription(state);
        Message lastDescription = getLastDescription(state);
        if (state == null) {
            baseDescription = defaultBaseDescription(itemStack, currentDescription);
        } else if (!messagesEqual(currentDescription, lastDescription)) {
            baseDescription = defaultBaseDescription(itemStack, currentDescription);
        } else {
            baseDescription = normalizeBaseDescription(itemStack, baseDescription);
        }

        Message enchantmentBlock = buildEnchantmentBlock(data, enchantmentManager);

        if (enchantmentBlock == null) {
            if (state == null) {
                return itemStack;
            }
            return removeManagedDescription(itemStack, currentName, currentDescription, baseDescription, lastDescription);
        }

        Message composedDescription = composeDescription(baseDescription, enchantmentBlock);
        ItemStack updated = writeDisplay(itemStack, currentName, composedDescription);
        return writeState(updated, baseDescription, composedDescription, data.computeStableHash());
    }

    public static void refreshAllPlayers() {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }
        EnchantmentManager manager = resolveEnchantmentManager();
        for (PlayerRef playerRef : universe.getPlayers()) {
            refreshPlayer(playerRef, manager);
        }
    }

    public static void refreshPlayer(@Nonnull UUID playerUuid) {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }
        PlayerRef playerRef = universe.getPlayer(playerUuid);
        if (playerRef != null) {
            refreshPlayer(playerRef, resolveEnchantmentManager());
        }
    }

    private static void refreshPlayer(@Nonnull PlayerRef playerRef, @Nullable EnchantmentManager enchantmentManager) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        Runnable refresh = () -> {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player != null) {
                refreshInventory(player.getInventory(), enchantmentManager);
            }
        };
        if (world != null && world.isAlive() && !world.isInThread()) {
            world.execute(refresh);
        } else {
            refresh.run();
        }
    }

    private static void refreshInventory(@Nullable Inventory inventory, @Nullable EnchantmentManager enchantmentManager) {
        if (inventory == null) {
            return;
        }
        refreshContainer(inventory.getHotbar(), enchantmentManager);
        refreshContainer(inventory.getStorage(), enchantmentManager);
        refreshContainer(inventory.getBackpack(), enchantmentManager);
        refreshContainer(inventory.getArmor(), enchantmentManager);
        refreshContainer(inventory.getUtility(), enchantmentManager);
        refreshContainer(inventory.getTools(), enchantmentManager);
    }

    private static void refreshContainer(
            @Nullable ItemContainer container,
            @Nullable EnchantmentManager enchantmentManager) {
        if (container == null) {
            return;
        }
        for (short slot = 0; slot < container.getCapacity(); slot = (short) (slot + 1)) {
            ItemStack current = container.getItemStack(slot);
            if (ItemStack.isEmpty(current)) {
                continue;
            }
            ItemStack updated = apply(current, enchantmentManager);
            if (!updated.isEquivalentType(current)) {
                container.setItemStackForSlot(slot, updated, false);
            }
        }
    }

    @Nullable
    private static EnchantmentManager resolveEnchantmentManager() {
        SimpleEnchanting plugin = SimpleEnchanting.getInstance();
        return plugin != null ? plugin.getEnchantmentManager() : null;
    }

    @Nullable
    private static Message defaultBaseDescription(
            @Nonnull ItemStack itemStack,
            @Nullable Message currentDescription) {
        if (!isMessageEmpty(currentDescription)) {
            return normalizeBaseDescription(currentDescription);
        }
        return getDefaultItemDescription(itemStack);
    }

    @Nullable
    private static Message normalizeBaseDescription(
            @Nonnull ItemStack itemStack,
            @Nullable Message baseDescription) {
        if (isMessageEmpty(baseDescription)) {
            return getDefaultItemDescription(itemStack);
        }
        return normalizeBaseDescription(baseDescription);
    }

    @Nullable
    private static Message getDefaultItemDescription(@Nonnull ItemStack itemStack) {
        String descriptionKey = itemStack.getItem().getDescriptionTranslationKey();
        String resolved = resolveServerTranslation(descriptionKey);
        if (isMissingTranslation(descriptionKey, resolved)) {
            return null;
        }
        return hasLegacyMarkup(resolved) ? parseLegacyMarkup(resolved) : Message.translation(descriptionKey);
    }

    @Nullable
    private static Message normalizeBaseDescription(@Nullable Message source) {
        if (isMessageEmpty(source)) {
            return null;
        }
        String rawText = source.getRawText();
        if (rawText != null && isDescriptionKey(rawText)) {
            return null;
        }
        if (rawText != null && hasLegacyMarkup(rawText)) {
            return parseLegacyMarkup(rawText);
        }

        String messageId = source.getMessageId();
        if (messageId == null || messageId.isBlank()) {
            return source;
        }

        String resolved = resolveServerTranslation(messageId);
        if (isMissingTranslation(messageId, resolved)) {
            return isDescriptionKey(messageId) ? null : source;
        }
        if (!hasLegacyMarkup(resolved)) {
            return source;
        }
        return parseLegacyMarkup(resolved);
    }

    private static boolean isDescriptionKey(@Nonnull String messageId) {
        return messageId.endsWith(".description") || messageId.endsWith(".Description");
    }

    private static boolean isMissingTranslation(@Nonnull String messageId, @Nullable String resolved) {
        return resolved == null || resolved.isBlank() || resolved.equals(messageId);
    }

    @Nullable
    private static String resolveServerTranslation(@Nonnull String messageId) {
        try {
            I18nModule i18n = I18nModule.get();
            return i18n != null ? i18n.getMessage("en-US", messageId) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean hasLegacyMarkup(@Nonnull String text) {
        return text.contains("<i>") || text.contains("</i>")
                || text.contains("<b>") || text.contains("</b>")
                || text.contains("<color") || text.contains("</color>")
                || text.contains("<item");
    }

    @Nonnull
    private static Message parseLegacyMarkup(@Nonnull String text) {
        Message root = Message.empty();
        java.util.ArrayDeque<TextStyle> stack = new java.util.ArrayDeque<>();
        TextStyle style = TextStyle.DEFAULT;
        int segmentStart = 0;

        for (int i = 0; i < text.length();) {
            if (text.charAt(i) != '<') {
                i++;
                continue;
            }

            int close = text.indexOf('>', i);
            if (close < 0) {
                i++;
                continue;
            }

            String tag = text.substring(i, close + 1);
            TextStyle nextStyle = null;
            boolean recognized = true;
            Message inlineMessage = null;

            if ("<i>".equals(tag)) {
                nextStyle = style.withItalic(true);
            } else if ("</i>".equals(tag)) {
                nextStyle = stack.isEmpty() ? TextStyle.DEFAULT : stack.pop();
            } else if ("<b>".equals(tag)) {
                nextStyle = style.withBold(true);
            } else if ("</b>".equals(tag)) {
                nextStyle = stack.isEmpty() ? TextStyle.DEFAULT : stack.pop();
            } else if (tag.startsWith("<color ")) {
                String color = extractTagAttribute(tag, "is");
                recognized = color != null;
                if (recognized) {
                    nextStyle = style.withColor(color);
                }
            } else if ("</color>".equals(tag)) {
                nextStyle = stack.isEmpty() ? TextStyle.DEFAULT : stack.pop();
                stack = new java.util.ArrayDeque<>(stack);
            } else if (tag.startsWith("<item ")) {
                String itemId = extractTagAttribute(tag, "is");
                recognized = itemId != null;
                if (recognized) {
                    inlineMessage = style.apply(createItemReferenceMessage(itemId));
                }
            } else {
                recognized = false;
            }

            if (!recognized) {
                i++;
                continue;
            }

            appendRawSegment(root, text.substring(segmentStart, i), style);
            if (inlineMessage != null) {
                root.insert(inlineMessage);
            } else if (nextStyle != null) {
                if (!tag.startsWith("</")) {
                    stack.push(style);
                }
                style = nextStyle;
            }
            i = close + 1;
            segmentStart = i;
        }

        appendRawSegment(root, text.substring(segmentStart), style);
        return root;
    }

    private static void appendRawSegment(@Nonnull Message root, @Nonnull String text, @Nonnull TextStyle style) {
        if (!text.isEmpty()) {
            root.insert(style.apply(Message.raw(text)));
        }
    }

    @Nonnull
    private static Message createItemReferenceMessage(@Nonnull String itemId) {
        Item item = Item.getAssetMap().getAsset(itemId);
        return item != null ? item.getTranslationMessage() : Message.raw(itemId);
    }

    @Nullable
    private static String extractTagAttribute(@Nonnull String tag, @Nonnull String attribute) {
        String prefix = attribute + "=\"";
        int start = tag.indexOf(prefix);
        if (start < 0) {
            prefix = attribute + "='";
            start = tag.indexOf(prefix);
        }
        if (start < 0) {
            return null;
        }
        start += prefix.length();
        char quote = prefix.charAt(prefix.length() - 1);
        int end = tag.indexOf(quote, start);
        return end > start ? tag.substring(start, end) : null;
    }

    private record TextStyle(boolean italic, boolean bold, @Nullable String color) {
        private static final TextStyle DEFAULT = new TextStyle(false, false, null);

        @Nonnull
        TextStyle withItalic(boolean value) {
            return new TextStyle(value, this.bold, this.color);
        }

        @Nonnull
        TextStyle withBold(boolean value) {
            return new TextStyle(this.italic, value, this.color);
        }

        @Nonnull
        TextStyle withColor(@Nullable String value) {
            return new TextStyle(this.italic, this.bold, value);
        }

        @Nonnull
        Message apply(@Nonnull Message message) {
            if (this.italic) {
                message.italic(true);
            }
            if (this.bold) {
                message.bold(true);
            }
            if (this.color != null) {
                message.color(this.color);
            }
            return message;
        }
    }

    @Nullable
    private static BsonDocument getState(@Nonnull ItemStack itemStack) {
        try {
            return itemStack.getFromMetadataOrNull(METADATA_KEY, Codec.BSON_DOCUMENT);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nonnull
    private static EnchantmentData getEnchantmentData(@Nonnull ItemStack itemStack) {
        try {
            BsonDocument enchantments = itemStack.getFromMetadataOrNull(
                    EnchantmentData.METADATA_KEY,
                    Codec.BSON_DOCUMENT);
            if (enchantments != null && !enchantments.isEmpty()) {
                return EnchantmentData.fromBson(enchantments);
            }
            String legacyData = itemStack.getFromMetadataOrNull(EnchantmentData.METADATA_KEY, Codec.STRING);
            if (legacyData != null && !legacyData.isBlank()) {
                return EnchantmentData.deserialize(legacyData);
            }
        } catch (Exception ignored) {
        }
        return EnchantmentData.EMPTY;
    }

    @Nullable
    private static Message buildEnchantmentBlock(
            @Nonnull EnchantmentData data,
            @Nullable EnchantmentManager enchantmentManager) {
        if (data.isEmpty()) {
            return null;
        }

        Message block = Message.empty();
        boolean hasAnyLine = false;
        for (Map.Entry<EnchantmentType, Integer> entry : data.getAllEnchantments().entrySet()) {
            EnchantmentType type = entry.getKey();
            int level = entry.getValue();
            if (level <= 0 || (enchantmentManager != null && !enchantmentManager.isEnchantmentEnabled(type))) {
                continue;
            }
            if (!hasAnyLine) {
                block.insert(translationOrRaw("tooltip.enchantments", "Enchantments:").color(HEADER_COLOR));
                hasAnyLine = true;
            }
            block.insert(Message.raw("\n"));
            block.insert(buildEnchantmentLine(type, level));
        }
        return hasAnyLine ? block : null;
    }

    @Nonnull
    private static Message buildEnchantmentLine(@Nonnull EnchantmentType type, int level) {
        String color = type.isLegendary() ? LEGENDARY_COLOR : ENCHANTMENT_COLOR;
        Message line = Message.empty()
                .insert(Message.raw(ENCHANT_SYMBOL).color(color))
                .insert(translationOrRaw(type.getNameKey(), type.getDisplayName()).color(color))
                .insert(Message.raw(" " + EnchantmentType.toRoman(level)).color(color));

        Message bonus = buildBonusMessage(type, level);
        if (bonus != null) {
            line.insert(Message.raw(" "));
            line.insert(bonus.color(BONUS_COLOR));
        }
        return line;
    }

    @Nullable
    private static Message buildBonusMessage(@Nonnull EnchantmentType type, int level) {
        if (level <= 0) {
            return null;
        }
        String key = type.getBonusTranslationKey();
        if (!hasSimpleEnchantingTranslation(key)) {
            String fallback = type.getBonusDescription(level, "en-US", "en-US");
            return fallback == null || fallback.isBlank() ? null : Message.raw(fallback);
        }

        Message message = Message.translation(serverKey(key));
        message.param("amount", String.valueOf(getDisplayAmount(type, level)));
        message.param("duration", String.valueOf((float) type.getMultiplierValue(type.getId() + ":duration")));
        return message;
    }

    private static float getDisplayAmount(@Nonnull EnchantmentType type, int level) {
        double multiplier = type.getScaledMultiplier(level);
        float percentage = (float) (multiplier * 100);
        float amount = percentage;
        if (type.equals(EnchantmentType.EAGLES_EYE)) {
            amount = percentage * 50;
        }
        if (type.equals(EnchantmentType.FREEZE)) {
            amount = (float) ((1.0 - type.getEffectMultiplier()) * 100);
        }
        if (type.equals(EnchantmentType.BURN) || type.equals(EnchantmentType.POISON)
                || type.equals(EnchantmentType.REGENERATION)) {
            amount = (float) type.getEffectMultiplier();
        }
        return amount;
    }

    @Nonnull
    private static Message translationOrRaw(@Nonnull String key, @Nonnull String fallback) {
        return hasSimpleEnchantingTranslation(key) ? Message.translation(serverKey(key)) : Message.raw(fallback);
    }

    private static boolean hasSimpleEnchantingTranslation(@Nonnull String key) {
        try {
            SimpleEnchanting plugin = SimpleEnchanting.getInstance();
            LanguageManager languageManager = plugin != null ? plugin.getLanguageManager() : null;
            if (languageManager == null) {
                return false;
            }
            String resolved = languageManager.getRawMessage(key, "en-US", "en-US");
            return resolved != null && !resolved.equals(key);
        } catch (Exception ignored) {
            return false;
        }
    }

    @Nonnull
    private static String serverKey(@Nonnull String key) {
        return key.startsWith("server.") ? key : "server." + key;
    }

    @Nonnull
    private static Message composeDescription(@Nullable Message baseDescription, @Nonnull Message enchantmentBlock) {
        Message composed = Message.empty();
        if (!isMessageEmpty(baseDescription)) {
            composed.insert(baseDescription);
            composed.insert(Message.raw("\n\n"));
        }
        composed.insert(enchantmentBlock);
        return composed;
    }

    @Nonnull
    private static ItemStack removeManagedDescription(
            @Nonnull ItemStack itemStack,
            @Nullable Message currentName,
            @Nullable Message currentDescription,
            @Nullable Message baseDescription,
            @Nullable Message lastDescription) {
        Message restoredDescription;
        if (messagesEqual(currentDescription, lastDescription)) {
            restoredDescription = baseDescription;
        } else {
            restoredDescription = currentDescription;
        }
        return writeDisplay(itemStack, currentName, restoredDescription)
                .withMetadata(METADATA_KEY, Codec.BSON_DOCUMENT, null);
    }

    @Nonnull
    private static ItemStack writeDisplay(
            @Nonnull ItemStack itemStack,
            @Nullable Message name,
            @Nullable Message description) {
        if (isMessageEmpty(name) && isMessageEmpty(description)) {
            return itemStack.withMetadata(ItemDisplayMetadata.KEYED_CODEC, null);
        }
        return itemStack.withMetadata(ItemDisplayMetadata.KEYED_CODEC,
                new ItemDisplayMetadata(name, isMessageEmpty(description) ? null : description));
    }

    @Nonnull
    private static ItemStack writeState(
            @Nonnull ItemStack itemStack,
            @Nullable Message baseDescription,
            @Nonnull Message composedDescription,
            @Nonnull String hash) {
        BsonDocument state = new BsonDocument();
        state.put(MANAGED_KEY, BsonBoolean.TRUE);
        state.put(LAST_HASH_KEY, new BsonString(hash));
        BsonValue encodedBase = encodeMessage(baseDescription);
        if (encodedBase != null) {
            state.put(BASE_DESCRIPTION_KEY, encodedBase);
        }
        BsonValue encodedDescription = encodeMessage(composedDescription);
        if (encodedDescription != null) {
            state.put(LAST_DESCRIPTION_KEY, encodedDescription);
        }
        return itemStack.withMetadata(METADATA_KEY, Codec.BSON_DOCUMENT, state);
    }

    @Nullable
    private static Message getBaseDescription(@Nullable BsonDocument state) {
        return decodeMessage(state, BASE_DESCRIPTION_KEY);
    }

    @Nullable
    private static Message getLastDescription(@Nullable BsonDocument state) {
        return decodeMessage(state, LAST_DESCRIPTION_KEY);
    }

    @Nullable
    private static Message decodeMessage(@Nullable BsonDocument state, @Nonnull String key) {
        if (state == null) {
            return null;
        }
        BsonValue value = state.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return Message.CODEC.decode(value);
        } catch (Exception e) {
            LOGGER.atWarning().log("Could not decode native tooltip message metadata: " + e.getMessage());
            return null;
        }
    }

    @Nullable
    private static BsonValue encodeMessage(@Nullable Message message) {
        if (isMessageEmpty(message)) {
            return null;
        }
        try {
            return Message.CODEC.encode(message);
        } catch (Exception e) {
            LOGGER.atWarning().log("Could not encode native tooltip message metadata: " + e.getMessage());
            return null;
        }
    }

    private static boolean messagesEqual(@Nullable Message first, @Nullable Message second) {
        if (isMessageEmpty(first) && isMessageEmpty(second)) {
            return true;
        }
        BsonValue encodedFirst = encodeMessage(first);
        BsonValue encodedSecond = encodeMessage(second);
        return encodedFirst != null && encodedFirst.equals(encodedSecond);
    }

    private static boolean isMessageEmpty(@Nullable Message message) {
        if (message == null) {
            return true;
        }
        String rawText = message.getRawText();
        String messageId = message.getMessageId();
        return (rawText == null || rawText.isEmpty())
                && (messageId == null || messageId.isEmpty())
                && message.getChildren().isEmpty();
    }
}
