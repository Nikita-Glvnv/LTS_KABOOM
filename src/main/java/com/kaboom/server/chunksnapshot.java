package com.kaboom.server;

import com.kaboom.explosionwork.oktoderevo;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraft.entity.Entity;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.kaboom.ModLogger.LOGGER;

public class chunksnapshot {
    private static final int MAX_REQUESTS_PER_TICK = 512;
    private static final int MAX_INITIAL_SECTIONS_PER_TICK = 4;
    private static final int MAX_INITIAL_CHECKS_PER_TICK = 64;
    private static final int LOG_INTERVAL_TICKS = 20;

    private static final byte[] AIR_TYPES = new byte[4096];
    private static final float[] ZERO_VALUES = new float[4096];

    private final ChunkUpdateConroller updateController =
            new ChunkUpdateConroller(this);

    private final Map<Integer, Map<Long, TrackedChunk>> trackedChunks =
            new HashMap<>();

    private final Queue<TrackingRequest> incomingRequests =
            new ConcurrentLinkedQueue<>();

    private final Deque<TrackedChunk> pendingInitial =
            new ArrayDeque<>();

    // Only these counters are shared with DimensionWorker threads.
    private final AtomicLong enqueuedStarts = new AtomicLong();
    private final AtomicLong enqueuedStops = new AtomicLong();

    // All other counters are written on the server thread.
    private static final class Metrics {
        long requests, starts, duplicateStarts, stops, missingStops, missingWorld;
        long initialChecks, initialSent, initialUnavailable, initialStale;
        long initialRejected, initialComplete, requestLimitHits, initialLimitHits;
        long requestNanos, initialNanos, dirtyNanos, scanNanos, maxTickNanos;
    }

    private Metrics metrics = new Metrics();
    private int logTicks;
    private int idleIntervals;
    private long windowStart = System.nanoTime();

    private enum RequestType {
        START,
        STOP
    }

    private static final class TrackingRequest {
        final RequestType type;
        final int dimensionId;
        final int chunkX;
        final int chunkZ;
        final int sectionY;

        TrackingRequest(
                RequestType type,
                int dimensionId,
                int chunkX,
                int chunkZ,
                int sectionY
        ) {
            this.type = type;
            this.dimensionId = dimensionId;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.sectionY = sectionY;
        }
    }

    private static final class TrackedChunk {
        final int dimensionId;
        final int chunkX;
        final int chunkZ;

        final ChunkUpdateConroller.ChunkState updates =
                new ChunkUpdateConroller.ChunkState();

        // Секции, отслеживание которых было запрошено.
        int requestedMask = 0;

        // Секции, ожидающие первоначальной или повторной отправки.
        int pendingMask = 0;

        // Секции, снимки которых успешно переданы.
        int initializedMask = 0;

        // Предотвращает повторное добавление чанка в очередь.
        boolean queuedForInitial = false;

