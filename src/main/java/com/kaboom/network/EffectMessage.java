package com.kaboom.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.Arrays;

public class EffectMessage implements IMessage {

    public static final int MAX_EFFECTS = 1024;

    public static final byte EXPLOSION_CENTER = 0;
    public static final byte AIR = 1;

    private int dimensionId;
    private int chunkX;
    private int chunkZ;

    private short[] positions;
    private byte[] types;
    private double[] energies;

    public EffectMessage() {
    }

    public EffectMessage(
            int dimensionId,
            int chunkX,
            int chunkZ,
            short[] positions,
            byte[] types,
            double[] energies,
            int offset,
            int count
    ) {
        if (positions == null || types == null || energies == null
                || count <= 0 || count > MAX_EFFECTS
                || offset < 0
                || offset > positions.length - count
                || offset > types.length - count
                || offset > energies.length - count) {
            throw new IllegalArgumentException("Invalid effect batch");
        }

        this.dimensionId = dimensionId;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;

        this.positions = Arrays.copyOfRange(
                positions, offset, offset + count
        );
        this.types = Arrays.copyOfRange(
                types, offset, offset + count
        );
        this.energies = Arrays.copyOfRange(
                energies, offset, offset + count
        );

        for (int i = 0; i < count; i++) {
            validateEffect(this.types[i], this.energies[i]);
        }
    }
    private static void validateEffect(byte type, double energy) {
        if ((type != EXPLOSION_CENTER && type != AIR)
                || !Double.isFinite(energy)
                || energy < 0.0) {
            throw new IllegalArgumentException("Invalid effect");
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dimensionId);
        buf.writeInt(chunkX);
        buf.writeInt(chunkZ);
        buf.writeShort(positions.length);

        for (int i = 0; i < positions.length; i++) {
            buf.writeShort(positions[i]);
            buf.writeByte(types[i]);
            buf.writeDouble(energies[i]);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dimensionId = buf.readInt();
        chunkX = buf.readInt();
        chunkZ = buf.readInt();

        int count = buf.readUnsignedShort();

        if (count == 0 || count > MAX_EFFECTS
                || buf.readableBytes() < count * 11) {
            throw new IllegalArgumentException(
                    "Invalid effect packet"
            );
        }

        positions = new short[count];
        types = new byte[count];
        energies = new double[count];

        for (int i = 0; i < count; i++) {
            positions[i] = buf.readShort();
            types[i] = buf.readByte();
            energies[i] = buf.readDouble();

            validateEffect(types[i], energies[i]);
        }
    }

    public static class Handler
            implements IMessageHandler<EffectMessage, IMessage> {

        @Override
        public IMessage onMessage(
                EffectMessage message,
                MessageContext ctx
        ) {
            // Позже здесь будет передача данных
            // клиентскому обработчику эффектов.
            return null;
        }
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public int getDimensionId() {
        return dimensionId;
    }

    public int getCount() {
        return positions.length;
    }

    public byte getType(int index) {
        return types[index];
    }

    public double getEnergy(int index) {
        return energies[index];
    }

    public int getX(int index) {
        return (chunkX << 4) | (positions[index] & 15);
    }

    public int getY(int index) {
        return (positions[index] & 0xFFFF) >>> 8;
    }

    public int getZ(int index) {
        return (chunkZ << 4)
                | ((positions[index] >>> 4) & 15);
    }
}
