package com.kaboom.server;

import java.util.BitSet;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import com.kaboom.explosionwork.oktoderevo;
import net.minecraft.block.Block;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.DimensionManager;
import java.util.ArrayDeque;
import java.util.Deque;

import javax.annotation.Nullable;

import static com.kaboom.ModLogger.LOGGER;

public class ChunkUpdateConroller implements IWorldEventListener{
    static final class ChunkState {
        boolean queued = false;
        final BitSet[] dirtySections = new BitSet[16];
        final long[] sectionRevisions = new long[16];
        int nextScanSection = 0;

        final long[] sectionHashes = new long[16];
        final boolean[] sectionHashReady = new boolean[16];
    }

    private final chunksnapshot owner;

    // Updated on the server thread only.
    private static final class Metrics {
        long blockEvents, duplicateEvents, dirtyQueued, dirtyChecks;
        long dirtyStale, dirtyDeferredInitial, dirtyDeferredUnloaded;
        long dirtySent, dirtyBlocks, dirtyFullSent, dirtyRejected, dirtyLimitHits;
        long scanChecks, scanStale, scanDeferredInitial, scanDeferredUnloaded;
        long scanSkippedDirty, scanCompared, scanChanged, scanUnchanged;
        long scanLimitHits, fullResendsMarked, initialHashes;
    }

    private Metrics metrics = new Metrics();

    public ChunkUpdateConroller(chunksnapshot owner) {
        this.owner = owner;
    }

    @Override
    public void notifyBlockUpdate(
            World world,
            BlockPos pos,
            IBlockState oldState,
            IBlockState newState,
            int flags
    ) {
        if (!(world instanceof WorldServer)) {
            return;
        }

        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        if (y < 0 || y >= 256) {
            return;
        }

        ChunkState state = owner.getUpdateState(
                world.provider.getDimension(),
                x >> 4,
                z >> 4
        );

        // Чанк не отслеживается.
        if (state == null) {
            return;
        }

        int sectionY = y >> 4;


        if (!owner.isSectionInitialized(
                world.provider.getDimension(),
                x >> 4,
                z >> 4,
                sectionY
        )) {
            return;
        }


        int index =
                ((y & 15) << 8)
                        | ((z & 15) << 4)
                        | (x & 15);

        BitSet dirty = state.dirtySections[sectionY];

        if (dirty == null) {
            dirty = new BitSet(4096);
            state.dirtySections[sectionY] = dirty;
        }

        metrics.blockEvents++;
        if (dirty.get(index)) {
            metrics.duplicateEvents++;
        }
        dirty.set(index);
        if (!state.queued) {
            metrics.dirtyQueued++;
            state.queued = true;

            pendingDirty.addLast(new DirtyChunk(
                    world.provider.getDimension(),
                    x >> 4,
                    z >> 4,
                    state
            ));
        }
    }

    private static final int MAX_DIRTY_SECTIONS_PER_TICK = 4;
    private static final int MAX_DIRTY_CHUNK_CHECKS_PER_TICK = 64;

    private static final class DirtyChunk {
        final int dimensionId, chunkX, chunkZ;
        final ChunkState state;

        DirtyChunk(int dimensionId, int chunkX, int chunkZ, ChunkState state) {
            this.dimensionId = dimensionId;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.state = state;
        }
    }

    private final Deque<DirtyChunk> pendingDirty = new ArrayDeque<>();

    private chunksnapshot.SectionChanges createChanges(
            WorldServer world,
            Chunk chunk,
            DirtyChunk request,
            int sectionY,
            BitSet dirty
    ) {
        int count = dirty.cardinality();

        short[] positions = new short[count];
        byte[] types = new byte[count];
        float[] hardness = new float[count];
        float[] resistance = new float[count];

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        int i = 0;

        for (int index = dirty.nextSetBit(0);
             index >= 0;
             index = dirty.nextSetBit(index + 1)) {

            int localX = index & 15;
            int localZ = (index >> 4) & 15;
            int localY = (index >> 8) & 15;

            pos.setPos(
                    (request.chunkX << 4) + localX,
                    (sectionY << 4) + localY,
                    (request.chunkZ << 4) + localZ
            );

            positions[i] = (short) index;

            IBlockState blockState = chunk.getBlockState(pos);
            Block block = blockState.getBlock();

            if (!block.isAir(blockState, world, pos)) {
                if (blockState.getMaterial().isLiquid()) {
                    types[i] = oktoderevo.LIQUID;
                } else {
                    types[i] = oktoderevo.BLOCK;
                    hardness[i] = blockState.getBlockHardness(world, pos);
                    resistance[i] =
                            block.getExplosionResistance((Entity) null);
                }
            }

            i++;
        }

        return new chunksnapshot.SectionChanges(
                request.dimensionId,
                request.chunkX,
                request.chunkZ,
                sectionY,
                positions,
                types,
                hardness,
                resistance,
                count
        );
    }

