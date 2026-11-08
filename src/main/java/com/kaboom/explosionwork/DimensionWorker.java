package com.kaboom.explosionwork;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.kaboom.Kaboom;
import com.kaboom.server.ChunkLoadingManager;
import net.minecraft.util.math.ChunkPos;

import net.minecraft.util.math.BlockPos;

public class DimensionWorker {
    private final int dimensionId;
    private static final int TEST_RADIUS = 64;
    private static final long TEST_RETENTION_MS = 10_000L;
    private final SnapshotManager snapshotManager;

    public DimensionWorker(int dimensionId) {
        this.dimensionId = dimensionId;
        this.snapshotManager = new SnapshotManager(dimensionId);
    }
    private static final Logger LOGGER =
            LogManager.getLogger("LTS_KABOOM");


    public void submitExplosion(BlockPos pos, int power){
        LOGGER.info(
                "Explosion accepted: dimension={}, pos={}, power={}",
                dimensionId, pos, power
        );
    }
    public SnapshotManager getSnapshotManager() {
        return snapshotManager;
    }

    //snapshotManager.processIncomingSnapshots(4);
}
