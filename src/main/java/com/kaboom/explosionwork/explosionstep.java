package com.kaboom.explosionwork;

import java.util.Arrays;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.util.Iterator;

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
    private static final double LIQUID_MIN_ABSORPTION = 1.0;
    private static final double LIQUID_ABSORPTION_FRACTION = 0.20;
    private static final double LIQUID_DESTRUCTION_THRESHOLD = 100.0;
    private static final double LIQUID_DIRECTION_FACTOR = 0.5;

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

    private final oktoderevo.AirRegion cachedAirRegion =
            new oktoderevo.AirRegion();

    private static final boolean ENABLE_ADAPTIVE_CUBES = true;

    private final Long2ObjectOpenHashMap<AdaptiveCube> macroEight =
            new Long2ObjectOpenHashMap<>();

    private final Long2ObjectOpenHashMap<AdaptiveCube> macroSixteen =
            new Long2ObjectOpenHashMap<>();

    private long macroReceivedCells;

    public long getMacroReceivedCells() {
        return macroReceivedCells;
    }

    public int getActiveMacroCubeCount() {
        return macroEight.size() + macroSixteen.size();
    }

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

    private final AdaptiveCube.Diagnostics adaptiveDiagnostics =
            new AdaptiveCube.Diagnostics();

    private long adaptiveCubeCalls;
    private long adaptive8Calls;
    private long adaptive16Calls;

    private long adaptiveCubeTimeNs;
    private long adaptive8TimeNs;
    private long adaptive16TimeNs;

    private long adaptiveMaxCubeNs;

    private int adaptiveHotX;
    private int adaptiveHotY;
    private int adaptiveHotZ;
    private int adaptiveHotSize;

    private long adaptivePendingExitsThisStep;
    private long adaptivePendingPacketsThisStep;

    private long adaptiveMaxPendingExits;
    private long adaptiveMaxPendingPackets;

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
        private static final int DIRECT_INDEX_THRESHOLD = 6;
        private static final int SOURCE_SLOTS = 27;

        private static final int DENSE_THRESHOLD = 256;
        private static final int MAX_REUSED_SECTIONS = 128;

        private final Long2ObjectOpenHashMap<SectionCells> sections =
                new Long2ObjectOpenHashMap<>();

        private final ArrayDeque<SectionCells> reusableSections =
                new ArrayDeque<>();

        // Кеш последней секции для последовательных обращений.
        private long cachedSectionKey;
        private SectionCells cachedSection;

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

        private int[] directOffset = new int[64];
        private int[] directLinks = new int[SOURCE_SLOTS * 8];
        private int directLinksUsed;

        // Входящие передачи.
        long[] linkSource = new long[128];

        double[] linkEnergy = new double[128];
        double[] linkMomentX = new double[128];
        double[] linkMomentY = new double[128];
        double[] linkMomentZ = new double[128];
        double[] linkArrivalSum = new double[128];

        // Связь передач, приходящих в одну клетку.
        int[] nextLink = new int[128];

        private static final class SectionCells {

            private final Int2IntOpenHashMap sparse =
                    new Int2IntOpenHashMap();

            private int[] dense;
            private int sparseCount;

            SectionCells() {
                sparse.defaultReturnValue(0);
            }

            int get(int localIndex) {
                return dense != null
                        ? dense[localIndex]
                        : sparse.get(localIndex);
            }

            void put(int localIndex, int cellIndex) {

                if (dense != null) {
                    dense[localIndex] = cellIndex;
                    return;
                }

                sparse.put(localIndex, cellIndex);
                sparseCount++;

                // Переключаемся на прямую индексацию.
                if (sparseCount >= DENSE_THRESHOLD) {

                    dense = new int[4096];

                    for (Int2IntMap.Entry entry
                            : sparse.int2IntEntrySet()) {

                        dense[entry.getIntKey()] =
                                entry.getIntValue();
                    }

                    sparse.clear();
                }
            }

            void reset() {
                sparseCount = 0;

                if (dense != null) {
                    Arrays.fill(dense, 0);
                } else {
                    sparse.clear();
                }
            }
        }

        void add(long source, long target, double e,
                 double mx, double my, double mz, double arrival) {

            int x = unpackX(target);
            int y = unpackY(target);
            int z = unpackZ(target);

// Координаты секции.
            long sectionKey = pack(
                    x >> 4,
                    y >> 4,
                    z >> 4
            );

// Индекс блока внутри секции 16×16×16.
            int localIndex =
                    ((y & 15) << 8)
                            | ((z & 15) << 4)
                            | (x & 15);

// Получаем индекс секции.
            SectionCells section;

            if (cachedSection != null
                    && cachedSectionKey == sectionKey) {

                section = cachedSection;

            } else {

                section = sections.get(sectionKey);

                if (section == null) {

                    section = reusableSections.pollFirst();

                    if (section == null) {
                        section = new SectionCells();
                    }

                    sections.put(sectionKey, section);
                }

                cachedSectionKey = sectionKey;
                cachedSection = section;
            }

// Индекс клетки, 0 означает отсутствие.
            int c = section.get(localIndex) - 1;

            if (c == -1) {

                growCells();

                c = cellCount++;

                section.put(localIndex, c + 1);

                position[c] = target;

                energy[c] = 0.0;
                momentX[c] = 0.0;
                momentY[c] = 0.0;
                momentZ[c] = 0.0;
                arrivalSum[c] = 0.0;

                firstLink[c] = -1;
                directOffset[c] = -1;
            }

            // Суммарные данные клетки.
            energy[c] += e;
            momentX[c] += mx;
            momentY[c] += my;
            momentZ[c] += mz;
            arrivalSum[c] += e * arrival;

            // Проверяем, была ли передача из этого источника.
            int offset = directOffset[c];
            int slot = -1;
            int foundLink = -1;
            int previousLinks = 0;

            if (offset >= 0) {
                slot = sourceSlot(source, target);

                if (slot >= 0) {
                    int indexed = directLinks[offset + slot];
                    if (indexed != 0) {
                        foundLink = indexed - 1;
                    }
                } else {
                    for (int link = firstLink[c];
                         link != -1;
                         link = nextLink[link]) {

                        if (linkSource[link] == source) {
                            foundLink = link;
                            break;
                        }
                    }
                }
            } else {
                for (int link = firstLink[c];
                     link != -1;
                     link = nextLink[link]) {

                    previousLinks++;

                    if (linkSource[link] == source) {
                        foundLink = link;
                        break;
                    }
                }
            }

            if (foundLink != -1) {
                linkEnergy[foundLink] += e;
                linkMomentX[foundLink] += mx;
                linkMomentY[foundLink] += my;
                linkMomentZ[foundLink] += mz;
                linkArrivalSum[foundLink] += e * arrival;
                return;
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
            if (offset >= 0) {
                if (slot >= 0) {
                    directLinks[offset + slot] = link + 1;
                }
            } else if (previousLinks + 1 >= DIRECT_INDEX_THRESHOLD) {
                buildDirectIndex(c);
            }
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
            directOffset = Arrays.copyOf(directOffset, n);
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

            // Возвращаем индексы секций в пул.
            for (SectionCells section : sections.values()) {

                if (reusableSections.size() < MAX_REUSED_SECTIONS) {

                    section.reset();
                    reusableSections.addLast(section);
                }
            }

            sections.clear();

// Сбрасываем кеш последней секции.
            cachedSection = null;
            cachedSectionKey = 0L;

            cellCount = 0;
            linkCount = 0;

            directLinksUsed = 0;
        }

        private static int sourceSlot(long source, long target) {
            int dx = unpackX(source) - unpackX(target);
            int dy = unpackY(source) - unpackY(target);
            int dz = unpackZ(source) - unpackZ(target);

            if (dx < -1 || dx > 1
                    || dy < -1 || dy > 1
                    || dz < -1 || dz > 1) {
                return -1;
            }

            return (dx + 1) * 9 + (dy + 1) * 3 + (dz + 1);
        }

        private void buildDirectIndex(int c) {
            int offset = directLinksUsed;
            int end = offset + SOURCE_SLOTS;

            if (end > directLinks.length) {
                directLinks = Arrays.copyOf(
                        directLinks,
                        Math.max(end, directLinks.length * 2)
                );
            }

            Arrays.fill(directLinks, offset, end, 0);

            for (int link = firstLink[c];
                 link != -1;
                 link = nextLink[link]) {

                int slot = sourceSlot(linkSource[link], position[c]);

                if (slot >= 0) {
                    directLinks[offset + slot] = link + 1;
                }
            }

            directOffset[c] = offset;
            directLinksUsed = end;
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
            boolean wall,
            int axisMask,
            SnapshotManager snapshots
    ) {
        // Ищем воздушную область только для нетвёрдых клеток.
        if (!wall && !isCachedAir(x, y, z)) {
            if (!snapshots.getAirRegionIfReady(
                    x, y, z, cachedAirRegion
            )) {
                cachedAirRegion.size = 0;
            }
        }

// Все шесть соседей гарантированно воздушные.
        if (isAirInterior(x, y, z)) {
            Arrays.fill(axisResistance, 0.0);
            return true;
        }
        for (int i = 0; i < 6; i++) {
            if ((axisMask & (1 << i)) == 0) {
                continue;
            }

            int ax = x + AX[i];
            int ay = y + AY[i];
            int az = z + AZ[i];

            if (ay < 0 || ay >= 256
                    || ax < -30000000 || ax > 30000000
                    || az < -30000000 || az > 30000000) {

                axisResistance[i] = 0.0;
                continue;
            }

            // Сосед уже находится в известной воздушной области.
            if (isCachedAir(ax, ay, az)) {
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

                cellTypeCache.put(pos, block.type);

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
        cachedAirRegion.size = 0;
        materialResistanceCache.clear();
        nextCell = 0;
        currentTick++;
    }

    public boolean hasPending() {
        if (!macroEight.isEmpty() || !macroSixteen.isEmpty()) {
            return true;
        }

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

        if (ENABLE_ADAPTIVE_CUBES) {

            // Находим самую большую полностью
            // воздушную область, содержащую ячейку.
            if (!isCachedAir(x, y, z)) {
                if (!snapshots.getAirRegionIfReady(
                        x, y, z, cachedAirRegion
                )) {
                    cachedAirRegion.size = 0;
                }
            }

            int cubeSize = cachedAirRegion.size;

            // Малые области обрабатываются обычной физикой.
            if (cubeSize == 8 || cubeSize == 16) {

                Long2ObjectOpenHashMap<AdaptiveCube> cubes =
                        cubeSize == 16
                                ? macroSixteen
                                : macroEight;

                long key = pack(
                        cachedAirRegion.x,
                        cachedAirRegion.y,
                        cachedAirRegion.z
                );

                AdaptiveCube cube = cubes.get(key);

                if (cube == null) {
                    cube = new AdaptiveCube(cachedAirRegion);
                    cubes.put(key, cube);
                }

                double arrival = b.arrivalSum[c] / incident;

                cube.receive(
                        x, y, z,
                        incident,
                        b.momentX[c],
                        b.momentY[c],
                        b.momentZ[c],
                        arrival
                );

                macroReceivedCells++;

                // Энергия передана макрокубу.
                // Обычная физика эту ячейку
                // повторно не обрабатывает.
                return true;
            }
        }

        boolean wall;
        boolean liquid;

        double h = 0.0;
        double resistance = 0.0;

        byte cachedType = cellTypeCache.get(pos);

        if (isCachedAir(x, y, z)
                || cachedType == oktoderevo.AIR) {

            wall = false;
            liquid = false;

        } else if (cachedType == oktoderevo.LIQUID) {

            wall = false;
            liquid = true;

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
            liquid = block.type == oktoderevo.LIQUID;

            cellTypeCache.put(pos, block.type);

            if (wall) {
                h = block.hardness;
                resistance = block.resistance;
            }
        }
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

                    int distanceSquared =
                            dx * dx + dy * dy + dz * dz;

                    double returnDistance =
                            LENGTH[Math.max(1, distanceSquared)];

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

        boolean liquidDestroyed = false;

        if (liquid) {

            double absorption = Math.min(
                    incident,
                    LIQUID_MIN_ABSORPTION
                            + incident * LIQUID_ABSORPTION_FRACTION
            );

            if (absorption > LIQUID_DESTRUCTION_THRESHOLD) {

                liquidDestroyed = true;
                spent = LIQUID_DESTRUCTION_THRESHOLD;

            } else {

                spent = absorption;
            }
        }

        // Блок либо отсутствует, либо будет разрушен.
        double available = incident - spent;

        if (available <= 0.0) {
            recordMaterialInteraction(
                    pos,
                    spent,
                    wall || liquidDestroyed
            );
            return true;
        }

// Затухание на минимальном расстоянии.
        double attenuation = attenuationEnergy(available);

        double axial = available * Math.exp(
                -AIR_ATTENUATION * LENGTH[1] / attenuation
        );

// Даже максимальная возможная передача
// не достигает минимальной активной энергии.
        if (axial < MIN_ACTIVE_ENERGY) {

            recordMaterialInteraction(
                    pos,
                    spent,
                    wall || liquidDestroyed
            );

            culled += available;
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

        if (liquid && !liquidDestroyed) {
            k *= LIQUID_DIRECTION_FACTOR;
        }

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
            recordMaterialInteraction(
                    pos,
                    spent,
                    wall || liquidDestroyed
            );

            absorbed += available;
            return true;
        }

        double meanArrival = b.arrivalSum[c] / incident;
        double invWeight = 1.0 / totalWeight;

        // Затухание рассчитывается лишь для трёх расстояний.

        double face = available * Math.exp(
                -AIR_ATTENUATION * LENGTH[2] / attenuation
        );

        double space = available * Math.exp(
                -AIR_ATTENUATION * LENGTH[3] / attenuation
        );

// Определяем, сопротивления каких соседей
// действительно понадобятся.
        int axisMask = 0;

        for (int i = 0; i < DIRECTIONS; i++) {

            int cls = LENGTH_CLASS[i];

            // Осевые направления не требуют
            // дополнительного сопротивления.
            if (cls == 1) {
                continue;
            }

            double weight = weights[i] * invWeight;

            if (weight <= 0.0) {
                continue;
            }

            double air = cls == 2 ? face : space;
            double afterAir = weight * air;

            // Направление гарантированно не создаст
            // активную передачу.
            if (afterAir < MIN_ACTIVE_ENERGY) {
                continue;
            }

            if (DX[i] != 0) {
                axisMask |= DX[i] > 0 ? 1 : 2;
            }

            if (DY[i] != 0) {
                axisMask |= DY[i] > 0 ? 4 : 8;
            }

            if (DZ[i] != 0) {
                axisMask |= DZ[i] > 0 ? 16 : 32;
            }
        }

// Загружаем только необходимые сопротивления.
// При отсутствии снимка откладываем всю клетку,
// прежде чем она создаст какие-либо передачи.
        if (axisMask != 0
                && !loadAxisResistance(
                x, y, z, wall, axisMask, snapshots
        )) {
            return false;
        }

        recordMaterialInteraction(
                pos,
                spent,
                wall || liquidDestroyed
        );

        // Распространение энергии.
        for (int i = 0; i < DIRECTIONS; i++) {

            double weight = weights[i] * invWeight;

            if (weight <= 0.0) {
                continue;
            }

            int cls = LENGTH_CLASS[i];
            double air = cls == 1 ? axial
                    : cls == 2 ? face : space;

            double afterAir = weight * air;

            airAbsorbed += weight * available - afterAir;

            // После затухания в воздухе энергии уже
// недостаточно для создания передачи.
            if (afterAir < MIN_ACTIVE_ENERGY) {
                culled += afterAir;
                continue;
            }

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
                        -extraResistance / air
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

                double a = liquid && !liquidDestroyed
                        ? DIRECTION_MEMORY * LIQUID_DIRECTION_FACTOR
                        : DIRECTION_MEMORY;
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

        activateDistant();

        // Сначала выпускаем готовые передачи
// из макрокубов в текущий WaveBucket.
        if (ENABLE_ADAPTIVE_CUBES) {
            adaptivePendingExitsThisStep = 0;
            adaptivePendingPacketsThisStep = 0;
            processMacroCubes(macroEight);
            processMacroCubes(macroSixteen);

            adaptiveMaxPendingExits = Math.max(
                    adaptiveMaxPendingExits,
                    adaptivePendingExitsThisStep
            );

            adaptiveMaxPendingPackets = Math.max(
                    adaptiveMaxPendingPackets,
                    adaptivePendingPacketsThisStep
            );

        }

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

    private boolean isCachedAir(int x, int y, int z) {
        int size = cachedAirRegion.size;

        return size > 0
                && x >= cachedAirRegion.x
                && x < cachedAirRegion.x + size
                && y >= cachedAirRegion.y
                && y < cachedAirRegion.y + size
                && z >= cachedAirRegion.z
                && z < cachedAirRegion.z + size;
    }

    private boolean isAirInterior(int x, int y, int z) {
        int size = cachedAirRegion.size;

        return size >= 3
                && x > cachedAirRegion.x
                && x < cachedAirRegion.x + size - 1
                && y > cachedAirRegion.y
                && y < cachedAirRegion.y + size - 1
                && z > cachedAirRegion.z
                && z < cachedAirRegion.z + size - 1;
    }

    private static double attenuationEnergy(double energy) {
        double e2 = energy * energy;

        return energy
                + WEAK_WAVE_ENERGY_SCALE
                * WEAK_WAVE_ENERGY_SCALE * e2
                / ((energy + WEAK_WAVE_ENERGY_SCALE)
                * (e2 + WEAK_WAVE_FADE_SCALE
                * WEAK_WAVE_FADE_SCALE));
    }

    private void processMacroCubes(
            Long2ObjectOpenHashMap<AdaptiveCube> cubes
    ) {
        Iterator<Long2ObjectMap.Entry<AdaptiveCube>> iterator =
                cubes.long2ObjectEntrySet().iterator();

        while (iterator.hasNext()) {

            AdaptiveCube cube = iterator.next().getValue();

            // Передаём готовую энергию обратно
            // в обычную очередь физических волн.
            long startNs = System.nanoTime();

            cube.process(currentTick, this::emitAt);

            long durationNs = System.nanoTime() - startNs;

// Общее время обработки макрокубов.
            adaptiveCubeCalls++;
            adaptiveCubeTimeNs += durationNs;

// Раздельная статистика по размерам.
            if (cube.getSize() == 8) {
                adaptive8Calls++;
                adaptive8TimeNs += durationNs;
            } else {
                adaptive16Calls++;
                adaptive16TimeNs += durationNs;
            }

// Поиск самого дорогого куба.
            if (durationNs > adaptiveMaxCubeNs) {
                adaptiveMaxCubeNs = durationNs;

                adaptiveHotX = cube.getX();
                adaptiveHotY = cube.getY();
                adaptiveHotZ = cube.getZ();
                adaptiveHotSize = cube.getSize();
            }

// Получаем статистику внутреннего расчёта.
            cube.drainDiagnostics(adaptiveDiagnostics);

// Существующий учёт энергетических потерь.
            airAbsorbed += cube.takeAirLoss();
            escaped += cube.takeEscapedEnergy();
            culled += cube.takeCulledEnergy();

// Удаление завершённого куба.
            if (!cube.hasPending()) {
                iterator.remove();
            } else {
                adaptivePendingPacketsThisStep += cube.getPacketCount();

                adaptivePendingExitsThisStep +=
                        cube.getPendingExitCount();
            }
        }
    }

    public String drainAdaptiveReport() {

        if (adaptiveCubeCalls == 0
                && adaptiveDiagnostics.received == 0
                && getActiveMacroCubeCount() == 0) {
            return null;
        }

        long otherNs = Math.max(
                0L,
                adaptiveCubeTimeNs - adaptiveDiagnostics.prepareNs
        );

        String report =
                "calls=" + adaptiveCubeCalls
                        + ", calls8=" + adaptive8Calls
                        + ", calls16=" + adaptive16Calls

                        + ", timeUs=" + adaptiveCubeTimeNs / 1000L
                        + ", time8Us=" + adaptive8TimeNs / 1000L
                        + ", time16Us=" + adaptive16TimeNs / 1000L

                        + ", prepareUs=" + adaptiveDiagnostics.prepareNs / 1000L
                        + ", otherProcessUs=" + otherNs / 1000L

                        + ", maxPrepareUs=" + adaptiveDiagnostics.maxPrepareNs / 1000L
                        + ", maxCubeUs=" + adaptiveMaxCubeNs / 1000L

                        + ", hotCube=("
                        + adaptiveHotX + ","
                        + adaptiveHotY + ","
                        + adaptiveHotZ
                        + ";size=" + adaptiveHotSize + ")"

                        + ", received=" + adaptiveDiagnostics.received
                        + ", prepared=" + adaptiveDiagnostics.prepared
                        + ", completed=" + adaptiveDiagnostics.completed

                        + ", origins=" + adaptiveDiagnostics.origins
                        + ", directions=" + adaptiveDiagnostics.directions
                        + ", attenuations=" + adaptiveDiagnostics.attenuationCalls

                        + ", exitsCreated=" + adaptiveDiagnostics.exitsCreated
                        + ", exitsChecked=" + adaptiveDiagnostics.exitsChecked
                        + ", exitsWaiting=" + adaptiveDiagnostics.exitsWaiting

                        + ", emittedParts=" + adaptiveDiagnostics.emittedParts
                        + ", culledParts=" + adaptiveDiagnostics.culledParts
                        + ", escapedParts=" + adaptiveDiagnostics.escapedParts

                        + ", maxExitsPerPacket="
                        + adaptiveDiagnostics.maxExitsPerPacket

                        + ", activeCubes=" + getActiveMacroCubeCount()

                        + ", pendingPackets=" + adaptivePendingPacketsThisStep
                        + ", pendingExits=" + adaptivePendingExitsThisStep

                        + ", maxPendingPackets=" + adaptiveMaxPendingPackets
                        + ", maxPendingExits=" + adaptiveMaxPendingExits

                        + ", receivedEnergy=" + adaptiveDiagnostics.receivedEnergy
                        + ", generatedEnergy=" + adaptiveDiagnostics.generatedEnergy
                        + ", emittedEnergy=" + adaptiveDiagnostics.emittedEnergy
                        + ", culledEnergy=" + adaptiveDiagnostics.culledEnergy
                        + ", escapedEnergy=" + adaptiveDiagnostics.escapedEnergy;

        // Сбрасываем интервальную статистику.
        adaptiveDiagnostics.reset();

        adaptiveCubeCalls = adaptive8Calls = adaptive16Calls = 0;

        adaptiveCubeTimeNs = 0;
        adaptive8TimeNs = 0;
        adaptive16TimeNs = 0;

        adaptiveMaxCubeNs = 0;

        adaptiveMaxPendingExits = 0;
        adaptiveMaxPendingPackets = 0;

        adaptiveHotX = adaptiveHotY = 0;
        adaptiveHotZ = adaptiveHotSize = 0;

        return report;
    }

    private void recordMaterialInteraction(
            long pos,
            double spent,
            boolean destroy
    ) {
        absorbed += spent;

        if (destroy) {
            destroyed.add(pos);
        }
    }
}
