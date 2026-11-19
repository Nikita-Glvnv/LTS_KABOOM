package com.kaboom.explosionwork;

import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicReferenceArray;
import com.kaboom.server.EntitySnapshot;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

public class AdaptiveCube {
    private double airLoss;
    private double escapedEnergy;
    private double culledEnergy;

    private static final double MIN_ACTIVE_ENERGY = 1e-5;
    private static final double AIR_ATTENUATION = 0.15;
    private static final double LOBE_POWER = 2.2;
    private static final double SPREAD_FACTOR = 19.0;
    private static final int ATTENUATION_SAMPLES = 8;
    // Fixed midpoint-quadrature positions: SPREAD_FACTOR * ((i + 0.5) / N)^2.
    // The same values were previously recalculated for every ray.
    private static final double[] ATTENUATION_FACTORS = createAttenuationFactors();
    private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));
    private static final double[][][] DIRECTIONS = createDirections();
    private static final GeometryTable[] GEOMETRY_TABLES = createGeometryTables();
    private int[] damageBoxIndexes = new int[0];
    private int[] damageBoxCounts = new int[0];

    // Не обращается к WorldServer: получает данные только через receive().
    private final int x, y, z, size;
    private final int count;
    private final double[][] dirs;
    private final double[] weights;
    private final GeometryTable geometryTable;
    // Local coordinates lose some fraction bits when translated near world borders.
    // Preserve the original world-coordinate geometry there.
    private final boolean useSharedGeometry;
    // One (source,target) pair per face slot, translated to this cube's world coordinates.
    private final long[] faceSources, faceTargets;
    private final boolean[] faceEscaped;
    private final EnergyPacket scratch = new EnergyPacket();

    private static final int INPUT_STRIDE = 5;
    private short[] inputOffset = new short[64];
    private double[] inputData = new double[64 * INPUT_STRIDE];
    private int incomingCount;
    private int activePackets;

    // Агрегированные выходы: один набор передач на физический тик.
    // Ключ внутри тика — индекс конкретной пары (source, target) на грани куба.
    private final Long2ObjectOpenHashMap<TickBucket> scheduled = new Long2ObjectOpenHashMap<>();
    private final ArrayDeque<TickBucket> reusableBuckets = new ArrayDeque<>();
    private static final int MAX_REUSED_BUCKETS = 16;
    private final int faceSlots;
    private long previousTick = Long.MIN_VALUE;
    private int pendingExitObjects;
    private double preparedOutgoing;
    private final Diagnostics diagnostics = new Diagnostics();

    // Временные корзины и моменты прибытия общие для всех выходов одного луча.
    // Буферы переиспользуются; новых объектов на луч или выход не создаём.
    private final long[] rayTicks = new long[3];
    private final double[] rayArrivals = new double[3];
    private final TickBucket[] rayBuckets = new TickBucket[3];
    private boolean rayScheduleReady;

    private static final class TickBucket {
        // 0 — слот не занят; индекс записи хранится с прибавленной единицей.
        final short[] byFaceSlot;
        short[] activeSlots = new short[32];
        long[] sources = new long[32], targets = new long[32];
        double[] energy = new double[32], momentX = new double[32];
        double[] momentY = new double[32], momentZ = new double[32];
        double[] arrivalSum = new double[32];
        int count;
        long emittedParts, culledParts;
        double emittedEnergy, culledEnergy;
        int completedPackets;

        TickBucket(int slots) {
            byFaceSlot = new short[slots];
        }

        // true — новая уникальная пара в текущем тике.
        boolean add(int slot, long source, long target, double e,
                    double mx, double my, double mz, double arrival) {
            int index = (byFaceSlot[slot] & 0xffff) - 1;
            boolean created = index < 0;
            if (created) {
                index = count++;
                if (index == activeSlots.length) {
                    int n = expanded(activeSlots.length);
                    activeSlots = Arrays.copyOf(activeSlots, n);
                    sources = Arrays.copyOf(sources, n);
                    targets = Arrays.copyOf(targets, n);
                    energy = Arrays.copyOf(energy, n);
                    momentX = Arrays.copyOf(momentX, n);
                    momentY = Arrays.copyOf(momentY, n);
                    momentZ = Arrays.copyOf(momentZ, n);
                    arrivalSum = Arrays.copyOf(arrivalSum, n);
                }
                byFaceSlot[slot] = (short) (index + 1);
                activeSlots[index] = (short) slot;
                sources[index] = source;
                targets[index] = target;
                energy[index] = momentX[index] = momentY[index] = momentZ[index] = 0.0;
                arrivalSum[index] = 0.0;
            }
            energy[index] += e;
            momentX[index] += mx;
            momentY[index] += my;
            momentZ[index] += mz;
            arrivalSum[index] += e * arrival;
            emittedParts++;
            emittedEnergy += e;
            return created;
        }

        void reset() {
            for (int i = 0; i < count; i++) byFaceSlot[activeSlots[i] & 0xffff] = 0;
            count = 0;
            emittedParts = culledParts = 0;
            emittedEnergy = culledEnergy = 0.0;
            completedPackets = 0;
        }
    }

    private static final class EnergyPacket {
        long lastTick;
        int exitCandidates;
        double energy, momentX, momentY, momentZ, time, timeVariance;
        double centerX, centerY, centerZ, varianceX, varianceY, varianceZ;
        double earliestArrival;
        int localOrigin;
    }

    public AdaptiveCube(int x, int y, int z, int size) {
        if (size < 2 || size > 16 || (size & (size - 1)) != 0)
            throw new IllegalArgumentException("Invalid cube size");
        if (((x | y | z) & (size - 1)) != 0)
            throw new IllegalArgumentException("Cube is not aligned");
        if (y < 0 || y + size > 256)
            throw new IllegalArgumentException("Invalid cube height");

        this.x = x;
        this.y = y;
        this.z = z;
        this.size = size;
        count = size <= 2 ? 32 : size <= 4 ? 64 : size <= 8 ? 128 : 256;
        dirs = DIRECTIONS[Integer.numberOfTrailingZeros(count) - 5];
        weights = new double[count];

        faceSlots = 6 * size * size;
        geometryTable = GEOMETRY_TABLES[Integer.numberOfTrailingZeros(size) - 1];
        useSharedGeometry = Math.abs((long) x) < (1L << 18)
                && Math.abs((long) z) < (1L << 18);
        faceSources = new long[faceSlots];
        faceTargets = new long[faceSlots];
        faceEscaped = new boolean[faceSlots];
        prepareFacePairs();
    }

    public AdaptiveCube(oktoderevo.AirRegion region) {
        this(region.x, region.y, region.z, region.size);
    }

    public void receive(int ex, int ey, int ez, double e, double mx, double my, double mz, double arrival) {
        if (ex < x || ex >= x + size || ey < y || ey >= y + size || ez < z || ez >= z + size)
            throw new IllegalArgumentException("Entry outside cube");
        if (!Double.isFinite(e) || e < 0 || !Double.isFinite(mx) || !Double.isFinite(my)
                || !Double.isFinite(mz) || !Double.isFinite(arrival) || arrival < 0)
            throw new IllegalArgumentException("Invalid energy transfer");
        if (e == 0) return;
        if (incomingCount == inputOffset.length) {
            int nc = inputOffset.length + (inputOffset.length >> 1);
            inputOffset = Arrays.copyOf(inputOffset, nc);
            inputData = Arrays.copyOf(inputData, nc * INPUT_STRIDE);
        }
        int off = (ex - x) | ((ey - y) << 4) | ((ez - z) << 8);
        inputOffset[incomingCount] = (short) off;
        int p = incomingCount * INPUT_STRIDE;
        inputData[p] = e;
        inputData[p + 1] = mx;
        inputData[p + 2] = my;
        inputData[p + 3] = mz;
        inputData[p + 4] = arrival;
        incomingCount++;
        activePackets++;
        diagnostics.received++;
        diagnostics.receivedEnergy += e;
    }

    // Shared, immutable geometry rows. They are built only for entry cells that
    // actually receive energy. No WorldServer or per-packet data is cached.
    private static final class GeometryRow {
        // Per direction: distance, spread for zero timeVariance, w00, w01, w10, w11.
        final double[] values;
        // Four face slots per direction, before thresholding and duplicate merging.
        final short[] slots;

        GeometryRow(int count) {
            values = new double[6 * count];
            slots = new short[4 * count];
        }
    }

    private static final class GeometryTable {
        final int size;
        final double[][] directions;
        final AtomicReferenceArray<GeometryRow> rows;

        GeometryTable(int size, double[][] directions) {
            this.size = size;
            this.directions = directions;
            rows = new AtomicReferenceArray<>(size * size * size);
        }

        GeometryRow get(int cell) {
            GeometryRow row = rows.get(cell);
            if (row != null) return row;
            GeometryRow calculated = create(cell);
            if (rows.compareAndSet(cell, null, calculated)) return calculated;
            return rows.get(cell);
        }

        private GeometryRow create(int cell) {
            GeometryRow row = new GeometryRow(directions.length);
            double ox = (cell % size) + .5;
            double oy = ((cell / size) % size) + .5;
            double oz = (cell / (size * size)) + .5;

            for (int i = 0; i < directions.length; i++) {
                double ux = directions[i][0], uy = directions[i][1], uz = directions[i][2];
                double tx = ux > 1e-12 ? (size - ox) / ux
                        : ux < -1e-12 ? -ox / ux : Double.POSITIVE_INFINITY;
                double ty = uy > 1e-12 ? (size - oy) / uy
                        : uy < -1e-12 ? -oy / uy : Double.POSITIVE_INFINITY;
                double tz = uz > 1e-12 ? (size - oz) / uz
                        : uz < -1e-12 ? -oz / uz : Double.POSITIVE_INFINITY;
                int face = tx <= ty && tx <= tz ? 0 : ty <= tz ? 1 : 2;
                double distance = Math.min(tx, Math.min(ty, tz));
                int data = 6 * i;
                if (!Double.isFinite(distance) || distance <= 0.0) continue;

                row.values[data] = distance;
                row.values[data + 1] = Math.sqrt(2.0 * Math.max(0.0, .06 * distance));
                double hx = ox + ux * distance;
                double hy = oy + uy * distance;
                double hz = oz + uz * distance;
                double fa = (face == 0 ? hy : hx) - .5;
                double fb = (face == 2 ? hy : hz) - .5;
                int a0 = (int) Math.floor(fa), b0 = (int) Math.floor(fb);
                double aw = fa - a0, bw = fb - b0;
                row.values[data + 2] = (1.0 - aw) * (1.0 - bw);
                row.values[data + 3] = (1.0 - aw) * bw;
                row.values[data + 4] = aw * (1.0 - bw);
                row.values[data + 5] = aw * bw;

                int aFirst = clampCell(a0, 0, size - 1);
                int aSecond = clampCell(a0 + 1, 0, size - 1);
                int bFirst = clampCell(b0, 0, size - 1);
                int bSecond = clampCell(b0 + 1, 0, size - 1);
                double component = face == 0 ? ux : face == 1 ? uy : uz;
                int base = (face * 2 + (component > 0.0 ? 1 : 0)) * size * size;
                int slot = 4 * i;
                row.slots[slot] = (short) (base + aFirst * size + bFirst);
                row.slots[slot + 1] = (short) (base + aFirst * size + bSecond);
                row.slots[slot + 2] = (short) (base + aSecond * size + bFirst);
                row.slots[slot + 3] = (short) (base + aSecond * size + bSecond);
            }
            return row;
        }
    }

    private static GeometryTable[] createGeometryTables() {
        GeometryTable[] tables = new GeometryTable[4];
        for (int level = 0; level < tables.length; level++)
            tables[level] = new GeometryTable(2 << level, DIRECTIONS[level]);
        return tables;
    }

    private void prepareFacePairs() {
        int area = size * size;
        for (int slot = 0; slot < faceSlots; slot++) {
            int faceGroup = slot / area;
            int face = faceGroup / 2;
            int step = (faceGroup & 1) == 1 ? 1 : -1;
            int a = (slot % area) / size;
            int b = slot % size;

            int sx = face == 0 ? (step > 0 ? x + size - 1 : x) : x + a;
            int sy = face == 1 ? (step > 0 ? y + size - 1 : y)
                    : face == 0 ? y + a : y + b;
            int sz = face == 2 ? (step > 0 ? z + size - 1 : z) : z + b;
            int tx = sx + (face == 0 ? step : 0);
            int ty = sy + (face == 1 ? step : 0);
            int tz = sz + (face == 2 ? step : 0);
            boolean escaped = ty < 0 || ty >= 256 || tx < -30000000 || tx > 30000000
                    || tz < -30000000 || tz > 30000000;
            faceEscaped[slot] = escaped;
            if (!escaped) {
                faceSources[slot] = pack(sx, sy, sz);
                faceTargets[slot] = pack(tx, ty, tz);
            }
        }
    }

    // No divisions, floor/clamp, ray-face intersections or coordinate packing
    // in this hot path: only energy-dependent work remains.
    private void appendCachedOrigin(EnergyPacket packet, double totalWeight) {
        GeometryRow geometry = geometryTable.get(packet.localOrigin);
        diagnostics.origins++;
        diagnostics.directions += count;
        for (int i = 0; i < count; i++) {
            double energy = packet.energy * weights[i] / totalWeight;
            if (energy < MIN_ACTIVE_ENERGY) continue;
            int data = 6 * i;
            double distance = geometry.values[data];
            if (distance <= 0.0) continue;
            diagnostics.attenuationCalls++;
            energy *= sectorTransmittance(packet.energy, distance);
            if (energy < MIN_ACTIVE_ENERGY) continue;
            appendCachedFaceExits(packet, geometry, i, energy);
        }
    }

    private void appendCachedFaceExits(EnergyPacket packet, GeometryRow geometry,
                                       int direction, double energy) {
        int data = 6 * direction;
        int base = 4 * direction;
        double e00 = energy * geometry.values[data + 2];
        double e01 = energy * geometry.values[data + 3];
        double e10 = energy * geometry.values[data + 4];
        double e11 = energy * geometry.values[data + 5];
        if (e00 < MIN_ACTIVE_ENERGY) e00 = 0.0;
        if (e01 < MIN_ACTIVE_ENERGY) e01 = 0.0;
        if (e10 < MIN_ACTIVE_ENERGY) e10 = 0.0;
        if (e11 < MIN_ACTIVE_ENERGY) e11 = 0.0;

        short[] slots = geometry.slots;
        if (slots[base] == slots[base + 2]) {
            if (e00 > 0 && e10 > 0) diagnostics.faceDuplicatesMerged++;
            if (e01 > 0 && e11 > 0) diagnostics.faceDuplicatesMerged++;
            e00 += e10;
            e01 += e11;
            e10 = e11 = 0.0;
        }
        if (slots[base] == slots[base + 1]) {
            if (e00 > 0 && e01 > 0) diagnostics.faceDuplicatesMerged++;
            if (e10 > 0 && e11 > 0) diagnostics.faceDuplicatesMerged++;
            e00 += e01;
            e10 += e11;
            e01 = e11 = 0.0;
        }

        // Каждый луч имеет только одну тройку временных частей.
        // Расчёт и поиск TickBucket откладываем до первого неэкранированного выхода.
        rayScheduleReady = false;
        double distance = geometry.values[data];
        double arrival = packet.time + .5 + 1.35 * distance;
        double spread = packet.timeVariance == 0.0 ? geometry.values[data + 1]
                : Math.sqrt(2.0 * Math.max(0.0, packet.timeVariance + .06 * distance));
        if (e00 != 0.0) appendCachedExit(packet, slots[base] & 65535, e00, direction, arrival, spread);
        if (e01 != 0.0) appendCachedExit(packet, slots[base + 1] & 65535, e01, direction, arrival, spread);
        if (e10 != 0.0) appendCachedExit(packet, slots[base + 2] & 65535, e10, direction, arrival, spread);
        if (e11 != 0.0) appendCachedExit(packet, slots[base + 3] & 65535, e11, direction, arrival, spread);
    }

    private void appendCachedExit(EnergyPacket packet, int slot, double energy,
                                  int direction, double arrival, double spread) {
        if (faceEscaped[slot]) {
            escapedEnergy += energy;
            diagnostics.escapedParts++;
            diagnostics.escapedEnergy += energy;
        } else {
            addExit(packet, faceSources[slot], faceTargets[slot],
                    energy, direction, slot, arrival, spread);
        }
    }

    private static long pack(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38)
                | (((long) y & 0xFFFL) << 26)
                | ((long) z & 0x3FFFFFFL);
    }

    private static double[] createAttenuationFactors() {
        double[] factors = new double[ATTENUATION_SAMPLES];
        for (int i = 0; i < factors.length; i++) {
            double position = (i + 0.5) / ATTENUATION_SAMPLES;
            factors[i] = SPREAD_FACTOR * position * position;
        }
        return factors;
    }

    private static double sectorTransmittance(double energy, double length) {
        // Inside a cube of size <= 16, exp(-attenuation) rounds to 1.0
        // for such energies; this also prevents overflow in intermediate products.
        if (energy >= 1e24) return 1.0;
        double step = length / ATTENUATION_SAMPLES;
        double lengthSquared = length * length;
        double energySquared = energy * energy;
        double hundredEnergy = 100.0 * energy;
        double inverseAttenuationSum = 0.0;

        for (int i = 0; i < ATTENUATION_SAMPLES; i++) {
            // q = 1 + SPREAD_FACTOR * distance^2, distance = (i+0.5)*step.
            double q = 1.0 + ATTENUATION_FACTORS[i] * lengthSquared;
            double qSquared = q * q;
            double base = (energy + 10.0 * q) * (energySquared + 0.01 * qSquared);
            // Algebraically equal to 1 / attenuationEnergy(energy / q).
            // One division per sample instead of three; no numerical integration changes.
            inverseAttenuationSum += q * base / (energy * (base + hundredEnergy * qSquared));
        }
        return Math.exp(-AIR_ATTENUATION * step * inverseAttenuationSum);
    }

    private static int expanded(int n) {
        return Math.max(64, n + (n >> 1));
    }

    private TickBucket bucket(long tick) {
        TickBucket value = scheduled.get(tick);
        if (value != null) return value;
        value = reusableBuckets.pollFirst();
        if (value == null) value = new TickBucket(faceSlots);
        scheduled.put(tick, value);
        return value;
    }

    // Один раз на луч. Вызывается лениво: полностью отсечённый луч или выход
    // за границы мира не создаёт пустых корзин и не продлевает жизнь пакета.
    private void prepareRaySchedule(EnergyPacket packet, double arrival, double spread) {
        double earliest = packet.earliestArrival;
        for (int part = 0; part < 3; part++) {
            // Сохраняем исходный порядок операций, включая округление double.
            double partArrival = Math.max(earliest, arrival + (part - 1) * spread);
            long tick = (long) Math.floor(partArrival);
            if (tick < previousTick) tick = previousTick;
            if (tick > packet.lastTick) packet.lastTick = tick;
            rayTicks[part] = tick;
            rayArrivals[part] = Math.max(partArrival, (double) tick);
            rayBuckets[part] = part > 0 && tick == rayTicks[part - 1]
                    ? rayBuckets[part - 1] : bucket(tick);
        }
        rayScheduleReady = true;
    }

    // Каждую временную часть сразу складываем в (тик, пара блоков), сохраняя
    // прежний порядок накопления: выход -> временные части 0, 1, 2.
    private void addExit(EnergyPacket packet, long source, long target,
                         double energy, int direction, int slot, double arrival, double spread) {
        packet.exitCandidates++;
        preparedOutgoing += energy;
        diagnostics.rawExits++;
        if (!rayScheduleReady) prepareRaySchedule(packet, arrival, spread);

        double[] dir = dirs[direction];
        for (int part = 0; part < 3; part++) {
            double partEnergy = energy * (part == 1 ? .5 : .25);
            TickBucket atTick = rayBuckets[part];
            if (partEnergy >= MIN_ACTIVE_ENERGY) {
                if (atTick.add(slot, source, target, partEnergy,
                        partEnergy * dir[0], partEnergy * dir[1],
                        partEnergy * dir[2], rayArrivals[part])) {
                    pendingExitObjects++;
                    diagnostics.exitsCreated++;
                } else {
                    diagnostics.sameTickMerges++;
                }
            } else {
                // Как и раньше, отсечение до объединения энергий.
                atTick.culledParts++;
                atTick.culledEnergy += partEnergy;
            }
        }
    }

    private static int clampCell(int v, int a, int b) {
        return Math.max(a, Math.min(b, v));
    }

    private double keepInside(double v, int min) {
        return Math.max(min + 1e-6, Math.min(min + size - 1e-6, v));
    }

    private void appendFaceExits(EnergyPacket packet, int face,
                                 double hx, double hy, double hz,
                                 double ux, double uy, double uz,
                                 double distance, double energy, int direction) {
        double ca = face == 0 ? hy : hx;
        double cb = face == 2 ? hy : hz;
        int minA = face == 0 ? y : x;
        int minB = face == 2 ? y : z;
        double fa = ca - .5, fb = cb - .5;
        int a0 = (int) Math.floor(fa), b0 = (int) Math.floor(fb);
        double aw = fa - a0, bw = fb - b0;
        int aFirst = clampCell(a0, minA, minA + size - 1);
        int aSecond = clampCell(a0 + 1, minA, minA + size - 1);
        int bFirst = clampCell(b0, minB, minB + size - 1);
        int bSecond = clampCell(b0 + 1, minB, minB + size - 1);

        int normalMin = face == 0 ? x : face == 1 ? y : z;
        double component = face == 0 ? ux : face == 1 ? uy : uz;
        boolean positive = component > 0;
        int ni = positive ? normalMin + size - 1 : normalMin;
        int no = positive ? normalMin + size : normalMin - 1;
        double arrival = packet.time + .5 + 1.35 * distance;
        double spread = Math.sqrt(2.0 * Math.max(0, packet.timeVariance + .06 * distance));
        rayScheduleReady = false;

        // Сохраняем прежний порог: сначала отсекаем слабые части,
        // затем объединяем те, что после clampCell ведут в одну клетку.
        double e00 = energy * ((1 - aw) * (1 - bw));
        double e01 = energy * ((1 - aw) * bw);
        double e10 = energy * (aw * (1 - bw));
        double e11 = energy * (aw * bw);
        if (e00 < MIN_ACTIVE_ENERGY) e00 = 0;
        if (e01 < MIN_ACTIVE_ENERGY) e01 = 0;
        if (e10 < MIN_ACTIVE_ENERGY) e10 = 0;
        if (e11 < MIN_ACTIVE_ENERGY) e11 = 0;

        if (aFirst == aSecond) {
            if (e00 > 0 && e10 > 0) diagnostics.faceDuplicatesMerged++;
            if (e01 > 0 && e11 > 0) diagnostics.faceDuplicatesMerged++;
            e00 += e10;
            e01 += e11;
            e10 = e11 = 0;
        }
        if (bFirst == bSecond) {
            if (e00 > 0 && e01 > 0) diagnostics.faceDuplicatesMerged++;
            if (e10 > 0 && e11 > 0) diagnostics.faceDuplicatesMerged++;
            e00 += e01;
            e10 += e11;
            e01 = e11 = 0;
        }

        int faceSlotBase = (face * 2 + (positive ? 1 : 0)) * size * size;
        for (int ai = 0; ai < 2; ai++) {
            for (int bi = 0; bi < 2; bi++) {
                double e = ai == 0 ? (bi == 0 ? e00 : e01) : (bi == 0 ? e10 : e11);
                if (e == 0) continue;
                int ia = ai == 0 ? aFirst : aSecond;
                int ib = bi == 0 ? bFirst : bSecond;
                int sx = face == 0 ? ni : ia;
                int sy = face == 1 ? ni : face == 0 ? ia : ib;
                int sz = face == 2 ? ni : ib;
                int tx = face == 0 ? no : ia;
                int ty = face == 1 ? no : face == 0 ? ia : ib;
                int tz = face == 2 ? no : ib;
                if (ty < 0 || ty >= 256 || tx < -30000000 || tx > 30000000
                        || tz < -30000000 || tz > 30000000) {
                    escapedEnergy += e;
                    diagnostics.escapedParts++;
                    diagnostics.escapedEnergy += e;
                    continue;
                }
                int slot = faceSlotBase + (ia - minA) * size + (ib - minB);
                addExit(packet, pack(sx, sy, sz), pack(tx, ty, tz),
                        e, direction, slot, arrival, spread);
            }
        }
    }

    private void appendOrigin(EnergyPacket packet, double ox, double oy, double oz,
                              double originWeight, double totalWeight) {
        ox = keepInside(ox, x);
        oy = keepInside(oy, y);
        oz = keepInside(oz, z);
        diagnostics.origins++;
        diagnostics.directions += count;
        for (int i = 0; i < count; i++) {
            double energy = packet.energy * originWeight * weights[i] / totalWeight;
            if (energy < MIN_ACTIVE_ENERGY) continue;
            double ux = dirs[i][0], uy = dirs[i][1], uz = dirs[i][2];
            double tx = ux > 1e-12 ? (x + size - ox) / ux
                    : ux < -1e-12 ? (x - ox) / ux : Double.POSITIVE_INFINITY;
            double ty = uy > 1e-12 ? (y + size - oy) / uy
                    : uy < -1e-12 ? (y - oy) / uy : Double.POSITIVE_INFINITY;
            double tz = uz > 1e-12 ? (z + size - oz) / uz
                    : uz < -1e-12 ? (z - oz) / uz : Double.POSITIVE_INFINITY;
            int face = tx <= ty && tx <= tz ? 0 : ty <= tz ? 1 : 2;
            double distance = Math.min(tx, Math.min(ty, tz));
            if (!Double.isFinite(distance) || distance <= 0) continue;
            diagnostics.attenuationCalls++;
            energy *= sectorTransmittance(packet.energy, distance);
            if (energy < MIN_ACTIVE_ENERGY) continue;
            appendFaceExits(packet, face, ox + ux * distance,
                    oy + uy * distance, oz + uz * distance,
                    ux, uy, uz, distance, energy, i);
        }
    }

    private void prepare(EnergyPacket packet) {
        double px = packet.momentX, py = packet.momentY, pz = packet.momentZ;
        double m = Math.sqrt(px * px + py * py + pz * pz);
        double k = Math.min(1.0, m / packet.energy);
        double total, maxWeight;

        if (k == 0.0) {
            Arrays.fill(weights, 1.0);
            total = count;
            maxWeight = 1.0;
        } else {
            double vx = m > 1e-15 ? px / m : 0;
            double vy = m > 1e-15 ? py / m : 0;
            double vz = m > 1e-15 ? pz / m : 0;
            total = 0;
            maxWeight = 0;
            double scale = 2.0 * (LOBE_POWER + 1.0) * k;
            for (int i = 0; i < count; i++) {
                double[] dir = dirs[i];
                double dot = dir[0] * vx + dir[1] * vy + dir[2] * vz;
                double directed = dot > 0 ? scale * Math.pow(dot, LOBE_POWER) : 0.0;
                double diffuse = 1.0 - k;
                weights[i] = diffuse + directed;
                total += weights[i];
                if (weights[i] > maxWeight) maxWeight = weights[i];
            }
        }

        if (total <= 0) return;
        // Верхняя граница: любое затухание и вес originWeight <= 1
        // могут только уменьшить энергию каждого отдельного направления.
        if (packet.energy * maxWeight / total < MIN_ACTIVE_ENERGY) {
            diagnostics.weakPacketsSkipped++;
            return;
        }
        // Раньше Math.floor(packet.time) выполнялся для каждого выхода.
        packet.earliestArrival = Math.floor(packet.time) + 1.0;
        double sx = Math.sqrt(Math.max(0, packet.varianceX));
        double sy = Math.sqrt(Math.max(0, packet.varianceY));
        double sz = Math.sqrt(Math.max(0, packet.varianceZ));
        if (sx + sy + sz < 1e-6) {
            // process() always provides a block-center origin. Keep the
            // original geometry path for any future non-centred input.
            int local = packet.localOrigin;
            int lx = local % size, ly = (local / size) % size, lz = local / (size * size);
            if (useSharedGeometry && packet.centerX == x + lx + .5
                    && packet.centerY == y + ly + .5 && packet.centerZ == z + lz + .5) {
                appendCachedOrigin(packet, total);
            } else {
                appendOrigin(packet, packet.centerX, packet.centerY, packet.centerZ, 1, total);
            }
            return;
        }
        appendOrigin(packet, packet.centerX + sx, packet.centerY + sy, packet.centerZ + sz, .25, total);
        appendOrigin(packet, packet.centerX + sx, packet.centerY - sy, packet.centerZ - sz, .25, total);
        appendOrigin(packet, packet.centerX - sx, packet.centerY + sy, packet.centerZ - sz, .25, total);
        appendOrigin(packet, packet.centerX - sx, packet.centerY - sy, packet.centerZ + sz, .25, total);
    }

    @FunctionalInterface
    public interface EnergyOutput {
        void emit(long source, long target, double energy,
                  double mx, double my, double mz, double arrival);
    }

    private void emitDue(long tick, EnergyOutput output) {
        TickBucket due = scheduled.remove(tick);
        if (due == null) return;
        for (int i = 0; i < due.count; i++) {
            double e = due.energy[i];
            double arrival = due.arrivalSum[i] / e;
            // Разрешаем накопление только в этом тике, включая погрешность double.
            arrival = Math.max((double) tick,
                    Math.min(arrival, Math.nextDown((double) tick + 1.0)));
            output.emit(due.sources[i], due.targets[i], e,
                    due.momentX[i], due.momentY[i], due.momentZ[i], arrival);
        }
        diagnostics.exitsChecked += due.count;
        diagnostics.emittedTransfers += due.count;
        diagnostics.emittedParts += due.emittedParts;
        diagnostics.emittedEnergy += due.emittedEnergy;
        diagnostics.culledParts += due.culledParts;
        diagnostics.culledEnergy += due.culledEnergy;
        culledEnergy += due.culledEnergy;
        pendingExitObjects -= due.count;
        activePackets -= due.completedPackets;
        diagnostics.completed += due.completedPackets;

        due.reset();
        if (reusableBuckets.size() < MAX_REUSED_BUCKETS) reusableBuckets.addFirst(due);
    }

    public void process(
            long tick,
            EnergyOutput output,
            List<EntitySnapshot.Hitbox> hitboxes,
            explosionstep.DamageSink damageSink
    ) {
        if (tick < 0 || output == null)
            throw new IllegalArgumentException("Invalid process args");
        if (previousTick != Long.MIN_VALUE && tick != previousTick + 1)
            throw new IllegalStateException("AdaptiveCube requires consecutive ticks");
        previousTick = tick;
        int damageBoxCount = 0;

        if (incomingCount > 0
                && hitboxes != null
                && damageSink != null) {

            int count = hitboxes.size();

            if (damageBoxIndexes.length < count) {
                damageBoxIndexes = new int[count];
                damageBoxCounts = new int[count];
            }

            for (int i = 0; i < count; i++) {
                EntitySnapshot.Hitbox box = hitboxes.get(i);

                int nx = overlapCells(
                        box.minX, box.maxX, x, size
                );

                if (nx == 0) continue;

                int ny = overlapCells(
                        box.minY, box.maxY, y, size
                );

                if (ny == 0) continue;

                int nz = overlapCells(
                        box.minZ, box.maxZ, z, size
                );

                if (nz == 0) continue;

                damageBoxIndexes[damageBoxCount] = i;
                damageBoxCounts[damageBoxCount] = nx * ny * nz;

                damageBoxCount++;
            }
        }
        for (int i = incomingCount - 1; i >= 0; i--) {
            int p = i * INPUT_STRIDE, off = inputOffset[i] & 65535;
            scratch.energy = inputData[p];
            scratch.momentX = inputData[p + 1];
            scratch.momentY = inputData[p + 2];
            scratch.momentZ = inputData[p + 3];
            scratch.time = inputData[p + 4];
            scratch.timeVariance = 0;
            scratch.centerX = x + (off & 15) + .5;
            scratch.centerY = y + ((off >> 4) & 15) + .5;
            scratch.centerZ = z + ((off >> 8) & 15) + .5;
            scratch.varianceX = scratch.varianceY = scratch.varianceZ = 0;
            scratch.localOrigin = (off & 15) + size * (((off >> 4) & 15)
                    + size * ((off >> 8) & 15));
            scratch.lastTick = Long.MIN_VALUE;
            scratch.exitCandidates = 0;
            preparedOutgoing = 0;
            double before = escapedEnergy;
            long start = System.nanoTime();
            if (damageBoxCount > 0) {

                double damage = scratch.energy
                        * explosionstep.ENTITY_DAMAGE_SCALE
                        / (size * size * size);

                if (Double.isFinite(damage) && damage > 0.0) {

                    for (int j = 0; j < damageBoxCount; j++) {

                        EntitySnapshot.Hitbox box =
                                hitboxes.get(damageBoxIndexes[j]);

                        int intersections = damageBoxCounts[j];

                        for (int k = 0; k < intersections; k++) {
                            damageSink.hit(
                                    box.entityId,
                                    box.entityUuid,
                                    box.partIndex,
                                    damage
                            );
                        }
                    }
                }
            }
            prepare(scratch);
            long nanos = System.nanoTime() - start;
            diagnostics.prepared++;
            diagnostics.prepareNs += nanos;
            diagnostics.maxPrepareNs = Math.max(diagnostics.maxPrepareNs, nanos);
            diagnostics.generatedEnergy += preparedOutgoing;
            diagnostics.maxExitsPerPacket = Math.max(diagnostics.maxExitsPerPacket,
                    scratch.exitCandidates);
            airLoss += Math.max(0, scratch.energy - preparedOutgoing - (escapedEnergy - before));
            if (scratch.lastTick == Long.MIN_VALUE) {
                activePackets--;
                diagnostics.completed++;
            } else {
                // Пакет считается завершённым после последней временной части,
                // даже если она отсечена по порогу активности.
                bucket(scratch.lastTick).completedPackets++;
            }
        }
        incomingCount = 0;
        emitDue(tick, output);
    }

    public boolean hasPending() { return activePackets != 0; }
    public int getPacketCount() { return activePackets; }
    public int getPendingExitCount() { return pendingExitObjects; }
    public void drainDiagnostics(Diagnostics target) { target.add(diagnostics); diagnostics.reset(); }
    public double takeAirLoss() { double v = airLoss; airLoss = 0; return v; }
    public double takeEscapedEnergy() { double v = escapedEnergy; escapedEnergy = 0; return v; }
    public double takeCulledEnergy() { double v = culledEnergy; culledEnergy = 0; return v; }

    public void clear() {
        incomingCount = 0;
        activePackets = 0;
        pendingExitObjects = 0;
        preparedOutgoing = 0.0;
        rayScheduleReady = false;
        Arrays.fill(rayBuckets, null);
        scheduled.clear();
        reusableBuckets.clear();
        previousTick = Long.MIN_VALUE;
        airLoss = escapedEnergy = culledEnergy = 0.0;
        diagnostics.reset();
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    public int getSize() { return size; }

    private static double[][][] createDirections() {
        double[][][] tables = new double[4][][];
        for (int level = 0; level < 4; level++) {
            int count = 32 << level;
            int half = count / 2;
            double[][] directions = new double[count][3];
            for (int i = 0; i < half; i++) {
                double y = 1.0 - 2.0 * (i + 0.5) / half;
                double r = Math.sqrt(Math.max(0.0, 1.0 - y * y));
                double angle = i * GOLDEN_ANGLE;
                double dx = r * Math.cos(angle);
                double dz = r * Math.sin(angle);
                directions[i][0] = dx;
                directions[i][1] = y;
                directions[i][2] = dz;
                directions[i + half][0] = -dx;
                directions[i + half][1] = -y;
                directions[i + half][2] = -dz;
            }
            tables[level] = directions;
        }
        return tables;
    }

    public static final class Diagnostics {
        public long received;
        public long prepared;
        public long completed;
        public long origins;
        public long directions;
        public long attenuationCalls;
        public long exitsCreated;
        // Число исходных выходов до дробления на тики и группировки.
        public long rawExits;
        public long exitsChecked;
        public long exitsWaiting;
        public long emittedParts;
        public long emittedTransfers;
        public long sameTickMerges;
        public long faceDuplicatesMerged;
        public long weakPacketsSkipped;
        public long culledParts;
        public long escapedParts;
        public long prepareNs;
        public long maxPrepareNs;
        public long maxExitsPerPacket;
        public double receivedEnergy;
        public double generatedEnergy;
        public double emittedEnergy;
        public double culledEnergy;
        public double escapedEnergy;

        public void add(Diagnostics other) {
            received += other.received;
            prepared += other.prepared;
            completed += other.completed;
            origins += other.origins;
            directions += other.directions;
            attenuationCalls += other.attenuationCalls;
            exitsCreated += other.exitsCreated;
            rawExits += other.rawExits;
            exitsChecked += other.exitsChecked;
            exitsWaiting += other.exitsWaiting;
            emittedParts += other.emittedParts;
            emittedTransfers += other.emittedTransfers;
            sameTickMerges += other.sameTickMerges;
            faceDuplicatesMerged += other.faceDuplicatesMerged;
            weakPacketsSkipped += other.weakPacketsSkipped;
            culledParts += other.culledParts;
            escapedParts += other.escapedParts;
            prepareNs += other.prepareNs;
            maxPrepareNs = Math.max(maxPrepareNs, other.maxPrepareNs);
            maxExitsPerPacket = Math.max(maxExitsPerPacket, other.maxExitsPerPacket);
            receivedEnergy += other.receivedEnergy;
            generatedEnergy += other.generatedEnergy;
            emittedEnergy += other.emittedEnergy;
            culledEnergy += other.culledEnergy;
            escapedEnergy += other.escapedEnergy;
        }

        public void reset() {
            received = prepared = completed = 0;
            origins = directions = attenuationCalls = 0;
            exitsCreated = exitsChecked = exitsWaiting = 0;
            rawExits = 0;
            emittedParts = emittedTransfers = sameTickMerges = 0;
            faceDuplicatesMerged = weakPacketsSkipped = 0;
            culledParts = escapedParts = 0;
            prepareNs = maxPrepareNs = maxExitsPerPacket = 0;
            receivedEnergy = generatedEnergy = 0.0;
            emittedEnergy = culledEnergy = escapedEnergy = 0.0;
        }
    }

    private static int overlapCells(
            double min,
            double max,
            int cubeStart,
            int cubeSize
    ) {
        int first = Math.max(
                cubeStart,
                (int) Math.floor(min)
        );

        int end = Math.min(
                cubeStart + cubeSize,
                (int) Math.ceil(max)
        );

        return Math.max(0, end - first);
    }
}
