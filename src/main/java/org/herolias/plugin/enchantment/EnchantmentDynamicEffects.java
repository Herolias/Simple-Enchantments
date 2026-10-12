package org.herolias.plugin.enchantment;

import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.config.EnchantingConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Applies the configured duration/damage/slow values to the enchantment
 * {@link EntityEffect}s (Burn, Freeze and its Environmental-Protection
 * variants, Poison).
 * <p>
 * The effects ship as JSON in this plugin's asset pack. Rather than mutating
 * the live asset objects, the shipped document is read from the jar, the
 * configured values are written into it, the document is decoded through the
 * asset store (which resolves {@code Parent} inheritance and runs the normal
 * validation) and the result is loaded into the store under this plugin's pack
 * key. Loading replaces the asset map entry, so the cached client packet and
 * every lookup by id see the new values.
 * <p>
 * This runs whenever the asset pack (re)loads the effects
 * ({@link LoadedAssetsEvent}) and whenever the configuration is (re)loaded
 * ({@link #applyOverrides}).
 */
public final class EnchantmentDynamicEffects {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final String BURN_EFFECT_ID = "BurnEnchantment";
    public static final String FREEZE_EFFECT_ID = "FreezeEnchantment";
    public static final String POISON_EFFECT_ID = "PoisonEnchantment";
    /** Freeze variants whose slow is pre-mitigated by Environmental Protection; they share Freeze's duration. */
    public static final List<String> FREEZE_ENV_PROT_EFFECT_IDS = List.of(
            "FreezeEnchantment_EnvProt_1", "FreezeEnchantment_EnvProt_2", "FreezeEnchantment_EnvProt_3");

    private static final List<String> MANAGED_EFFECT_IDS;

    static {
        List<String> ids = new ArrayList<>(List.of(BURN_EFFECT_ID, FREEZE_EFFECT_ID, POISON_EFFECT_ID));
        ids.addAll(FREEZE_ENV_PROT_EFFECT_IDS);
        MANAGED_EFFECT_IDS = List.copyOf(ids);
    }

    /** Location of the shipped effect documents inside the jar. */
    private static final String RESOURCE_DIRECTORY = "/Server/Entity/Effects/Status/";

    private static final String KEY_DURATION = "Duration";
    private static final String KEY_APPLICATION_EFFECTS = "ApplicationEffects";
    private static final String KEY_HORIZONTAL_SPEED_MULTIPLIER = "HorizontalSpeedMultiplier";
    private static final String KEY_DAMAGE_CALCULATOR = "DamageCalculator";
    private static final String KEY_BASE_DAMAGE = "BaseDamage";

    /** Guards against reacting to the LoadedAssetsEvent fired by our own loadAssets call. */
    private static volatile boolean rebuilding;

    private EnchantmentDynamicEffects() {
    }

    public static void registerEventListener(@Nonnull SimpleEnchanting plugin) {
        plugin.getEventRegistry().register(
                LoadedAssetsEvent.class,
                EntityEffect.class,
                EnchantmentDynamicEffects::onEffectsLoaded);
        LOGGER.atInfo().log("EnchantmentDynamicEffects registered");
    }

    private static void onEffectsLoaded(
            @Nonnull LoadedAssetsEvent<String, EntityEffect, DefaultAssetMap<String, EntityEffect>> event) {
        if (rebuilding) {
            return;
        }
        List<String> loadedIds = new ArrayList<>();
        Map<String, EntityEffect> loaded = event.getLoadedAssets();
        for (String id : MANAGED_EFFECT_IDS) {
            if (loaded.containsKey(id)) {
                loadedIds.add(id);
            }
        }
        if (loadedIds.isEmpty()) {
            return;
        }
        rebuild(SimpleEnchanting.getInstance().getConfigManager().getConfig(), loadedIds);
    }

    /**
     * Applies the configuration to every managed effect that is currently
     * loaded. Effects the asset pack has not loaded yet are picked up by the
     * {@link LoadedAssetsEvent} listener instead.
     */
    public static void applyOverrides(@Nonnull EnchantingConfig config) {
        IndexedLookupTableAssetMap<String, EntityEffect> assetMap;
        try {
            assetMap = EntityEffect.getAssetMap();
        } catch (RuntimeException e) {
            LOGGER.atFine().withCause(e).log("EntityEffect asset store not available yet; effects are configured on load");
            return;
        }
        List<String> presentIds = new ArrayList<>();
        for (String id : MANAGED_EFFECT_IDS) {
            if (assetMap.getAsset(id) != null) {
                presentIds.add(id);
            }
        }
        if (presentIds.isEmpty()) {
            return;
        }
        rebuild(config, presentIds);
    }

    private static void rebuild(@Nonnull EnchantingConfig config, @Nonnull Collection<String> ids) {
        String packKey = SimpleEnchanting.getInstance().getIdentifier().toString();
        AssetStore<String, EntityEffect, IndexedLookupTableAssetMap<String, EntityEffect>> store = EntityEffect
                .getAssetStore();

        List<EntityEffect> rebuilt = new ArrayList<>(ids.size());
        List<String> rebuiltIds = new ArrayList<>(ids.size());
        for (String id : ids) {
            BsonDocument document = readShippedDocument(id);
            if (document == null) {
                LOGGER.atWarning().log("Effect %s has no shipped definition in the jar; configured values not applied", id);
                continue;
            }
            applyConfig(id, document, config);
            try {
                rebuilt.add(store.decode(packKey, id, document));
                rebuiltIds.add(id);
            } catch (RuntimeException e) {
                LOGGER.atWarning().withCause(e).log("Failed to decode effect %s with configured values", id);
            }
        }
        if (rebuilt.isEmpty()) {
            return;
        }

        rebuilding = true;
        try {
            store.loadAssets(packKey, rebuilt);
        } catch (RuntimeException e) {
            LOGGER.atSevere().withCause(e).log("Failed to load rebuilt enchantment effects %s", rebuiltIds);
            return;
        } finally {
            rebuilding = false;
        }
        LOGGER.atInfo().log("Applied configured values to enchantment effects %s", rebuiltIds);
    }

    /** Reads the shipped JSON definition of an effect from the jar, or null when it is not bundled. */
    @Nullable
    private static BsonDocument readShippedDocument(@Nonnull String effectId) {
        String path = RESOURCE_DIRECTORY + effectId + ".json";
        try (InputStream in = EnchantmentDynamicEffects.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return BsonDocument.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            LOGGER.atWarning().withCause(e).log("Failed to read shipped effect definition %s", path);
            return null;
        }
    }

    /** Writes the configured values for the effect into its document. */
    private static void applyConfig(@Nonnull String effectId, @Nonnull BsonDocument document,
            @Nonnull EnchantingConfig config) {
        Map<String, Double> multipliers = config.enchantmentMultipliers;
        switch (effectId) {
            case BURN_EFFECT_ID -> {
                setDuration(document, multipliers.getOrDefault("burn:duration", 3.0));
                setBaseDamage(document, "Fire", multipliers.getOrDefault("burn", 5.0));
            }
            case POISON_EFFECT_ID -> {
                setDuration(document, multipliers.getOrDefault("poison:duration", 4.0));
                setBaseDamage(document, "Poison", multipliers.getOrDefault("poison", 3.0));
            }
            case FREEZE_EFFECT_ID -> {
                setDuration(document, multipliers.getOrDefault("freeze:duration", 5.0));
                setHorizontalSpeedMultiplier(document, multipliers.getOrDefault("freeze", 0.5));
            }
            default -> {
                if (FREEZE_ENV_PROT_EFFECT_IDS.contains(effectId)) {
                    // The variants keep their own (pre-mitigated) slow; only the duration is shared.
                    setDuration(document, multipliers.getOrDefault("freeze:duration", 5.0));
                }
            }
        }
    }

    private static void setDuration(@Nonnull BsonDocument document, double seconds) {
        document.put(KEY_DURATION, new BsonDouble(seconds));
    }

    private static void setHorizontalSpeedMultiplier(@Nonnull BsonDocument document, double multiplier) {
        childDocument(document, KEY_APPLICATION_EFFECTS).put(KEY_HORIZONTAL_SPEED_MULTIPLIER, new BsonDouble(multiplier));
    }

    private static void setBaseDamage(@Nonnull BsonDocument document, @Nonnull String damageCause, double damage) {
        childDocument(childDocument(document, KEY_DAMAGE_CALCULATOR), KEY_BASE_DAMAGE)
                .put(damageCause, new BsonDouble(damage));
    }

    @Nonnull
    private static BsonDocument childDocument(@Nonnull BsonDocument parent, @Nonnull String key) {
        if (parent.containsKey(key) && parent.get(key).isDocument()) {
            return parent.getDocument(key);
        }
        BsonDocument child = new BsonDocument();
        parent.put(key, child);
        return child;
    }
}
