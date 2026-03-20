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
import java.util.Map;

/**
 * Applies dynamic configuration values to the static JSON EntityEffects for
 * Burn and Freeze.
 * Uses Java reflection to modify the loaded asset objects.
 */
public class EnchantmentDynamicEffects {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final String BURN_EFFECT_ID = "BurnEnchantment";
    public static final String FREEZE_EFFECT_ID = "FreezeEnchantment";
    public static final String POISON_EFFECT_ID = "PoisonEnchantment";

    private static Field effectDurationField;
    private static Field effectAppEffectsField;
    private static Field appEffectsSpeedMultField;
    private static Field effectDamageCalcField;
    private static Field damageCalcBaseDamageField;

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
        // Only run config applicator once when our effects show up
        if (event.getLoadedAssets().containsKey(BURN_EFFECT_ID) || event.getLoadedAssets().containsKey(FREEZE_EFFECT_ID)
                || event.getLoadedAssets().containsKey(POISON_EFFECT_ID)) {
            EnchantingConfig config = SimpleEnchanting.getInstance().getConfigManager().getConfig();
            applyOverrides(config, event.getLoadedAssets());
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
