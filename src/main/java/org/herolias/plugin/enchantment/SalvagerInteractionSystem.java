package org.herolias.plugin.enchantment;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.meta.BlockState;
import com.hypixel.hytale.builtin.crafting.state.ProcessingBenchState;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;

import javax.annotation.Nonnull;

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
        return Archetype.empty();
    }

    @Override
    @SuppressWarnings("removal")
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull UseBlockEvent.Pre event) {
        
        if (event.isCancelled()) return;
        
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        Player player = commandBuffer.getComponent(ref, Player.getComponentType());
        
        if (player == null) return;
        
        Vector3i pos = event.getTargetBlock();
        if (pos == null) return;

        if (player.getWorld() == null) return;

        // Helper to get BlockState respecting filler blocks (Multi-block structures)
        long chunkIndex = ChunkUtil.indexChunkFromBlock(pos.x, pos.z);
        WorldChunk chunk = player.getWorld().getChunk(chunkIndex);
        
        BlockState state = null;
        if (chunk != null) {
            int filler = chunk.getFiller(pos.x, pos.y, pos.z);
            int targetX = pos.x;
            int targetY = pos.y;
            int targetZ = pos.z;
            
            if (filler != 0) {
                targetX -= FillerBlockUtil.unpackX(filler);
                targetY -= FillerBlockUtil.unpackY(filler);
                targetZ -= FillerBlockUtil.unpackZ(filler);
            }
            // getState accepts global coordinates in WorldChunk (handles masking internally)
            state = chunk.getState(targetX, targetY, targetZ);
        }

        
        if (state instanceof ProcessingBenchState) {
            ProcessingBenchState bench = (ProcessingBenchState) state;
            if (bench.getBench() != null) {
                String id = bench.getBench().getId();
                if (BENCH_ID.equals(id)) {
                    salvageSystem.startSession(player, bench);
                }
            }
        }
    }
}
