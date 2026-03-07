package org.herolias.plugin.enchantment;

import com.hypixel.hytale.server.core.event.events.entity.LivingEntityInventoryChangeEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import org.herolias.plugin.SimpleEnchanting;

/**
 * Event-driven listener for updating enchantment visuals (Glow).
 * Replaces the heavy polling mechanism for inventory content changes.
 */
public class EnchantmentVisualsListener {
    
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final EnchantmentManager enchantmentManager;

    public EnchantmentVisualsListener(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
    }

    public void onInventoryChange(LivingEntityInventoryChangeEvent event) {
        try {
            LivingEntity entity = event.getEntity();
            if (!(entity instanceof Player player)) {
                return;
            }

            if (player.getWorld() != null && !player.getWorld().isInThread()) {
                player.getWorld().execute(() -> onInventoryChange(event));
                return;
            }

            Ref<EntityStore> entityRef = player.getReference();
            if (entityRef == null || !entityRef.isValid()) {
                return;
            }
            
            // Get the ECS store from the world
            if (player.getWorld() == null) {
                return;
            }
            Store<EntityStore> store = player.getWorld().getEntityStore().getStore();
            
            if (store != null) {
                // Update glow stats when inventory content changes
                // This covers: Armor equip/unequip, Picking up items, Dropping items, Consuming items
                EnchantmentVisualsHelper.updateGlowStats(entityRef, store, player, enchantmentManager);
            }
        } catch (Exception e) {
            LOGGER.atWarning().log("Error in EnchantmentVisualsListener: " + e.getMessage());
        }
    }
}
