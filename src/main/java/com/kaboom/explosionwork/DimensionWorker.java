package com.kaboom.explosionwork;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.concurrent.locks.LockSupport;

import net.minecraft.util.math.BlockPos;

public class DimensionWorker {
    private final int dimensionId;
    private final SnapshotManager snapshotManager;

    private static final int MAX_SNAPSHOTS_PER_TICK = 4;
    private static final long TICK_NANOS = 50_000_000L;

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

            // Здесь позднее будет содержимое тика.

            nextTick += TICK_NANOS;

            long now = System.nanoTime();
            if (nextTick - now <= 0) {
                nextTick = now + TICK_NANOS;
            }
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
