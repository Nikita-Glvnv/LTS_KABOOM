package com.kaboom.explosionwork;

import java.util.ArrayList;

public class AdaptiveCube {
    private static final double MIN_ACTIVE_ENERGY = 1e-5;

    private static final double AIR_ATTENUATION = 0.15;
    private static final double LOBE_POWER = 2.2;

    // Приближённый коэффициент пространственного рассеивания.
    private static final double SPREAD_FACTOR = 19.0;

    // Число точек интегрирования затухания вдоль луча.
    private static final int ATTENUATION_SAMPLES = 8;

    // Угол для равномерного распределения направлений по сфере.
    private static final double GOLDEN_ANGLE =
            Math.PI * (3.0 - Math.sqrt(5.0));

    // Координаты минимального угла куба в мире.
    private int x;
    private int y;
    private int z;

    // Длина ребра куба в блоках.
    private int size;

    // Энергия, находящаяся в процессе прохождения куба.
    private final ArrayList<EnergyPacket> packets = new ArrayList<>();

    private static final class EnergyPacket {
        boolean prepared;

        final ArrayList<SectorExit> exits = new ArrayList<>();

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

    private static long pack(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38)
                | (((long) y & 0xFFFL) << 26)
                | ((long) z & 0x3FFFFFFL);
    }

    private int sampleCount() {
        if (size <= 2) return 32;
        if (size <= 4) return 64;
        if (size <= 8) return 128;

        return 256;
    }

    private static double directionY(int i, int count) {
        return 1.0 - 2.0 * (i + 0.5) / count;
    }

    private static double directionX(int i, int count) {
        double y = directionY(i, count);

        double r = Math.sqrt(Math.max(0.0, 1.0 - y * y));

        return r * Math.cos(i * GOLDEN_ANGLE);
    }

    private static double directionZ(int i, int count) {
        double y = directionY(i, count);

        double r = Math.sqrt(Math.max(0.0, 1.0 - y * y));

        return r * Math.sin(i * GOLDEN_ANGLE);
    }

    private double intersectionDistance(
            EnergyPacket packet,
            double dx,
            double dy,
            double dz
    ) {
        double distance = Double.POSITIVE_INFINITY;

        if (dx > 1e-12) {
            distance = Math.min(
                    distance,
                    (x + size - packet.centerX) / dx
            );
        } else if (dx < -1e-12) {
            distance = Math.min(
                    distance,
                    (x - packet.centerX) / dx
            );
        }

        if (dy > 1e-12) {
            distance = Math.min(
                    distance,
                    (y + size - packet.centerY) / dy
            );
        } else if (dy < -1e-12) {
            distance = Math.min(
                    distance,
                    (y - packet.centerY) / dy
            );
        }

        if (dz > 1e-12) {
            distance = Math.min(
                    distance,
                    (z + size - packet.centerZ) / dz
            );
        } else if (dz < -1e-12) {
            distance = Math.min(
                    distance,
                    (z - packet.centerZ) / dz
            );
        }

        return distance;
    }

    private static double attenuationEnergy(double energy) {
        double e2 = energy * energy;

        return energy
                + 100.0 * e2
                / ((energy + 10.0) * (e2 + 0.01));
    }

    private static double sectorTransmittance(
            double energy,
            double length
    ) {
        double step = length / ATTENUATION_SAMPLES;

        double integral = 0.0;

        for (int i = 0; i < ATTENUATION_SAMPLES; i++) {

            double distance = (i + 0.5) * step;

            // Приближённое уменьшение локальной
            // концентрации энергии из-за рассеивания.
            double localEnergy = energy
                    / (1.0 + SPREAD_FACTOR
                    * distance * distance);

            double attenuation = attenuationEnergy(localEnergy);

            integral += step / Math.max(attenuation, 1e-12);
        }

        return Math.exp(-AIR_ATTENUATION * integral);
    }

