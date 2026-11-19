package com.kaboom.explosionwork;

import static com.kaboom.ModLogger.LOGGER;
import java.util.concurrent.locks.LockSupport;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import com.kaboom.Kaboom;
import com.kaboom.server.DestroyApplyManager;

import net.minecraft.util.math.BlockPos;

public class DimensionWorker {
    private final int dimensionId;
    private final SnapshotManager snapshotManager;
    private final explosionstep physics = new explosionstep();

    // Результаты завершённого физического шага,
// ещё не полностью переданные серверному потоку.
    private boolean pendingDestroyApply;
    private int nextDestroyedToApply;

    private static final int MAX_SNAPSHOTS_PER_TICK = 32;
    private static final long TICK_NANOS = 50_000_000L;
    private static final int MAX_EXPLOSIONS_PER_TICK = 64;

    private final Queue<ExplosionRequest> pendingExplosions =
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
    private volatile boolean failed;
    private boolean started;

    public boolean hasFailed() {
        return failed;
    }
    private static final class ExplosionRequest {

        final BlockPos pos;
        final int power;

        ExplosionRequest(BlockPos pos, int power) {
            this.pos = pos;
            this.power = power;
        }
    }

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
        long physicsSteps = 0;
        long physicsVisitedCells = 0;
        long physicsDeferredCells = 0;
        long physicsDestroyedBlocks = 0;
        long physicsPrefetchChecks = 0;
        long physicsMacroAttempts = 0;
        long physicsMacroTransfers = 0;
        long physicsMacroHops = 0;
        long physicsMacroEight = 0;
        long physicsMacroSixteen = 0;

        long physicsTimeNs = 0;
        long maxPhysicsStepNs = 0;
        int maxPhysicsCells = 0;
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
                snapshotManager.processIncomingEntitySnapshots();
                snapshotTimeNs += System.nanoTime() - start;
                snapshots += processed;
                if (processed == MAX_SNAPSHOTS_PER_TICK) {
                    snapshotLimitHits++;
                }

                stage = "explosions";

                long attemptsBefore = explosionAttempts;
                start = System.nanoTime();

                boolean waveWasActive =
                        physics.hasPending() || pendingDestroyApply;

// Нельзя рассчитывать следующий шаг, пока
// разрушения предыдущего не переданы серверу.
                if (!pendingDestroyApply || applyPendingDestructions()) {

                    // Регистрируем новые источники энергии.
                    processPendingExplosions();

                    // Расчёт выполняется только при наличии
                    // активных или ожидающих волн.
                    if (physics.hasPending()) {

                        stage = "physics";

                        long physicsStart = System.nanoTime();

                        boolean finished =
                                physics.step(snapshotManager);

                        int activeCells = physics.getCellsLastStep();

                        long duration = System.nanoTime() - physicsStart;

                        physicsTimeNs += duration;
                        maxPhysicsStepNs = Math.max(maxPhysicsStepNs, duration);
                        maxPhysicsCells = Math.max(maxPhysicsCells, activeCells);

                        if (finished) {
                            physicsSteps++;

                            physicsVisitedCells += activeCells;
                            physicsDeferredCells += physics.getDeferredLastStep();
                            physicsDestroyedBlocks += physics.destroyedCount();

                            physicsPrefetchChecks +=
                                    physics.getPrefetchedSectionsLastStep();
                        }

                        if (finished) {

                            // Шаг завершён. Передаём его разрушения.
                            pendingDestroyApply = true;

                            stage = "apply destructions";
                            applyPendingDestructions();
                        }
                    }
                }

                explosionTimeNs += System.nanoTime() - start;

                long attemptsThisTick =
                        explosionAttempts - attemptsBefore;

                if (attemptsThisTick == MAX_EXPLOSIONS_PER_TICK) {
                    explosionLimitHits++;
                }

