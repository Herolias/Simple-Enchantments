package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.modules.interaction.BlockInteractionUtils;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;

/**
 * ECS system that applies Silk Touch enchantment to block break events.
 * Drops the block itself instead of the usual drops.
 */
public class EnchantmentSilktouchSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final EnchantmentManager enchantmentManager;

    public EnchantmentSilktouchSystem(EnchantmentManager enchantmentManager) {
        super(BreakBlockEvent.class);
        this.enchantmentManager = enchantmentManager;
        LOGGER.atInfo().log("EnchantmentSilktouchSystem initialized");
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

        if (!enchantmentManager.hasEnchantment(tool, EnchantmentType.PICK_PERFECT)) {
            return;
        }

        if (!enchantmentManager.isTool(tool)) {
            return;
        }

        BlockType blockType = event.getBlockType();
        if (blockType == null) {
            return;
        }

        // Get the item representation of the block
        Item blockItem = blockType.getItem();
        if (blockItem == null) {
            // Some blocks might not have an item form (e.g. specialized technical blocks),
            // so we skip silk touch for them.
            return;
        }
        
        // Create the drop: 1 unit of the block itself
        ItemStack silkTouchDrop = new ItemStack(blockItem.getId(), 1);

        // Standard block breaking logic (remove block, play sounds/particles)
        Vector3i targetBlock = event.getTargetBlock();
        World world = store.getExternalData().getWorld();
        Store<ChunkStore> chunkStore = world.getChunkStore().getStore();
        
        long chunkIndex = ChunkUtil.indexChunkFromBlock(targetBlock.getX(), targetBlock.getZ());
        // Use World.getChunk to safely get the chunk wrapper
        com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk chunk = world.getChunk(chunkIndex);
        
        if (chunk == null) {
            return;
        }
        
        Ref<ChunkStore> chunkRef = chunk.getReference();
        if (chunkRef == null || !chunkRef.isValid()) {
            return;
        }

        BlockChunk blockChunk = chunkStore.getComponent(chunkRef, BlockChunk.getComponentType());
        if (blockChunk == null) {
            return;
        }

        BlockSection blockSection = blockChunk.getSectionAtBlockY(targetBlock.getY());
        int filler = blockSection.getFiller(targetBlock.getX(), targetBlock.getY(), targetBlock.getZ());

        Ref<EntityStore> breakerRef = archetypeChunk.getReferenceTo(index);
        boolean naturalAction = breakerRef != null && breakerRef.isValid()
            ? BlockInteractionUtils.isNaturalAction(breakerRef, store)
            : BlockInteractionUtils.isNaturalAction(null, store);

        int setBlockSettings = 0;
        setBlockSettings |= 0x100;
        if (!naturalAction) {
            setBlockSettings |= 0x800;
        }

        // IMPORTANT: Cancel the original event so normal drops don't spawn
        event.setCancelled(true);

        // Remove the block from the world
        BlockHarvestUtils.naturallyRemoveBlock(targetBlock, blockType, filler, 0, null, null, setBlockSettings, chunkRef, store, chunkStore);

        // Spawn the Silk Touch drop
        Vector3d dropPosition = new Vector3d(targetBlock.getX() + 0.5, targetBlock.getY(), targetBlock.getZ() + 0.5);
        List<ItemStack> drops = Collections.singletonList(silkTouchDrop);
        
        enchantmentManager.spawnDrops(commandBuffer, drops, dropPosition);
    }
}
