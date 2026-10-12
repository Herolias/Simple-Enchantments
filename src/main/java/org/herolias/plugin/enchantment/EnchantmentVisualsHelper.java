package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.herolias.plugin.SimpleEnchanting;
import org.herolias.plugin.engravingtable.EngravingTableColorOption;
import org.herolias.plugin.engravingtable.EngravingTableCustomizationData;
import org.herolias.plugin.util.InventoryAccess;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.TimeUnit;

/**
 * Writes the {@code EnchantmentGlow_*} entity stats that drive the glow
 * {@code ItemAppearanceConditions} injected by {@link EnchantmentGlowInjector}.
 * Called on inventory content changes ({@link EnchantmentVisualsListener}),
 * active slot changes ({@link EnchantmentActiveSlotSystem}) and when the
 * player entity is added ({@link EnchantmentPlayerLifecycleSystem}).
 */
public final class EnchantmentVisualsHelper {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String STAT_GLOW_PRIMARY = "EnchantmentGlow_Primary";
    private static final String STAT_GLOW_SHIELD = "EnchantmentGlow_Shield";
    private static final String STAT_GLOW_HEAD = "EnchantmentGlow_Head";
    private static final String STAT_GLOW_CHEST = "EnchantmentGlow_Chest";
    private static final String STAT_GLOW_HANDS = "EnchantmentGlow_Hands";
    private static final String STAT_GLOW_LEGS = "EnchantmentGlow_Legs";
    private static final float GLOW_OFF = 0.0f;

    private EnchantmentVisualsHelper() {
    }

    /**
     * Recomputes every glow stat of the player from the items currently held
     * and worn. Must be called on the owning world thread.
     */
    public static void updateGlowStats(@Nonnull Ref<EntityStore> entityRef,
            @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull EnchantmentManager enchantmentManager) {
        EntityStatMap statMap = accessor.getComponent(entityRef, EntityStatMap.getComponentType());
        if (statMap == null) {
            return;
        }
        PlayerRef playerRef = accessor.getComponent(entityRef, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }

        boolean glowEnabled;
        try {
            glowEnabled = SimpleEnchanting.getInstance().getUserSettingsManager()
                    .getEnableEnchantmentGlow(playerRef.getUuid());
        } catch (RuntimeException e) {
            LOGGER.atWarning().atMostEvery(30, TimeUnit.SECONDS).withCause(e)
                    .log("Could not read the enchantment glow setting for %s; leaving glow unchanged", playerRef.getUuid());
            return;
        }

        if (!glowEnabled) {
            setGlowStat(statMap, STAT_GLOW_PRIMARY, GLOW_OFF);
            setGlowStat(statMap, STAT_GLOW_SHIELD, GLOW_OFF);
            setGlowStat(statMap, STAT_GLOW_HEAD, GLOW_OFF);
            setGlowStat(statMap, STAT_GLOW_CHEST, GLOW_OFF);
            setGlowStat(statMap, STAT_GLOW_HANDS, GLOW_OFF);
            setGlowStat(statMap, STAT_GLOW_LEGS, GLOW_OFF);
            return;
        }

        ItemStack offHandItem = InventoryAccess.getUtilityItem(accessor, entityRef);
        boolean hasOffHand = !ItemStack.isEmpty(offHandItem);

        // 1. Shield glow: a shield may sit in the off-hand or the main hand.
        float shieldGlowValue = GLOW_OFF;
        if (hasOffHand && enchantmentManager.hasAnyEnabledEnchantment(offHandItem)
                && enchantmentManager.isShield(offHandItem)) {
            shieldGlowValue = getGlowStatValue(offHandItem, false);
        }

        ItemStack heldItem = getPrimaryHeldItem(accessor, entityRef);
        boolean heldEnchanted = enchantmentManager.hasAnyEnabledEnchantment(heldItem);
        boolean heldIsShield = enchantmentManager.isShield(heldItem);
        if (heldEnchanted && heldIsShield) {
            shieldGlowValue = getGlowStatValue(heldItem, false);
        }
        setGlowStat(statMap, STAT_GLOW_SHIELD, shieldGlowValue);

        // 2. Primary glow (weapons/tools): only when the held item is enchanted and
        // not a shield (shields use the shield stat).
        if (heldEnchanted && !heldIsShield) {
            setGlowStat(statMap, STAT_GLOW_PRIMARY, getGlowStatValue(heldItem, !hasOffHand));
        } else {
            setGlowStat(statMap, STAT_GLOW_PRIMARY, GLOW_OFF);
        }

        // 3. Armor glow per slot.
        updateArmorGlow(statMap, InventoryAccess.getArmor(accessor, entityRef), enchantmentManager);
    }

    @Nonnull
    private static ItemStack getPrimaryHeldItem(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> entityRef) {
        ItemStack item = InventoryAccess.getItemInHand(accessor, entityRef);
        if (!ItemStack.isEmpty(item)) {
            return item;
        }
        ItemStack hotbarItem = InventoryAccess.getActiveHotbarItem(accessor, entityRef);
        if (!ItemStack.isEmpty(hotbarItem)) {
            return hotbarItem;
        }
        ItemStack toolItem = InventoryAccess.getActiveToolItem(accessor, entityRef);
        if (!ItemStack.isEmpty(toolItem)) {
            return toolItem;
        }
        ItemStack utilityItem = InventoryAccess.getUtilityItem(accessor, entityRef);
        return utilityItem == null ? ItemStack.EMPTY : utilityItem;
    }

    private static void setGlowStat(@Nonnull EntityStatMap statMap, @Nonnull String statId, float value) {
        int index = EntityStatType.getAssetMap().getIndex(statId);
        if (index == Integer.MIN_VALUE) {
            LOGGER.atWarning().atMostEvery(5, TimeUnit.MINUTES)
                    .log("Entity stat %s is not loaded; enchantment glow cannot be shown", statId);
            return;
        }
        statMap.setStatValue(index, value);
    }

    private static float getGlowStatValue(@Nonnull ItemStack itemStack, boolean singleGlow) {
        EngravingTableColorOption glowColor = EngravingTableCustomizationData.fromItemStack(itemStack).getGlowColorOrDefault();
        return glowColor.getGlowStatValue(singleGlow);
    }

    private static void updateArmorGlow(@Nonnull EntityStatMap statMap,
            @Nullable ItemContainer armorContainer,
            @Nonnull EnchantmentManager manager) {
        if (armorContainer == null) {
            return;
        }
        for (ItemArmorSlot slot : ItemArmorSlot.VALUES) {
            ItemStack armorItem = armorContainer.getItemStack((short) slot.getValue());
            boolean isEnchanted = manager.hasAnyEnabledEnchantment(armorItem);
            String statId = switch (slot) {
                case Head -> STAT_GLOW_HEAD;
                case Chest -> STAT_GLOW_CHEST;
                case Hands -> STAT_GLOW_HANDS;
                case Legs -> STAT_GLOW_LEGS;
            };
            setGlowStat(statMap, statId, isEnchanted ? getGlowStatValue(armorItem, false) : GLOW_OFF);
        }
    }
}
