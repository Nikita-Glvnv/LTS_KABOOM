package com.kaboom.explosionwork;

import java.util.Arrays;

public class oktoderevo {
    public static final byte AIR = 0;
    private static final byte BRANCH = 1;
    public static final byte BLOCK = 2;
    public static final byte LIQUID = 3;

    private byte[] nodeType = new byte[32];
    private byte[] childMask = new byte[32];
    private int[] firstChild = new int[32];

    private float[] hardness = new float[32];
    private float[] resistance = new float[32];

    private int nodeCount = 1;

    public static final class BlockData {

        public final byte type;
        public final float hardness;
        public final float resistance;

        public BlockData(byte type, float hardness, float resistance) {
            if (type != AIR && type != BLOCK && type != LIQUID) {
                throw new IllegalArgumentException("Unknown block type");
            }

            this.type = type;
            this.hardness = type == BLOCK ? hardness : 0.0f;
            this.resistance = type == BLOCK ? resistance : 0.0f;
        }
    }

    private int reserveNodes(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Negative node count");
        }

        int first = nodeCount;
        int required = Math.addExact(nodeCount, count);

        if (required > nodeType.length) {
            int capacity = nodeType.length;

            while (capacity < required) {
                capacity = capacity <= Integer.MAX_VALUE / 2
                        ? capacity * 2
                        : required;
            }

            nodeType = Arrays.copyOf(nodeType, capacity);
            childMask = Arrays.copyOf(childMask, capacity);
            firstChild = Arrays.copyOf(firstChild, capacity);
            hardness = Arrays.copyOf(hardness, capacity);
            resistance = Arrays.copyOf(resistance, capacity);
        }

        nodeCount = required;
        return first;
    }

    private static boolean containsNonAir(
            BlockData[] blocks,
            int x0, int y0, int z0,
            int size
    ) {

        for (int y = y0; y < y0 + size; y++) {
            for (int z = z0; z < z0 + size; z++) {

                int index = (y << 8) | (z << 4) | x0;

                for (int x = 0; x < size; x++) {

                    if (blocks[index++].type != AIR) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private void buildNode(
            BlockData[] blocks,
            int node,
            int x0, int y0, int z0,
            int size
    ) {

        if (size == 1) {

            int index = (y0 << 8) | (z0 << 4) | x0;
            BlockData block = blocks[index];

            nodeType[node] = block.type;
            childMask[node] = 0;
            firstChild[node] = 0;

            hardness[node] = block.hardness;
            resistance[node] = block.resistance;

            return;
        }

        int half = size >>> 1;
        int mask = 0;

        // Определяем, какие из восьми октантов не пусты.
        for (int octant = 0; octant < 8; octant++) {

            int x = x0 + ((octant & 1) != 0 ? half : 0);
            int y = y0 + ((octant & 4) != 0 ? half : 0);
            int z = z0 + ((octant & 2) != 0 ? half : 0);

            if (containsNonAir(blocks, x, y, z, half)) {
                mask |= 1 << octant;
            }
        }

        // Весь куб воздушный.
        if (mask == 0) {
            nodeType[node] = AIR;
            childMask[node] = 0;
            firstChild[node] = 0;
            hardness[node] = 0;
            resistance[node] = 0;
            return;
        }

        // Куб неоднородный, создаём ветвь.
        nodeType[node] = BRANCH;
        childMask[node] = (byte) mask;
        hardness[node] = 0;
        resistance[node] = 0;

        int base = reserveNodes(Integer.bitCount(mask));
        firstChild[node] = base;

        int offset = 0;

        for (int octant = 0; octant < 8; octant++) {

            if ((mask & (1 << octant)) == 0) {
                continue;
            }

            int x = x0 + ((octant & 1) != 0 ? half : 0);
            int y = y0 + ((octant & 4) != 0 ? half : 0);
            int z = z0 + ((octant & 2) != 0 ? half : 0);

            buildNode(
                    blocks,
                    base + offset++,
                    x, y, z,
                    half
            );
        }
    }

    public void fill(BlockData[] blocks) {

        if (blocks == null || blocks.length != 4096) {
            throw new IllegalArgumentException(
                    "Section must contain exactly 4096 blocks"
            );
        }

        for (int i = 0; i < blocks.length; i++) {
            if (blocks[i] == null) {
                throw new IllegalArgumentException(
                        "Null block at index " + i
                );
            }
        }

        nodeCount = 1;

        buildNode(blocks, 0, 0, 0, 0, 16);
    }
}
