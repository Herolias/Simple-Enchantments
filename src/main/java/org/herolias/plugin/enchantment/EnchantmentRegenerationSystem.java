package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ECS ticking system that applies passive health regeneration to players
 * wearing a chestplate with the Regeneration enchantment.
 * <p>
 * Regeneration rate is configurable via the enchantment multiplier system.
 * Default rate: 0.5 HP/s.
 */
public class EnchantmentRegenerationSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final short CHEST_SLOT = (short) ItemArmorSlot.Chest.getValue();

    /** Throttle event firing to avoid spam (fire every ~5 seconds). */
    private static final float EVENT_FIRE_INTERVAL = 5.0f;

    /** How often stale (invalid-ref) event timers are evicted. */
    private static final long SWEEP_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    @Nonnull
    private static final Query<EntityStore> QUERY = Query.and(
            Player.getComponentType(),
            EntityStatMap.getComponentType(),
            InventoryComponent.Armor.getComponentType());

    private final EnchantmentManager enchantmentManager;

    /**
     * Time since last event fire per player, to throttle event spam. Shared by
     * all world threads, hence concurrent; entries whose ref died are swept
     * periodically (ticking systems get no entity-removal callback).
     */
    private final Map<Ref<EntityStore>, Float> eventTimers = new ConcurrentHashMap<>();
    private final AtomicLong lastSweepNanos = new AtomicLong(System.nanoTime());

    public EnchantmentRegenerationSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentRegenerationSystem initialized");
    }

    /**
     * @deprecated The Regeneration → Second Stomach hand-off was removed: the two
     *             enchantments conflict, so it could never run. Use
     *             {@link #EnchantmentRegenerationSystem(EnchantmentManager)}; the
     *             second argument is ignored.
     */
    @Deprecated
    public EnchantmentRegenerationSystem(EnchantmentManager enchantmentManager,
            @Nullable EnchantmentSecondStomachSystem ignored) {
        this(enchantmentManager);
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
            InventoryComponent.Armor armorComponent = archetypeChunk.getComponent(index,
                    InventoryComponent.Armor.getComponentType());
            ItemContainer armorContainer = armorComponent != null ? armorComponent.getInventory() : null;
            if (armorContainer == null || armorContainer.getCapacity() <= CHEST_SLOT) {
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

            // Get the configured regeneration rate (HP/s)
            double regenRate = EnchantmentType.REGENERATION.getEffectMultiplier();
            float healAmount = (float) (regenRate * dt);

            if (healAmount <= 0.0f) {
                return;
            }

            EntityStatMap statMap = archetypeChunk.getComponent(index, EntityStatMap.getComponentType());
            if (statMap == null) {
                return;
            }

            statMap.addStatValue(DefaultEntityStatTypes.getHealth(), healAmount);

            // Throttle event firing
            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
            float timer = eventTimers.getOrDefault(ref, 0.0f) + dt;
            if (timer >= EVENT_FIRE_INTERVAL) {
                timer = 0.0f;
                PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
                EnchantmentEventHelper.fireActivated(playerRef, chestplate, EnchantmentType.REGENERATION, level);
            }
            eventTimers.put(ref, timer);

            sweepStaleTimers();
        } catch (Exception e) {
            LOGGER.atWarning().atMostEvery(30, TimeUnit.SECONDS).withCause(e)
                    .log("Error in Regeneration system");
        }
    }

    /** Drops timers of entities that no longer exist so the map cannot grow unbounded. */
    private void sweepStaleTimers() {
        long now = System.nanoTime();
        long last = lastSweepNanos.get();
        if (now - last < SWEEP_INTERVAL_NANOS || !lastSweepNanos.compareAndSet(last, now)) {
            return;
        }
        eventTimers.keySet().removeIf(ref -> !ref.isValid());
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return false;
    }
}
