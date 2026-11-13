package com.kaboom.network;

import com.kaboom.Kaboom;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class BlockDestroyPackage {
    private static final SimpleNetworkWrapper CHANNEL =
            NetworkRegistry.INSTANCE.newSimpleChannel(Kaboom.MODID);

    private final Map<Integer, Map<Long, BlockBatch>> pending =
            new HashMap<>();

    private static final class BlockBatch {
        final int chunkX;
        final int chunkZ;

        short[] blocks = new short[32];
        int count;

        BlockBatch(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        void add(short position) {
            if (count == blocks.length) {
                blocks = Arrays.copyOf(blocks, blocks.length * 2);
            }

            blocks[count++] = position;
        }
    }

    public void recordDestroy(int dimensionId, BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;

        Map<Long, BlockBatch> chunks = pending.computeIfAbsent(
                dimensionId,
                id -> new HashMap<>()
        );

        BlockBatch batch = chunks.computeIfAbsent(
                ChunkPos.asLong(chunkX, chunkZ),
                key -> new BlockBatch(chunkX, chunkZ)
        );

        int packed =
                ((pos.getY() & 255) << 8)
                        | ((pos.getZ() & 15) << 4)
                        | (pos.getX() & 15);

        batch.add((short) packed);
    }

    public static void registerNetwork() {
        CHANNEL.registerMessage(
                BlockDestroyMessage.Handler.class,
                BlockDestroyMessage.class,
                0,
                Side.CLIENT
        );
    }

    public void flush() {
        try {
            for (Map.Entry<Integer, Map<Long, BlockBatch>> dimension
                    : pending.entrySet()) {

                int dimensionId = dimension.getKey();

                for (BlockBatch batch : dimension.getValue().values()) {

                    NetworkRegistry.TargetPoint target =
                            new NetworkRegistry.TargetPoint(
                                    dimensionId,
                                    batch.chunkX * 16.0 + 8.0,
                                    64.0,
                                    batch.chunkZ * 16.0 + 8.0,
                                    0.0
                            );

                    for (int offset = 0; offset < batch.count;
                         offset += BlockDestroyMessage.MAX_BLOCKS) {

                        int count = Math.min(
                                BlockDestroyMessage.MAX_BLOCKS,
                                batch.count - offset
                        );

                        CHANNEL.sendToAllTracking(
                                new BlockDestroyMessage(
                                        dimensionId,
                                        batch.chunkX,
                                        batch.chunkZ,
                                        batch.blocks,
                                        offset,
                                        count
                                ),
                                target
                        );
                    }
                }
            }
        } finally {
            pending.clear();
        }
    }

}
