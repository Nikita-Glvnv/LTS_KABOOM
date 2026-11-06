package com.kaboom.explosionwork;

import com.kaboom.server.chunksnapshot;
import net.minecraft.util.math.ChunkPos;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;

public class SnapshotManager {
    private final int dimensionId;

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

    private final ArrayBlockingQueue<chunksnapshot.SectionSnapshot>
            incomingSections = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    public boolean offerSectionSnapshot(
            chunksnapshot.SectionSnapshot snapshot
    ) {
        if (snapshot == null || snapshot.dimensionId != dimensionId) {
            return false;
        }

        return incomingSections.offer(snapshot);
    }

    public int processIncomingSnapshots(int maxSnapshots) {

        int processed = 0;

        while (processed < maxSnapshots) {

            chunksnapshot.SectionSnapshot snapshot =
                    incomingSections.poll();

            if (snapshot == null) {
                break;
            }

            acceptFullSnapshot(snapshot);

            processed++;
        }

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
}
