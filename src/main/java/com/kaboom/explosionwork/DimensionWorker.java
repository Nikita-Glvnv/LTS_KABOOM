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


    private void requestTestChunks(BlockPos pos) {

        ChunkLoadingManager manager = Kaboom.getChunkLoadingManager();

        if (manager == null) {
            LOGGER.warn("ChunkLoadingManager is not initialized");
            return;
        }

        int centerX = pos.getX();
        int centerZ = pos.getZ();

        int minChunkX = (centerX - TEST_RADIUS) >> 4;
        int maxChunkX = (centerX + TEST_RADIUS) >> 4;

        int minChunkZ = (centerZ - TEST_RADIUS) >> 4;
        int maxChunkZ = (centerZ + TEST_RADIUS) >> 4;

        int count = 0;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {

            int minX = cx << 4;
            int maxX = minX + 15;

            int nearestX = Math.max(minX, Math.min(centerX, maxX));
            long dx = (long) nearestX - centerX;

            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {

                int minZ = cz << 4;
                int maxZ = minZ + 15;

                int nearestZ = Math.max(minZ, Math.min(centerZ, maxZ));
                long dz = (long) nearestZ - centerZ;

                if (dx * dx + dz * dz > TEST_RADIUS * TEST_RADIUS) {
                    continue;
                }

                manager.requestChunk(
                        dimensionId,
                        new ChunkPos(cx, cz),
                        TEST_RETENTION_MS
                );

                if (Kaboom.getChunkSnapshot() != null) {
                    Kaboom.getChunkSnapshot().requestTracking(
                            dimensionId, cx, cz
                    );
                }

                count++;
            }
        }

        LOGGER.info(
                "Chunk test: dimension={}, center={}, requested={}",
                dimensionId, pos, count
        );
    }
    public void submitExplosion(BlockPos pos, int power){
        LOGGER.info(
                "Explosion accepted: dimension={}, pos={}, power={}",
                dimensionId, pos, power
        );
        requestTestChunks(pos);

    }
    //snapshotManager.processIncomingSnapshots(4);
}
