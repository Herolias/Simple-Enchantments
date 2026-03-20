package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ColorLight;
import com.hypixel.hytale.protocol.ComponentUpdate;
import com.hypixel.hytale.protocol.ComponentUpdateType;
import com.hypixel.hytale.protocol.DynamicLightUpdate;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Set;

/**
 * ECS ticking system that sends a per-player DynamicLight packet update
 * to players wearing a helmet with the Night Vision enchantment.
 * 
 * Instead of adding a real DynamicLight component (which broadcasts to all
 * players),
 * this queues a ComponentUpdate with the DynamicLight data directly to the
 * wearing player's own EntityViewer — making the light visible only to them.
 */
public class EnchantmentNightVisionSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final short HELMET_SLOT = 0;

    // Light config
    private static final byte LIGHT_RADIUS = -1;
    // Minimal values
    private static final byte LIGHT_RED = (byte) 1;
    private static final byte LIGHT_GREEN = (byte) 1;
    private static final byte LIGHT_BLUE = (byte) 1;

    @Nonnull
    private static final Query<EntityStore> QUERY = Query.and(
            EntityModule.get().getPlayerComponentType(),
            EntityTrackerSystems.EntityViewer.getComponentType());

    private final EnchantmentManager enchantmentManager;

    // Track which players currently have the night vision light active
    private final Set<Ref<EntityStore>> activeNightVision = new HashSet<>();

    public EnchantmentNightVisionSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentNightVisionSystem initialized (per-player mode)");
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return QUERY;
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

            // Check if helmet has Night Vision enchantment
            boolean hasNightVision = false;
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

            var ref = archetypeChunk.getReferenceTo(index);
            boolean wasActive = activeNightVision.contains(ref);

            // Get the player's own EntityViewer component
            EntityTrackerSystems.EntityViewer viewer = archetypeChunk.getComponent(
                    index, EntityTrackerSystems.EntityViewer.getComponentType());
            if (viewer == null) {
                return;
            }

            if (hasNightVision && !wasActive) {
                // Send DynamicLight update ONLY to this player
                DynamicLightUpdate update = new DynamicLightUpdate();
                // update.type is not accessible or needed for DynamicLightUpdate
                update.dynamicLight = new ColorLight(LIGHT_RADIUS, LIGHT_RED, LIGHT_GREEN, LIGHT_BLUE);
                viewer.queueUpdate(ref, update);
                activeNightVision.add(ref);

                com.hypixel.hytale.server.core.universe.PlayerRef playerRef = store.getComponent(ref,
                        com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                int level = enchantmentManager.getEnchantmentLevel(inventory.getArmor().getItemStack(HELMET_SLOT),
                        EnchantmentType.NIGHT_VISION);
                EnchantmentEventHelper.fireActivated(playerRef, inventory.getArmor().getItemStack(HELMET_SLOT),
                        EnchantmentType.NIGHT_VISION, level);
            } else if (!hasNightVision && wasActive) {
                // Remove the light from this player only
                viewer.queueRemove(ref, ComponentUpdateType.DynamicLight);
                activeNightVision.remove(ref);
            }
            // Optimization: Do NOTHING if hasNightVision && wasActive.
            // This prevents spamming packets every tick.

        } catch (Exception e) {
            LOGGER.atWarning().log("Error in NightVision system: " + e.getMessage());
        }
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return false; // Not parallel-safe due to shared activeNightVision set
    }
}
