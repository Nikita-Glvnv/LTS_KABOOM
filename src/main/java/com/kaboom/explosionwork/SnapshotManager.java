package com.kaboom.explosionwork;

import com.kaboom.server.chunksnapshot;
import net.minecraft.util.math.ChunkPos;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import com.kaboom.Kaboom;
import com.kaboom.server.ChunkLoadingManager;
import java.util.concurrent.TimeUnit;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;

import static com.kaboom.ModLogger.LOGGER;

public class SnapshotManager {
    private final int dimensionId;
    private static final long LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long CHUNK_RETENTION_MS = 30_000L;
    private static final long INACTIVITY_TIMEOUT_NANOS =
            TimeUnit.SECONDS.toNanos(30);

    private static final long SNAPSHOT_CLEANUP_DELAY_NANOS =
            TimeUnit.SECONDS.toNanos(30);

    private static final long REQUEST_INTERVAL_NANOS =
            TimeUnit.SECONDS.toNanos(10);

    // Счётчики offerSnapshotUpdate обновляются серверным потоком.
    private final AtomicLong offered = new AtomicLong();
    private final AtomicLong rejectedNull = new AtomicLong();
    private final AtomicLong rejectedDimension = new AtomicLong();
    private final AtomicLong rejectedFullQueue = new AtomicLong();

    // Остальные счётчики обновляет поток DimensionWorker.
    private static final class Metrics {
        long processed;
        long limitHits;
        long fullApplied;
        long fullDiscarded;
        long fullReplaced;
        long deltasApplied;
        long changedBlocks;
        long chunksCreated;
        long chunksCompleted;
        long blockReads;
        long blockHits;
        long missingChunks;
        long missingSections;
        long invalidHeights;
        long trackingChecks;
        long trackingRequests;
        long trackingRefreshes;
        long trackingUnavailable;
        long reactivations;
        long trackingReleases;
        long releaseFailures;
        long chunksEvicted;
        long processingNanos;
        long cleanupNanos;
    }

    private Metrics metrics = new Metrics();
    private long metricsWindowStart = System.nanoTime();

    private final Map<Long, Long> lastTrackingRequests =
            new HashMap<>();
    private static final class SectionRequestState {
        int requestedMask = 0;
        final long[] lastRequestNanos = new long[16];
    }

    private final Map<Long, SectionRequestState> sectionRequests =
            new HashMap<>();

    private final Map<Long, Long> lastBlockRequests = new HashMap<>();
    private final Map<Long, Long> inactiveSince = new HashMap<>();
    private final Map<Long, ChunkPos> chunkPositions = new HashMap<>();

    private static final class StoredChunk {

        final oktoderevo[] sections = new oktoderevo[16];

        // Биты готовности секций 0–15.
        int readyMask = 0;
    }

    private final Map<Long, StoredChunk> chunks =
            new HashMap<>();

    public SnapshotManager(int dimensionId) {
        this.dimensionId = dimensionId;
        LOGGER.info(
                "SnapshotManager created: dimension={}, queueCapacity={}, inactivityMs={}, cleanupDelayMs={}, trackingIntervalMs={}",
                dimensionId, QUEUE_CAPACITY,
                TimeUnit.NANOSECONDS.toMillis(INACTIVITY_TIMEOUT_NANOS),
                TimeUnit.NANOSECONDS.toMillis(SNAPSHOT_CLEANUP_DELAY_NANOS),
                TimeUnit.NANOSECONDS.toMillis(REQUEST_INTERVAL_NANOS)
        );
    }

    private static final int QUEUE_CAPACITY = 128;

    private final ArrayBlockingQueue<chunksnapshot.SnapshotUpdate>
            incomingSections = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    public boolean offerSnapshotUpdate(chunksnapshot.SnapshotUpdate update) {
        if (update == null) {
            rejectedNull.incrementAndGet();
            return false;
        }
        if (update.getDimensionId() != dimensionId) {
            rejectedDimension.incrementAndGet();
            return false;
        }
        if (!incomingSections.offer(update)) {
            rejectedFullQueue.incrementAndGet();
            return false;
        }
        offered.incrementAndGet();
        return true;
    }