    void processDirtySections() {
        int budget = MAX_DIRTY_SECTIONS_PER_TICK;
        int checks = Math.min(
                pendingDirty.size(),
                MAX_DIRTY_CHUNK_CHECKS_PER_TICK
        );

        while (budget > 0 && checks-- > 0) {
            DirtyChunk request = pendingDirty.pollFirst();
            metrics.dirtyChecks++;

            ChunkState state = owner.getUpdateState(
                    request.dimensionId,
                    request.chunkX,
                    request.chunkZ
            );

            // Запрос от уже удалённого или заменённого чанка.
            if (state != request.state) {
                metrics.dirtyStale++;
                continue;
            }

            WorldServer world = DimensionManager.getWorld(
                    request.dimensionId, false
            );

            Chunk chunk = world == null ? null
                    : world.getChunkProvider().getLoadedChunk(
                    request.chunkX,
                    request.chunkZ
            );

            if (chunk == null) {
                metrics.dirtyDeferredUnloaded++;
                pendingDirty.addLast(request);
                continue;
            }

            boolean queueFull = false;

            for (int sectionY = 0;
                 sectionY < 16 && budget > 0;
                 sectionY++) {

                BitSet dirty = state.dirtySections[sectionY];

                if (dirty == null || dirty.isEmpty()) {
                    continue;
                }

                if (!owner.isSectionInitialized(
                        request.dimensionId,
                        request.chunkX,
                        request.chunkZ,
                        sectionY
                )) {
                    metrics.dirtyDeferredInitial++;
                    continue;
                }


                chunksnapshot.SectionChanges changes =
                        createChanges(world, chunk, request, sectionY, dirty);

                if (!owner.offerUpdate(changes)) {
                    metrics.dirtyRejected++;
                    LOGGER.debug("Server delta offer rejected: dimension={}, chunk=({}, {}), section={}, blocks={}",
                            request.dimensionId, request.chunkX, request.chunkZ, sectionY, changes.count);
                    queueFull = true;
                    break;
                }
                metrics.dirtySent++;
                metrics.dirtyBlocks += changes.count;
                if (changes.count == 4096) {
                    metrics.dirtyFullSent++;
                }
                LOGGER.debug("Server delta sent: dimension={}, chunk=({}, {}), section={}, blocks={}, fullSection={}",
                        request.dimensionId, request.chunkX, request.chunkZ, sectionY,
                        changes.count, changes.count == 4096);
                onFullSectionChangesSent(state, changes);
                // Очищаем только после успешного принятия.
                dirty.clear();
                budget--;
            }

            boolean hasPending = false;

            for (BitSet dirty : state.dirtySections) {
                if (dirty != null && !dirty.isEmpty()) {
                    hasPending = true;
                    break;
                }
            }

            if (hasPending) {
                pendingDirty.addLast(request);
            } else {
                state.queued = false;
            }

            if (queueFull) {
                break;
            }
        }
        if (budget == 0 && !pendingDirty.isEmpty()) {
            metrics.dirtyLimitHits++;
        }
    }

    private static final long HASH_OFFSET = 0xcbf29ce484222325L;
    private static final long HASH_PRIME = 0x100000001b3L;

    private static long calculateSectionHash(
            byte[] types,
            float[] hardness,
            float[] resistance
    ) {
        long hash = HASH_OFFSET;

        for (int i = 0; i < 4096; i++) {
            hash = (hash ^ (types[i] & 0xFFL)) * HASH_PRIME;

            hash = (hash ^
                    (Float.floatToIntBits(hardness[i]) & 0xFFFFFFFFL))
                    * HASH_PRIME;

            hash = (hash ^
                    (Float.floatToIntBits(resistance[i]) & 0xFFFFFFFFL))
                    * HASH_PRIME;
        }

        return hash;
    }

