package com.kaboom.explosionwork;

import java.util.Arrays;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

public class explosionstep {
    private static final double MIN_ACTIVE_ENERGY = 1e-5;
    private static final double AIR_ATTENUATION = 0.15;
    private static final double LOBE_POWER = 2.2;
    private static final double DIRECTION_MEMORY = 0.6;
    private static final double MAX_REFLECTION = 0.40;
    private static final double HARDNESS_SCALE = 5.0;
    private static final double DESTRUCTION_MULTIPLIER = 1.0;

    private static final int DIRECTIONS = 26;

    private static final int[] DX = new int[DIRECTIONS];
    private static final int[] DY = new int[DIRECTIONS];
    private static final int[] DZ = new int[DIRECTIONS];

    private static final int[] LENGTH_CLASS = new int[DIRECTIONS];

    private static final double[] UX = new double[DIRECTIONS];
    private static final double[] UY = new double[DIRECTIONS];
    private static final double[] UZ = new double[DIRECTIONS];

    private static final double[] LENGTH = {
            0.0, 1.0, Math.sqrt(2.0), Math.sqrt(3.0)
    };

    private static final int[] AX = {1, -1, 0, 0, 0, 0};
    private static final int[] AY = {0, 0, 1, -1, 0, 0};
    private static final int[] AZ = {0, 0, 0, 0, 1, -1};

    private static final int MAX_PREFETCH_SECTIONS_PER_STEP = 64;

    // Секции, для которых уже проверена готовность в этом шаге.
    private final LongOpenHashSet prefetchedSections =
            new LongOpenHashSet();

    // Количество клеток, отложенных на последнем шаге.
    private int deferredLastStep;

    public int getDeferredLastStep() {
        return deferredLastStep;
    }

