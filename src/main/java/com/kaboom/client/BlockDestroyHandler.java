package com.kaboom.client;

import com.kaboom.network.BlockDestroyMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;

import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class BlockDestroyHandler
        implements IMessageHandler<BlockDestroyMessage, IMessage> {
    @Override
    public IMessage onMessage(
            BlockDestroyMessage message,
            MessageContext ctx
    ) {
        Minecraft.getMinecraft().addScheduledTask(
                () -> applyDestructions(message)
        );

        return null;
    }

    private static void applyDestructions(
            BlockDestroyMessage message
    ) {
        Minecraft minecraft = Minecraft.getMinecraft();
        WorldClient world = minecraft.world;

        if (world == null) {
            return;
        }

        if (world.provider.getDimension()
                != message.getDimensionId()) {
            return;
        }

        int chunkX = message.getChunkX();
        int chunkZ = message.getChunkZ();

        if (world.getChunkProvider().getLoadedChunk(
                chunkX, chunkZ
        ) == null) {
            return;
        }

        short[] blocks = message.getBlocks();

        for (short packedPos : blocks) {
            int packed = packedPos & 0xFFFF;

            int x = (chunkX << 4) | (packed & 15);
            int z = (chunkZ << 4) | ((packed >>> 4) & 15);
            int y = (packed >>> 8) & 255;

            BlockPos pos = new BlockPos(x, y, z);

            world.setBlockState(
                    pos,
                    Blocks.AIR.getDefaultState(),
                    2
            );
        }
    }
}