    void onInitialSectionSent(
            ChunkState state,
            chunksnapshot.SectionSnapshot snapshot
    ) {
        int sectionY = snapshot.sectionY;

        state.sectionHashes[sectionY] = calculateSectionHash(
                snapshot.types,
                snapshot.hardness,
                snapshot.resistance
        );

        state.sectionHashReady[sectionY] = true;
        metrics.initialHashes++;

// Первоначальный снимок уже содержит все изменения,
// произошедшие до его создания.
        BitSet dirty = state.dirtySections[sectionY];

        if (dirty != null) {
            dirty.clear();
        }

    }

    private static final int MAX_SCAN_SECTIONS_PER_TICK = 1;
    private static final int MAX_SCAN_CHECKS_PER_TICK = 64;

    private final Deque<DirtyChunk> pendingScan = new ArrayDeque<>();

    void onChunkTracked(
            int dimensionId,
            int chunkX,
            int chunkZ,
            ChunkState state
    ) {
        pendingScan.addLast(
                new DirtyChunk(dimensionId, chunkX, chunkZ, state)
        );
    }

    void onChunkUntracked(ChunkState state) {
        pendingScan.removeIf(request -> request.state == state);
        pendingDirty.removeIf(request -> request.state == state);
    }

    void onDimensionUntracked(int dimensionId) {
        pendingScan.removeIf(request -> request.dimensionId == dimensionId);
        pendingDirty.removeIf(request -> request.dimensionId == dimensionId);
    }

    private void markSectionDirty(
            DirtyChunk request,
            int sectionY
    ) {
        ChunkState state = request.state;

        BitSet dirty = state.dirtySections[sectionY];

        if (dirty == null) {
            dirty = new BitSet(4096);
            state.dirtySections[sectionY] = dirty;
        }

        dirty.set(0, 4096);
        metrics.fullResendsMarked++;

        if (!state.queued) {
            state.queued = true;
            pendingDirty.addLast(request);
        }
    }

    void scanSections() {
        int budget = MAX_SCAN_SECTIONS_PER_TICK;
        int checks = Math.min(
                pendingScan.size(),
                MAX_SCAN_CHECKS_PER_TICK
        );

        while (budget > 0 && checks-- > 0) {
            DirtyChunk request = pendingScan.pollFirst();
            metrics.scanChecks++;

            ChunkState state = owner.getUpdateState(
                    request.dimensionId,
                    request.chunkX,
                    request.chunkZ
            );

            // Отбрасываем записи удалённых чанков.
            if (state != request.state) {
                metrics.scanStale++;
                continue;
            }

            // Сохраняем циклический порядок обхода.
            pendingScan.addLast(request);


// Ищем следующую инициализированную секцию.
            int sectionY = -1;

            for (int attempt = 0; attempt < 16; attempt++) {
                int candidate = state.nextScanSection;
                state.nextScanSection = (candidate + 1) & 15;

                if (owner.isSectionInitialized(
                        request.dimensionId,
                        request.chunkX,
                        request.chunkZ,
                        candidate
                )) {
                    sectionY = candidate;
                    break;
                }
            }

// В чанке пока нет готовых секций.
            if (sectionY == -1) {
                metrics.scanDeferredInitial++;
                continue;
            }


            WorldServer world = DimensionManager.getWorld(
                    request.dimensionId, false
            );

            Chunk chunk = world == null ? null
                    : world.getChunkProvider().getLoadedChunk(
                    request.chunkX,
                    request.chunkZ
            );

            if (chunk == null) {
                metrics.scanDeferredUnloaded++;
                continue;
            }

            BitSet dirty = state.dirtySections[sectionY];

            // Не проверяем секцию с ещё не отправленными изменениями.
            if (dirty != null && !dirty.isEmpty()) {
                metrics.scanSkippedDirty++;
                budget--;
                continue;
            }

            chunksnapshot.SectionSnapshot snapshot =
                    owner.captureSection(
                            world,
                            chunk,
                            request.dimensionId,
                            sectionY
                    );

            long currentHash = calculateSectionHash(
                    snapshot.types,
                    snapshot.hardness,
                    snapshot.resistance
            );

            metrics.scanCompared++;
            if (!state.sectionHashReady[sectionY]
                    || state.sectionHashes[sectionY] != currentHash) {
                metrics.scanChanged++;
                LOGGER.debug("Server section hash mismatch: dimension={}, chunk=({}, {}), section={}, previousHash={}, currentHash={}, hashReady={}, fullResendQueued=true",
                        request.dimensionId, request.chunkX, request.chunkZ, sectionY,
                        state.sectionHashes[sectionY], currentHash, state.sectionHashReady[sectionY]);
                markSectionDirty(request, sectionY);
            } else {
                metrics.scanUnchanged++;
            }

            budget--;
        }
        if (budget == 0 && !pendingScan.isEmpty()) {
            metrics.scanLimitHits++;
        }
    }

