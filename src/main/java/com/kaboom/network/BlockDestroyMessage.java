package com.kaboom.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import java.util.Arrays;

public class BlockDestroyMessage implements IMessage {
    public static final int MAX_BLOCKS = 1024;

    private int dimensionId;
    private int chunkX;
    private int chunkZ;
    private short[] blocks;

    public BlockDestroyMessage() {
    }

    public BlockDestroyMessage(
            int dimensionId,
            int chunkX,
            int chunkZ,
            short[] source,
            int offset,
            int count
    ) {
        if (count <= 0 || count > MAX_BLOCKS) {
            throw new IllegalArgumentException("Invalid block count");
        }

        this.dimensionId = dimensionId;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.blocks = Arrays.copyOfRange(
                source, offset, offset + count
        );
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dimensionId);
        buf.writeInt(chunkX);
        buf.writeInt(chunkZ);
        buf.writeShort(blocks.length);

        for (short pos : blocks) {
            buf.writeShort(pos);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dimensionId = buf.readInt();
        chunkX = buf.readInt();
        chunkZ = buf.readInt();

        int count = buf.readUnsignedShort();

        if (count == 0 || count > MAX_BLOCKS
                || buf.readableBytes() < count * 2) {
            throw new IllegalArgumentException(
                    "Invalid destruction packet"
            );
        }

        blocks = new short[count];

        for (int i = 0; i < count; i++) {
            blocks[i] = buf.readShort();
        }
    }

    public static class Handler
            implements IMessageHandler<BlockDestroyMessage, IMessage> {

        @Override
        public IMessage onMessage(
                BlockDestroyMessage message,
                MessageContext ctx
        ) {
            return null;
        }
    }

    public int getDimensionId() {
        return dimensionId;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public short[] getBlocks() {
        return blocks;
    }
}
