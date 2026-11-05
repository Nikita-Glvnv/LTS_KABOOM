package com.kaboom.server;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.DimensionManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

public class ChunkLoadingManager {
    private static final int MAX_CHUNKS_PER_TICK = 2;

    private static final Logger LOGGER =
            LogManager.getLogger("LTS_KABOOM");

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

    private void processPendingLoads() {

        for (int i = 0; i < MAX_CHUNKS_PER_TICK; i++) {

            ChunkEntry entry = pendingLoads.pollFirst();

            if (entry == null) {
                break;
            }

            entry.enqueued = false;

            if (entry.status != ChunkStatus.QUEUED) {
                continue;
            }

            WorldServer world =
                    DimensionManager.getWorld(entry.dimensionId);

            if (world == null) {
                entry.status = ChunkStatus.FAILED;
                continue;
            }

            entry.status = ChunkStatus.LOADING;

            try {
                Chunk chunk = world.getChunkProvider()
                        .getLoadedChunk(entry.pos.x, entry.pos.z);

                if (chunk == null) {
                    chunk = world.getChunkFromChunkCoords(
                            entry.pos.x,
                            entry.pos.z
                    );
                }

                if (chunk == null) {
                    entry.status = ChunkStatus.FAILED;
                    continue;
                }

                entry.status = ChunkStatus.READY;

                LOGGER.debug(
                        "Chunk loaded: dimension={}, x={}, z={}",
                        entry.dimensionId,
                        entry.pos.x,
                        entry.pos.z
                );

            } catch (Exception e) {

                entry.status = ChunkStatus.FAILED;

                LOGGER.error(
                        "Failed to load chunk: dimension={}, pos={}",
                        entry.dimensionId,
                        entry.pos,
                        e
                );
            }
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {

        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        processIncomingRequests();
        processPendingLoads();
    }

}
