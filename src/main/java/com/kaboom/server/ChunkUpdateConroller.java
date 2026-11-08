package com.kaboom.server;

import java.util.BitSet;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import java.util.BitSet;

public class ChunkUpdateConroller implements IWorldEventListener{
    static final class ChunkState {
        final BitSet[] dirtySections = new BitSet[16];
        final long[] sectionRevisions = new long[16];
        int nextScanSection = 0;
    }

    private final chunksnapshot owner;

    public ChunkUpdateConroller(chunksnapshot owner) {
        this.owner = owner;
    }

    @Override
    public void notifyBlockUpdate(
            World world,
            BlockPos pos,
            IBlockState oldState,
            IBlockState newState,
            int flags
    ) {
        if (!(world instanceof WorldServer)) {
            return;
        }

        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        if (y < 0 || y >= 256) {
            return;
        }

        ChunkState state = owner.getUpdateState(
                world.provider.getDimension(),
                x >> 4,
                z >> 4
        );

        // Чанк не отслеживается.
        if (state == null) {
            return;
        }

        int sectionY = y >> 4;

        int index =
                ((y & 15) << 8)
                        | ((z & 15) << 4)
                        | (x & 15);

        BitSet dirty = state.dirtySections[sectionY];

        if (dirty == null) {
            dirty = new BitSet(4096);
            state.dirtySections[sectionY] = dirty;
        }

        dirty.set(index);
    }

    void registerWorld(WorldServer world) {
        world.addEventListener(this);
    }

    void unregisterWorld(WorldServer world) {
        world.removeEventListener(this);
    }
}
