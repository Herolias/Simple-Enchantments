package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockBreakingDropType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * ECS system that applies Fortune enchantment to block break events.
 */
public class EnchantmentFortuneSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final EnchantmentManager enchantmentManager;

    public EnchantmentFortuneSystem(EnchantmentManager enchantmentManager) {
        super(BreakBlockEvent.class);
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentFortuneSystem initialized");
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void handle(int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull BreakBlockEvent event) {
        if (event.isCancelled()) {
            return;
        }

        ItemStack tool = event.getItemInHand();
        if (tool == null || tool.isEmpty()) {
            return;
        }

        if (!enchantmentManager.hasEnchantment(tool, EnchantmentType.FORTUNE)) {
            return;
        }

        if (enchantmentManager.categorizeItem(tool) != ItemCategory.PICKAXE) {
            return;
        }

        BlockType blockType = event.getBlockType();
        if (blockType == null) {
            return;
        }

        BlockGathering gathering = blockType.getGathering();
        BlockBreakingDropType breaking = gathering != null ? gathering.getBreaking() : null;
        if (breaking == null) {
            return;
        }

        if (!enchantmentManager.isFortuneTarget(blockType, breaking)) {
            return;
        }

        int fortuneLevel = enchantmentManager.getEnchantmentLevel(tool, EnchantmentType.FORTUNE);
        List<ItemStack> extraDrops = enchantmentManager.getFortuneDrops(blockType, breaking, fortuneLevel);

        if (extraDrops.isEmpty()) {
            return;
        }

        Vector3i targetBlock = event.getTargetBlock();
        Vector3d dropPosition = new Vector3d(targetBlock.getX() + 0.5, targetBlock.getY(), targetBlock.getZ() + 0.5);

        enchantmentManager.spawnDrops(commandBuffer, extraDrops, dropPosition);

        com.hypixel.hytale.server.core.universe.PlayerRef playerRef = store.getComponent(
                com.hypixel.hytale.server.core.entity.EntityUtils.getEntity(index, archetypeChunk).getReference(),
                com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
        EnchantmentEventHelper.fireActivated(playerRef, tool, EnchantmentType.FORTUNE, fortuneLevel);
    }

}
