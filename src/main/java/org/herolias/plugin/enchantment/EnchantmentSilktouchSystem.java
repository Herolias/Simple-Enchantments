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
import com.hypixel.hytale.server.core.asset.type.blocksound.config.BlockSoundSet;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockBreakingDropType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.event.events.ecs.DamageBlockEvent;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.blockhealth.BlockHealthChunk;
import com.hypixel.hytale.server.core.modules.blockhealth.BlockHealthModule;
import com.hypixel.hytale.server.core.modules.interaction.BlockInteractionUtils;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.protocol.BlockSoundEvent;
import com.hypixel.hytale.protocol.SoundCategory;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;

/**
 * ECS system that applies Silk Touch enchantment to block break events.
 * Drops the block itself instead of the usual drops.
 * 
 * We hook into DamageBlockEvent to detect when the block is about to be
 * destroyed.
 * This prevents the vanilla block dropping logic (which ignores BreakBlockEvent
 * cancellation
 * for things like gravel/rubble that have specific tool drops) from triggering.
 */
public class EnchantmentSilktouchSystem extends EntityEventSystem<EntityStore, DamageBlockEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final EnchantmentManager enchantmentManager;

    public EnchantmentSilktouchSystem(EnchantmentManager enchantmentManager) {
        super(DamageBlockEvent.class);
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
            @Nonnull DamageBlockEvent event) {
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
            return;
        }

        // Check if the item representation of the block is blacklisted
        String blockItemId = blockItem.getId();
        if (enchantmentManager.isPickPerfectBlacklistedItem(blockItemId)) {
            return;
        }

        // Only trigger Silk Touch if the block is going to break on this hit
        Vector3i targetBlock = event.getTargetBlock();
        World world = store.getExternalData().getWorld();
        Store<ChunkStore> chunkStore = world.getChunkStore().getStore();

        long chunkIndex = ChunkUtil.indexChunkFromBlock(targetBlock.getX(), targetBlock.getZ());
        WorldChunk chunk = world.getChunk(chunkIndex);

        if (chunk == null) {
            return;
        }

        Ref<ChunkStore> chunkRef = chunk.getReference();
        if (chunkRef == null || !chunkRef.isValid()) {
            return;
        }

        BlockHealthChunk healthChunk = chunkStore.getComponent(chunkRef,
                BlockHealthModule.get().getBlockHealthChunkComponentType());
        if (healthChunk == null) {
            return;
        }

        boolean willBreak = healthChunk.isBlockFragile(targetBlock)
                || (event.getCurrentDamage() - event.getDamage() <= 0.001f);
        if (!willBreak) {
            return;
        }

        // Resolve drops using the block's gathering data for correct quantities.
        // When a drop list exists AND all its resolved items match the block's own item,
        // use the resolved drops (handles combined half slabs dropping 2 instead of 1).
        // Otherwise fall back to 1x block item (silk touch: always drop the block itself).
        List<ItemStack> silkTouchDrops;
        BlockGathering gathering = blockType.getGathering();
        BlockBreakingDropType breaking = gathering != null ? gathering.getBreaking() : null;

        if (breaking != null && breaking.getDropListId() != null) {
            List<ItemStack> resolvedDrops = BlockHarvestUtils.getDrops(blockType, breaking.getQuantity(),
                    null, breaking.getDropListId());
            boolean allMatchBlockItem = !resolvedDrops.isEmpty();
            for (ItemStack drop : resolvedDrops) {
                if (drop.getItem() == null || !blockItemId.equals(drop.getItem().getId())) {
                    allMatchBlockItem = false;
                    break;
                }
            }
            silkTouchDrops = allMatchBlockItem
                    ? resolvedDrops
                    : Collections.singletonList(new ItemStack(blockItemId, 1));
        } else {
            silkTouchDrops = Collections.singletonList(new ItemStack(blockItemId, 1));
        }

        Ref<EntityStore> breakerRef = archetypeChunk.getReferenceTo(index);

        // IMPORTANT: Cancel the DamageBlockEvent so vanilla drops (and block breaking
        // logic) don't trigger
        event.setCancelled(true);

        BlockChunk blockChunk = chunkStore.getComponent(chunkRef, BlockChunk.getComponentType());
        if (blockChunk == null)
            return;

        int setBlockSettings = 0;
        setBlockSettings |= 0x100;
        boolean naturalAction = breakerRef != null && breakerRef.isValid()
                ? BlockInteractionUtils.isNaturalAction(breakerRef, store)
                : BlockInteractionUtils.isNaturalAction(null, store);

        if (!naturalAction) {
            setBlockSettings |= 0x800; // Suppress entity drops if unnatural
        }

        // Break the block manually
        chunk.breakBlock(targetBlock.getX(), targetBlock.getY(), targetBlock.getZ(), setBlockSettings);
        healthChunk.removeBlock(world, targetBlock);

        com.hypixel.hytale.server.core.universe.PlayerRef playerRef = null;
        if (breakerRef != null && breakerRef.isValid()) {
            playerRef = store.getComponent(breakerRef,
                    com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());

            // Play the block break sound
            BlockSoundSet soundSet = BlockSoundSet.getAssetMap().getAsset(blockType.getBlockSoundSetIndex());
            if (soundSet != null) {
                int soundEventIndex = soundSet.getSoundEventIndices().getOrDefault(BlockSoundEvent.Break, 0);
                if (soundEventIndex != 0) {
                    BlockSection section = blockChunk.getSectionAtBlockY(targetBlock.getY());
                    int rotationIndex = section.getRotationIndex(targetBlock.getX(), targetBlock.getY(),
                            targetBlock.getZ());
                    Vector3d centerPosition = new Vector3d();
                    blockType.getBlockCenter(rotationIndex, centerPosition);
                    centerPosition.add(targetBlock);
                    SoundUtil.playSoundEvent3d(soundEventIndex, SoundCategory.SFX, centerPosition, store);
                }
            }
        }

        // Spawn the Silk Touch drops
        Vector3d dropPosition = new Vector3d(targetBlock.getX() + 0.5, targetBlock.getY(), targetBlock.getZ() + 0.5);
        enchantmentManager.spawnDrops(commandBuffer, silkTouchDrops, dropPosition);

        EnchantmentEventHelper.fireActivated(playerRef, tool, EnchantmentType.PICK_PERFECT,
                enchantmentManager.getEnchantmentLevel(tool, EnchantmentType.PICK_PERFECT));

        // Apply durability manually, since we cancelled DamageBlockEvent and bypassed
        // the vanilla BlockHarvestUtils.performBlockDamage durability path.
        // Delegate to BlockHarvestUtils.calculateDurabilityUse to match vanilla exactly
        // (soft block check, block set matching, etc.).
        if (breakerRef != null && breakerRef.isValid()) {
            com.hypixel.hytale.server.core.entity.Entity rawEntity = com.hypixel.hytale.server.core.entity.EntityUtils
                    .getEntity(breakerRef, store);
            if (rawEntity instanceof LivingEntity entity) {
                byte activeHotbarSlot = entity.getInventory().getActiveHotbarSlot();
                if (activeHotbarSlot != -1 && ItemUtils.canDecreaseItemStackDurability(breakerRef, store)
                        && !tool.isUnbreakable()) {
                    double durabilityLoss = BlockHarvestUtils.calculateDurabilityUse(tool.getItem(), blockType);
                    if (durabilityLoss > 0) {
                        entity.updateItemStackDurability(breakerRef, tool, entity.getInventory().getHotbar(),
                                activeHotbarSlot, -durabilityLoss, store);
                    }
                }
            }
        }
    }
}
