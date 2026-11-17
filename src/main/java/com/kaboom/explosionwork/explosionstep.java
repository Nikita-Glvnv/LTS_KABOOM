package com.kaboom.explosionwork;

import java.util.Arrays;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

public class explosionstep {
    private static final double MIN_ACTIVE_ENERGY = 1e-5;
    private static final double AIR_ATTENUATION = 0.15;
    private static final double WEAK_WAVE_ENERGY_SCALE = 10.0;
    private static final double WEAK_WAVE_FADE_SCALE = 0.1;
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

    private static final double[] ANGULAR_WEIGHT = {
            0.0,
            1.0 / 21.0,
            4.0 / 105.0,
            9.0 / 280.0
    };

    // Секции, для которых уже проверена готовность в этом шаге.
    private final LongOpenHashSet prefetchedSections =
            new LongOpenHashSet();

    // Количество клеток, отложенных на последнем шаге.
    private int deferredLastStep;

    public int getDeferredLastStep() {
        return deferredLastStep;
    }

    private static final int MIN_MACRO_HOPS = 2;
    private static final int MAX_MACRO_HOPS = 12;
    private static final double MIN_MACRO_ENERGY = 0.5;
    private static final double MIN_MACRO_COHERENCE = 0.94;

    private final oktoderevo.AirRegion macroRegion =
            new oktoderevo.AirRegion();

    private final int[] macroX = new int[7];
    private final int[] macroY = new int[7];
    private final int[] macroZ = new int[7];

    private final Long2ObjectOpenHashMap<WaveBucket> distantPending =
            new Long2ObjectOpenHashMap<>();

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