        TrackedChunk(int dimensionId, int chunkX, int chunkZ) {
            this.dimensionId = dimensionId;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    public static final class SectionChanges implements SnapshotUpdate {
        public final int dimensionId;
        public final int chunkX, chunkZ, sectionY;

        public final short[] positions;
        public final byte[] types;
        public final float[] hardness;
        public final float[] resistance;
        public final int count;

        public SectionChanges(
                int dimensionId, int chunkX, int chunkZ, int sectionY,
                short[] positions, byte[] types,
                float[] hardness, float[] resistance,
                int count
        ) {
            this.dimensionId = dimensionId;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.sectionY = sectionY;
            this.positions = positions;
            this.types = types;
            this.hardness = hardness;
            this.resistance = resistance;
            this.count = count;
        }

        @Override
        public int getDimensionId() {
            return dimensionId;
        }
    }

    public static final class SectionSnapshot implements SnapshotUpdate {
        public final int dimensionId;
        public final int chunkX;
        public final int chunkZ;
        public final int sectionY;

        public final byte[] types;
        public final float[] hardness;
        public final float[] resistance;

        public SectionSnapshot(
                int dimensionId,
                int chunkX,
                int chunkZ,
                int sectionY,
                byte[] types,
                float[] hardness,
                float[] resistance
        ) {
            this.dimensionId = dimensionId;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.sectionY = sectionY;
            this.types = types;
            this.hardness = hardness;
            this.resistance = resistance;
        }


        @Override
        public int getDimensionId() {
            return dimensionId;
        }
    }
    @FunctionalInterface
    public interface SnapshotReceiver {
        boolean offer(SnapshotUpdate update);
    }

    private final SnapshotReceiver receiver;

    public chunksnapshot(SnapshotReceiver receiver) {
        this.receiver = Objects.requireNonNull(receiver);
        for (WorldServer world : DimensionManager.getWorlds()) {
            updateController.registerWorld(world);
        }
        LOGGER.info("Server snapshots initialized: worlds={}, requestsPerTick={}, initialSectionsPerTick={}",
                DimensionManager.getWorlds().length, MAX_REQUESTS_PER_TICK, MAX_INITIAL_SECTIONS_PER_TICK);
    }

    private TrackedChunk registerChunk(
            int dimensionId,
            int chunkX,
            int chunkZ,
            int sectionY
    ) {
        Map<Long, TrackedChunk> dimensionChunks =
                trackedChunks.computeIfAbsent(
                        dimensionId,
                        id -> new HashMap<>()
                );

        long key = ChunkPos.asLong(chunkX, chunkZ);

        TrackedChunk tracked = dimensionChunks.get(key);

        if (tracked == null) {
            tracked = new TrackedChunk(
                    dimensionId,
                    chunkX,
                    chunkZ
            );

            dimensionChunks.put(key, tracked);

            updateController.onChunkTracked(
                    dimensionId,
                    chunkX,
                    chunkZ,
                    tracked.updates
            );
        }

        int bit = 1 << sectionY;

        tracked.requestedMask |= bit;
        tracked.pendingMask |= bit;

        // Повторный запрос должен заново отправить секцию.
        tracked.initializedMask &= ~bit;

        if (!tracked.queuedForInitial) {
            pendingInitial.addLast(tracked);
            tracked.queuedForInitial = true;
        }

        return tracked;
    }

    private TrackedChunk getTrackedChunk(
            int dimensionId,
            int chunkX,
            int chunkZ
    ) {
        Map<Long, TrackedChunk> dimensionChunks =
                trackedChunks.get(dimensionId);

        if (dimensionChunks == null) {
            return null;
        }

        return dimensionChunks.get(
                ChunkPos.asLong(chunkX, chunkZ)
        );
    }

    private void unregisterChunk(
            int dimensionId,
            int chunkX,
            int chunkZ
    ) {
        Map<Long, TrackedChunk> dimensionChunks =
                trackedChunks.get(dimensionId);

        if (dimensionChunks == null) {
            metrics.missingStops++;
            return;
        }

        TrackedChunk removed = dimensionChunks.remove(
                ChunkPos.asLong(chunkX, chunkZ)
        );

        if (removed != null) {
            metrics.stops++;
            updateController.onChunkUntracked(removed.updates);
            pendingInitial.removeIf(
                    tracked -> tracked == removed
            );
            LOGGER.info(
                    "Server snapshot tracking stopped: dimension={}, chunk=({}, {}), requestedMask={}, initializedMask={}, pendingMask={}",
                    dimensionId, chunkX, chunkZ,
                    removed.requestedMask,
                    removed.initializedMask,
                    removed.pendingMask
            );
        } else {
            metrics.missingStops++;
        }

        if (dimensionChunks.isEmpty()) {
            trackedChunks.remove(dimensionId);
        }
    }

    private void unregisterDimension(int dimensionId) {
        Map<Long, TrackedChunk> removed = trackedChunks.remove(dimensionId);
        int initialBefore = pendingInitial.size();
        int requestsBefore = incomingRequests.size();

        pendingInitial.removeIf(
                tracked -> tracked.dimensionId == dimensionId
        );

        incomingRequests.removeIf(
                request -> request.dimensionId == dimensionId
        );
        updateController.onDimensionUntracked(dimensionId);
        LOGGER.info("Server snapshot dimension cleared: dimension={}, trackedRemoved={}, initialRemoved={}, requestsRemoved={}",
                dimensionId, removed == null ? 0 : removed.size(),
                initialBefore - pendingInitial.size(), requestsBefore - incomingRequests.size());
    }

    public void requestTracking(
            int dimensionId,
            int chunkX,
            int chunkZ,
            int sectionY
    ) {
        if (sectionY < 0 || sectionY >= 16) {
            throw new IllegalArgumentException(
                    "Invalid sectionY: " + sectionY
            );
        }

        incomingRequests.offer(
                new TrackingRequest(
                        RequestType.START,
                        dimensionId,
                        chunkX,
                        chunkZ,
                        sectionY
                )
        );
    }

    public void releaseTracking(
            int dimensionId,
            int chunkX,
            int chunkZ
    ) {
        incomingRequests.offer(
                new TrackingRequest(
                        RequestType.STOP,
                        dimensionId,
                        chunkX,
                        chunkZ,
                        -1
                )
        );
        enqueuedStops.incrementAndGet();
    }

    private void processRequests() {
        int processed = 0;
        for (int i = 0; i < MAX_REQUESTS_PER_TICK; i++) {
            TrackingRequest request = incomingRequests.poll();

            if (request == null) {
                break;
            }
            processed++;
            metrics.requests++;

            switch (request.type) {
                case START:
                    if (DimensionManager.getWorld(
                            request.dimensionId, false
                    ) != null) {
                        if (getTrackedChunk(request.dimensionId, request.chunkX, request.chunkZ) == null) {
                            registerChunk(
                                    request.dimensionId,
                                    request.chunkX,
                                    request.chunkZ,
                                    request.sectionY
                            );
                            metrics.starts++;
                            LOGGER.info("Server snapshot tracking started: dimension={}, chunk=({}, {}), initialQueue={}",
                                    request.dimensionId, request.chunkX, request.chunkZ, pendingInitial.size());
                        } else {
                            metrics.duplicateStarts++;
                            LOGGER.debug("Server snapshot tracking already active: dimension={}, chunk=({}, {})",
                                    request.dimensionId, request.chunkX, request.chunkZ);
                        }
                    } else {
                        metrics.missingWorld++;
                        LOGGER.debug("Server snapshot tracking skipped: world unavailable, dimension={}, chunk=({}, {})",
                                request.dimensionId, request.chunkX, request.chunkZ);
                    }
                    break;

                case STOP:
                    unregisterChunk(
                            request.dimensionId,
                            request.chunkX,
                            request.chunkZ
                    );
                    break;
            }
        }
        if (processed == MAX_REQUESTS_PER_TICK && !incomingRequests.isEmpty()) {
            metrics.requestLimitHits++;
        }
    }

    @SuppressWarnings("deprecation")
    SectionSnapshot captureSection(
            WorldServer world,
            Chunk chunk,
            int dimensionId,
            int sectionY
    ) {
        ExtendedBlockStorage storage =
                chunk.getBlockStorageArray()[sectionY];

        if (storage == null || storage.isEmpty()) {
            return new SectionSnapshot(
                    dimensionId, chunk.x, chunk.z, sectionY,
                    AIR_TYPES, ZERO_VALUES, ZERO_VALUES
            );
        }

        byte[] types = new byte[4096];
        float[] hardness = new float[4096];
        float[] resistance = new float[4096];

        int baseX = chunk.x << 4;
        int baseY = sectionY << 4;
        int baseZ = chunk.z << 4;

        BlockPos.MutableBlockPos pos =
                new BlockPos.MutableBlockPos();

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {

                    int index = (y << 8) | (z << 4) | x;

                    IBlockState state = storage.get(x, y, z);
                    Block block = state.getBlock();

                    pos.setPos(
                            baseX + x,
                            baseY + y,
                            baseZ + z
                    );

                    if (block.isAir(state, world, pos)) {
                        continue;
                    }

                    if (state.getMaterial().isLiquid()) {
                        types[index] = oktoderevo.LIQUID;
                        continue;
                    }

                    types[index] = oktoderevo.BLOCK;

                    hardness[index] =
                            state.getBlockHardness(world, pos);

                    resistance[index] =
                            block.getExplosionResistance((Entity) null);
                }
            }
        }

