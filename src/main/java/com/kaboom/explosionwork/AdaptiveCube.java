package com.kaboom.explosionwork;

import java.util.ArrayList;

public class AdaptiveCube {
    // Координаты минимального угла куба в мире.
    private int x;
    private int y;
    private int z;

    // Длина ребра куба в блоках.
    private int size;

    // Энергия, находящаяся в процессе прохождения куба.
    private final ArrayList<EnergyPacket> packets = new ArrayList<>();

    private static final class EnergyPacket {

        // Энергия пакета.
        double energy;

        // Суммарный импульс.
        double momentX;
        double momentY;
        double momentZ;

        // Текущее среднее время пакета.
        double time;

        // Разброс энергии по времени (дисперсия).
        double timeVariance;

        // Пространственный центр энергии.
        double centerX;
        double centerY;
        double centerZ;

        // Пространственный разброс энергии (дисперсии).
        double varianceX;
        double varianceY;
        double varianceZ;
    }

    public AdaptiveCube(int x, int y, int z, int size) {
        if (size < 2 || size > 16 || (size & (size - 1)) != 0) {
            throw new IllegalArgumentException("Invalid cube size");
        }

        if (((x | y | z) & (size - 1)) != 0) {
            throw new IllegalArgumentException("Cube is not aligned");
        }

        if (y < 0 || y + size > 256) {
            throw new IllegalArgumentException("Invalid cube height");
        }

        this.x = x;
        this.y = y;
        this.z = z;
        this.size = size;
    }

    public AdaptiveCube(oktoderevo.AirRegion region) {
        this(region.x, region.y, region.z, region.size);
    }

    public void receive(
            int entryX, int entryY, int entryZ,
            double energy,
            double mx, double my, double mz,
            double arrival
    ) {
        if (entryX < x || entryX >= x + size
                || entryY < y || entryY >= y + size
                || entryZ < z || entryZ >= z + size) {
            throw new IllegalArgumentException("Entry outside cube");
        }

        if (!Double.isFinite(energy) || energy < 0.0
                || !Double.isFinite(mx)
                || !Double.isFinite(my)
                || !Double.isFinite(mz)
                || !Double.isFinite(arrival)
                || arrival < 0.0) {
            throw new IllegalArgumentException("Invalid energy transfer");
        }

        if (energy == 0.0) {
            return;
        }

        EnergyPacket packet = new EnergyPacket();

        packet.energy = energy;

        packet.momentX = mx;
        packet.momentY = my;
        packet.momentZ = mz;

        packet.time = arrival;
        packet.timeVariance = 0.0;

        packet.centerX = entryX + 0.5;
        packet.centerY = entryY + 0.5;
        packet.centerZ = entryZ + 0.5;

        packet.varianceX = 0.0;
        packet.varianceY = 0.0;
        packet.varianceZ = 0.0;

        packets.add(packet);
    }

    public boolean hasPending() {
        return !packets.isEmpty();
    }

    public int getPacketCount() {
        return packets.size();
    }

    @FunctionalInterface
    public interface EnergyOutput {

        void emit(
                long source,
                long target,
                double energy,
                double mx, double my, double mz,
                double arrival
        );
    }

    public void process(long currentTick, EnergyOutput output) {

        // TODO: Рассчитать перемещение, смешивание,
        // затухание и временное распределение энергии.
        //
        // Результирующие передачи отправлять через output.emit(...).

        throw new UnsupportedOperationException(
                "Adaptive cube propagation is not implemented yet"
        );
    }




    public void clear() {
        packets.clear();
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public int getSize() {
        return size;
    }

    private static void merge(EnergyPacket a, EnergyPacket b) {
        double total = a.energy + b.energy;

        if (total <= 0.0) {
            return;
        }

        double wa = a.energy / total;
        double wb = b.energy / total;

        double time = wa * a.time + wb * b.time;
        double cx = wa * a.centerX + wb * b.centerX;
        double cy = wa * a.centerY + wb * b.centerY;
        double cz = wa * a.centerZ + wb * b.centerZ;

        a.timeVariance =
                wa * (a.timeVariance + sq(a.time - time))
                        + wb * (b.timeVariance + sq(b.time - time));

        a.varianceX =
                wa * (a.varianceX + sq(a.centerX - cx))
                        + wb * (b.varianceX + sq(b.centerX - cx));

        a.varianceY =
                wa * (a.varianceY + sq(a.centerY - cy))
                        + wb * (b.varianceY + sq(b.centerY - cy));

        a.varianceZ =
                wa * (a.varianceZ + sq(a.centerZ - cz))
                        + wb * (b.varianceZ + sq(b.centerZ - cz));

        a.energy = total;

        a.momentX += b.momentX;
        a.momentY += b.momentY;
        a.momentZ += b.momentZ;

        a.time = time;
        a.centerX = cx;
        a.centerY = cy;
        a.centerZ = cz;
    }

    private static double sq(double value) {
        return value * value;
    }
}