    public void acceptSectionChanges(chunksnapshot.SectionChanges changes) {
        if (changes.dimensionId != dimensionId) {
            LOGGER.error(
                    "SnapshotManager invalid delta dimension: expected={}, actual={}, chunk=({}, {}), section={}",
                    dimensionId, changes.dimensionId,
                    changes.chunkX, changes.chunkZ, changes.sectionY
            );
            throw new IllegalArgumentException("Wrong dimension");
        }

        if (changes.sectionY < 0 || changes.sectionY >= 16) {
            LOGGER.error(
                    "SnapshotManager invalid delta section: dimension={}, chunk=({}, {}), section={}",
                    dimensionId, changes.chunkX, changes.chunkZ, changes.sectionY
            );
            throw new IllegalArgumentException("Invalid section Y");
        }

        long key = ChunkPos.asLong(
                changes.chunkX,
                changes.chunkZ
        );

        SectionRequestState requestState = sectionRequests.get(key);
        int sectionBit = 1 << changes.sectionY;

        if (inactiveSince.containsKey(key)
                || requestState == null
                || (requestState.requestedMask & sectionBit) == 0) {

            LOGGER.debug(
                    "SnapshotManager delta discarded for untracked section: dimension={}, chunk=({}, {}), section={}",
                    dimensionId, changes.chunkX,
                    changes.chunkZ, changes.sectionY
            );

            return;
        }

        StoredChunk chunk = chunks.get(
                ChunkPos.asLong(changes.chunkX, changes.chunkZ)
        );

        if (chunk == null
                || (chunk.readyMask & (1 << changes.sectionY)) == 0
                || chunk.sections[changes.sectionY] == null) {
            LOGGER.error(
                    "SnapshotManager delta before initial snapshot: dimension={}, chunk=({}, {}), section={}, readyMask={}",
                    dimensionId, changes.chunkX, changes.chunkZ,
                    changes.sectionY, chunk == null ? 0 : chunk.readyMask
            );
            throw new IllegalStateException("Section is not initialized");
        }

        chunk.sections[changes.sectionY].applyChanges(
                changes.positions,
                changes.types,
                changes.hardness,
                changes.resistance,
                changes.count
        );

        metrics.deltasApplied++;
        metrics.changedBlocks += changes.count;
        LOGGER.debug(
                "SnapshotManager delta applied: dimension={}, chunk=({}, {}), section={}, blocks={}",
                dimensionId, changes.chunkX, changes.chunkZ,
                changes.sectionY, changes.count
        );
    }

    public int processIncomingSnapshots(int maxSnapshots) {
        long start = System.nanoTime();
        int processed = 0;

        try {
            while (processed < maxSnapshots) {
                chunksnapshot.SnapshotUpdate update = incomingSections.poll();
                if (update == null) {
                    break;
                }

                if (update instanceof chunksnapshot.SectionSnapshot) {
                    acceptFullSnapshot((chunksnapshot.SectionSnapshot) update);
                } else if (update instanceof chunksnapshot.SectionChanges) {
                    acceptSectionChanges((chunksnapshot.SectionChanges) update);
                } else {
                    throw new IllegalArgumentException("Unknown snapshot update: "
                            + update.getClass().getName());
                }

                processed++;
                metrics.processed++;
            }

            if (maxSnapshots > 0 && processed == maxSnapshots
                    && !incomingSections.isEmpty()) {
                metrics.limitHits++;
            }

            long cleanupStart = System.nanoTime();
            cleanupInactiveChunks();
            metrics.cleanupNanos += System.nanoTime() - cleanupStart;
            return processed;
        } catch (RuntimeException | Error e) {
            LOGGER.error(
                    "SnapshotManager processing failed: dimension=" + dimensionId
                            + ", processedBeforeFailure=" + processed
                            + ", queuedUpdates=" + incomingSections.size(),
                    e
            );
            throw e;
        } finally {
            metrics.processingNanos += System.nanoTime() - start;
            logMetricsIfDue();
        }
    }

