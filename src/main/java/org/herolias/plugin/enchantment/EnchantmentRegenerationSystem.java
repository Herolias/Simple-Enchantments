package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * ECS ticking system that applies passive health regeneration to players
 * wearing a chestplate with the Regeneration enchantment.
 * <p>
 * Regeneration rate is configurable via the enchantment multiplier system.
 * Default rate: 0.5 HP/s.
 * <p>
 * Reports its healing amounts to {@link EnchantmentSecondStomachSystem} so
 * that Second Stomach can distinguish between passive regen and instant healing.
 */
public class EnchantmentRegenerationSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final short CHEST_SLOT = (short) ItemArmorSlot.Chest.getValue();

    /** Throttle event firing to avoid spam (fire every ~5 seconds). */
    private static final float EVENT_FIRE_INTERVAL = 5.0f;

    @Nonnull
    private static final Query<EntityStore> QUERY = Query.and(
            EntityModule.get().getPlayerComponentType());

    private final EnchantmentManager enchantmentManager;

    /**
     * Reference to the Second Stomach system for reporting regen healing.
     * May be null if Second Stomach is not registered.
     */
    @Nullable
    private final EnchantmentSecondStomachSystem secondStomachSystem;

    /** Track time since last event fire per player to throttle event spam. */
    private final Map<Ref<EntityStore>, Float> eventTimers = new HashMap<>();

    public EnchantmentRegenerationSystem(EnchantmentManager enchantmentManager,
                                         @Nullable EnchantmentSecondStomachSystem secondStomachSystem) {
        this.enchantmentManager = enchantmentManager;
        this.secondStomachSystem = secondStomachSystem;
        LOGGER.atInfo().log("EnchantmentRegenerationSystem initialized");
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

            ItemContainer armorContainer = inventory.getArmor();
            if (armorContainer == null) {
                return;
            }

            ItemStack chestplate = armorContainer.getItemStack(CHEST_SLOT);
            if (chestplate == null || chestplate.isEmpty()) {
                return;
            }

            int level = enchantmentManager.getEnchantmentLevel(chestplate, EnchantmentType.REGENERATION);
            if (level <= 0) {
                return;
            }

            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);

            // Get the configured regeneration rate (HP/s)
            double regenRate = EnchantmentType.REGENERATION.getEffectMultiplier();
            float healAmount = (float) (regenRate * dt);

            if (healAmount <= 0.0f) {
                return;
            }

            EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
            if (statMap == null) {
                return;
            }

            statMap.addStatValue(DefaultEntityStatTypes.getHealth(), healAmount);

            // Report healing to Second Stomach system so it can exclude regen
            // from instant-heal detection
            if (secondStomachSystem != null) {
                secondStomachSystem.addExpectedRegeneration(ref, healAmount);
            }

            // Throttle event firing
            float timer = eventTimers.getOrDefault(ref, 0.0f) + dt;
            if (timer >= EVENT_FIRE_INTERVAL) {
                timer = 0.0f;
                com.hypixel.hytale.server.core.universe.PlayerRef playerRef = store.getComponent(ref,
                        com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                EnchantmentEventHelper.fireActivated(playerRef, chestplate,
                        EnchantmentType.REGENERATION, level);
            }
            eventTimers.put(ref, timer);

        } catch (Exception e) {
            LOGGER.atWarning().log("Error in Regeneration system: " + e.getMessage());
        }
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return false;
    }
}

