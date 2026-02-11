package org.herolias.plugin.enchantment;

import com.hypixel.hytale.server.core.event.events.entity.LivingEntityInventoryChangeEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;

/**
 * Event-driven listener that applies or removes the Night Vision status effect
 * based on whether the player's helmet has the Night Vision enchantment.
 * 
 * This handles ONLY the entity effect (status icon in HUD).
 * The DynamicLight component is handled separately by EnchantmentNightVisionSystem (ECS).
 */
public class EnchantmentNightVisionListener {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String NIGHT_VISION_EFFECT_ID = "Night_Vision";
    private static final short HELMET_SLOT = 0;

    private final EnchantmentManager enchantmentManager;

    public EnchantmentNightVisionListener(@Nonnull EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
    }

    public void onInventoryChange(LivingEntityInventoryChangeEvent event) {
        try {
            LivingEntity entity = event.getEntity();
            if (!(entity instanceof Player player)) {
                return;
            }

            Ref<EntityStore> entityRef = player.getReference();
            if (entityRef == null || !entityRef.isValid()) {
                return;
            }

            if (player.getWorld() == null) {
                return;
            }
            Store<EntityStore> store = player.getWorld().getEntityStore().getStore();
            if (store == null) {
                return;
            }

            // Check if the player's helmet has the Night Vision enchantment
            boolean hasNightVision = false;
            Inventory inventory = player.getInventory();
            if (inventory != null) {
                ItemContainer armorContainer = inventory.getArmor();
                if (armorContainer != null) {
                    ItemStack helmet = armorContainer.getItemStack(HELMET_SLOT);
                    if (helmet != null && !helmet.isEmpty()) {
                        int level = enchantmentManager.getEnchantmentLevel(helmet, EnchantmentType.NIGHT_VISION);
                        if (level > 0) {
                            hasNightVision = true;
                        }
                    }
                }
            }

            boolean effectActive = enchantmentManager.hasActiveEffect(entityRef, NIGHT_VISION_EFFECT_ID, store);

            if (hasNightVision && !effectActive) {
                enchantmentManager.applyStatusEffect(entityRef, NIGHT_VISION_EFFECT_ID, store);
            } else if (!hasNightVision && effectActive) {
                enchantmentManager.removeStatusEffect(entityRef, NIGHT_VISION_EFFECT_ID, store);
            }
        } catch (Exception e) {
            LOGGER.atWarning().log("Error in EnchantmentNightVisionListener: " + e.getMessage());
        }
    }
}
