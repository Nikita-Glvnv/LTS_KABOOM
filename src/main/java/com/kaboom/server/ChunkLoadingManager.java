package com.kaboom.server;

import net.minecraft.util.math.ChunkPos;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

public class ChunkLoadingManager {
    private enum ChunkStatus {
        QUEUED,
        LOADING,
        READY,
        FAILED
    }

    private static class ChunkEntry {

        final ChunkPos pos;

        ChunkStatus status = ChunkStatus.QUEUED;
        final int dimensionId;
        long retainUntil;
        int activeUsers;

        boolean forced;
        boolean enqueued;

        ChunkEntry(ChunkPos pos, int dimensionId) {
            this.dimensionId = dimensionId;
            this.pos = pos;
        }
    }

    private static class ChunkLoadRequest {

        final int dimensionId;
        final ChunkPos pos;
        final long retentionMillis;

        ChunkLoadRequest(int dimensionId, ChunkPos pos,
                         long retentionMillis) {
            this.dimensionId = dimensionId;
            this.pos = pos;
            this.retentionMillis = retentionMillis;
        }
    }

    private final Map<Integer, Map<ChunkPos, ChunkEntry>> chunks =
            new HashMap<>();

    private final Queue<ChunkLoadRequest> incomingRequests =
            new ConcurrentLinkedQueue<>();

    private final Deque<ChunkEntry> pendingLoads =
            new ArrayDeque<>();

    public void requestChunk(int dimensionId, ChunkPos pos, long retentionMillis) {
        incomingRequests.offer(
                new ChunkLoadRequest(dimensionId, pos, retentionMillis)
        );
    }

    private static final int MAX_REQUESTS_PER_TICK = 512;

    private void processIncomingRequests() {

        for (int i = 0; i < MAX_REQUESTS_PER_TICK; i++) {

            ChunkLoadRequest request = incomingRequests.poll();

            if (request == null) {
                break;
            }

            Map<ChunkPos, ChunkEntry> dimensionChunks =
                    chunks.computeIfAbsent(
                            request.dimensionId,
                            id -> new HashMap<>()
                    );

            ChunkEntry entry = dimensionChunks.computeIfAbsent(
                    request.pos,
                    pos -> new ChunkEntry(pos, request.dimensionId)
            );

            long expiration = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(request.retentionMillis);

            entry.retainUntil = Math.max(
                    entry.retainUntil,
                    expiration
            );

            if (entry.status == ChunkStatus.QUEUED && !entry.enqueued) {
                pendingLoads.addLast(entry);
                entry.enqueued = true;
            }
        }
    }

}