    static {
        int i = 0;

        for (int z = -1; z <= 1; z++) {
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {

                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }

                    int n = x * x + y * y + z * z;

                    DX[i] = x;
                    DY[i] = y;
                    DZ[i] = z;

                    LENGTH_CLASS[i] = n;

                    UX[i] = x / LENGTH[n];
                    UY[i] = y / LENGTH[n];
                    UZ[i] = z / LENGTH[n];

                    i++;
                }
            }
        }
    }

    private static long pack(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38)
                | (((long) y & 0xFFFL) << 26)
                | ((long) z & 0x3FFFFFFL);
    }

    private static int unpackX(long p) {
        return (int) (p >> 38);
    }

    private static int unpackY(long p) {
        return (int) ((p >> 26) & 0xFFFL);
    }

    private static int unpackZ(long p) {
        return (int) (p << 38 >> 38);
    }

    private static boolean blocked(int direction, int solidMask) {

        int axes = LENGTH_CLASS[direction];

        if (axes == 1) {
            return false;
        }

        int solid = 0;

        if (DX[direction] != 0
                && (solidMask & (DX[direction] > 0 ? 1 : 2)) != 0) {
            solid++;
        }

        if (DY[direction] != 0
                && (solidMask & (DY[direction] > 0 ? 4 : 8)) != 0) {
            solid++;
        }

        if (DZ[direction] != 0
                && (solidMask & (DZ[direction] > 0 ? 16 : 32)) != 0) {
            solid++;
        }

        return solid == axes;
    }

    static final class WaveBucket {

        private final Long2IntOpenHashMap cellIndex =
                new Long2IntOpenHashMap();

        int cellCount;
        int linkCount;

        // Активные клетки.
        long[] position = new long[64];

        double[] energy = new double[64];
        double[] momentX = new double[64];
        double[] momentY = new double[64];
        double[] momentZ = new double[64];
        double[] arrivalSum = new double[64];

        // Индекс первой входящей передачи клетки.
        int[] firstLink = new int[64];

        // Входящие передачи.
        long[] linkSource = new long[128];

        double[] linkEnergy = new double[128];
        double[] linkMomentX = new double[128];
        double[] linkMomentY = new double[128];
        double[] linkMomentZ = new double[128];
        double[] linkArrivalSum = new double[128];

        // Связь передач, приходящих в одну клетку.
        int[] nextLink = new int[128];

        WaveBucket() {
            cellIndex.defaultReturnValue(-1);
        }

        void add(long source, long target, double e,
                 double mx, double my, double mz, double arrival) {

            int c = cellIndex.get(target);

            if (c == -1) {
                growCells();

                c = cellCount++;
                cellIndex.put(target, c);

                position[c] = target;

                energy[c] = 0.0;
                momentX[c] = 0.0;
                momentY[c] = 0.0;
                momentZ[c] = 0.0;
                arrivalSum[c] = 0.0;

                firstLink[c] = -1;
            }

            // Суммарные данные клетки.
            energy[c] += e;
            momentX[c] += mx;
            momentY[c] += my;
            momentZ[c] += mz;
            arrivalSum[c] += e * arrival;

            // Проверяем, была ли передача из этого источника.
            for (int link = firstLink[c];
                 link != -1;
                 link = nextLink[link]) {

                if (linkSource[link] == source) {

                    linkEnergy[link] += e;
                    linkMomentX[link] += mx;
                    linkMomentY[link] += my;
                    linkMomentZ[link] += mz;
                    linkArrivalSum[link] += e * arrival;

                    return;
                }
            }

            // Новый источник: создаём запись передачи.
            growLinks();

            int link = linkCount++;

            linkSource[link] = source;
            linkEnergy[link] = e;

            linkMomentX[link] = mx;
            linkMomentY[link] = my;
            linkMomentZ[link] = mz;

            linkArrivalSum[link] = e * arrival;

            nextLink[link] = firstLink[c];
            firstLink[c] = link;
        }

        private void growCells() {

            if (cellCount < position.length) {
                return;
            }

            int n = position.length * 2;

            position = Arrays.copyOf(position, n);

            energy = Arrays.copyOf(energy, n);
            momentX = Arrays.copyOf(momentX, n);
            momentY = Arrays.copyOf(momentY, n);
            momentZ = Arrays.copyOf(momentZ, n);
            arrivalSum = Arrays.copyOf(arrivalSum, n);

            firstLink = Arrays.copyOf(firstLink, n);
        }

        private void growLinks() {

            if (linkCount < linkSource.length) {
                return;
            }

            int n = linkSource.length * 2;

            linkSource = Arrays.copyOf(linkSource, n);

            linkEnergy = Arrays.copyOf(linkEnergy, n);
            linkMomentX = Arrays.copyOf(linkMomentX, n);
            linkMomentY = Arrays.copyOf(linkMomentY, n);
            linkMomentZ = Arrays.copyOf(linkMomentZ, n);
            linkArrivalSum = Arrays.copyOf(linkArrivalSum, n);

            nextLink = Arrays.copyOf(nextLink, n);
        }

        void clear() {

            cellIndex.clear();

            cellCount = 0;
            linkCount = 0;
        }
    }

    private static final int RING_SIZE = 3;

    private final WaveBucket[] pending = {
            new WaveBucket(),
            new WaveBucket(),
            new WaveBucket()
    };

    private long currentTick = 0;

    public void schedule(
            long source,
            long target,
            double energy,
            double mx,
            double my,
            double mz,
            double arrival
    ) {

        if (!Double.isFinite(energy) || energy < 0.0
                || !Double.isFinite(mx)
                || !Double.isFinite(my)
                || !Double.isFinite(mz)
                || !Double.isFinite(arrival)) {

            throw new IllegalArgumentException(
                    "Invalid wave transfer"
            );
        }

        if (energy == 0.0) {
            return;
        }

        if (Math.abs(unpackX(target) - unpackX(source)) > 1
                || Math.abs(unpackY(target) - unpackY(source)) > 1
                || Math.abs(unpackZ(target) - unpackZ(source)) > 1
                || unpackY(target) >= 256) {

            throw new IllegalArgumentException(
                    "Non-local wave transfer"
            );
        }

        long arrivalTick = (long) Math.floor(arrival);

        if (arrivalTick < currentTick
                || arrivalTick >= currentTick + RING_SIZE) {

            throw new IllegalArgumentException(
                    "Arrival outside the 3-tick window"
            );
        }

        if (arrivalTick == currentTick && nextCell != 0) {
            throw new IllegalStateException(
                    "Current tick already partially processed"
            );
        }

        pending[(int) (arrivalTick % RING_SIZE)].add(
                source, target,
                energy, mx, my, mz, arrival
        );
    }

    private int axisSolidMask(
            int x, int y, int z,
            SnapshotManager snapshots
    ) {
        int mask = 0;

        for (int i = 0; i < 6; i++) {

            int ax = x + AX[i];
            int ay = y + AY[i];
            int az = z + AZ[i];

            if (ay < 0 || ay >= 256) {
                continue;
            }

            long p = pack(ax, ay, az);
            byte solid = solidCache.get(p);

            if (solid == -1) {

                if (!snapshots.getBlock(ax, ay, az, block)) {
                    return -1;
                }

                solid = (byte) (
                        block.type == oktoderevo.BLOCK ? 1 : 0
                );

                solidCache.put(p, solid);
            }

            if (solid == 1) {
                mask |= 1 << i;
            }
        }

        return mask;
    }

    public long getCurrentTick() {
        return currentTick;
    }

    WaveBucket currentBucket() {
        return pending[(int) (currentTick % RING_SIZE)];
    }

    void finishTick() {

        currentBucket().clear();
        solidCache.clear();
        nextCell = 0;
        currentTick++;
    }

    public boolean hasPending() {

        for (WaveBucket bucket : pending) {
            if (bucket.cellCount != 0) {
                return true;
            }
        }

        return false;
    }

    private int nextCell = 0;

    private final double[] weights = new double[DIRECTIONS];
    private final double[] dots = new double[DIRECTIONS];

    private final Long2ByteOpenHashMap solidCache =
            new Long2ByteOpenHashMap();

    private final oktoderevo.BlockResult block =
            new oktoderevo.BlockResult();

    private final LongArrayList destroyed = new LongArrayList();

    private boolean awaitingDestructionApply;

    private double absorbed;
    private double airAbsorbed;
    private double escaped;
    private double culled;

    public explosionstep() {
        solidCache.defaultReturnValue((byte) -1);
    }

    public int destroyedCount() {
        return destroyed.size();
    }

    public long destroyedAt(int index) {
        return destroyed.getLong(index);
    }

    public void clearDestroyed() {
        destroyed.clear();
        awaitingDestructionApply = false;
    }

    public double getAbsorbed() {
        return absorbed;
    }

    public double getAirAbsorbed() {
        return airAbsorbed;
    }

    public double getEscaped() {
        return escaped;
    }

    public double getCulled() {
        return culled;
    }

    private void emit(
            long source,
            long target,
            double e,
            double mx,
            double my,
            double mz,
            double arrival
    ) {
        if (e <= 0.0) {
            return;
        }

        if (e < MIN_ACTIVE_ENERGY) {
            culled += e;
            return;
        }

        pending[(int) (((long) arrival) % RING_SIZE)].add(
                source, target, e, mx, my, mz, arrival
        );
    }

    private boolean processCell(
            WaveBucket b,
            int c,
            SnapshotManager snapshots
    ) {
        double incident = b.energy[c];

        if (incident <= 0.0) {
            return true;
        }

        long pos = b.position[c];

        int x = unpackX(pos);
        int y = unpackY(pos);
        int z = unpackZ(pos);

        boolean wall;
        double h = 0.0;
        double resistance = 0.0;

        if (solidCache.get(pos) == 0) {
            wall = false;
        } else {
            if (!snapshots.getBlock(x, y, z, block)) {
                return false;
            }

            wall = block.type == oktoderevo.BLOCK;
            solidCache.put(pos, (byte) (wall ? 1 : 0));

            if (wall) {
                h = block.hardness;
                resistance = block.resistance;
            }
        }

        double reflect = 0.0;
        double spent = 0.0;

        // Взаимодействие с твёрдым блоком.
        if (wall) {

            reflect = h < 0.0
                    ? MAX_REFLECTION
                    : MAX_REFLECTION * h / (h + HARDNESS_SCALE);

            spent = Math.max(0.0, resistance)
                    * DESTRUCTION_MULTIPLIER;

            // Блок выдерживает удар.
            if (h < 0.0 || incident * (1.0 - reflect) < spent) {

                absorbed += incident * (1.0 - reflect);

                double meanArrival =
                        b.arrivalSum[c] / incident;

                for (int link = b.firstLink[c];
                     link != -1;
                     link = b.nextLink[link]) {

                    long src = b.linkSource[link];

                    int sx = unpackX(src);
                    int sy = unpackY(src);
                    int sz = unpackZ(src);

                    int dx = x - sx;
                    int dy = y - sy;
                    int dz = z - sz;

                    double returnDistance = Math.sqrt(
                            (double) dx * dx +
                                    (double) dy * dy +
                                    (double) dz * dz
                    );

                    if (returnDistance < 1.0) {
                        returnDistance = 1.0;
                    }

                    emit(
                            src,
                            src,
                            b.linkEnergy[link] * reflect,
                            -b.linkMomentX[link] * reflect,
                            -b.linkMomentY[link] * reflect,
                            -b.linkMomentZ[link] * reflect,
                            meanArrival + returnDistance
                    );
                }

                return true;
            }
        }

        // Блок либо отсутствует, либо будет разрушен.
        double available = incident - spent;

        int solidMask = available > 0.0
                ? axisSolidMask(x, y, z, snapshots)
                : 0;

        if (solidMask == -1) {
            return false;
        }

        if (wall) {
            absorbed += spent;
            destroyed.add(pos);

            // Разрушенный блок считается воздухом уже в этом шаге.
            solidCache.put(pos, (byte) 0);
        }

        if (available <= 0.0) {
            return true;
        }

        // Направление суммарного потока.
        double px = b.momentX[c];
        double py = b.momentY[c];
        double pz = b.momentZ[c];

        double momentLength = Math.sqrt(
                px * px + py * py + pz * pz
        );

        double k = Math.min(1.0, momentLength / incident);

        double vx = momentLength > 1e-15
                ? px / momentLength : 0.0;

        double vy = momentLength > 1e-15
                ? py / momentLength : 0.0;

        double vz = momentLength > 1e-15
                ? pz / momentLength : 0.0;

        double diffuse = (1.0 - k) / DIRECTIONS;

        // Расчёт угловых весов.
        double totalWeight = 0.0;

        for (int i = 0; i < DIRECTIONS; i++) {

            if (blocked(i, solidMask)) {
                weights[i] = 0.0;
                continue;
            }

            double dot =
                    vx * UX[i] +
                            vy * UY[i] +
                            vz * UZ[i];

            dots[i] = dot;

            double w = diffuse
                    + (dot > 0.0
                    ? k * Math.pow(dot, LOBE_POWER)
                    : 0.0);

            weights[i] = w;
            totalWeight += w;
        }

        if (totalWeight <= 0.0) {
            absorbed += available;
            return true;
        }

        double meanArrival = b.arrivalSum[c] / incident;
        double invWeight = 1.0 / totalWeight;

        // Затухание рассчитывается лишь для трёх расстояний.
        double axial = Math.max(
                0.0, available - AIR_ATTENUATION * LENGTH[1]
        );

        double face = Math.max(
                0.0, available - AIR_ATTENUATION * LENGTH[2]
        );

        double space = Math.max(
                0.0, available - AIR_ATTENUATION * LENGTH[3]
        );

        // Распространение энергии.
        for (int i = 0; i < DIRECTIONS; i++) {

            double weight = weights[i] * invWeight;

            if (weight <= 0.0) {
                continue;
            }

            int cls = LENGTH_CLASS[i];

            double transmitted = weight * (
                    cls == 1 ? axial
                            : cls == 2 ? face : space
            );

            airAbsorbed += weight * available - transmitted;

            if (transmitted <= 0.0) {
                continue;
            }

            if (transmitted < MIN_ACTIVE_ENERGY) {
                culled += transmitted;
                continue;
            }

            int nx = x + DX[i];
            int ny = y + DY[i];
            int nz = z + DZ[i];

            if (ny < 0 || ny >= 256
                    || nx < -30000000 || nx > 30000000
                    || nz < -30000000 || nz > 30000000) {

                escaped += transmitted;
                continue;
            }

            double ox = UX[i];
            double oy = UY[i];
            double oz = UZ[i];

            // Сохранение части предыдущего направления.
            if (k > 0.0) {

                double a = DIRECTION_MEMORY;
                double bb = 1.0 - a;

                double inv = 1.0 / Math.sqrt(
                        a * a + bb * bb
                                + 2.0 * a * bb * dots[i]
                );

                ox = (a * vx + bb * ox) * inv;
                oy = (a * vy + bb * oy) * inv;
                oz = (a * vz + bb * oz) * inv;
            }

            emit(
                    pos,
                    pack(nx, ny, nz),
                    transmitted,
                    transmitted * ox,
                    transmitted * oy,
                    transmitted * oz,
                    meanArrival + LENGTH[cls]
            );
        }

        return true;
    }

    public boolean step(SnapshotManager snapshots) {

        if (awaitingDestructionApply) {
            throw new IllegalStateException(
                    "Apply destroyed blocks before the next step"
            );
        }

        deferredLastStep = 0;
        prefetchedSections.clear();

        // Запрашиваем секции до начала расчёта.
        prefetchPending(snapshots);

        WaveBucket bucket = currentBucket();

        while (nextCell < bucket.cellCount) {

            if (!processCell(bucket, nextCell, snapshots)) {

                // Отсутствует снимок: откладываем только
                // эту клетку, продолжая остальные.
                deferCell(bucket, nextCell);

                deferredLastStep++;
            }

            nextCell++;
        }

        finishTick();

        // Запрашиваем секции для новых передач,
        // созданных только что завершённым шагом.
        prefetchPending(snapshots);

        awaitingDestructionApply = !destroyed.isEmpty();

        return true;
    }

    private void prefetchSection(
            int x, int y, int z,
            SnapshotManager snapshots
    ) {
        if (y < 0 || y >= 256
                || x < -30000000 || x > 30000000
                || z < -30000000 || z > 30000000) {
            return;
        }

        if (prefetchedSections.size()
                >= MAX_PREFETCH_SECTIONS_PER_STEP) {
            return;
        }

        // Уникальный ключ секции 16×16×16.
        long sectionKey = pack(
                x >> 4,
                y >> 4,
                z >> 4
        );

        // Одна проверка на секцию за шаг.
        if (prefetchedSections.add(sectionKey)) {
            snapshots.ensureSectionTracked(x, y, z);
        }
    }

    private void prefetchPending(
            SnapshotManager snapshots
    ) {
        // Сначала текущий тик, затем два будущих.
        for (int offset = 0; offset < RING_SIZE; offset++) {

            WaveBucket bucket = pending[
                    (int) ((currentTick + offset) % RING_SIZE)
                    ];

            for (int c = 0; c < bucket.cellCount; c++) {

                if (prefetchedSections.size()
                        >= MAX_PREFETCH_SECTIONS_PER_STEP) {
                    return;
                }

                long pos = bucket.position[c];

                int x = unpackX(pos);
                int y = unpackY(pos);
                int z = unpackZ(pos);

                // Секция, в которую прибывает волна.
                prefetchSection(x, y, z, snapshots);

                // Секции вокруг неё: понадобятся для проверки
                // препятствий и заблокированных диагоналей.
                for (int i = 0; i < 6; i++) {
                    prefetchSection(
                            x + AX[i],
                            y + AY[i],
                            z + AZ[i],
                            snapshots
                    );
                }
            }
        }
    }

    private void deferCell(WaveBucket bucket, int cell) {

        WaveBucket next = pending[
                (int) ((currentTick + 1) % RING_SIZE)
                ];

        long target = bucket.position[cell];

        // Переносим все входящие передачи по отдельности.
        for (int link = bucket.firstLink[cell];
             link != -1;
             link = bucket.nextLink[link]) {

            double energy = bucket.linkEnergy[link];

            if (energy <= 0.0) {
                continue;
            }

            double arrival =
                    bucket.linkArrivalSum[link] / energy + 1.0;

            next.add(
                    bucket.linkSource[link],
                    target,
                    energy,
                    bucket.linkMomentX[link],
                    bucket.linkMomentY[link],
                    bucket.linkMomentZ[link],
                    arrival
            );
        }
    }

}