    public void acceptFullSnapshot(
            chunksnapshot.SectionSnapshot snapshot
    ) {
        if (snapshot.dimensionId != dimensionId) {
            LOGGER.error(
                    "SnapshotManager invalid full snapshot dimension: expected={}, actual={}, chunk=({}, {}), section={}",
                    dimensionId, snapshot.dimensionId,
                    snapshot.chunkX, snapshot.chunkZ, snapshot.sectionY
            );
            throw new IllegalArgumentException("Wrong dimension");
        }

        if (snapshot.sectionY < 0 || snapshot.sectionY >= 16) {
            LOGGER.error(
                    "SnapshotManager invalid full snapshot section: dimension={}, chunk=({}, {}), section={}",
                    dimensionId, snapshot.chunkX, snapshot.chunkZ, snapshot.sectionY
            );
            throw new IllegalArgumentException("Invalid section Y");
        }

        long key = ChunkPos.asLong(
                snapshot.chunkX,
                snapshot.chunkZ
        );
        SectionRequestState requestState = sectionRequests.get(key);
        int sectionBit = 1 << snapshot.sectionY;

        if (!lastBlockRequests.containsKey(key)
                || inactiveSince.containsKey(key)
                || requestState == null
                || (requestState.requestedMask & sectionBit) == 0) {

            metrics.fullDiscarded++;

            LOGGER.debug(
                    "SnapshotManager unrequested section discarded: dimension={}, chunk=({}, {}), section={}",
                    dimensionId, snapshot.chunkX,
                    snapshot.chunkZ, snapshot.sectionY
            );

            return;
        }

// Создаём октодерево только для действительно нужной секции.
        oktoderevo section = new oktoderevo();

        section.fill(
                snapshot.types,
                snapshot.hardness,
                snapshot.resistance
        );

        boolean newChunk = !chunks.containsKey(key);
        StoredChunk chunk = chunks.computeIfAbsent(
                key,
                k -> new StoredChunk()
        );

        int previousMask = chunk.readyMask;
        boolean replaced = chunk.sections[snapshot.sectionY] != null;
        chunk.sections[snapshot.sectionY] = section;
        chunk.readyMask |= 1 << snapshot.sectionY;

        metrics.fullApplied++;
        if (newChunk) {
            metrics.chunksCreated++;
            LOGGER.info(
                    "SnapshotManager chunk snapshot created: dimension={}, chunk=({}, {})",
                    dimensionId, snapshot.chunkX, snapshot.chunkZ
            );
        }
        if (replaced) {
            metrics.fullReplaced++;
            LOGGER.debug(
                    "SnapshotManager section snapshot replaced: dimension={}, chunk=({}, {}), section={}",
                    dimensionId, snapshot.chunkX, snapshot.chunkZ, snapshot.sectionY
            );
        }
        LOGGER.debug(
                "SnapshotManager full section stored: dimension={}, chunk=({}, {}), section={}, readyMask={}",
                dimensionId, snapshot.chunkX, snapshot.chunkZ,
                snapshot.sectionY, chunk.readyMask
        );
        if (previousMask != 0xFFFF && chunk.readyMask == 0xFFFF) {
            metrics.chunksCompleted++;
            LOGGER.info(
                    "SnapshotManager chunk snapshot complete: dimension={}, chunk=({}, {})",
                    dimensionId, snapshot.chunkX, snapshot.chunkZ
            );
        }
    }

    public boolean getBlock(
            int x, int y, int z,
            oktoderevo.BlockResult result
    ) {
        Objects.requireNonNull(result, "result");
        metrics.blockReads++;
        if (y < 0 || y >= 256) {
            metrics.invalidHeights++;
            return false;
        }

        if (!ensureSectionTracked(x, y, z)) {
            long key = ChunkPos.asLong(x >> 4, z >> 4);

            if (chunks.containsKey(key)) {
                metrics.missingSections++;
            } else {
                metrics.missingChunks++;
            }

            return false;
        }

        // Координаты чанка и вертикальной секции.
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        int sectionY = y >> 4;

        // Находим чанк в хранилище.
        StoredChunk chunk = chunks.get(
                ChunkPos.asLong(chunkX, chunkZ)
        );

        if (chunk == null) {
            metrics.missingChunks++;
            return false;
        }

        // Проверяем готовность секции.
        if ((chunk.readyMask & (1 << sectionY)) == 0) {
            metrics.missingSections++;
            return false;
        }

        oktoderevo section = chunk.sections[sectionY];

        if (section == null) {
            metrics.missingSections++;
            return false;
        }

        // Читаем блок по локальным координатам секции.
        section.getBlock(
                x & 15,
                y & 15,
                z & 15,
                result
        );

        metrics.blockHits++;
        return true;
    }

