package com.kaboom.network;

import com.kaboom.Kaboom;
import com.kaboom.client.BlockDestroyHandler;
import com.kaboom.client.Effecthandler;
import com.kaboom.sound.ExplosionAcoustics;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraftforge.fml.common.FMLCommonHandler;
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

    private final Map<Integer, Map<Long, EffectBatch>> pendingEffects =
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
        if (FMLCommonHandler.instance().getSide().isClient()) {
            CHANNEL.registerMessage(
                    BlockDestroyHandler.class,
                    BlockDestroyMessage.class,
                    0,
                    Side.CLIENT
            );
        } else {
            CHANNEL.registerMessage(
                    BlockDestroyMessage.Handler.class,
                    BlockDestroyMessage.class,
                    0,
                    Side.CLIENT
            );
        }
        if (FMLCommonHandler.instance().getSide().isClient()) {
            CHANNEL.registerMessage(
                    Effecthandler.class,
                    EffectMessage.class,
                    1,
                    Side.CLIENT
            );
        } else {
            CHANNEL.registerMessage(
                    EffectMessage.Handler.class,
                    EffectMessage.class,
                    1,
                    Side.CLIENT
            );
        }
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
            for (Map.Entry<Integer, Map<Long, EffectBatch>> dimension
                    : pendingEffects.entrySet()) {

                int dimensionId = dimension.getKey();

                for (EffectBatch batch : dimension.getValue().values()) {

                    NetworkRegistry.TargetPoint target =
                            new NetworkRegistry.TargetPoint(
                                    dimensionId,
                                    batch.chunkX * 16.0 + 8.0,
                                    64.0,
                                    batch.chunkZ * 16.0 + 8.0,
                                    0.0
                            );

                    for (int offset = 0; offset < batch.count;
                         offset += EffectMessage.MAX_EFFECTS) {

                        int count = Math.min(
                                EffectMessage.MAX_EFFECTS,
                                batch.count - offset
                        );

                        CHANNEL.sendToAllTracking(
                                new EffectMessage(
                                        dimensionId,
                                        batch.chunkX,
                                        batch.chunkZ,
                                        batch.positions,
                                        batch.types,
                                        batch.energies,
                                        offset,
                                        count
                                ),
                                target
                        );
                    }
                }
            }
        }finally {
                pending.clear();
                pendingEffects.clear();
        }
    }

    public static void sendEffects(EffectMessage message) {
        NetworkRegistry.TargetPoint target =
                new NetworkRegistry.TargetPoint(
                        message.getDimensionId(),
                        message.getChunkX() * 16.0 + 8.0,
                        64.0,
                        message.getChunkZ() * 16.0 + 8.0,
                        0.0
                );

        CHANNEL.sendToAllTracking(message, target);
    }

    private static final class EffectBatch {
        final int chunkX;
        final int chunkZ;

        short[] positions = new short[32];
        byte[] types = new byte[32];
        double[] energies = new double[32];

        int count;

        EffectBatch(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        void add(int x, int y, int z, byte type, double energy) {
            if (count == positions.length) {
                int capacity = positions.length * 2;

                positions = Arrays.copyOf(positions, capacity);
                types = Arrays.copyOf(types, capacity);
                energies = Arrays.copyOf(energies, capacity);
            }

            positions[count] = (short) (
                    (y << 8)
                            | ((z & 15) << 4)
                            | (x & 15)
            );

            types[count] = type;
            energies[count] = energy;
            count++;
        }
    }

    public void recordEffect(
            int dimensionId,
            int x, int y, int z,
            byte type,
            double energy
    ) {
        if (y < 0 || y >= 256
                || !Double.isFinite(energy)
                || energy < 0.0) {
            return;
        }

        if (type != EffectMessage.EXPLOSION_CENTER
                && type != EffectMessage.AIR) {
            throw new IllegalArgumentException(
                    "Invalid effect type"
            );
        }
        if (type == EffectMessage.EXPLOSION_CENTER) {
            int chunkX = x >> 4;
            int chunkZ = z >> 4;

            short packed = (short) (
                    ((y & 255) << 8)
                            | ((z & 15) << 4)
                            | (x & 15)
            );

            EffectMessage message = new EffectMessage(
                    dimensionId,
                    chunkX,
                    chunkZ,
                    new short[]{packed},
                    new byte[]{EffectMessage.EXPLOSION_CENTER},
                    new double[]{energy},
                    0,
                    1
            );

            CHANNEL.sendToAllAround(
                    message,
                    new NetworkRegistry.TargetPoint(
                            dimensionId,
                            x + 0.5,
                            y + 0.5,
                            z + 0.5,
                            ExplosionAcoustics.radius(energy)
                    )
            );

            return;
        }

        int chunkX = x >> 4;
        int chunkZ = z >> 4;

        Map<Long, EffectBatch> chunks =
                pendingEffects.computeIfAbsent(
                        dimensionId,
                        id -> new HashMap<>()
                );

        EffectBatch batch = chunks.computeIfAbsent(
                ChunkPos.asLong(chunkX, chunkZ),
                key -> new EffectBatch(chunkX, chunkZ)
        );

        batch.add(x, y, z, type, energy);
    }

}