    private void prepare(EnergyPacket packet) {

        int count = sampleCount();

        double[] weights = new double[count];

        double px = packet.momentX;
        double py = packet.momentY;
        double pz = packet.momentZ;

        double momentLength = Math.sqrt(
                px * px + py * py + pz * pz
        );

        double k = Math.min(
                1.0,
                momentLength / packet.energy
        );

        double vx = momentLength > 1e-15
                ? px / momentLength : 0.0;

        double vy = momentLength > 1e-15
                ? py / momentLength : 0.0;

        double vz = momentLength > 1e-15
                ? pz / momentLength : 0.0;

        double totalWeight = 0.0;

        // Распределяем энергию по угловым направлениям.
        for (int i = 0; i < count; i++) {

            double dx = directionX(i, count);
            double dy = directionY(i, count);
            double dz = directionZ(i, count);

            double dot = dx * vx + dy * vy + dz * vz;

            double diffuse = (1.0 - k) / 26.0;

            double directed = dot > 0.0
                    ? k * Math.pow(dot, LOBE_POWER)
                    : 0.0;

            weights[i] = diffuse + directed;

            totalWeight += weights[i];
        }

        if (totalWeight <= 0.0) {
            return;
        }

        // Для каждого направления вычисляем выход из куба.
        for (int i = 0; i < count; i++) {

            double energy = packet.energy
                    * weights[i] / totalWeight;

            if (energy < MIN_ACTIVE_ENERGY) {
                continue;
            }

            double dx = directionX(i, count);
            double dy = directionY(i, count);
            double dz = directionZ(i, count);

            double distance = intersectionDistance(
                    packet, dx, dy, dz
            );

            if (!Double.isFinite(distance) || distance < 0.0) {
                continue;
            }

            // Затухание при движении до границы.
            energy *= sectorTransmittance(
                    packet.energy,
                    distance
            );

            // Энергия далее делится между тремя
            // временными составляющими.
            if (energy * 0.25 < MIN_ACTIVE_ENERGY) {
                continue;
            }

            // Точки непосредственно перед и после границы.
            double before = distance - 1e-6;
            double after = distance + 1e-6;

            int sx = (int) Math.floor(
                    packet.centerX + dx * before
            );
            int sy = (int) Math.floor(
                    packet.centerY + dy * before
            );
            int sz = (int) Math.floor(
                    packet.centerZ + dz * before
            );

            int tx = (int) Math.floor(
                    packet.centerX + dx * after
            );
            int ty = (int) Math.floor(
                    packet.centerY + dy * after
            );
            int tz = (int) Math.floor(
                    packet.centerZ + dz * after
            );

            // Источник должен оставаться внутри куба.
            sx = Math.max(x, Math.min(x + size - 1, sx));
            sy = Math.max(y, Math.min(y + size - 1, sy));
            sz = Math.max(z, Math.min(z + size - 1, sz));

            // Не создаём передачу, если координата цели
            // из-за численной погрешности осталась внутри.
            if (tx >= x && tx < x + size
                    && ty >= y && ty < y + size
                    && tz >= z && tz < z + size) {
                continue;
            }

            // Границы игрового мира.
            if (ty < 0 || ty >= 256
                    || tx < -30000000 || tx > 30000000
                    || tz < -30000000 || tz > 30000000) {
                continue;
            }

            // Выходная передача должна оставаться локальной
            // относительно последней ячейки куба.
            if (Math.abs(sx - tx) > 1
                    || Math.abs(sy - ty) > 1
                    || Math.abs(sz - tz) > 1) {
                throw new IllegalStateException(
                        "Non-local sector exit"
                );
            }

            // Приближённое время прохождения.
            double arrival = packet.time
                    + 0.5
                    + 1.35 * distance;

            // Разброс времени накапливается при движении.
            double spread = Math.sqrt(
                    2.0 * Math.max(
                            0.0,
                            packet.timeVariance + 0.06 * distance
                    )
            );

            packet.exits.add(new SectorExit(
                    pack(sx, sy, sz),
                    pack(tx, ty, tz),
                    energy,
                    dx, dy, dz,
                    arrival,
                    spread
            ));
        }
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

        if (currentTick < 0 || output == null) {
            throw new IllegalArgumentException(
                    "Invalid process arguments"
            );
        }

        for (int i = packets.size() - 1; i >= 0; i--) {

            EnergyPacket packet = packets.get(i);

            // Рассчитываем секторные выходы один раз.
            if (!packet.prepared) {
                prepare(packet);
                packet.prepared = true;
            }

            for (int j = packet.exits.size() - 1; j >= 0; j--) {

                SectorExit exit = packet.exits.get(j);

                // Один секторный выход разделён
                // на три временные составляющие.
                while (exit.nextPart < 3) {

                    int part = exit.nextPart;

                    double arrival = Math.max(
                            packet.time,
                            exit.arrival
                                    + (part - 1) * exit.spread
                    );

                    // Передача ещё не должна выйти из куба.
                    if (Math.floor(arrival) > currentTick) {
                        break;
                    }

                    double energy = exit.energy
                            * (part == 1 ? 0.5 : 0.25);

                    if (energy >= MIN_ACTIVE_ENERGY) {

                        // При запоздалой обработке
                        // не отправляем энергию в прошлый тик.
                        double time = Math.max(
                                arrival,
                                (double) currentTick
                        );

                        output.emit(
                                exit.source,
                                exit.target,
                                energy,
                                energy * exit.dx,
                                energy * exit.dy,
                                energy * exit.dz,
                                time
                        );
                    }

                    exit.nextPart++;
                }

                // Все временные части вышли из куба.
                if (exit.nextPart == 3) {

                    int last = packet.exits.size() - 1;

                    packet.exits.set(
                            j,
                            packet.exits.get(last)
                    );

                    packet.exits.remove(last);
                }
            }

            // Пакет полностью покинул куб.
            if (packet.exits.isEmpty()) {
                packets.remove(i);
            }
        }
    }

    private static final class SectorExit {

        long source;
        long target;

        double energy;

        double dx;
        double dy;
        double dz;

        double arrival;
        double spread;

        int nextPart;

        SectorExit(
                long source,
                long target,
                double energy,
                double dx,
                double dy,
                double dz,
                double arrival,
                double spread
        ) {
            this.source = source;
            this.target = target;

            this.energy = energy;

            this.dx = dx;
            this.dy = dy;
            this.dz = dz;

            this.arrival = arrival;
            this.spread = spread;
        }
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
