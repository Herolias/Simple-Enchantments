package org.herolias.plugin.enchantment;

import com.hypixel.hytale.server.core.inventory.InventoryChangeEvent;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import javax.annotation.Nonnull;
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
public class EnchantmentVisualsListener extends EntityEventSystem<EntityStore, InventoryChangeEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final EnchantmentManager enchantmentManager;

    public EnchantmentVisualsListener(EnchantmentManager enchantmentManager) {
        super(InventoryChangeEvent.class);
        this.enchantmentManager = enchantmentManager;
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk, @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull InventoryChangeEvent event) {
        try {
            LivingEntity entity = (LivingEntity) EntityUtils.getEntity(index, archetypeChunk);
            if (!(entity instanceof Player player)) {
                return;
            }

            if (player.getWorld() != null && !player.getWorld().isInThread()) {
                player.getWorld().execute(() -> handle(index, archetypeChunk, store, commandBuffer, event));
                return;
            }

            Ref<EntityStore> entityRef = player.getReference();
            if (entityRef == null || !entityRef.isValid()) {
                return;
            }

            if (player.getWorld() == null) {
                return;
            }

            if (store != null) {
                // Update glow stats when inventory content changes
                // This covers: Armor equip/unequip, Picking up items, Dropping items, Consuming
                // items
                EnchantmentVisualsHelper.updateGlowStats(entityRef, store, player, enchantmentManager);
            }
        } catch (Exception e) {
            LOGGER.atWarning().log("Error in EnchantmentVisualsListener: " + e.getMessage());
        }
    }
}
