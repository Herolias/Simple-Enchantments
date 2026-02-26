package org.herolias.plugin.enchantment;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import org.herolias.plugin.SimpleEnchanting;

public class EnchantmentVisualsHelper {

    private static final String STAT_GLOW_PRIMARY = "EnchantmentGlow_Primary";

    private static final String STAT_GLOW_SHIELD = "EnchantmentGlow_Shield";
    private static final String STAT_GLOW_HEAD = "EnchantmentGlow_Head";
    private static final String STAT_GLOW_CHEST = "EnchantmentGlow_Chest";
    private static final String STAT_GLOW_HANDS = "EnchantmentGlow_Hands";
    private static final String STAT_GLOW_LEGS = "EnchantmentGlow_Legs";
    private static final float GLOW_ON = 1.0f;
    private static final float GLOW_OFF = 0.0f;
    


    public static void updateGlowStats(
        com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> entityRef,
        com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> store,
        Player player,
        EnchantmentManager enchantmentManager
    ) {
        try {
            clearLegacyEntityGlow(entityRef, store);
            EntityStatMap statMap = store.getComponent(entityRef, EntityStatMap.getComponentType());
            if (statMap == null) {
                return;
            }

            com.hypixel.hytale.server.core.universe.PlayerRef playerRef = store.getComponent(entityRef, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
            if (playerRef == null) return;

            boolean isGlowEnabled = SimpleEnchanting.getInstance().getUserSettingsManager().getEnableEnchantmentGlow(playerRef.getUuid());
            
            Inventory inventory = player.getInventory();
            if (inventory == null) {
                return;
            }

            if (!isGlowEnabled) {
                setGlowStat(statMap, STAT_GLOW_PRIMARY, false);
                setGlowStat(statMap, STAT_GLOW_SHIELD, false);
                setGlowStat(statMap, STAT_GLOW_HEAD, false);
                setGlowStat(statMap, STAT_GLOW_CHEST, false);
                setGlowStat(statMap, STAT_GLOW_HANDS, false);
                setGlowStat(statMap, STAT_GLOW_LEGS, false);
                return;
            }

            // GLOW LOGIC START

            ItemStack offHandItem = inventory.getUtilityItem();
            boolean hasOffHand = !ItemStack.isEmpty(offHandItem);

            // 1. Calculate Shield Glow (Checks both offhand and mainhand for shield)
            boolean shouldGlowShield = false;

            // Check Offhand for Shield
            if (hasOffHand && enchantmentManager.hasAnyEnabledEnchantment(offHandItem) && enchantmentManager.isShield(offHandItem)) {
                shouldGlowShield = true;
            }

            // Check Mainhand for Shield (in case they hold shield in main hand)
            // Cache the held item reference — reused for Primary Glow and Eternal Shot
            ItemStack heldItem = getPrimaryHeldItem(inventory);
            boolean heldEnchanted = enchantmentManager.hasAnyEnabledEnchantment(heldItem);
            boolean heldIsShield = enchantmentManager.isShield(heldItem);

            if (heldEnchanted && heldIsShield) {
                shouldGlowShield = true;
            }

            setGlowStat(statMap, STAT_GLOW_SHIELD, shouldGlowShield);

            // 2. Calculate Primary Glow (Weapons/Tools)
            // Only applies if the held item is enchanted AND NOT a shield (since shields use STAT_GLOW_SHIELD)
            if (heldEnchanted && !heldIsShield) {
                 if (hasOffHand) {
                    // Value 1.0f = Standard Glow
                    statMap.setStatValue(EntityStatType.getAssetMap().getIndex(STAT_GLOW_PRIMARY), 1.0f);
                } else {
                    // Value 2.0f = Single Glow
                    statMap.setStatValue(EntityStatType.getAssetMap().getIndex(STAT_GLOW_PRIMARY), 2.0f);
                }
            } else {
                setGlowStat(statMap, STAT_GLOW_PRIMARY, false);
            }
            // GLOW LOGIC END

            // 3. Update Eternal Shot Stat — reuses cached heldItem
            boolean hasEternalShot = !ItemStack.isEmpty(heldItem) && enchantmentManager.hasEnchantment(heldItem, EnchantmentType.ETERNAL_SHOT);
            setGlowStat(statMap, "eternal_shot_active", hasEternalShot);
            
            // Update armor glow for each slot
            updateArmorGlow(statMap, inventory, enchantmentManager);
        } catch (Exception e) {
            // Suppress errors
        }
    }

    private static ItemStack getPrimaryHeldItem(Inventory inventory) {
        ItemStack item = inventory.getItemInHand();
        if (!ItemStack.isEmpty(item)) {
            return item;
        }
        ItemStack hotbarItem = inventory.getActiveHotbarItem();
        if (!ItemStack.isEmpty(hotbarItem)) {
            return hotbarItem;
        }
        ItemStack toolItem = inventory.getActiveToolItem();
        if (!ItemStack.isEmpty(toolItem)) {
            return toolItem;
        }
        ItemStack utilityItem = inventory.getUtilityItem();
        return utilityItem == null ? ItemStack.EMPTY : utilityItem;
    }



    private static void setGlowStat(EntityStatMap statMap, String statId, boolean enabled) {
        int index = EntityStatType.getAssetMap().getIndex(statId);
        if (index == Integer.MIN_VALUE) {
            return;
        }
        statMap.setStatValue(index, enabled ? GLOW_ON : GLOW_OFF);
    }

    private static void updateArmorGlow(EntityStatMap statMap, Inventory inventory, EnchantmentManager manager) {
        ItemContainer armorContainer = inventory.getArmor();
        if (armorContainer == null) {
            return;
        }

        // Check each armor slot: Head (0), Chest (1), Hands (2), Legs (3)
        for (ItemArmorSlot slot : ItemArmorSlot.VALUES) {
            ItemStack armorItem = armorContainer.getItemStack((short) slot.ordinal());
            boolean isEnchanted = manager.hasAnyEnabledEnchantment(armorItem);
            
            String statId = switch (slot) {
                case Head -> STAT_GLOW_HEAD;
                case Chest -> STAT_GLOW_CHEST;
                case Hands -> STAT_GLOW_HANDS;
                case Legs -> STAT_GLOW_LEGS;
            };
            
            setGlowStat(statMap, statId, isEnchanted);
        }
    }

    private static void clearLegacyEntityGlow(
        com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> entityRef,
        com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> store
    ) {
        EffectControllerComponent effectController = store.getComponent(entityRef, EffectControllerComponent.getComponentType());
        if (effectController == null) {
            return;
        }

        String effectName = "EnchantmentGlow";
        EntityEffect effectAsset = EntityEffect.getAssetMap().getAsset(effectName);
        if (effectAsset == null) {
            effectName = "enchantmentglow";
            effectAsset = EntityEffect.getAssetMap().getAsset(effectName);
        }
        if (effectAsset == null) {
            return;
        }

        int effectIndex = EntityEffect.getAssetMap().getIndex(effectName);
        if (effectController.getActiveEffects().containsKey(effectIndex)) {
            effectController.removeEffect(entityRef, effectIndex, store);
        }
    }
}
