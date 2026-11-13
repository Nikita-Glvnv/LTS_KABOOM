package com.kaboom.explosionwork;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.concurrent.locks.LockSupport;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.util.math.BlockPos;

public class DimensionWorker {
    private final int dimensionId;
    private final SnapshotManager snapshotManager;

    private static final int MAX_SNAPSHOTS_PER_TICK = 4;
    private static final long TICK_NANOS = 50_000_000L;
    private static final int MAX_EXPLOSIONS_PER_TICK = 64;

    private final Queue<BlockPos> pendingExplosions =
            new ConcurrentLinkedQueue<>();

    private final Thread workerThread;
    private volatile boolean running;
    private boolean started;

    public synchronized void start() {
        if (started) {
            return;
        }

        started = true;
        running = true;
        workerThread.start();
    }

    public void stop() {
        running = false;
        LockSupport.unpark(workerThread);

        if (Thread.currentThread() != workerThread) {
            try {
                workerThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runLoop() {
        long nextTick = System.nanoTime();

        while (running) {
            long remaining = nextTick - System.nanoTime();

            if (remaining > 0) {
                LockSupport.parkNanos(remaining);
                continue;
            }

            snapshotManager.processIncomingSnapshots(MAX_SNAPSHOTS_PER_TICK);

            processPendingExplosions();

            nextTick += TICK_NANOS;

            long now = System.nanoTime();
            if (nextTick - now <= 0) {
                nextTick = now + TICK_NANOS;
            }
        }
    }

    private void processPendingExplosions() {

        int count = Math.min(
                pendingExplosions.size(),
                MAX_EXPLOSIONS_PER_TICK
        );

        oktoderevo.BlockResult result =
                new oktoderevo.BlockResult();

        for (int i = 0; i < count; i++) {

            BlockPos pos = pendingExplosions.poll();

            if (pos == null) {
                break;
            }

            boolean ready = snapshotManager.getBlock(
                    pos.getX(),
                    pos.getY(),
                    pos.getZ(),
                    result
            );

            if (!ready) {
                pendingExplosions.offer(pos);
                continue;
            }

            LOGGER.info(
                    "Explosion block: dimension={}, pos={}, type={}, hardness={}, resistance={}",
                    dimensionId,
                    pos,
                    result.type,
                    result.hardness,
                    result.resistance
            );
        }
    }

    public DimensionWorker(int dimensionId) {
        this.dimensionId = dimensionId;
        this.snapshotManager = new SnapshotManager(dimensionId);
        this.workerThread = new Thread(
                this::runLoop,
                "LTS_KABOOM-Dimension-" + dimensionId
        );
    }
    private static final Logger LOGGER =
            LogManager.getLogger("LTS_KABOOM");


    public void submitExplosion(BlockPos pos, int power) {

        BlockPos capturedPos = new BlockPos(
                pos.getX(),
                pos.getY(),
                pos.getZ()
        );

        pendingExplosions.offer(capturedPos);

        LOGGER.info(
                "Explosion accepted: dimension={}, pos={}, power={}",
                dimensionId, capturedPos, power
        );
    }
    public SnapshotManager getSnapshotManager() {
        return snapshotManager;
    }

    //snapshotManager.processIncomingSnapshots(4);
}
