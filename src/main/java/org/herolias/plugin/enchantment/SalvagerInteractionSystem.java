package org.herolias.plugin.enchantment;

import com.hypixel.hytale.builtin.crafting.component.BenchBlock;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.Bench;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Starts tracking a Salvager Bench when a player uses it. Everything but a
 * cheap block-type check happens only for the bench itself.
 */
public class SalvagerInteractionSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final EnchantmentSalvageSystem salvageSystem;
    private static final String BENCH_ID = "Salvagebench";

    public SalvagerInteractionSystem(EnchantmentSalvageSystem salvageSystem) {
        super(UseBlockEvent.Pre.class);
        this.salvageSystem = salvageSystem;
        LOGGER.atInfo().log("SalvagerInteractionSystem initialized");
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void handle(int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull UseBlockEvent.Pre event) {

        if (event.isCancelled())
            return;

        // Early exit on the block type before touching the chunk store. Filler
        // positions of a multiblock bench carry the same block id, and state
        // variants inherit the Bench config from their base type.
        if (!isSalvageBench(event.getBlockType()))
            return;

        Vector3i pos = event.getTargetBlock();
        if (pos == null)
            return;

        World world = store.getExternalData().getWorld();

        // Resolve the section components directly, as required by Update 6's cubic
        // chunk API. This also preserves filler-block handling for multiblock benches.
        ChunkStore chunkStoreManager = world.getChunkStore();
        Store<ChunkStore> chunkStore = chunkStoreManager.getStore();
        Ref<ChunkStore> sectionRef = chunkStoreManager.getChunkSectionReferenceAtBlock(pos.x, pos.y, pos.z);
        if (sectionRef == null || !sectionRef.isValid()) {
            return;
        }

        BlockSection blockSection = chunkStore.getComponent(sectionRef, BlockSection.getComponentType());
        if (blockSection == null) {
            return;
        }

        int filler = blockSection.getFiller(pos.x, pos.y, pos.z);
        int targetX = pos.x - FillerBlockUtil.unpackX(filler);
        int targetY = pos.y - FillerBlockUtil.unpackY(filler);
        int targetZ = pos.z - FillerBlockUtil.unpackZ(filler);

        sectionRef = chunkStoreManager.getChunkSectionReferenceAtBlock(targetX, targetY, targetZ);
        if (sectionRef == null || !sectionRef.isValid()) {
            return;
        }

        Ref<ChunkStore> blockRef = BlockModule.getBlockEntity(chunkStore, sectionRef, targetX, targetY, targetZ);
        if (blockRef != null && blockRef.isValid()) {
            ProcessingBenchBlock benchState = chunkStore.getComponent(blockRef,
                    ProcessingBenchBlock.getComponentType());
            if (benchState != null && benchState.getBench() != null
                    && BENCH_ID.equals(benchState.getBench().getId())) {
                salvageSystem.trackBench(world, benchState, new Vector3i(targetX, targetY, targetZ));
            }
        }
    }

    private static boolean isSalvageBench(@Nullable BlockType blockType) {
        if (blockType == null) {
            return false;
        }
        Bench bench = blockType.getBench();
        if (bench == null) {
            BlockType base = BenchBlock.getBaseBlockType(blockType);
            bench = base != null ? base.getBench() : null;
        }
        return bench != null && BENCH_ID.equals(bench.getId());
    }
}
