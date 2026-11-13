package com.kaboom.explosionwork;

import static com.kaboom.ModLogger.LOGGER;
import java.util.concurrent.locks.LockSupport;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.util.math.BlockPos;

public class DimensionWorker {
    private final int dimensionId;
    private final SnapshotManager snapshotManager;

    private static final int MAX_SNAPSHOTS_PER_TICK = 4;
    private static final long TICK_NANOS = 50_000_000L;
    private static final int MAX_EXPLOSIONS_PER_TICK = 64;

    private final Queue<BlockPos> pendingExplosions =
            new ConcurrentLinkedQueue<>();

    private static final int LOG_INTERVAL_TICKS = 20;
    private static final int IDLE_HEARTBEAT_INTERVALS = 60;

    private final AtomicLong acceptedExplosions = new AtomicLong();
    // Счётчики меняются только в потоке данного измерения.
    private long explosionAttempts;
    private long explosionReady;
    private long explosionDeferred;
    private BlockPos processingPos;

    private final Thread workerThread;
    private volatile boolean running;
    private boolean started;

    public synchronized void start() {
        if (started) {
            LOGGER.warn("DimensionWorker start ignored: dimension={}, already started", dimensionId);
            return;
        }

        started = true;
        running = true;
        LOGGER.info(
                "DimensionWorker starting: dimension={}, thread={}, tickMs={}, snapshotLimit={}, explosionLimit={}",
                dimensionId, workerThread.getName(), TICK_NANOS / 1_000_000L,
                MAX_SNAPSHOTS_PER_TICK, MAX_EXPLOSIONS_PER_TICK
        );
        workerThread.start();
    }

    public void stop() {
        LOGGER.info("DimensionWorker stop requested: dimension={}, pending={}, alive={}",
                dimensionId, pendingExplosions.size(), workerThread.isAlive());
        running = false;
        LockSupport.unpark(workerThread);

        if (Thread.currentThread() != workerThread) {
            try {
                workerThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("DimensionWorker stop interrupted: dimension={}", dimensionId, e);
            }
        }
        LOGGER.info("DimensionWorker stop returned: dimension={}, pending={}, alive={}",
                dimensionId, pendingExplosions.size(), workerThread.isAlive());
    }

