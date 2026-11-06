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
}
