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

public class chunksnapshot {
    private static final int MAX_REQUESTS_PER_TICK = 512;
    private static final int MAX_INITIAL_SECTIONS_PER_TICK = 4;
    private static final int MAX_INITIAL_CHECKS_PER_TICK = 64;

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

    private enum RequestType {
        START,
        STOP
    }

    private static final class TrackingRequest {
        final RequestType type;
        final int dimensionId;
        final int chunkX;
        final int chunkZ;

        TrackingRequest(
                RequestType type,
                int dimensionId,
                int chunkX,
                int chunkZ
        ) {
            this.type = type;
            this.dimensionId = dimensionId;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    private static final class TrackedChunk {
        final int dimensionId;
        final int chunkX;
        final int chunkZ;

        final ChunkUpdateConroller.ChunkState updates =
                new ChunkUpdateConroller.ChunkState();

        int nextInitialSection = 0;
        boolean initialized = false;

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
    }

    private TrackedChunk registerChunk(
            int dimensionId,
            int chunkX,
            int chunkZ
    ) {
        Map<Long, TrackedChunk> dimensionChunks =
                trackedChunks.computeIfAbsent(
                        dimensionId,
                        id -> new HashMap<>()
                );

        long key = ChunkPos.asLong(chunkX, chunkZ);

        return dimensionChunks.computeIfAbsent(
                key,
                k -> {
                    TrackedChunk tracked =
                            new TrackedChunk(
                                    dimensionId,
                                    chunkX,
                                    chunkZ
                            );

                    pendingInitial.addLast(tracked);
                    updateController.onChunkTracked(
                            dimensionId, chunkX, chunkZ, tracked.updates
                    );
                    return tracked;
                }
        );
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
            return;
        }

        dimensionChunks.remove(
                ChunkPos.asLong(chunkX, chunkZ)
        );

        if (dimensionChunks.isEmpty()) {
            trackedChunks.remove(dimensionId);
        }

        TrackedChunk removed = dimensionChunks.remove(
                ChunkPos.asLong(chunkX, chunkZ)
        );

        if (removed != null) {
            updateController.onChunkUntracked(removed.updates);
        }
    }

    private void unregisterDimension(int dimensionId) {
        trackedChunks.remove(dimensionId);

        pendingInitial.removeIf(
                tracked -> tracked.dimensionId == dimensionId
        );

        incomingRequests.removeIf(
                request -> request.dimensionId == dimensionId
        );
        updateController.onDimensionUntracked(dimensionId);
    }

    public void requestTracking(
            int dimensionId,
            int chunkX,
            int chunkZ
    ) {
        incomingRequests.offer(
                new TrackingRequest(
                        RequestType.START,
                        dimensionId,
                        chunkX,
                        chunkZ
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
                        chunkZ
                )
        );
    }

    private void processRequests() {
        for (int i = 0; i < MAX_REQUESTS_PER_TICK; i++) {
            TrackingRequest request = incomingRequests.poll();

            if (request == null) {
                break;
            }

            switch (request.type) {
                case START:
                    if (DimensionManager.getWorld(
                            request.dimensionId, false
                    ) != null) {
                        registerChunk(
                                request.dimensionId,
                                request.chunkX,
                                request.chunkZ
                        );
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

            if (getTrackedChunk(
                    tracked.dimensionId,
                    tracked.chunkX,
                    tracked.chunkZ
            ) != tracked) {
                continue;
            }

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
                unavailable++;
                continue;
            }

            SectionSnapshot snapshot = captureSection(
                    world,
                    chunk,
                    tracked.dimensionId,
                    tracked.nextInitialSection
            );

            if (!receiver.offer(snapshot)) {
                pendingInitial.addFirst(tracked);
                break;
            }

            updateController.onInitialSectionSent(
                    tracked.updates,
                    snapshot
            );

            tracked.nextInitialSection++;
            budget--;
            unavailable = 0;

            if (tracked.nextInitialSection < 16) {
                pendingInitial.addLast(tracked);
            } else {
                tracked.initialized = true;
            }
        }
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (event.getWorld() instanceof WorldServer) {
            updateController.registerWorld(
                    (WorldServer) event.getWorld()
            );
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        processRequests();
        processInitialSections();
        updateController.processDirtySections();
        updateController.scanSections();
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
    }

    public void stop() {
        for (WorldServer world : DimensionManager.getWorlds()) {
            updateController.unregisterWorld(world);
        }
        updateController.stop();
        incomingRequests.clear();
        pendingInitial.clear();
        trackedChunks.clear();
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

    boolean isChunkInitialized(int dimensionId, int chunkX, int chunkZ) {
        TrackedChunk tracked = getTrackedChunk(dimensionId, chunkX, chunkZ);
        return tracked != null && tracked.initialized;
    }

    boolean offerUpdate(SnapshotUpdate update) {
        return receiver.offer(update);
    }
}