    private void runLoop() {
        long nextTick = System.nanoTime();
        long intervalStart = nextTick;
        long acceptedAtLastReport = acceptedExplosions.get();
        int ticks = 0;
        int snapshots = 0;
        int snapshotLimitHits = 0;
        int explosionLimitHits = 0;
        int overruns = 0;
        int idleIntervals = 0;
        long snapshotTimeNs = 0;
        long explosionTimeNs = 0;
        long maxTickTimeNs = 0;
        boolean wasIdle = false;
        String stage = "startup";

        LOGGER.info("DimensionWorker loop started: dimension={}, thread={}",
                dimensionId, workerThread.getName());

        try {
            while (running) {
                long remaining = nextTick - System.nanoTime();
                if (remaining > 0) {
                    LockSupport.parkNanos(remaining);
                    continue;
                }

                long tickStart = System.nanoTime();

                stage = "snapshots";
                long start = System.nanoTime();
                int processed = snapshotManager.processIncomingSnapshots(MAX_SNAPSHOTS_PER_TICK);
                snapshotTimeNs += System.nanoTime() - start;
                snapshots += processed;
                if (processed == MAX_SNAPSHOTS_PER_TICK) {
                    snapshotLimitHits++;
                }

                stage = "explosions";
                long attemptsBefore = explosionAttempts;
                start = System.nanoTime();
                processPendingExplosions();
                explosionTimeNs += System.nanoTime() - start;
                long attemptsThisTick = explosionAttempts - attemptsBefore;
                if (attemptsThisTick == MAX_EXPLOSIONS_PER_TICK) {
                    explosionLimitHits++;
                }

                boolean idle = processed == 0 && attemptsThisTick == 0;
                if (idle != wasIdle) {
                    LOGGER.debug("DimensionWorker state: dimension={}, state={}",
                            dimensionId, idle ? "idle" : "active");
                    wasIdle = idle;
                }

                maxTickTimeNs = Math.max(maxTickTimeNs, System.nanoTime() - tickStart);
                nextTick += TICK_NANOS;
                long now = System.nanoTime();
                if (nextTick - now <= 0) {
                    overruns++;
                    nextTick = now + TICK_NANOS;
                }

                if (++ticks >= LOG_INTERVAL_TICKS) {
                    long acceptedNow = acceptedExplosions.get();
                    long accepted = acceptedNow - acceptedAtLastReport;
                    if (accepted > 0 || snapshots > 0 || explosionAttempts > 0 || overruns > 0) {
                        LOGGER.info(
                                "DimensionWorker stats: dimension={}, elapsedMs={}, ticks={}, accepted={}, snapshots={}, snapshotLimitHits={}, explosionAttempts={}, explosionReady={}, explosionDeferred={}, explosionLimitHits={}, queuedExplosions={}, overruns={}, snapshotTimeUs={}, explosionTimeUs={}, maxTickUs={}",
                                dimensionId, (now - intervalStart) / 1_000_000L, ticks,
                                accepted, snapshots, snapshotLimitHits,
                                explosionAttempts, explosionReady, explosionDeferred,
                                explosionLimitHits, pendingExplosions.size(), overruns,
                                snapshotTimeNs / 1_000L, explosionTimeNs / 1_000L,
                                maxTickTimeNs / 1_000L
                        );
                        if (snapshotLimitHits == LOG_INTERVAL_TICKS
                                || explosionLimitHits == LOG_INTERVAL_TICKS) {
                            LOGGER.warn(
                                    "DimensionWorker processing limit reached every tick: dimension={}, snapshotLimitHits={}, explosionLimitHits={}",
                                    dimensionId, snapshotLimitHits, explosionLimitHits
                            );
                        }
                        idleIntervals = 0;
                    } else if (++idleIntervals >= IDLE_HEARTBEAT_INTERVALS) {
                        LOGGER.debug("DimensionWorker heartbeat: dimension={}, queuedExplosions={}",
                                dimensionId, pendingExplosions.size());
                        idleIntervals = 0;
                    }

                    acceptedAtLastReport = acceptedNow;
                    intervalStart = now;
                    ticks = snapshots = snapshotLimitHits = explosionLimitHits = overruns = 0;
                    explosionAttempts = explosionReady = explosionDeferred = 0;
                    snapshotTimeNs = explosionTimeNs = maxTickTimeNs = 0;
                }
            }
        } catch (RuntimeException | Error e) {
            LOGGER.error(
                    "DimensionWorker failed: dimension=" + dimensionId
                            + ", stage=" + stage
                            + ", explosionPos=" + processingPos
                            + ", queuedExplosions=" + pendingExplosions.size(),
                    e
            );
            throw e;
        } finally {
            LOGGER.info("DimensionWorker loop finished: dimension={}, acceptedTotal={}, queuedExplosions={}",
                    dimensionId, acceptedExplosions.get(), pendingExplosions.size());
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

            processingPos = pos;
            explosionAttempts++;

            boolean ready = snapshotManager.getBlock(
                    pos.getX(),
                    pos.getY(),
                    pos.getZ(),
                    result
            );

            if (!ready) {
                pendingExplosions.offer(pos);
                explosionDeferred++;
                processingPos = null;
                continue;
            }

            explosionReady++;
            LOGGER.info(
                    "Explosion block: dimension={}, pos={}, type={}, hardness={}, resistance={}",
                    dimensionId,
                    pos,
                    result.type,
                    result.hardness,
                    result.resistance
            );
            processingPos = null;
        }
    }

    public DimensionWorker(int dimensionId) {
        this.dimensionId = dimensionId;
        this.snapshotManager = new SnapshotManager(dimensionId);
        this.workerThread = new Thread(
                this::runLoop,
                "LTS_KABOOM-Dimension-" + dimensionId
        );
        LOGGER.info("DimensionWorker created: dimension={}, thread={}",
                dimensionId, workerThread.getName());
    }

    public void submitExplosion(BlockPos pos, int power) {

        BlockPos capturedPos = new BlockPos(
                pos.getX(),
                pos.getY(),
                pos.getZ()
        );

        pendingExplosions.offer(capturedPos);
        long totalAccepted = acceptedExplosions.incrementAndGet();

        LOGGER.info(
                "Explosion accepted: dimension={}, pos={}, power={}, acceptedTotal={}",
                dimensionId, capturedPos, power, totalAccepted
        );
    }
    public SnapshotManager getSnapshotManager() {
        return snapshotManager;
    }

    //snapshotManager.processIncomingSnapshots(4);
}