    private static double materialLossFactor(double resistance) {
        double r = Math.max(0.0, resistance);

        double q = 0.3 / r;
        double q2 = q * q;
        double q4 = q2 * q2;

        return 1.0 / (1.0 + q4 * q4 * q2);
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

    private long macroAttemptsLastStep;
    private long macroTransfersLastStep;
    private long macroHopsLastStep;
    private long macroEightLastStep;
    private long macroSixteenLastStep;

    public long getMacroAttemptsLastStep() {
        return macroAttemptsLastStep;
    }

    public long getMacroTransfersLastStep() {
        return macroTransfersLastStep;
    }

    public long getMacroHopsLastStep() {
        return macroHopsLastStep;
    }

    public long getMacroEightLastStep() {
        return macroEightLastStep;
    }

    public long getMacroSixteenLastStep() {
        return macroSixteenLastStep;
    }

    private final Long2ObjectOpenHashMap<WaveBucket> waitingBySection =
            new Long2ObjectOpenHashMap<>();

    private long missingSectionKey;
    private int waitingCellCount;
    private long lastWaitingRefreshNanos;

    private int cellsLastStep;

    public int getCellsLastStep() {
        return cellsLastStep;
    }

    public int getWaitingCellCount() {
        return waitingCellCount;
    }

    public int getWaitingSectionCount() {
        return waitingBySection.size();
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

    private boolean loadAxisResistance(
            int x, int y, int z,
            SnapshotManager snapshots
    ) {
        for (int i = 0; i < 6; i++) {

            int ax = x + AX[i];
            int ay = y + AY[i];
            int az = z + AZ[i];

            if (ay < 0 || ay >= 256
                    || ax < -30000000 || ax > 30000000
                    || az < -30000000 || az > 30000000) {

                axisResistance[i] = 0.0;
                continue;
            }

            long pos = pack(ax, ay, az);

            float resistance = materialResistanceCache.get(pos);

            if (Float.isNaN(resistance)) {

                if (!snapshots.getBlock(ax, ay, az, block)) {
                    missingSectionKey = pack(
                            ax >> 4,
                            ay >> 4,
                            az >> 4
                    );
                    return false;
                }

                boolean solid = block.type == oktoderevo.BLOCK;

                solidCache.put(pos, (byte) (solid ? 1 : 0));

                if (!solid) {
                    resistance = 0.0f;
                } else if (block.hardness < 0.0f) {
                    resistance = Float.POSITIVE_INFINITY;
                } else {
                    resistance = Math.max(0.0f, block.resistance);

                    if (Float.isNaN(resistance)) {
                        resistance = Float.POSITIVE_INFINITY;
                    } else {
                        resistance *= materialLossFactor(resistance);
                    }
                }

                materialResistanceCache.put(pos, resistance);
            }

            axisResistance[i] = resistance;
        }

        return true;
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
        materialResistanceCache.clear();
        nextCell = 0;
        currentTick++;
    }

    public boolean hasPending() {
        if (!waitingBySection.isEmpty()) {
            return true;
        }

        if (!distantPending.isEmpty()) {
            return true;
        }
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

    private final Long2FloatOpenHashMap materialResistanceCache =
            new Long2FloatOpenHashMap();

    private final double[] axisResistance = new double[6];

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
        materialResistanceCache.defaultReturnValue(Float.NaN);
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
                missingSectionKey = pack(
                        x >> 4,
                        y >> 4,
                        z >> 4
                );
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

            double destructionThreshold =
                    Math.max(0.0, resistance)
                            * DESTRUCTION_MULTIPLIER;

            spent = destructionThreshold
                    * materialLossFactor(resistance);

            // Блок выдерживает удар.
            if (h < 0.0
                    || incident * (1.0 - reflect) < destructionThreshold) {

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

        if (available > 0.0
                && !loadAxisResistance(x, y, z, snapshots)) {

            return false;
        }

        if (wall) {
            absorbed += spent;

            // Только регистрируем разрушение.
            // Состояние материала текущего тика не меняем.
            destroyed.add(pos);
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

            double dot =
                    vx * UX[i] +
                            vy * UY[i] +
                            vz * UZ[i];

            dots[i] = dot;

            double w = ANGULAR_WEIGHT[LENGTH_CLASS[i]] *
                    (diffuse
                            + (dot > 0.0
                            ? k * Math.pow(dot, LOBE_POWER)
                            : 0.0));

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

        double axial = airAfterDistance(available, LENGTH[1]);
        double face = airAfterDistance(available, LENGTH[2]);
        double space = airAfterDistance(available, LENGTH[3]);

        // Распространение энергии.
        for (int i = 0; i < DIRECTIONS; i++) {

            double weight = weights[i] * invWeight;

            if (weight <= 0.0) {
                continue;
            }

            int cls = LENGTH_CLASS[i];

            double afterAir = weight * (
                    cls == 1 ? axial
                            : cls == 2 ? face : space
            );

            airAbsorbed += weight * available - afterAir;

// Дополнительное сопротивление материала
// на диагональном участке траектории.
            double extraResistance = diagonalResistance(i);

            double transmitted;

            if (extraResistance == 0.0) {
                transmitted = afterAir;
            } else if (afterAir <= 0.0) {
                transmitted = 0.0;
            } else {
                transmitted = afterAir * Math.exp(
                        -weight * extraResistance / afterAir
                );
            }

// Потери на прохождение промежуточного материала.
            absorbed += afterAir - transmitted;

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
            if (k >= MIN_MACRO_COHERENCE
                    && weight >= 0.03
                    && tryMacroEmit(
                    nx, ny, nz,
                    i,
                    transmitted,
                    ox, oy, oz,
                    meanArrival + LENGTH[cls],
                    k,
                    snapshots
            )) {
                continue;
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

        activateDistant();

        macroAttemptsLastStep = 0;
        macroTransfersLastStep = 0;
        macroHopsLastStep = 0;
        macroEightLastStep = 0;
        macroSixteenLastStep = 0;

        deferredLastStep = 0;
        cellsLastStep = 0;
        prefetchedSections.clear();

        resumeWaitingCells(snapshots);

        prefetchPending(snapshots);
        // Запрашиваем секции до начала расчёта.

        WaveBucket bucket = currentBucket();

        while (nextCell < bucket.cellCount) {

            if (!processCell(bucket, nextCell, snapshots)) {

                // Отсутствует снимок: откладываем только
                // эту клетку, продолжая остальные.
                parkCell(bucket, nextCell);

                deferredLastStep++;
            }

            nextCell++;
        }
        cellsLastStep = bucket.cellCount;
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

    private void parkCell(WaveBucket source, int cell) {
        WaveBucket waiting = waitingBySection.get(missingSectionKey);

        if (waiting == null) {
            waiting = new WaveBucket();
            waitingBySection.put(missingSectionKey, waiting);
        }

        long target = source.position[cell];
        int previousCount = waiting.cellCount;

        for (int link = source.firstLink[cell];
             link != -1;
             link = source.nextLink[link]) {

            double energy = source.linkEnergy[link];

            if (energy <= 0.0) {
                continue;
            }

            double relativeArrival =
                    source.linkArrivalSum[link] / energy - currentTick;

            waiting.add(
                    source.linkSource[link],
                    target,
                    energy,
                    source.linkMomentX[link],
                    source.linkMomentY[link],
                    source.linkMomentZ[link],
                    relativeArrival
            );
        }

        waitingCellCount += waiting.cellCount - previousCount;
    }

    public int getPrefetchedSectionsLastStep() {
        return prefetchedSections.size();
    }

    public int getPendingCellCount() {
        int count = waitingCellCount;

        for (WaveBucket bucket : pending) {
            count += bucket.cellCount;
        }

        for (WaveBucket bucket : distantPending.values()) {
            count += bucket.cellCount;
        }

        return count;
    }

    private double diagonalResistance(int direction) {

        int axes = LENGTH_CLASS[direction];

        if (axes == 1) {
            return 0.0;
        }

        double resistance = 0.0;

        if (DX[direction] != 0) {
            resistance += axisResistance[
                    DX[direction] > 0 ? 0 : 1
                    ];
        }

        if (DY[direction] != 0) {
            resistance += axisResistance[
                    DY[direction] > 0 ? 2 : 3
                    ];
        }

        if (DZ[direction] != 0) {
            resistance += axisResistance[
                    DZ[direction] > 0 ? 4 : 5
                    ];
        }

        double averageResistance = resistance / axes;

        return averageResistance * (LENGTH[axes] - 1.0);
    }

    private void resumeWaitingCells(SnapshotManager snapshots) {
        if (waitingBySection.isEmpty()) {
            return;
        }

        long now = System.nanoTime();

        boolean refresh = lastWaitingRefreshNanos == 0
                || now - lastWaitingRefreshNanos >= 1_000_000_000L;

        if (refresh) {
            lastWaitingRefreshNanos = now;
        }

        LongArrayList readySections = new LongArrayList();

        for (Long2ObjectMap.Entry<WaveBucket> entry
                : waitingBySection.long2ObjectEntrySet()) {

            long key = entry.getLongKey();

            int x = unpackX(key) << 4;
            int y = unpackY(key) << 4;
            int z = unpackZ(key) << 4;

            if (snapshots.isSectionReady(x, y, z)) {
                readySections.add(key);
            } else if (refresh) {
                snapshots.ensureSectionTracked(x, y, z);
            }
        }

        WaveBucket active = currentBucket();

        for (int i = 0; i < readySections.size(); i++) {
            long key = readySections.getLong(i);
            WaveBucket waiting = waitingBySection.remove(key);

            if (waiting == null) {
                continue;
            }

            waitingCellCount -= waiting.cellCount;

            for (int c = 0; c < waiting.cellCount; c++) {
                long target = waiting.position[c];

                for (int link = waiting.firstLink[c];
                     link != -1;
                     link = waiting.nextLink[link]) {

                    double energy = waiting.linkEnergy[link];

                    if (energy <= 0.0) {
                        continue;
                    }

                    active.add(
                            waiting.linkSource[link],
                            target,
                            energy,
                            waiting.linkMomentX[link],
                            waiting.linkMomentY[link],
                            waiting.linkMomentZ[link],
                            currentTick
                                    + waiting.linkArrivalSum[link] / energy
                    );
                }
            }
        }
    }

    private void emitAt(
            long source, long target,
            double energy,
            double mx, double my, double mz,
            double arrival
    ) {
        if (energy <= 0.0) {
            return;
        }

        if (energy < MIN_ACTIVE_ENERGY) {
            culled += energy;
            return;
        }

        long tick = (long) Math.floor(arrival);

        if (tick < currentTick) {
            throw new IllegalStateException("Past wave arrival");
        }

        WaveBucket destination;

        if (tick < currentTick + RING_SIZE) {
            destination = pending[(int) (tick % RING_SIZE)];
        } else {
            destination = distantPending.get(tick);

            if (destination == null) {
                destination = new WaveBucket();
                distantPending.put(tick, destination);
            }
        }

        destination.add(
                source, target,
                energy, mx, my, mz, arrival
        );
    }

    private void activateDistant() {
        WaveBucket ready = distantPending.remove(currentTick);

        if (ready == null) {
            return;
        }

        WaveBucket active = currentBucket();

        for (int c = 0; c < ready.cellCount; c++) {
            long target = ready.position[c];

            for (int link = ready.firstLink[c];
                 link != -1;
                 link = ready.nextLink[link]) {

                double energy = ready.linkEnergy[link];

                if (energy <= 0.0) {
                    continue;
                }

                active.add(
                        ready.linkSource[link],
                        target,
                        energy,
                        ready.linkMomentX[link],
                        ready.linkMomentY[link],
                        ready.linkMomentZ[link],
                        ready.linkArrivalSum[link] / energy
                );
            }
        }
    }

    private static double airAfterDistance(
            double energy,
            double distance
    ) {
        if (energy <= 0.0) {
            return 0.0;
        }

        double e2 = energy * energy;

        double attenuationEnergy = energy
                + WEAK_WAVE_ENERGY_SCALE
                * WEAK_WAVE_ENERGY_SCALE * e2
                / ((energy + WEAK_WAVE_ENERGY_SCALE)
                * (e2 + WEAK_WAVE_FADE_SCALE
                * WEAK_WAVE_FADE_SCALE));

        return energy * Math.exp(
                -AIR_ATTENUATION * distance / attenuationEnergy
        );
    }

    private boolean tryMacroEmit(
            int x, int y, int z,
            int direction,
            double energy,
            double vx, double vy, double vz,
            double arrival,
            double coherence,
            SnapshotManager snapshots
    ) {
        if (energy < MIN_MACRO_ENERGY) {
            return false;
        }

        macroAttemptsLastStep++;

        if (!snapshots.getAirRegionIfReady(x, y, z, macroRegion)
                || macroRegion.size < 8) {
            return false;
        }

        double sum = 0.0;
        double meanX = 0.0, meanY = 0.0, meanZ = 0.0;
        double secondX = 0.0, secondY = 0.0, secondZ = 0.0;
        double meanLength = 0.0;

        for (int i = 0; i < DIRECTIONS; i++) {
            double dot = vx * UX[i]
                    + vy * UY[i]
                    + vz * UZ[i];

            double w = ANGULAR_WEIGHT[LENGTH_CLASS[i]]
                    * ((1.0 - coherence) / DIRECTIONS
                    + (dot > 0.0
                    ? coherence * Math.pow(dot, LOBE_POWER)
                    : 0.0));

            sum += w;

            meanX += w * DX[i];
            meanY += w * DY[i];
            meanZ += w * DZ[i];

            secondX += w * DX[i] * DX[i];
            secondY += w * DY[i] * DY[i];
            secondZ += w * DZ[i] * DZ[i];

            meanLength += w * LENGTH[LENGTH_CLASS[i]];
        }

        if (sum <= 0.0) {
            return false;
        }

        meanX /= sum;
        meanY /= sum;
        meanZ /= sum;
        meanLength /= sum;

        double varX = Math.max(0.0, secondX / sum - meanX * meanX);
        double varY = Math.max(0.0, secondY / sum - meanY * meanY);
        double varZ = Math.max(0.0, secondZ / sum - meanZ * meanZ);

        int chosenHops = 0;

        int maxHops = Math.min(
                MAX_MACRO_HOPS,
                macroRegion.size - 2
        );

        for (int hops = maxHops;
             hops >= MIN_MACRO_HOPS; hops--) {

            int cx = x + (int) Math.round(hops * meanX);
            int cy = y + (int) Math.round(hops * meanY);
            int cz = z + (int) Math.round(hops * meanZ);

            int rx = (int) Math.round(
                    2.0 * Math.sqrt(hops * varX));
            int ry = (int) Math.round(
                    2.0 * Math.sqrt(hops * varY));
            int rz = (int) Math.round(
                    2.0 * Math.sqrt(hops * varZ));

            boolean valid = true;

            for (int j = 0; j < 7; j++) {
                macroX[j] = cx
                        + (j == 1 ? rx : j == 2 ? -rx : 0);
                macroY[j] = cy
                        + (j == 3 ? ry : j == 4 ? -ry : 0);
                macroZ[j] = cz
                        + (j == 5 ? rz : j == 6 ? -rz : 0);

                if (macroX[j] <= macroRegion.x
                        || macroY[j] <= macroRegion.y
                        || macroZ[j] <= macroRegion.z
                        || macroX[j] >= macroRegion.x + macroRegion.size - 1
                        || macroY[j] >= macroRegion.y + macroRegion.size - 1
                        || macroZ[j] >= macroRegion.z + macroRegion.size - 1) {
                    valid = false;
                    break;
                }
            }

            if (valid) {
                chosenHops = hops;
                break;
            }
        }

        if (chosenHops == 0) {
            return false;
        }

        macroTransfersLastStep++;
        macroHopsLastStep += chosenHops;

        if (macroRegion.size == 8) {
            macroEightLastStep++;
        } else if (macroRegion.size == 16) {
            macroSixteenLastStep++;
        }

        // Воздушное затухание на пропущенных шагах.
        double remaining = energy;

        for (int i = 0; i < chosenHops; i++) {
            double after = airAfterDistance(
                    remaining, meanLength);

            airAbsorbed += remaining - after;
            remaining = after;

            if (remaining < MIN_ACTIVE_ENERGY) {
                culled += remaining;
                return true;
            }
        }

        double exitArrival =
                arrival + chosenHops * meanLength;

        // Консервативное распределение энергии по 7 точкам.
        for (int j = 0; j < 7; j++) {
            double fraction = j == 0 ? 0.25 : 0.125;
            double e = remaining * fraction;

            long target = pack(
                    macroX[j], macroY[j], macroZ[j]);

            // Виртуальный источник остаётся соседней клеткой.
            long source = pack(
                    macroX[j] - DX[direction],
                    macroY[j] - DY[direction],
                    macroZ[j] - DZ[direction]);

            emitAt(
                    source, target,
                    e,
                    e * vx,
                    e * vy,
                    e * vz,
                    exitArrival
            );
        }

        return true;
    }

}
