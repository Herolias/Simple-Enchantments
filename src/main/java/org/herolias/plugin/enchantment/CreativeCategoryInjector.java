package org.herolias.plugin.enchantment;

import com.hypixel.hytale.assetstore.AssetLoadResult;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.RawAsset;
import com.hypixel.hytale.assetstore.codec.ContainedAssetCodec;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemCategory;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.herolias.plugin.SimpleEnchanting;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.List;

/**
 * Adds the enchantment-scroll sub-tab to the vanilla {@code Items} tab of the
 * creative library instead of shipping a top-level tab of its own.
 * <p>
 * The creative library has no data-driven way to extend another pack's
 * category: an {@code Items.json} in this mod's pack would replace the vanilla
 * file wholesale and hide any sub-tab Hytale adds later. Instead, whenever the
 * {@code Items} category is (re)loaded, its live definition is serialised
 * through {@link ItemCategory#CODEC}, the scroll sub-tab is appended to
 * {@code Children}, and the result is loaded back through the regular asset
 * pipeline under this mod's pack key. The asset map layers packs per key, so
 * the vanilla definition stays underneath and is restored automatically when
 * the mod's pack is removed; clients receive the merged category through the
 * normal category packets.
 */
public final class CreativeCategoryInjector {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Vanilla creative-library tab the scroll sub-tab is added to. */
    public static final String PARENT_CATEGORY_ID = "Items";
    /** Id of the scroll sub-tab inside {@link #PARENT_CATEGORY_ID}. */
    public static final String SCROLL_SUBCATEGORY_ID = "Scrolls";
    /** Category path scroll items use in their {@code Categories} list. */
    public static final String SCROLL_CATEGORY_PATH = PARENT_CATEGORY_ID + "." + SCROLL_SUBCATEGORY_ID;

    private static final String SUBCATEGORY_NAME_KEY = "server.ui.itemcategory.simpleEnchantments.scrolls";
    private static final String SUBCATEGORY_ICON = "Icons/ItemCategories/Scroll_Tab_Small.png";
    /** Vanilla sub-tabs leave Order at 0, so a high value sorts the scroll tab last. */
    private static final int SUBCATEGORY_ORDER = 100;

    /** Re-entrance guard: loading the merged category fires a nested LoadedAssetsEvent. */
    private static volatile boolean injecting;

    private CreativeCategoryInjector() {
    }

    /**
     * Registers the asset listener. Called during plugin {@code setup()}. If the
     * categories are already loaded (plugin reload), the merge runs right away.
     */
    public static void registerEventListener(@Nonnull SimpleEnchanting plugin) {
        plugin.getEventRegistry().register(
                LoadedAssetsEvent.class,
                ItemCategory.class,
                CreativeCategoryInjector::onCategoriesLoaded);
        LOGGER.atInfo().log("CreativeCategoryInjector registered");

        ItemCategory items = currentParent();
        if (items != null) {
            inject(plugin, items);
        }
    }

    @Nullable
    private static ItemCategory currentParent() {
        try {
            return ItemCategory.getAssetMap().getAsset(PARENT_CATEGORY_ID);
        } catch (RuntimeException e) {
            // Asset store not registered yet (normal during boot)
            return null;
        }
    }

    private static void onCategoriesLoaded(
            @Nonnull LoadedAssetsEvent<String, ItemCategory, DefaultAssetMap<String, ItemCategory>> event) {
        if (injecting) {
            return;
        }
        ItemCategory items = event.getLoadedAssets().get(PARENT_CATEGORY_ID);
        SimpleEnchanting plugin = SimpleEnchanting.getInstance();
        if (items == null || plugin == null) {
            return;
        }
        inject(plugin, items);
    }

    /** Loads a copy of {@code items} with the scroll sub-tab appended, unless it already has one. */
    private static void inject(@Nonnull SimpleEnchanting plugin, @Nonnull ItemCategory items) {
        if (hasScrollSubcategory(items)) {
            return;
        }

        BsonDocument document;
        try {
            document = ItemCategory.CODEC.encode(items).asDocument();
        } catch (RuntimeException e) {
            LOGGER.atWarning().withCause(e).log(
                    "Could not serialise the '%s' creative category; scroll sub-tab not added", PARENT_CATEGORY_ID);
            return;
        }
        // Absent optional fields may be encoded as null; the decoder expects them to be omitted.
        document.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().isNull());

        BsonArray children = document.containsKey("Children") ? document.getArray("Children") : new BsonArray();
        children.add(new BsonDocument()
                .append("Id", new BsonString(SCROLL_SUBCATEGORY_ID))
                .append("Name", new BsonString(SUBCATEGORY_NAME_KEY))
                .append("Icon", new BsonString(SUBCATEGORY_ICON))
                .append("Order", new BsonInt32(SUBCATEGORY_ORDER)));
        document.put("Children", children);

        String packKey = plugin.getIdentifier().toString();
        injecting = true;
        try {
            // Same pipeline a JSON file goes through: codec, validation, packet cache
            // invalidation and update packets. forceLoadAll skips duplicate detection so
            // re-merging after a vanilla reload is idempotent.
            AssetLoadResult<String, ItemCategory> result = ItemCategory.getAssetStore().loadBuffersWithKeys(
                    packKey,
                    List.of(new RawAsset<>((Path) null, PARENT_CATEGORY_ID, null, 0,
                            document.toJson().toCharArray(), null, ContainedAssetCodec.Mode.NONE)),
                    AssetUpdateQuery.DEFAULT, true);
            if (result.getFailedToLoadKeys().isEmpty()) {
                LOGGER.atInfo().log("Added the '%s' sub-tab to the '%s' creative tab; sub-tabs are now: %s",
                        SCROLL_SUBCATEGORY_ID, PARENT_CATEGORY_ID, childIds(currentParent()));
            } else {
                LOGGER.atSevere().log(
                        "The merged '%s' creative category failed to load; scrolls will be missing from the creative library",
                        PARENT_CATEGORY_ID);
            }
        } catch (RuntimeException e) {
            LOGGER.atSevere().withCause(e).log(
                    "Failed to add the scroll sub-tab to the '%s' creative tab", PARENT_CATEGORY_ID);
        } finally {
            injecting = false;
        }
    }

    @Nonnull
    private static String childIds(@Nullable ItemCategory category) {
        if (category == null || category.getChildren() == null) {
            return "(none)";
        }
        StringBuilder ids = new StringBuilder();
        for (ItemCategory child : category.getChildren()) {
            if (ids.length() > 0) {
                ids.append(", ");
            }
            ids.append(child != null ? child.getId() : "null");
        }
        return ids.toString();
    }

    private static boolean hasScrollSubcategory(@Nonnull ItemCategory category) {
        ItemCategory[] children = category.getChildren();
        if (children == null) {
            return false;
        }
        for (ItemCategory child : children) {
            if (child != null && SCROLL_SUBCATEGORY_ID.equals(child.getId())) {
                return true;
            }
        }
        return false;
    }
}
