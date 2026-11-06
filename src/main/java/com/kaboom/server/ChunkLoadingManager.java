package com.kaboom.server;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.event.world.WorldEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import com.kaboom.Kaboom;
import net.minecraftforge.common.ForgeChunkManager;

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
        ForgeChunkManager.Ticket ticket;
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

            if (DimensionManager.getWorld(
                    request.dimensionId, false
            ) == null) {
                continue;
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
            if (entry.status == ChunkStatus.FAILED) {
                entry.status = ChunkStatus.QUEUED;
            }

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

            Map<ChunkPos, ChunkEntry> dimensionChunks =
                    chunks.get(entry.dimensionId);

            if (dimensionChunks == null ||
                    dimensionChunks.get(entry.pos) != entry) {
                continue;
            }

            if (System.nanoTime() - entry.retainUntil >= 0) {
                continue;
            }

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

            ForgeChunkManager.Ticket ticket = getAvailableTicket(world);

            if (ticket == null) {
                entry.status = ChunkStatus.FAILED;
                LOGGER.warn(
                        "No available ticket: dimension={}, pos={}",
                        entry.dimensionId, entry.pos
                );
                continue;
            }

            entry.ticket = ticket;

            try {
                ForgeChunkManager.forceChunk(ticket, entry.pos);

                if (!ticket.getChunkList().contains(entry.pos)) {
                    throw new IllegalStateException(
                            "Chunk was not registered in ticket"
                    );
                }

                entry.forced = true;

                Chunk chunk = world.getChunkProvider()
                        .getLoadedChunk(entry.pos.x, entry.pos.z);

                if (chunk == null) {
                    chunk = world.getChunkFromChunkCoords(
                            entry.pos.x,
                            entry.pos.z
                    );
                }

                if (chunk == null) {
                    throw new IllegalStateException("Chunk loading failed");
                }

                entry.status = ChunkStatus.READY;
                LOGGER.info(
                        "Chunk READY: dimension={}, pos={}",
                        entry.dimensionId, entry.pos
                );

            } catch (Exception e) {
                releaseChunkHold(entry);
                entry.status = ChunkStatus.FAILED;
                LOGGER.error("Chunk loading failed: {}", entry.pos, e);
            }
        }
    }

    private void releaseChunkHold(ChunkEntry entry) {

        ForgeChunkManager.Ticket ticket = entry.ticket;

        if (ticket == null) {
            return;
        }

        if (ticket.getChunkList().contains(entry.pos)) {
            ForgeChunkManager.unforceChunk(ticket, entry.pos);
            LOGGER.info(
                    "Chunk RELEASED: dimension={}, pos={}",
                    entry.dimensionId, entry.pos
            );
        }

        entry.ticket = null;
        entry.forced = false;

        if (ticket.getChunkList().isEmpty()) {

            ForgeChunkManager.releaseTicket(ticket);

            List<ForgeChunkManager.Ticket> dimensionTickets =
                    tickets.get(entry.dimensionId);

            if (dimensionTickets != null) {
                dimensionTickets.remove(ticket);

                if (dimensionTickets.isEmpty()) {
                    tickets.remove(entry.dimensionId);
                }
            }
        }
    }

    private void releaseDimension(int dimensionId) {

        Map<ChunkPos, ChunkEntry> removedChunks =
                chunks.remove(dimensionId);

        pendingLoads.removeIf(
                entry -> entry.dimensionId == dimensionId
        );

        incomingRequests.removeIf(
                request -> request.dimensionId == dimensionId
        );

        List<ForgeChunkManager.Ticket> dimensionTickets =
                tickets.remove(dimensionId);

        int releasedTickets = 0;

        if (dimensionTickets != null) {

            for (ForgeChunkManager.Ticket ticket : dimensionTickets) {

                if (!ticket.getChunkList().isEmpty()) {
                    LOGGER.error(
                            "Dimension {} unloaded with {} chunks still forced",
                            dimensionId,
                            ticket.getChunkList().size()
                    );
                    continue;
                }

                ForgeChunkManager.releaseTicket(ticket);
                releasedTickets++;
            }
        }

        LOGGER.info(
                "Dimension cleanup: dimension={}, entries={}, tickets={}",
                dimensionId,
                removedChunks == null ? 0 : removedChunks.size(),
                releasedTickets
        );
    }

    private int cleanupTickCounter;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {

        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        processIncomingRequests();
        processPendingLoads();
        if (++cleanupTickCounter >= 20) {
            cleanupTickCounter = 0;
            processExpiredChunks();
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {

        if (!(event.getWorld() instanceof WorldServer)) {
            return;
        }

        int dimensionId = event.getWorld()
                .provider.getDimension();

        releaseDimension(dimensionId);
    }

    //удержание чанков

    private final Map<Integer, List<ForgeChunkManager.Ticket>> tickets =
            new HashMap<>();

    private static final int MAX_CHUNKS_PER_TICKET = 25;

    private ForgeChunkManager.Ticket getAvailableTicket(WorldServer world) {

        int dimensionId = world.provider.getDimension();

        List<ForgeChunkManager.Ticket> dimensionTickets =
                tickets.computeIfAbsent(
                        dimensionId,
                        id -> new ArrayList<>()
                );

        for (ForgeChunkManager.Ticket ticket : dimensionTickets) {

            int depth = ticket.getChunkListDepth();

            int limit = depth > 0
                    ? Math.min(depth, MAX_CHUNKS_PER_TICKET)
                    : MAX_CHUNKS_PER_TICKET;

            if (ticket.getChunkList().size() < limit) {
                return ticket;
            }
        }

        ForgeChunkManager.Ticket ticket =
                ForgeChunkManager.requestTicket(
                        Kaboom.INSTANCE,
                        world,
                        ForgeChunkManager.Type.NORMAL
                );

        if (ticket != null) {
            dimensionTickets.add(ticket);
        }

        return ticket;
    }

    private void processExpiredChunks() {

        long now = System.nanoTime();

        Iterator<Map.Entry<Integer, Map<ChunkPos, ChunkEntry>>> dimensions =
                chunks.entrySet().iterator();

        while (dimensions.hasNext()) {

            Map<ChunkPos, ChunkEntry> dimensionChunks =
                    dimensions.next().getValue();

            Iterator<ChunkEntry> iterator =
                    dimensionChunks.values().iterator();

            while (iterator.hasNext()) {

                ChunkEntry entry = iterator.next();

                if (now - entry.retainUntil < 0) {
                    continue;
                }

                releaseChunkHold(entry);
                iterator.remove();
            }

            if (dimensionChunks.isEmpty()) {
                dimensions.remove();
            }
        }
    }

    public void stop() {

        for (List<ForgeChunkManager.Ticket> dimensionTickets
                : tickets.values()) {

            for (ForgeChunkManager.Ticket ticket : dimensionTickets) {
                ForgeChunkManager.releaseTicket(ticket);
            }
        }

        tickets.clear();
        chunks.clear();
        pendingLoads.clear();
        incomingRequests.clear();
    }
}
