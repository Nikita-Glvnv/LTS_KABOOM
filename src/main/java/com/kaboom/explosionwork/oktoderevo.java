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
            byte[] types,
            int x0, int y0, int z0,
            int size
    ) {
        for (int y = y0; y < y0 + size; y++) {
            for (int z = z0; z < z0 + size; z++) {

                int index = (y << 8) | (z << 4) | x0;

                for (int x = 0; x < size; x++) {
                    if (types[index++] != AIR) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private void buildNode(
            byte[] types,
            float[] hardnessInput,
            float[] resistanceInput,
            int node,
            int x0, int y0, int z0,
            int size
    ) {

        if (size == 1) {

            int index = (y0 << 8) | (z0 << 4) | x0;
            byte type = types[index];

            nodeType[node] = type;
            childMask[node] = 0;
            firstChild[node] = 0;

            if (type == BLOCK) {
                hardness[node] = hardnessInput[index];
                resistance[node] = resistanceInput[index];
            } else {
                hardness[node] = 0.0f;
                resistance[node] = 0.0f;
            }

            return;
        }

        int half = size >>> 1;
        int mask = 0;

        // Определяем, какие из восьми октантов не пусты.
        for (int octant = 0; octant < 8; octant++) {

            int x = x0 + ((octant & 1) != 0 ? half : 0);
            int y = y0 + ((octant & 4) != 0 ? half : 0);
            int z = z0 + ((octant & 2) != 0 ? half : 0);

            if (containsNonAir(types, x, y, z, half)) {
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
                    types,
                    hardnessInput,
                    resistanceInput,
                    base + offset++,
                    x, y, z,
                    half
            );
        }
    }

    public void fill(
            byte[] types,
            float[] hardnessInput,
            float[] resistanceInput
    ) {
        if (types == null || types.length != 4096
                || hardnessInput == null || hardnessInput.length != 4096
                || resistanceInput == null || resistanceInput.length != 4096) {

            throw new IllegalArgumentException(
                    "All input arrays must contain exactly 4096 elements"
            );
        }

        for (int i = 0; i < 4096; i++) {
            byte type = types[i];

            if (type != AIR && type != BLOCK && type != LIQUID) {
                throw new IllegalArgumentException(
                        "Unknown block type at index " + i
                );
            }
        }

        nodeCount = 1;

        buildNode(
                types,
                hardnessInput,
                resistanceInput,
                0,
                0, 0, 0,
                16
        );
    }

    public static final class BlockResult {
        public byte type;
        public float hardness;
        public float resistance;
    }

    public void getBlock(int x, int y, int z, BlockResult result) {

        if (((x | y | z) & ~15) != 0) {
            throw new IllegalArgumentException(
                    "Coordinates must be between 0 and 15"
            );
        }

        if (result == null) {
            throw new IllegalArgumentException("Result cannot be null");
        }

        int node = 0;

        for (int shift = 3; shift >= 0; shift--) {

            byte type = nodeType[node];

            if (type == AIR) {
                result.type = AIR;
                result.hardness = 0;
                result.resistance = 0;
                return;
            }

            if (type != BRANCH) {
                break;
            }

            int octant =
                    ((x >> shift) & 1)
                            | (((z >> shift) & 1) << 1)
                            | (((y >> shift) & 1) << 2);

            int mask = childMask[node] & 0xFF;
            int bit = 1 << octant;

            if ((mask & bit) == 0) {
                result.type = AIR;
                result.hardness = 0;
                result.resistance = 0;
                return;
            }

            node = firstChild[node]
                    + Integer.bitCount(mask & (bit - 1));
        }

        result.type = nodeType[node];

        if (result.type == BLOCK) {
            result.hardness = hardness[node];
            result.resistance = resistance[node];
        } else {
            result.hardness = 0;
            result.resistance = 0;
        }
    }
}
