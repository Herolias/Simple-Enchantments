package org.herolias.plugin.enchantment;

import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.ApplicationEffects;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.combat.DamageCalculator;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.config.EnchantingConfig;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies dynamic configuration values to the static JSON EntityEffects for
 * Burn and Freeze.
 * Uses Java reflection to modify the loaded asset objects.
 *
 * Also provides a programmatic fallback registration path: if the asset pack
 * fails to load the custom effects (e.g. due to server version mismatch or
 * asset path changes), this class will construct and register them at runtime
 * using the same {@code AssetStore.loadAssets()} pattern used elsewhere
 * in the mod (see {@link ScrollItemGenerator}).
 */
public class EnchantmentDynamicEffects {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String SOURCE_ID = "SimpleEnchanting:EffectFallback";

    public static final String BURN_EFFECT_ID = "BurnEnchantment";
    public static final String FREEZE_EFFECT_ID = "FreezeEnchantment";
    public static final String POISON_EFFECT_ID = "PoisonEnchantment";

    /** Guards against re-entrant loadAssets triggering nested events. */
    private static volatile boolean registering = false;
    /** Tracks whether fallback registration has already been attempted. */
    private static volatile boolean fallbackAttempted = false;

    private static Field effectDurationField;
    private static Field effectAppEffectsField;
    private static Field appEffectsSpeedMultField;
    private static Field effectDamageCalcField;
    private static Field damageCalcBaseDamageField;
    private static Field effectDebuffField;
    private static Field effectInfiniteField;
    private static Field effectOverlapBehaviorField;
    private static Field effectDamageCalcCooldownField;

    /** Reflective constructors for classes with protected no-arg constructors. */
    private static java.lang.reflect.Constructor<ApplicationEffects> appEffectsConstructor;
    private static java.lang.reflect.Constructor<DamageCalculator> damageCalcConstructor;

    static {
        try {
            effectDurationField = EntityEffect.class.getDeclaredField("duration");
            effectDurationField.setAccessible(true);

            effectAppEffectsField = EntityEffect.class.getDeclaredField("applicationEffects");
            effectAppEffectsField.setAccessible(true);

            appEffectsSpeedMultField = ApplicationEffects.class.getDeclaredField("horizontalSpeedMultiplier");
            appEffectsSpeedMultField.setAccessible(true);

            effectDamageCalcField = EntityEffect.class.getDeclaredField("damageCalculator");
            effectDamageCalcField.setAccessible(true);

            damageCalcBaseDamageField = DamageCalculator.class.getDeclaredField("baseDamage");
            damageCalcBaseDamageField.setAccessible(true);

        } catch (NoSuchFieldException e) {
            LOGGER.atSevere().log("Failed to initialize EnchantmentDynamicEffects reflection: " + e.getMessage());
        }

        // Optional fields — may not exist on all engine versions, so fail silently
        try {
            effectDebuffField = EntityEffect.class.getDeclaredField("debuff");
            effectDebuffField.setAccessible(true);
        } catch (NoSuchFieldException ignored) { }

        try {
            effectInfiniteField = EntityEffect.class.getDeclaredField("infinite");
            effectInfiniteField.setAccessible(true);
        } catch (NoSuchFieldException ignored) { }

        try {
            effectOverlapBehaviorField = EntityEffect.class.getDeclaredField("overlapBehavior");
            effectOverlapBehaviorField.setAccessible(true);
        } catch (NoSuchFieldException ignored) { }

        try {
            effectDamageCalcCooldownField = EntityEffect.class.getDeclaredField("damageCalculatorCooldown");
            effectDamageCalcCooldownField.setAccessible(true);
        } catch (NoSuchFieldException ignored) { }

        // Constructors for ApplicationEffects and DamageCalculator are protected —
        // must use reflection to instantiate from a different package.
        try {
            appEffectsConstructor = ApplicationEffects.class.getDeclaredConstructor();
            appEffectsConstructor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            LOGGER.atSevere().log("Failed to find ApplicationEffects() constructor: " + e.getMessage());
        }

        try {
            damageCalcConstructor = DamageCalculator.class.getDeclaredConstructor();
            damageCalcConstructor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            LOGGER.atSevere().log("Failed to find DamageCalculator() constructor: " + e.getMessage());
        }
    }

    public static void registerEventListener(SimpleEnchanting plugin) {
        plugin.getEventRegistry().register(
                LoadedAssetsEvent.class,
                EntityEffect.class,
                EnchantmentDynamicEffects::onEffectsLoaded);
        LOGGER.atInfo().log("EnchantmentDynamicEffects registered");
    }

