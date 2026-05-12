package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.protocol.ValueType;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.livingentity.LivingEntityEffectSystem;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ECS ticking system that boosts instant healing for players wearing a
 * chestplate with the Second Stomach enchantment.
 * <p>
 * Detects health increases by comparing current health to the value stored
 * at the end of the previous tick. To distinguish instant healing from
 * passive regeneration, the system subtracts all known passive sources:
 * <ol>
 *   <li><b>Reported regeneration</b> — from other mod systems.</li>
 *   <li><b>Native regeneration</b> — read from {@code tempRegenerationValues}.</li>
 *   <li><b>Effect regeneration</b> — periodic food/potion healing (calculated
 *       mathematically by reading {@code ActiveEntityEffect.sinceLastDamage}).</li>
 * </ol>
 */
public class EnchantmentSecondStomachSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final short CHEST_SLOT = (short) ItemArmorSlot.Chest.getValue();

    private static final float EPSILON = 0.001f;

    @Nonnull
    private static final Query<EntityStore> QUERY = Query.and(
            EntityModule.get().getPlayerComponentType());

    private final EnchantmentManager enchantmentManager;

    private final Map<Ref<EntityStore>, Float> previousHealth = new HashMap<>();

    private final ConcurrentHashMap<Ref<EntityStore>, Float> expectedRegeneration = new ConcurrentHashMap<>();

    private final Field tempRegenField;
    private final Field sinceLastDamageField;

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency(Order.AFTER, LivingEntityEffectSystem.class)
    );

    public EnchantmentSecondStomachSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        this.tempRegenField = initField(EntityStatMap.class, "tempRegenerationValues");
        this.sinceLastDamageField = initField(ActiveEntityEffect.class, "sinceLastDamage");
        LOGGER.atInfo().log("EnchantmentSecondStomachSystem initialized");
    }

    private static Field initField(Class<?> clazz, String fieldName) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to reflect " + fieldName + " on " + clazz.getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private float getNativeRegenAmount(EntityStatMap statMap, int statIndex) {
        if (tempRegenField == null) {
            return 0.0f;
        }
        try {
            float[] tempRegenValues = (float[]) tempRegenField.get(statMap);
            if (tempRegenValues != null && statIndex < tempRegenValues.length) {
                return tempRegenValues[statIndex];
            }
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to read tempRegenerationValues: " + e.getMessage());
        }
        return 0.0f;
    }

    private float getSinceLastDamage(ActiveEntityEffect effect) {
        if (sinceLastDamageField == null) {
            return 0.0f;
        }
        try {
            return (float) sinceLastDamageField.get(effect);
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to read sinceLastDamage: " + e.getMessage());
        }
        return 0.0f;
    }

    public void addExpectedRegeneration(Ref<EntityStore> ref, float amount) {
        expectedRegeneration.merge(ref, amount, Float::sum);
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    @Override
    @Nonnull
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public void tick(float dt, int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            Entity entity = EntityUtils.getEntity(index, archetypeChunk);
            if (!(entity instanceof LivingEntity livingEntity)) {
                return;
            }

            Inventory inventory = livingEntity.getInventory();
            if (inventory == null) {
                return;
            }

            ItemContainer armorContainer = inventory.getArmor();
            if (armorContainer == null) {
                return;
            }

            ItemStack chestplate = armorContainer.getItemStack(CHEST_SLOT);
            if (chestplate == null || chestplate.isEmpty()) {
                return;
            }

            int level = enchantmentManager.getEnchantmentLevel(chestplate, EnchantmentType.SECOND_STOMACH);
            if (level <= 0) {
                return;
            }

            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);

            EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
            if (statMap == null) {
                return;
            }

            int healthIndex = DefaultEntityStatTypes.getHealth();
            EntityStatValue healthStat = statMap.get(healthIndex);
            if (healthStat == null) {
                return;
            }

            float currentHealth = healthStat.get();
            Float prevHealth = previousHealth.get(ref);

            float modRegenAmount = expectedRegeneration.getOrDefault(ref, 0.0f);
            expectedRegeneration.remove(ref);

            float nativeRegenAmount = getNativeRegenAmount(statMap, healthIndex);

            // Calculate food/potion regen
            float foodRegenAmount = 0.0f;
            EffectControllerComponent effectController = commandBuffer.getComponent(ref, EffectControllerComponent.getComponentType());
            if (effectController != null) {
                for (ActiveEntityEffect activeEffect : effectController.getActiveEffects().values()) {
                    EntityEffect effect = EntityEffect.getAssetMap().getAsset(activeEffect.getEntityEffectIndex());
                    if (effect == null) continue;
                    
                    float cooldown = effect.getDamageCalculatorCooldown();
                    if (cooldown > 0.0f) {
                        Int2FloatMap stats = effect.getEntityStats();
                        if (stats != null && stats.containsKey(healthIndex)) {
                            float sinceLastDamage = getSinceLastDamage(activeEffect);
                            // Mathematically determine if the effect ticked this frame
                            // cycles = ceil((dt - sinceLastDamage) / cooldown)
                            double ratio = (dt - sinceLastDamage) / cooldown;
                            int cycles = (int) Math.ceil(ratio);
                            
                            if (cycles > 0) {
                                float amount = stats.get(healthIndex);
                                if (effect.getValueType() == ValueType.Percent) {
                                    amount = amount * (healthStat.getMax() - healthStat.getMin()) / 100.0f;
                                }
                                foodRegenAmount += (amount * cycles);
                            }
                        }
                    }
                }
            }

            if (prevHealth == null) {
                previousHealth.put(ref, currentHealth);
                return;
            }

            float totalHealthGain = currentHealth - prevHealth;
            float instantHealGain = totalHealthGain - modRegenAmount - nativeRegenAmount - foodRegenAmount;

            if (instantHealGain <= EPSILON) {
                previousHealth.put(ref, currentHealth);
                return;
            }

            double multiplier = level * EnchantmentType.SECOND_STOMACH.getEffectMultiplier();
            float bonusHeal = (float) (instantHealGain * multiplier);

            if (bonusHeal <= 0.0f) {
                previousHealth.put(ref, currentHealth);
                return;
            }

            statMap.addStatValue(healthIndex, bonusHeal);
            previousHealth.put(ref, currentHealth + bonusHeal);

            com.hypixel.hytale.server.core.universe.PlayerRef playerRef = store.getComponent(ref,
                    com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
            EnchantmentEventHelper.fireActivated(playerRef, chestplate,
                    EnchantmentType.SECOND_STOMACH, level);

        } catch (Exception e) {
            LOGGER.atWarning().log("Error in Second Stomach system: " + e.getMessage());
        }
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return false;
    }
}
