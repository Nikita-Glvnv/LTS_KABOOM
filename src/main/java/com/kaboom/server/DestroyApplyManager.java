package com.kaboom.server;

import net.minecraft.util.math.BlockPos;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class DestroyApplyManager {
    private static final int MAX_DESTROY_REQUESTS = 16384;

    private final BlockingQueue<DestroyRequest> destroyQueue =
            new ArrayBlockingQueue<>(MAX_DESTROY_REQUESTS);

    private static final class DestroyRequest {
        final int dimensionId;
        final BlockPos pos;


        DestroyRequest(int dimensionId, BlockPos pos) {
            this.dimensionId = dimensionId;
            this.pos = new BlockPos(
                    pos.getX(),
                    pos.getY(),
                    pos.getZ()
            );
        }
    }
    public boolean submitDestroy(int dimensionId, BlockPos pos) {
        Objects.requireNonNull(pos, "pos");

        return destroyQueue.offer(
                new DestroyRequest(dimensionId, pos)
        );
    }

    


}