                boolean idle =
                        processed == 0
                                && attemptsThisTick == 0
                                && !waveWasActive
                                && !physics.hasPending()
                                && !pendingDestroyApply;
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
                    if (physicsSteps > 0
                            || physics.hasPending()
                            || pendingDestroyApply) {

                        LOGGER.info(
                                "ExplosionPhysics stats: dimension={}, elapsedMs={}, " +
                                        "waveTick={}, steps={}, processedCells={}, " +
                                        "deferredCells={}, maxCellsInStep={}, " +
                                        "destroyedBlocks={}, prefetchChecks={}, " +
                                        "pendingCells={}, pendingDestroyBlocks={}, " +
                                        "pendingApply={}, physicsTimeUs={}, maxStepUs={}, " +
                                        "totalAbsorbed={}, totalAirAbsorbed={}, " +
                                        "totalEscaped={}, totalCulled={}",

                                dimensionId,
                                (now - intervalStart) / 1_000_000L,
                                physics.getCurrentTick(),
                                physicsSteps,
                                physicsVisitedCells - physicsDeferredCells,
                                physicsDeferredCells,
                                maxPhysicsCells,
                                physicsDestroyedBlocks,
                                physicsPrefetchChecks,
                                physics.getPendingCellCount(),
                                Math.max(
                                        0,
                                        physics.destroyedCount() - nextDestroyedToApply
                                ),
                                pendingDestroyApply,
                                physicsTimeNs / 1_000L,
                                maxPhysicsStepNs / 1_000L,
                                physics.getAbsorbed(),
                                physics.getAirAbsorbed(),
                                physics.getEscaped(),
                                physics.getCulled()
                        );

                        if (maxPhysicsStepNs > TICK_NANOS) {
                            LOGGER.warn(
                                    "ExplosionPhysics slow step: dimension={}, " +
                                            "maxStepMs={}, targetTickMs={}",
                                    dimensionId,
                                    maxPhysicsStepNs / 1_000_000L,
                                    TICK_NANOS / 1_000_000L
                            );
                        }
                    }
                    long accepted = acceptedNow - acceptedAtLastReport;
                    String adaptiveReport = physics.drainAdaptiveReport();

                    if (adaptiveReport != null) {
                        LOGGER.info(
                                "AdaptiveCube stats: dimension={}, {}",
                                dimensionId,
                                adaptiveReport
                        );
                    }

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
                    physicsSteps = 0;
                    physicsVisitedCells = 0;
                    physicsDeferredCells = 0;
                    physicsDestroyedBlocks = 0;
                    physicsPrefetchChecks = 0;

                    physicsTimeNs = 0;
                    maxPhysicsStepNs = 0;
                    maxPhysicsCells = 0;
                }
            }
        } catch (RuntimeException | Error e) {
            failed = true;
            running = false;
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

            ExplosionRequest request = pendingExplosions.poll();

            if (request == null) {
                break;
            }

            BlockPos pos = request.pos;
            processingPos = pos;
            explosionAttempts++;

            // Убеждаемся, что начальная секция доступна.
            if (!snapshotManager.getBlock(
                    pos.getX(),
                    pos.getY(),
                    pos.getZ(),
                    result
            )) {
                pendingExplosions.offer(request);
                explosionDeferred++;
                processingPos = null;
                continue;
            }

            long packed = pos.toLong();

            // Планируем начало взрыва на следующий
            // физический тик. Это безопасно даже при
            // частично обработанном текущем тике.
            double arrival = physics.getCurrentTick() + 1.0;

            physics.schedule(
                    packed,
                    packed,
                    (double) request.power,
                    0.0,
                    0.0,
                    0.0,
                    arrival
            );
            LOGGER.info(
                    "ExplosionPhysics source activated: dimension={}, " +
                            "pos={}, energy={}, arrivalTick={}",
                    dimensionId,
                    pos,
                    request.power,
                    (long) arrival
            );

            explosionReady++;

            processingPos = null;
        }
    }

    private boolean applyPendingDestructions() {

        int count = physics.destroyedCount();

        DestroyApplyManager manager =
                Kaboom.getDestroyApplyManager();

        if (nextDestroyedToApply < count && manager == null) {
            return false;
        }

        while (nextDestroyedToApply < count) {

            long packed =
                    physics.destroyedAt(nextDestroyedToApply);

            BlockPos pos = BlockPos.fromLong(packed);

            // Передаём разрушение на Server Thread.
            if (!manager.submitDestroy(dimensionId, pos)) {
                return false;
            }

            // После успешной постановки в серверную очередь
            // обновляем снимок для дальнейших расчётов.
            if (!snapshotManager.removeBlock(
                    pos.getX(),
                    pos.getY(),
                    pos.getZ()
            )) {
                LOGGER.warn(
                        "Failed to remove destroyed block from snapshot: dimension={}, pos={}",
                        dimensionId, pos
                );
            }

            // Увеличиваем индекс только после принятия
            // разрушения серверной очередью.
            nextDestroyedToApply++;
        }

        physics.clearDestroyed();

        nextDestroyedToApply = 0;
        pendingDestroyApply = false;

        return true;
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

        pendingExplosions.offer(
                new ExplosionRequest(capturedPos, power)
        );
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