    public boolean ensureSectionTracked(int x, int y, int z) {
        metrics.trackingChecks++;

        if (y < 0 || y >= 256) {
            return false;
        }

        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        int sectionY = y >> 4;

        long key = ChunkPos.asLong(chunkX, chunkZ);
        long now = System.nanoTime();

        // Повторная активация чанка.
        Long inactiveAt = inactiveSince.remove(key);

        if (inactiveAt != null) {
            StoredChunk old = chunks.remove(key);

            sectionRequests.remove(key);
            lastTrackingRequests.remove(key);

            metrics.reactivations++;

            LOGGER.warn(
                    "SnapshotManager chunk reactivated: dimension={}, chunk=({}, {}), inactiveMs={}, oldReadyMask={}, cacheInvalidated=true",
                    dimensionId, chunkX, chunkZ,
                    TimeUnit.NANOSECONDS.toMillis(now - inactiveAt),
                    old == null ? 0 : old.readyMask
            );
        }

        lastBlockRequests.put(key, now);

        chunkPositions.computeIfAbsent(
                key,
                k -> new ChunkPos(chunkX, chunkZ)
        );

        StoredChunk chunk = chunks.get(key);
        int sectionBit = 1 << sectionY;

        boolean ready = chunk != null
                && (chunk.readyMask & sectionBit) != 0
                && chunk.sections[sectionY] != null;

        // Продлеваем загрузку чанка независимо от готовности секции.
        Long lastChunkRequest = lastTrackingRequests.get(key);

        if (lastChunkRequest == null
                || now - lastChunkRequest >= REQUEST_INTERVAL_NANOS) {

            ChunkLoadingManager loadingManager =
                    Kaboom.getChunkLoadingManager();

            if (loadingManager != null) {
                loadingManager.requestChunk(
                        dimensionId,
                        new ChunkPos(chunkX, chunkZ),
                        CHUNK_RETENTION_MS
                );

                lastTrackingRequests.put(key, now);
            } else {
                metrics.trackingUnavailable++;
            }
        }

        // Данные секции уже готовы — повторно не запрашиваем.
        if (ready) {
            return true;
        }

        SectionRequestState requestState =
                sectionRequests.computeIfAbsent(
                        key,
                        k -> new SectionRequestState()
                );

        boolean firstRequest =
                (requestState.requestedMask & sectionBit) == 0;

        boolean retry =
                !firstRequest
                        && now - requestState.lastRequestNanos[sectionY]
                        >= REQUEST_INTERVAL_NANOS;

        if (firstRequest || retry) {
            chunksnapshot snapshotService =
                    Kaboom.getChunkSnapshot();

            if (snapshotService != null) {
                snapshotService.requestTracking(
                        dimensionId,
                        chunkX,
                        chunkZ,
                        sectionY
                );

                requestState.requestedMask |= sectionBit;
                requestState.lastRequestNanos[sectionY] = now;

                metrics.trackingRequests++;

                if (retry) {
                    metrics.trackingRefreshes++;
                }

                LOGGER.debug(
                        "SnapshotManager section requested: dimension={}, chunk=({}, {}), section={}, retry={}, requestedMask={}",
                        dimensionId, chunkX, chunkZ,
                        sectionY, retry, requestState.requestedMask
                );
            } else {
                metrics.trackingUnavailable++;
            }
        }

        return false;
    }

    private void cleanupInactiveChunks() {
        long now = System.nanoTime();

        Iterator<Map.Entry<Long, Long>> iterator =
                lastBlockRequests.entrySet().iterator();

        chunksnapshot snapshotService = Kaboom.getChunkSnapshot();

        while (iterator.hasNext()) {
            Map.Entry<Long, Long> entry = iterator.next();
            long key = entry.getKey();

            Long inactiveAt = inactiveSince.get(key);

            // Первый этап: прекращаем отслеживание.
            if (inactiveAt == null) {
                if (now - entry.getValue() < INACTIVITY_TIMEOUT_NANOS) {
                    continue;
                }

                ChunkPos pos = chunkPositions.get(key);

                if (pos != null && snapshotService != null) {
                    snapshotService.releaseTracking(
                            dimensionId,
                            pos.x,
                            pos.z
                    );
                    metrics.trackingReleases++;
                    LOGGER.info(
                            "SnapshotManager tracking released: dimension={}, chunk=({}, {}), idleMs={}",
                            dimensionId, pos.x, pos.z,
                            TimeUnit.NANOSECONDS.toMillis(now - entry.getValue())
                    );
                } else {
                    metrics.releaseFailures++;
                    LOGGER.warn(
                            "SnapshotManager tracking release skipped: dimension={}, chunkKey={}, missingPosition={}, missingService={}",
                            dimensionId, key, pos == null, snapshotService == null
                    );
                }

                inactiveSince.put(key, now);
                continue;
            }

            // Второй этап: удаляем снимок после дополнительного ожидания.
            if (now - inactiveAt < SNAPSHOT_CLEANUP_DELAY_NANOS) {
                continue;
            }

            StoredChunk removed = chunks.remove(key);
            ChunkPos pos = chunkPositions.get(key);
            metrics.chunksEvicted++;
            LOGGER.info(
                    "SnapshotManager cached chunk evicted: dimension={}, chunk={}, hadSnapshot={}, readyMask={}, inactiveMs={}",
                    dimensionId, pos,
                    removed != null, removed == null ? 0 : removed.readyMask,
                    TimeUnit.NANOSECONDS.toMillis(now - inactiveAt)
            );
            lastTrackingRequests.remove(key);
            inactiveSince.remove(key);
            chunkPositions.remove(key);
            sectionRequests.remove(key);
            iterator.remove();
        }
    }