    private static void onEffectsLoaded(
            LoadedAssetsEvent<String, EntityEffect, DefaultAssetMap<String, EntityEffect>> event) {
        // Guard against re-entrant events from our own loadAssets() call
        if (registering) return;

        // If this batch contains our effects (asset pack loaded them), apply overrides
        if (event.getLoadedAssets().containsKey(BURN_EFFECT_ID) || event.getLoadedAssets().containsKey(FREEZE_EFFECT_ID)
                || event.getLoadedAssets().containsKey(POISON_EFFECT_ID)) {
            LOGGER.atInfo().log("Custom enchantment effects found in loaded assets batch — applying overrides");
            EnchantingConfig config = SimpleEnchanting.getInstance().getConfigManager().getConfig();
            applyOverrides(config, event.getLoadedAssets());
            return;
        }

        // Fallback: if asset pack didn't load our effects, register them programmatically.
        // We wait for at least one EntityEffect load event (vanilla effects) so the
        // AssetStore is initialized, then check the global map.
        if (!fallbackAttempted) {
            fallbackAttempted = true;

            boolean burnMissing  = EntityEffect.getAssetMap().getAsset(BURN_EFFECT_ID) == null;
            boolean freezeMissing = EntityEffect.getAssetMap().getAsset(FREEZE_EFFECT_ID) == null;
            boolean poisonMissing = EntityEffect.getAssetMap().getAsset(POISON_EFFECT_ID) == null;

            if (burnMissing || freezeMissing || poisonMissing) {
                LOGGER.atWarning().log("Enchantment effects not loaded from asset pack (burn="
                        + burnMissing + ", freeze=" + freezeMissing + ", poison=" + poisonMissing
                        + "). Attempting programmatic fallback registration...");
                registerEffectsFallback(burnMissing, freezeMissing, poisonMissing);
            }
        }
    }

    // ─────────────────── Programmatic Fallback Registration ───────────────────

