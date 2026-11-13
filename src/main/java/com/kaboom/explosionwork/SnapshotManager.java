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

public class SnapshotManager {
    private final int dimensionId;
    private static final long CHUNK_RETENTION_MS = 30_000L;
    private static final long INACTIVITY_TIMEOUT_NANOS =
            TimeUnit.SECONDS.toNanos(30);

    private static final long SNAPSHOT_CLEANUP_DELAY_NANOS =
            TimeUnit.SECONDS.toNanos(30);

    private static final long REQUEST_INTERVAL_NANOS =
            TimeUnit.SECONDS.toNanos(10);

    private final Map<Long, Long> lastTrackingRequests =
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
    }

    private static final int QUEUE_CAPACITY = 128;

    private final ArrayBlockingQueue<chunksnapshot.SnapshotUpdate>
            incomingSections = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    public boolean offerSnapshotUpdate(chunksnapshot.SnapshotUpdate update) {
        return update != null
                && update.getDimensionId() == dimensionId
                && incomingSections.offer(update);
    }

    public void acceptSectionChanges(chunksnapshot.SectionChanges changes) {
        if (changes.dimensionId != dimensionId) {
            throw new IllegalArgumentException("Wrong dimension");
        }

        if (changes.sectionY < 0 || changes.sectionY >= 16) {
            throw new IllegalArgumentException("Invalid section Y");
        }

        StoredChunk chunk = chunks.get(
                ChunkPos.asLong(changes.chunkX, changes.chunkZ)
        );

        if (chunk == null
                || (chunk.readyMask & (1 << changes.sectionY)) == 0
                || chunk.sections[changes.sectionY] == null) {
            throw new IllegalStateException("Section is not initialized");
        }

        chunk.sections[changes.sectionY].applyChanges(
                changes.positions,
                changes.types,
                changes.hardness,
                changes.resistance,
                changes.count
        );
    }

    public int processIncomingSnapshots(int maxSnapshots) {

        int processed = 0;

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
                throw new IllegalArgumentException("Unknown snapshot update");
            }

            processed++;
        }
        cleanupInactiveChunks();
        return processed;
    }

    public void acceptFullSnapshot(
            chunksnapshot.SectionSnapshot snapshot
    ) {
        if (snapshot.dimensionId != dimensionId) {
            throw new IllegalArgumentException("Wrong dimension");
        }

        if (snapshot.sectionY < 0 || snapshot.sectionY >= 16) {
            throw new IllegalArgumentException("Invalid section Y");
        }

        oktoderevo section = new oktoderevo();

        section.fill(
                snapshot.types,
                snapshot.hardness,
                snapshot.resistance
        );

        long key = ChunkPos.asLong(
                snapshot.chunkX,
                snapshot.chunkZ
        );

        if (!lastBlockRequests.containsKey(key)
                || inactiveSince.containsKey(key)) {
            return;
        }

        StoredChunk chunk = chunks.computeIfAbsent(
                key,
                k -> new StoredChunk()
        );

        chunk.sections[snapshot.sectionY] = section;
        chunk.readyMask |= 1 << snapshot.sectionY;
    }

    public boolean getBlock(
            int x, int y, int z,
            oktoderevo.BlockResult result
    ) {
        Objects.requireNonNull(result, "result");
        if (!ensureSectionTracked(x, y, z)) {
            return false;
        }
        // Проверяем допустимую высоту мира.
        if (y < 0 || y >= 256) {
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
            return false;
        }

        // Проверяем готовность секции.
        if ((chunk.readyMask & (1 << sectionY)) == 0) {
            return false;
        }

        oktoderevo section = chunk.sections[sectionY];

        if (section == null) {
            return false;
        }

        // Читаем блок по локальным координатам секции.
        section.getBlock(
                x & 15,
                y & 15,
                z & 15,
                result
        );

        return true;
    }

    public boolean ensureSectionTracked(int x, int y, int z) {

        if (y < 0 || y >= 256) {
            return false;
        }

        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        int sectionY = y >> 4;

        long key = ChunkPos.asLong(chunkX, chunkZ);

        StoredChunk chunk = chunks.get(key);

        boolean ready = chunk != null
                && (chunk.readyMask & (1 << sectionY)) != 0
                && chunk.sections[sectionY] != null;

        long now = System.nanoTime();
        lastBlockRequests.put(key, now);
        inactiveSince.remove(key);
        chunkPositions.putIfAbsent(key, new ChunkPos(chunkX, chunkZ));
        Long lastRequest = lastTrackingRequests.get(key);

        lastBlockRequests.put(key, now);
        inactiveSince.remove(key);

        chunkPositions.computeIfAbsent(
                key,
                k -> new ChunkPos(chunkX, chunkZ)
        );

        if (lastRequest == null
                || now - lastRequest >= REQUEST_INTERVAL_NANOS) {

            ChunkLoadingManager loadingManager =
                    Kaboom.getChunkLoadingManager();

            chunksnapshot snapshotService =
                    Kaboom.getChunkSnapshot();

            if (loadingManager != null && snapshotService != null) {

                loadingManager.requestChunk(
                        dimensionId,
                        new ChunkPos(chunkX, chunkZ),
                        CHUNK_RETENTION_MS
                );

                snapshotService.requestTracking(
                        dimensionId,
                        chunkX,
                        chunkZ
                );

                lastTrackingRequests.put(key, now);
            }
        }

        return ready;
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
                }

                inactiveSince.put(key, now);
                continue;
            }

            // Второй этап: удаляем снимок после дополнительного ожидания.
            if (now - inactiveAt < SNAPSHOT_CLEANUP_DELAY_NANOS) {
                continue;
            }

            chunks.remove(key);
            lastTrackingRequests.remove(key);
            inactiveSince.remove(key);
            chunkPositions.remove(key);
            iterator.remove();
        }
    }
}