    private void logMetricsIfDue() {
        long now = System.nanoTime();
        if (now - metricsWindowStart < LOG_INTERVAL_NANOS) {
            return;
        }

        Metrics m = metrics;
        metrics = new Metrics();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(now - metricsWindowStart);
        metricsWindowStart = now;

        long accepted = offered.getAndSet(0);
        long nullUpdates = rejectedNull.getAndSet(0);
        long wrongDimension = rejectedDimension.getAndSet(0);
        long queueFull = rejectedFullQueue.getAndSet(0);

        if (accepted > 0 || nullUpdates > 0 || wrongDimension > 0
                || queueFull > 0 || m.processed > 0 || m.blockReads > 0
                || m.trackingReleases > 0 || m.chunksEvicted > 0
                || m.trackingUnavailable > 0) {
            LOGGER.info(
                    "SnapshotManager stats: dimension={}, elapsedMs={}, offered={}, processed={}, processingLimitHits={}, queueSize={}, rejectedNull={}, rejectedDimension={}, rejectedQueueFull={}, fullApplied={}, fullDiscarded={}, fullReplaced={}, deltasApplied={}, changedBlocks={}, chunksCreated={}, chunksCompleted={}, blockReads={}, blockHits={}, missingChunks={}, missingSections={}, invalidHeights={}, trackingChecks={}, trackingRequests={}, trackingRefreshes={}, trackingUnavailable={}, reactivations={}, trackingReleases={}, releaseFailures={}, chunksEvicted={}, cachedChunks={}, inactiveChunks={}, processingTimeUs={}, cleanupTimeUs={}",
                    dimensionId, elapsedMs, accepted, m.processed,
                    m.limitHits, incomingSections.size(), nullUpdates,
                    wrongDimension, queueFull, m.fullApplied, m.fullDiscarded,
                    m.fullReplaced, m.deltasApplied, m.changedBlocks,
                    m.chunksCreated, m.chunksCompleted, m.blockReads,
                    m.blockHits, m.missingChunks, m.missingSections,
                    m.invalidHeights, m.trackingChecks, m.trackingRequests,
                    m.trackingRefreshes, m.trackingUnavailable,
                    m.reactivations, m.trackingReleases, m.releaseFailures,
                    m.chunksEvicted, chunks.size(), inactiveSince.size(),
                    m.processingNanos / 1_000L, m.cleanupNanos / 1_000L
            );
        }

        if (nullUpdates > 0 || wrongDimension > 0 || queueFull > 0) {
            LOGGER.warn(
                    "SnapshotManager incoming updates rejected: dimension={}, null={}, wrongDimension={}, queueFull={}, queued={}",
                    dimensionId, nullUpdates, wrongDimension,
                    queueFull, incomingSections.size()
            );
        }
        if (m.trackingUnavailable > 0) {
            LOGGER.warn(
                    "SnapshotManager tracking service unavailable: dimension={}, attempts={}",
                    dimensionId, m.trackingUnavailable
            );
        }
    }

    public boolean removeBlock(int x, int y, int z) {
        if (y < 0 || y >= 256) {
            return false;
        }

        long key = ChunkPos.asLong(x >> 4, z >> 4);

        if (inactiveSince.containsKey(key)) {
            return false;
        }

        StoredChunk chunk = chunks.get(key);

        if (chunk == null) {
            return false;
        }

        int sectionY = y >> 4;

        if ((chunk.readyMask & (1 << sectionY)) == 0) {
            return false;
        }

        oktoderevo section = chunk.sections[sectionY];

        if (section == null) {
            return false;
        }

        return section.removeBlock(
                x & 15,
                y & 15,
                z & 15
        );
    }
}