    private void onFullSectionChangesSent(
            ChunkState state,
            chunksnapshot.SectionChanges changes
    ) {
        if (changes.count != 4096) {
            return;
        }

        int sectionY = changes.sectionY;

        state.sectionHashes[sectionY] = calculateSectionHash(
                changes.types,
                changes.hardness,
                changes.resistance
        );

        state.sectionHashReady[sectionY] = true;
    }

    void logMetricsIfDue(long elapsedMs) {
        Metrics m = metrics;
        metrics = new Metrics();
        if (m.blockEvents > 0 || m.dirtyChecks > 0 || m.scanChecks > 0 || m.initialHashes > 0) {
            LOGGER.info("Server chunk updates stats: elapsedMs={}, blockEvents={}, duplicateEvents={}, dirtyQueued={}, dirtyChecks={}, dirtyStale={}, dirtyDeferredInitial={}, dirtyDeferredUnloaded={}, dirtySent={}, dirtyBlocks={}, dirtyFullSent={}, dirtyRejected={}, dirtyLimitHits={}, dirtyQueue={}, scanChecks={}, scanStale={}, scanDeferredInitial={}, scanDeferredUnloaded={}, scanSkippedDirty={}, scanCompared={}, scanChanged={}, scanUnchanged={}, scanLimitHits={}, fullResendsMarked={}, initialHashes={}, scanQueue={}",
                    elapsedMs, m.blockEvents, m.duplicateEvents,
                    m.dirtyQueued, m.dirtyChecks, m.dirtyStale,
                    m.dirtyDeferredInitial, m.dirtyDeferredUnloaded,
                    m.dirtySent, m.dirtyBlocks, m.dirtyFullSent,
                    m.dirtyRejected, m.dirtyLimitHits, pendingDirty.size(),
                    m.scanChecks, m.scanStale, m.scanDeferredInitial,
                    m.scanDeferredUnloaded, m.scanSkippedDirty, m.scanCompared,
                    m.scanChanged, m.scanUnchanged, m.scanLimitHits,
                    m.fullResendsMarked, m.initialHashes, pendingScan.size());
        }
        if (m.dirtyRejected > 0) {
            LOGGER.warn("Server delta offers rejected: count={}, pendingDirty={}",
                    m.dirtyRejected, pendingDirty.size());
        }
    }

    void stop() {
        LOGGER.info("Server chunk updates stopping: pendingDirty={}, pendingScan={}",
                pendingDirty.size(), pendingScan.size());
        pendingDirty.clear();
        pendingScan.clear();
    }

    @Override
    public void notifyLightSet(BlockPos pos) {

    }

    @Override
    public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {

    }

    @Override
    public void playSoundToAllNearExcept(@Nullable EntityPlayer player, SoundEvent soundIn, SoundCategory category, double x, double y, double z, float volume, float pitch) {

    }

    @Override
    public void playRecord(SoundEvent soundIn, BlockPos pos) {

    }

    @Override
    public void spawnParticle(int particleID, boolean ignoreRange, double xCoord, double yCoord, double zCoord, double xSpeed, double ySpeed, double zSpeed, int... parameters) {

    }

    @Override
    public void spawnParticle(int id, boolean ignoreRange, boolean p_190570_3_, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed, int... parameters) {

    }

    @Override
    public void onEntityAdded(Entity entityIn) {

    }

    @Override
    public void onEntityRemoved(Entity entityIn) {

    }

    @Override
    public void broadcastSound(int soundID, BlockPos pos, int data) {

    }

    @Override
    public void playEvent(EntityPlayer player, int type, BlockPos blockPosIn, int data) {

    }

    @Override
    public void sendBlockBreakProgress(int breakerId, BlockPos pos, int progress) {

    }

    void registerWorld(WorldServer world) {
        world.addEventListener(this);
        LOGGER.info("Server chunk listener registered: dimension={}", world.provider.getDimension());
    }

    void unregisterWorld(WorldServer world) {
        world.removeEventListener(this);
        LOGGER.info("Server chunk listener unregistered: dimension={}", world.provider.getDimension());
    }
}
