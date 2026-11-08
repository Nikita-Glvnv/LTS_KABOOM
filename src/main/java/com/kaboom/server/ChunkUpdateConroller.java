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

public class ChunkUpdateConroller implements IWorldEventListener{
    static final class ChunkState {
        boolean queued = false;
        final BitSet[] dirtySections = new BitSet[16];
        final long[] sectionRevisions = new long[16];
        int nextScanSection = 0;
    }

    private final chunksnapshot owner;

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

        int index =
                ((y & 15) << 8)
                        | ((z & 15) << 4)
                        | (x & 15);

        BitSet dirty = state.dirtySections[sectionY];

        if (dirty == null) {
            dirty = new BitSet(4096);
            state.dirtySections[sectionY] = dirty;
        }

        dirty.set(index);
        if (!state.queued) {
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

            ChunkState state = owner.getUpdateState(
                    request.dimensionId,
                    request.chunkX,
                    request.chunkZ
            );

            // Запрос от уже удалённого или заменённого чанка.
            if (state != request.state) {
                continue;
            }

            // Дожидаемся завершения первичной отправки.
            if (!owner.isChunkInitialized(
                    request.dimensionId,
                    request.chunkX,
                    request.chunkZ
            )) {
                pendingDirty.addLast(request);
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

                chunksnapshot.SectionChanges changes =
                        createChanges(world, chunk, request, sectionY, dirty);

                if (!owner.offerUpdate(changes)) {
                    queueFull = true;
                    break;
                }

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
    }

    void stop() {
        pendingDirty.clear();
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
    }

    void unregisterWorld(WorldServer world) {
        world.removeEventListener(this);
    }
}
