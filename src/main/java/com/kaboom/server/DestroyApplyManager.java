package com.kaboom.server;

import net.minecraft.util.math.BlockPos;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraft.init.Blocks;

import static com.kaboom.ModLogger.LOGGER;

public class DestroyApplyManager {
    private static final int MAX_DESTROY_REQUESTS = 16384;
    private static final int MAX_DESTROYS_PER_TICK = 256;
    private static final long MAX_APPLY_NANOS = 2_000_000L;

    private final BlockingQueue<DestroyRequest> destroyQueue =
            new ArrayBlockingQueue<>(MAX_DESTROY_REQUESTS);

    private static final class DestroyRequest {
        final int dimensionId;
        final BlockPos pos;


        DestroyRequest(int dimensionId, BlockPos pos) {
            this.dimensionId = dimensionId;
            this.pos = new BlockPos(
                    pos.getX(),
                    pos.getY(),
                    pos.getZ()
            );
        }
    }
    public boolean submitDestroy(int dimensionId, BlockPos pos) {
        Objects.requireNonNull(pos, "pos");

        return destroyQueue.offer(
                new DestroyRequest(dimensionId, pos)
        );
    }

    private void applyPendingChanges() {
        long start = System.nanoTime();

        processDestroyQueue(start);

        // Здесь позже появятся другие очереди.
    }

    private void processDestroyQueue(long start) {

        for (int i = 0; i < MAX_DESTROYS_PER_TICK; i++) {

            if (System.nanoTime() - start >= MAX_APPLY_NANOS) {
                break;
            }

            DestroyRequest request = destroyQueue.poll();

            if (request == null) {
                break;
            }

            try {
                applyDestroy(request);
            } catch (RuntimeException e) {
                LOGGER.error(
                        "Failed to apply destruction: dimension={}, pos={}",
                        request.dimensionId,
                        request.pos,
                        e
                );
            }
        }
    }

    private void applyDestroy(DestroyRequest request) {

        WorldServer world = DimensionManager.getWorld(
                request.dimensionId,
                false
        );

        if (world == null) {
            return;
        }

        BlockPos pos = request.pos;

        if (pos.getY() < 0 || pos.getY() >= 256) {
            return;
        }

        if (world.getChunkProvider().getLoadedChunk(
                pos.getX() >> 4,
                pos.getZ() >> 4
        ) == null) {
            return;
        }

        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 0);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {

        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        applyPendingChanges();
    }

}
