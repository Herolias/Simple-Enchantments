package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.gameplay.DeathConfig;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.modules.entity.damage.DeferredCorpseRemoval;
import com.hypixel.hytale.server.core.modules.item.ItemModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.systems.NPCDamageSystems;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * ECS system that converts raw food drops to cooked versions when an entity
 * is killed by a weapon with the Burn enchantment.
 *
 * Effect: Enemies drop cooked versions of any cookable items (based on Campfire
 * recipes)
 * Applicable to: Any weapon with the Burn enchantment (melee or ranged via
 * projectile)
 *
 * <p>
 * This mirrors vanilla {@code NPCDamageSystems.DropDeathItems}: it ticks dead
 * NPCs, honours {@code DropDeathItemsInstantly} / the corpse-removal delay and
 * {@code Role.hasDroppedDeathItems()}, and runs right before the vanilla
 * system. It first decides <em>read-only</em> whether any cooking applies; only
 * then does it take over the drop (marking the role as dropped and setting
 * {@code ItemsLossMode.NONE}) and empty the NPC inventory. If nothing is
 * cookable the vanilla system drops everything untouched.
 * </p>
 */
public class EnchantmentBurnSmeltingSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Query<EntityStore> QUERY = Query.and(
            NPCEntity.getComponentType(),
            TransformComponent.getComponentType(),
            HeadRotation.getComponentType(),
            Query.not(Player.getComponentType()),
            DeathComponent.getComponentType());

    // Same window as vanilla DropDeathItems (after the corpse timer ticked), but
    // strictly before it so we can suppress its drops.
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, DeathSystems.TickCorpseRemoval.class),
            new SystemDependency<>(Order.BEFORE, NPCDamageSystems.DropDeathItems.class));

    private final EnchantmentManager enchantmentManager;
    private final CookingRecipeRegistry cookingRecipeRegistry;

    public EnchantmentBurnSmeltingSystem(EnchantmentManager enchantmentManager) {
        this.enchantmentManager = enchantmentManager;
        this.cookingRecipeRegistry = enchantmentManager.getCookingRecipeRegistry();
        LOGGER.atInfo().log("EnchantmentBurnSmeltingSystem initialized");
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
        DeathComponent deathComponent = archetypeChunk.getComponent(index, DeathComponent.getComponentType());
        if (deathComponent == null) {
            return;
        }
        // Only process if the built-in system would also run. If it would not
        // (config, or Looting already took over), nobody needs the DoT attribution
        // for this death any more.
        if (deathComponent.getItemsLossMode() != DeathConfig.ItemsLossMode.ALL) {
            releaseDoTAttribution(index, archetypeChunk);
            return;
        }

        NPCEntity npcComponent = archetypeChunk.getComponent(index, NPCEntity.getComponentType());
        Role role = npcComponent != null ? npcComponent.getRole() : null;
        if (role == null || role.hasDroppedDeathItems()) {
            releaseDoTAttribution(index, archetypeChunk);
            return;
        }

        if (!role.isDropDeathItemsInstantly()) {
            DeferredCorpseRemoval deferredRemoval = archetypeChunk.getComponent(index,
                    DeferredCorpseRemoval.getComponentType());
            if (deferredRemoval != null && !deferredRemoval.shouldRemove()) {
                // Vanilla waits for the corpse timer; so do we.
                return;
            }
        }

        // From here on this is the tick in which vanilla would drop. Decide once,
        // and release the DoT attribution whatever the outcome.
        try {
            Damage deathInfo = deathComponent.getDeathInfo();
            if (deathInfo == null) {
                return;
            }

            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
            EnchantmentManager.DamageEnchantments enchantments = enchantmentManager
                    .resolveDamageEnchantments(deathInfo, commandBuffer, ref);
            int burnLevel = enchantments.burnLevel();

            // Looting is handled (drops included) by EnchantmentLootingSystem.
            if (enchantments.lootingLevel() > 0 || burnLevel <= 0) {
                return;
            }

            // --- Read-only pass: what would vanilla drop, and is any of it cookable?
            ItemContainer storage = null;
            List<ItemStack> candidateDrops = new ArrayList<>();
            if (role.isPickupDropOnDeath()) {
                InventoryComponent.Storage storageComponent = archetypeChunk.getComponent(index,
                        InventoryComponent.Storage.getComponentType());
                if (storageComponent != null) {
                    storage = storageComponent.getInventory();
                    collectContents(storage, candidateDrops);
                }
            }

            List<ItemStack> rolledDrops = List.of();
            String dropListId = role.getDropListId();
            ItemModule itemModule = ItemModule.get();
            if (dropListId != null && itemModule != null && itemModule.isEnabled()) {
                // Roll once; the same roll is what we drop if we take over.
                rolledDrops = itemModule.getRandomItemDrops(dropListId);
                candidateDrops.addAll(rolledDrops);
            }

            if (candidateDrops.isEmpty() || !anyCookable(candidateDrops)) {
                // Nothing to cook: let the vanilla system drop everything as-is.
                return;
            }

            // --- Commit: take over from vanilla exactly like DropDeathItems does.
            role.setDeathItemsDropped();
            deathComponent.setItemsLossMode(DeathConfig.ItemsLossMode.NONE);

            List<ItemStack> allDrops = new ArrayList<>();
            if (storage != null) {
                allDrops.addAll(storage.dropAllItemStacks());
            }
            allDrops.addAll(rolledDrops);

            List<ItemStack> finalDrops = cook(allDrops);
            if (finalDrops.isEmpty()) {
                return;
            }

            TransformComponent transformComponent = archetypeChunk.getComponent(index,
                    TransformComponent.getComponentType());
            HeadRotation headRotationComponent = archetypeChunk.getComponent(index, HeadRotation.getComponentType());
            if (transformComponent == null || headRotationComponent == null) {
                return;
            }

            Vector3d dropPosition = new Vector3d(transformComponent.getPosition()).add(0.0, 1.0, 0.0);
            Rotation3f headRotation = headRotationComponent.getRotation();

            // Spawn ALL item drops ourselves (cooked versions where applicable)
            enchantmentManager.spawnDrops(commandBuffer, finalDrops, dropPosition, headRotation);
            LOGGER.atFine().log("Spawned %d item(s) with Burn cooking applied", finalDrops.size());

            EnchantmentManager.DamageContext ctx = enchantmentManager.getDamageContext(deathInfo, commandBuffer);
            if (ctx.hasAttacker()) {
                ItemStack weapon = enchantmentManager.getWeaponFromEntity(ctx.attackerRef(), commandBuffer);
                if (weapon != null) {
                    PlayerRef playerRef = store.getComponent(ctx.attackerRef(), PlayerRef.getComponentType());
                    EnchantmentEventHelper.fireActivated(playerRef, weapon, EnchantmentType.BURN, burnLevel);
                }
            }
        } finally {
            releaseDoTAttribution(index, archetypeChunk);
        }
    }

    /** Non-destructive snapshot of a container's contents. */
    private static void collectContents(@Nonnull ItemContainer container, @Nonnull List<ItemStack> out) {
        short capacity = container.getCapacity();
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack stack = container.getItemStack(slot);
            if (stack != null && !stack.isEmpty()) {
                out.add(stack);
            }
        }
    }

    private boolean anyCookable(@Nonnull List<ItemStack> drops) {
        for (ItemStack drop : drops) {
            if (cookedVersionOf(drop) != null) {
                return true;
            }
        }
        return false;
    }

    @Nonnull
    private List<ItemStack> cook(@Nonnull List<ItemStack> drops) {
        List<ItemStack> finalDrops = new ArrayList<>(drops.size());
        for (ItemStack drop : drops) {
            if (drop == null || drop.isEmpty()) {
                continue;
            }
            ItemStack cooked = cookedVersionOf(drop);
            if (cooked == null) {
                finalDrops.add(drop);
                continue;
            }
            finalDrops.add(cooked);
            LOGGER.atFine().log("Burn enchantment cooked %s -> %s", drop.getItemId(), cooked.getItemId());
        }
        return finalDrops;
    }

    /** The cooked stack for {@code drop}, or null if it has no (useful) recipe. */
    @Nullable
    private ItemStack cookedVersionOf(@Nullable ItemStack drop) {
        if (drop == null || drop.isEmpty()) {
            return null;
        }
        CookingRecipeRegistry.CookingRecipe recipe = cookingRecipeRegistry.getRecipe(drop);
        if (recipe == null) {
            return null;
        }
        ItemStack cookedOutput = recipe.createOutput(drop.getQuantity());
        if (cookedOutput == null || cookedOutput.isEmpty() || cookedOutput.getItemId().equals(drop.getItemId())) {
            return null;
        }
        return cookedOutput;
    }

    /**
     * Drops the Burn/Looting attribution stored for this entity's DoT death so
     * the map cannot leak entries for NPCs that died without us dropping items.
     */
    private void releaseDoTAttribution(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk) {
        UUIDComponent uuidComponent = archetypeChunk.getComponent(index, UUIDComponent.getComponentType());
        if (uuidComponent != null) {
            enchantmentManager.removeDoTEnchantments(uuidComponent.getUuid());
        }
    }
}