        return new SectionSnapshot(
                dimensionId,
                chunk.x,
                chunk.z,
                sectionY,
                types,
                hardness,
                resistance
        );
    }

    private void processInitialSections() {
        int budget = MAX_INITIAL_SECTIONS_PER_TICK;
        int unavailable = 0;
        int checks = 0;

        while (budget > 0
                && !pendingInitial.isEmpty()
                && checks < MAX_INITIAL_CHECKS_PER_TICK) {

            if (unavailable >= pendingInitial.size()) {
                break;
            }

            checks++;

            TrackedChunk tracked = pendingInitial.pollFirst();
            tracked.queuedForInitial = false;

            // Проверяем, что чанк ещё отслеживается.
            if (getTrackedChunk(
                    tracked.dimensionId,
                    tracked.chunkX,
                    tracked.chunkZ
            ) != tracked) {
                continue;
            }

            if (tracked.pendingMask == 0) {
                continue;
            }

            // Выбираем одну из запрошенных секций.
            int sectionY = Integer.numberOfTrailingZeros(
                    tracked.pendingMask
            );

            WorldServer world = DimensionManager.getWorld(
                    tracked.dimensionId,
                    false
            );

            Chunk chunk = world == null
                    ? null
                    : world.getChunkProvider().getLoadedChunk(
                    tracked.chunkX,
                    tracked.chunkZ
            );

            if (chunk == null) {
                pendingInitial.addLast(tracked);
                tracked.queuedForInitial = true;
                unavailable++;
                continue;
            }

            SectionSnapshot snapshot = captureSection(
                    world,
                    chunk,
                    tracked.dimensionId,
                    sectionY
            );

            if (!receiver.offer(snapshot)) {
                pendingInitial.addFirst(tracked);
                tracked.queuedForInitial = true;
                break;
            }

            updateController.onInitialSectionSent(
                    tracked.updates,
                    snapshot
            );

            int bit = 1 << sectionY;

            tracked.pendingMask &= ~bit;
            tracked.initializedMask |= bit;

            budget--;
            unavailable = 0;

            // Остались другие запрошенные секции.
            if (tracked.pendingMask != 0) {
                pendingInitial.addLast(tracked);
                tracked.queuedForInitial = true;
            }
        }
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (event.getWorld() instanceof WorldServer) {
            updateController.registerWorld(
                    (WorldServer) event.getWorld()
            );
            LOGGER.info("Server snapshot world loaded: dimension={}",
                    event.getWorld().provider.getDimension());
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        long tickStart = System.nanoTime();
        long stageStart = tickStart;
        String stage = "requests";
        try {
            processRequests();
            metrics.requestNanos += System.nanoTime() - stageStart;
            stage = "initial";
            stageStart = System.nanoTime();
            processInitialSections();
            metrics.initialNanos += System.nanoTime() - stageStart;
            stage = "dirty";
            stageStart = System.nanoTime();
            updateController.processDirtySections();
            metrics.dirtyNanos += System.nanoTime() - stageStart;
            stage = "scan";
            stageStart = System.nanoTime();
            updateController.scanSections();
            metrics.scanNanos += System.nanoTime() - stageStart;
        } catch (RuntimeException | Error e) {
            LOGGER.error("Server snapshot tick failed: stage=" + stage
                    + ", pendingRequests=" + incomingRequests.size()
                    + ", pendingInitial=" + pendingInitial.size(), e);
            throw e;
        } finally {
            long tickNanos = System.nanoTime() - tickStart;
            metrics.maxTickNanos = Math.max(metrics.maxTickNanos, tickNanos);
            if (++logTicks >= LOG_INTERVAL_TICKS) {
                logTicks = 0;
                logMetricsIfDue();
            }
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (!(event.getWorld() instanceof WorldServer)) {
            return;
        }
        updateController.unregisterWorld(
                (WorldServer) event.getWorld()
        );
        unregisterDimension(
                event.getWorld().provider.getDimension()
        );
        LOGGER.info("Server snapshot world unloaded: dimension={}",
                event.getWorld().provider.getDimension());
    }

    public void stop() {
        LOGGER.info("Server snapshots stopping: trackedChunks={}, pendingRequests={}, pendingInitial={}",
                trackedCount(), incomingRequests.size(), pendingInitial.size());
        for (WorldServer world : DimensionManager.getWorlds()) {
            updateController.unregisterWorld(world);
        }
        updateController.stop();
        incomingRequests.clear();
        pendingInitial.clear();
        trackedChunks.clear();
        LOGGER.info("Server snapshots stopped");
    }

    private int trackedCount() {
        int count = 0;
        for (Map<Long, TrackedChunk> dimension : trackedChunks.values()) {
            count += dimension.size();
        }
        return count;
    }

    private void logMetricsIfDue() {
        long now = System.nanoTime();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(now - windowStart);
        windowStart = now;
        Metrics m = metrics;
        metrics = new Metrics();
        long startsQueued = enqueuedStarts.getAndSet(0);
        long stopsQueued = enqueuedStops.getAndSet(0);
        if (startsQueued > 0 || stopsQueued > 0 || m.requests > 0
                || m.initialChecks > 0 || trackedCount() > 0) {
            idleIntervals = 0;
            LOGGER.info("Server snapshots stats: elapsedMs={}, startsQueued={}, stopsQueued={}, requests={}, started={}, duplicateStarts={}, stopped={}, missingStops={}, missingWorld={}, requestLimitHits={}, pendingRequests={}, trackedChunks={}, pendingInitial={}, initialChecks={}, initialStale={}, initialUnavailable={}, initialSent={}, initialRejected={}, initialComplete={}, initialLimitHits={}, requestTimeUs={}, initialTimeUs={}, dirtyTimeUs={}, scanTimeUs={}, maxTickUs={}",
                    elapsedMs, startsQueued, stopsQueued, m.requests, m.starts,
                    m.duplicateStarts, m.stops, m.missingStops, m.missingWorld,
                    m.requestLimitHits, incomingRequests.size(), trackedCount(),
                    pendingInitial.size(), m.initialChecks, m.initialStale,
                    m.initialUnavailable, m.initialSent, m.initialRejected,
                    m.initialComplete, m.initialLimitHits, m.requestNanos / 1_000L,
                    m.initialNanos / 1_000L, m.dirtyNanos / 1_000L,
                    m.scanNanos / 1_000L, m.maxTickNanos / 1_000L);
        } else if (++idleIntervals >= 60) {
            idleIntervals = 0;
            LOGGER.debug("Server snapshots idle: pendingRequests={}, pendingInitial={}",
                    incomingRequests.size(), pendingInitial.size());
        }
        if (m.requestLimitHits > 0) {
            LOGGER.warn("Server snapshot request limit reached: ticks={}, pendingRequests={}",
                    m.requestLimitHits, incomingRequests.size());
        }
        if (m.initialRejected > 0 || m.missingWorld > 0) {
            LOGGER.warn("Server snapshots deferred or rejected: initialRejected={}, missingWorld={}",
                    m.initialRejected, m.missingWorld);
        }
        updateController.logMetricsIfDue(elapsedMs);
    }

    ChunkUpdateConroller.ChunkState getUpdateState(
            int dimensionId,
            int chunkX,
            int chunkZ
    ) {
        TrackedChunk tracked = getTrackedChunk(
                dimensionId,
                chunkX,
                chunkZ
        );

        return tracked == null ? null : tracked.updates;
    }

    public interface SnapshotUpdate {
        int getDimensionId();
    }

    boolean isSectionInitialized(
            int dimensionId,
            int chunkX,
            int chunkZ,
            int sectionY
    ) {
        if (sectionY < 0 || sectionY >= 16) {
            return false;
        }

        TrackedChunk tracked = getTrackedChunk(
                dimensionId,
                chunkX,
                chunkZ
        );

        return tracked != null
                && (tracked.initializedMask & (1 << sectionY)) != 0;
    }

    boolean isChunkInitialized(
            int dimensionId,
            int chunkX,
            int chunkZ
    ) {
        TrackedChunk tracked = getTrackedChunk(
                dimensionId,
                chunkX,
                chunkZ
        );

        return tracked != null
                && tracked.initializedMask == 0xFFFF;
    }

    boolean offerUpdate(SnapshotUpdate update) {
        return receiver.offer(update);
    }
}