    /**
     * Constructs and registers missing EntityEffect assets at runtime.
     * Mirrors the JSON definitions shipped in the asset pack:
     *   Server/Entity/Effects/Status/FreezeEnchantment.json
     *   Server/Entity/Effects/Status/BurnEnchantment.json
     *   Server/Entity/Effects/Status/PoisonEnchantment.json
     */
    private static void registerEffectsFallback(boolean burnMissing, boolean freezeMissing, boolean poisonMissing) {
        List<EntityEffect> toRegister = new ArrayList<>();

        try {
            if (freezeMissing) {
                EntityEffect freeze = createFreezeEffect();
                if (freeze != null) toRegister.add(freeze);
            }
            if (burnMissing) {
                EntityEffect burn = createBurnEffect();
                if (burn != null) toRegister.add(burn);
            }
            if (poisonMissing) {
                EntityEffect poison = createPoisonEffect();
                if (poison != null) toRegister.add(poison);
            }

            if (!toRegister.isEmpty()) {
                registering = true;
                try {
                    EntityEffect.getAssetStore().loadAssets(SOURCE_ID, toRegister);
                    LOGGER.atInfo().log("Fallback: registered " + toRegister.size()
                            + " enchantment effect(s) programmatically");

                    // Now apply config overrides to the freshly registered effects
                    EnchantingConfig config = SimpleEnchanting.getInstance().getConfigManager().getConfig();
                    applyOverrides(config);
                } finally {
                    registering = false;
                }
            }
        } catch (Exception e) {
            LOGGER.atSevere().log("Fallback effect registration failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Constructs a FreezeEnchantment EntityEffect matching the JSON definition:
     *   Duration: 5, HorizontalSpeedMultiplier: 0.5, Debuff: true
     */
    private static EntityEffect createFreezeEffect() {
        try {
            EntityEffect effect = new EntityEffect(FREEZE_EFFECT_ID);

            if (effectDurationField != null)
                effectDurationField.set(effect, 5.0f);

            // Create ApplicationEffects with the slow multiplier (protected constructor — use reflection)
            if (appEffectsConstructor == null) {
                LOGGER.atWarning().log("Fallback: cannot create ApplicationEffects — constructor not available");
            } else {
                ApplicationEffects appEffects = appEffectsConstructor.newInstance();
                if (appEffectsSpeedMultField != null)
                    appEffectsSpeedMultField.set(appEffects, 0.5f);
                if (effectAppEffectsField != null)
                    effectAppEffectsField.set(effect, appEffects);
            }

            setOptionalFields(effect, true, false);

            LOGGER.atInfo().log("Fallback: constructed FreezeEnchantment effect");
            return effect;
        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to construct FreezeEnchantment: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Constructs a BurnEnchantment EntityEffect matching the JSON definition:
     *   Duration: 3, BaseDamage Fire: 5, DamageCalculatorCooldown: 1, Debuff: true
     */
    private static EntityEffect createBurnEffect() {
        try {
            EntityEffect effect = new EntityEffect(BURN_EFFECT_ID);

            if (effectDurationField != null)
                effectDurationField.set(effect, 3.0f);

            // Create DamageCalculator with Fire base damage (protected constructor — use reflection)
            if (damageCalcConstructor != null) {
                DamageCalculator calculator = damageCalcConstructor.newInstance();
                Int2FloatMap baseDamage = new Int2FloatOpenHashMap();
                int fireIndex = DamageCause.getAssetMap().getIndex("Fire");
                if (fireIndex != Integer.MIN_VALUE) {
                    baseDamage.put(fireIndex, 5.0f);
                } else {
                    LOGGER.atWarning().log("Fallback: 'Fire' DamageCause not found — burn damage may not work");
                }
                if (damageCalcBaseDamageField != null)
                    damageCalcBaseDamageField.set(calculator, baseDamage);
                if (effectDamageCalcField != null)
                    effectDamageCalcField.set(effect, calculator);
            }

            if (effectDamageCalcCooldownField != null)
                effectDamageCalcCooldownField.set(effect, 1.0f);

            // Create minimal ApplicationEffects (protected constructor — use reflection)
            if (appEffectsConstructor != null) {
                ApplicationEffects appEffects = appEffectsConstructor.newInstance();
                if (effectAppEffectsField != null)
                    effectAppEffectsField.set(effect, appEffects);
            }

            setOptionalFields(effect, true, false);

            LOGGER.atInfo().log("Fallback: constructed BurnEnchantment effect");
            return effect;
        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to construct BurnEnchantment: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Constructs a PoisonEnchantment EntityEffect matching the JSON definition:
     *   Duration: 4, BaseDamage Poison: 3, DamageCalculatorCooldown: 1, Debuff: true
     */
    private static EntityEffect createPoisonEffect() {
        try {
            EntityEffect effect = new EntityEffect(POISON_EFFECT_ID);

            if (effectDurationField != null)
                effectDurationField.set(effect, 4.0f);

            // Create DamageCalculator with Poison base damage (protected constructor — use reflection)
            if (damageCalcConstructor != null) {
                DamageCalculator calculator = damageCalcConstructor.newInstance();
                Int2FloatMap baseDamage = new Int2FloatOpenHashMap();
                int poisonIndex = DamageCause.getAssetMap().getIndex("Poison");
                if (poisonIndex != Integer.MIN_VALUE) {
                    baseDamage.put(poisonIndex, 3.0f);
                } else {
                    LOGGER.atWarning().log("Fallback: 'Poison' DamageCause not found — poison damage may not work");
                }
                if (damageCalcBaseDamageField != null)
                    damageCalcBaseDamageField.set(calculator, baseDamage);
                if (effectDamageCalcField != null)
                    effectDamageCalcField.set(effect, calculator);
            }

            if (effectDamageCalcCooldownField != null)
                effectDamageCalcCooldownField.set(effect, 1.0f);

            // Create minimal ApplicationEffects (protected constructor — use reflection)
            if (appEffectsConstructor != null) {
                ApplicationEffects appEffects = appEffectsConstructor.newInstance();
                if (effectAppEffectsField != null)
                    effectAppEffectsField.set(effect, appEffects);
            }

            setOptionalFields(effect, true, false);

            LOGGER.atInfo().log("Fallback: constructed PoisonEnchantment effect");
            return effect;
        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to construct PoisonEnchantment: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /** Sets debuff, infinite, and overlapBehavior fields if they exist on this engine version. */
    private static void setOptionalFields(EntityEffect effect, boolean debuff, boolean infinite) {
        try {
            if (effectDebuffField != null)
                effectDebuffField.set(effect, debuff);
            if (effectInfiniteField != null)
                effectInfiniteField.set(effect, infinite);
            if (effectOverlapBehaviorField != null) {
                // Attempt to set OverlapBehavior enum to "Overwrite"
                Class<?> enumClass = effectOverlapBehaviorField.getType();
                if (enumClass.isEnum()) {
                    for (Object constant : enumClass.getEnumConstants()) {
                        if (constant.toString().equalsIgnoreCase("Overwrite")) {
                            effectOverlapBehaviorField.set(effect, constant);
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to set optional effect fields: " + e.getMessage());
        }
    }

    /**
     * Applies the configuration overrides to the cached EntityEffect objects.
     * Can be called during config reload or asset load.
     */
    public static void applyOverrides(EnchantingConfig config) {
        try {
            // Need to fetch from AssetStore if they are already loaded
            if (EntityEffect.getAssetStore() != null) {
                EntityEffect burn = EntityEffect.getAssetMap().getAsset(BURN_EFFECT_ID);
                EntityEffect freeze = EntityEffect.getAssetMap().getAsset(FREEZE_EFFECT_ID);
                EntityEffect poison = EntityEffect.getAssetMap().getAsset(POISON_EFFECT_ID);

                if (burn != null)
                    applyBurnOverrides(burn, config);
                if (freeze != null)
                    applyFreezeOverrides(freeze, config);
                if (poison != null)
                    applyPoisonOverrides(poison, config);
            }
        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to apply dynamic effect overrides: " + e.getMessage());
        }
    }

    private static void applyOverrides(EnchantingConfig config, Map<String, EntityEffect> loadedAssets) {
        EntityEffect burn = loadedAssets.get(BURN_EFFECT_ID);
        EntityEffect freeze = loadedAssets.get(FREEZE_EFFECT_ID);
        EntityEffect poison = loadedAssets.get(POISON_EFFECT_ID);

        if (burn != null)
            applyBurnOverrides(burn, config);
        if (freeze != null)
            applyFreezeOverrides(freeze, config);
        if (poison != null)
            applyPoisonOverrides(poison, config);
    }

    private static void applyBurnOverrides(EntityEffect burnEffect, EnchantingConfig config) {
        if (effectDurationField == null)
            return;
        try {
            double burnDuration = config.enchantmentMultipliers.getOrDefault("burn:duration", 3.0);
            effectDurationField.set(burnEffect, (float) burnDuration);

            DamageCalculator calculator = (DamageCalculator) effectDamageCalcField.get(burnEffect);
            if (calculator != null) {
                Int2FloatMap baseDamage = (Int2FloatMap) damageCalcBaseDamageField.get(calculator);
                if (baseDamage == null) {
                    baseDamage = new Int2FloatOpenHashMap();
                    damageCalcBaseDamageField.set(calculator, baseDamage);
                }
                int fireIndex = DamageCause.getAssetMap().getIndex("Fire");
                if (fireIndex != Integer.MIN_VALUE) {
                    double dps = config.enchantmentMultipliers.getOrDefault("burn", 5.0);
                    baseDamage.put(fireIndex, (float) dps);
                }
            }
            LOGGER.atInfo().log("Applied dynamic overrides to BurnEnchantment. Duration: " + burnDuration + "s, DPS: "
                    + config.enchantmentMultipliers.get("burn"));
        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to override BurnEnchantment values: " + e.getMessage());
        }
    }

    private static void applyFreezeOverrides(EntityEffect freezeEffect, EnchantingConfig config) {
        if (effectDurationField == null)
            return;
        try {
            double freezeDuration = config.enchantmentMultipliers.getOrDefault("freeze:duration", 5.0);
            effectDurationField.set(freezeEffect, (float) freezeDuration);

            ApplicationEffects appEffects = (ApplicationEffects) effectAppEffectsField.get(freezeEffect);
            if (appEffects != null) {
                double slow = config.enchantmentMultipliers.getOrDefault("freeze", 0.5);
                appEffectsSpeedMultField.set(appEffects, (float) slow);
            }
            LOGGER.atInfo().log("Applied dynamic overrides to FreezeEnchantment. Duration: " + freezeDuration
                    + "s, Slow: " + config.enchantmentMultipliers.get("freeze"));
        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to override FreezeEnchantment values: " + e.getMessage());
        }
    }

    private static void applyPoisonOverrides(EntityEffect poisonEffect, EnchantingConfig config) {
        if (effectDurationField == null)
            return;
        try {
            double poisonDuration = config.enchantmentMultipliers.getOrDefault("poison:duration", 4.0);
            effectDurationField.set(poisonEffect, (float) poisonDuration);

            DamageCalculator calculator = (DamageCalculator) effectDamageCalcField.get(poisonEffect);
            if (calculator != null) {
                Int2FloatMap baseDamage = (Int2FloatMap) damageCalcBaseDamageField.get(calculator);
                if (baseDamage == null) {
                    baseDamage = new Int2FloatOpenHashMap();
                    damageCalcBaseDamageField.set(calculator, baseDamage);
                }
                int poisonIndex = DamageCause.getAssetMap().getIndex("Poison");
                if (poisonIndex != Integer.MIN_VALUE) {
                    double dps = config.enchantmentMultipliers.getOrDefault("poison", 3.0);
                    baseDamage.put(poisonIndex, (float) dps);
                }
            }
            LOGGER.atInfo().log("Applied dynamic overrides to PoisonEnchantment. Duration: " + poisonDuration
                    + "s, DPS: " + config.enchantmentMultipliers.get("poison"));
        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to override PoisonEnchantment values: " + e.getMessage());
        }
    }

}
