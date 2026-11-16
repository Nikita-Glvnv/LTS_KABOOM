package com.kaboom.explosionwork;

import java.util.Arrays;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

public class explosionstep {
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

        long arrivalTick = (long) Math.floor(arrival);

        if (arrivalTick < currentTick
                || arrivalTick >= currentTick + RING_SIZE) {

            throw new IllegalArgumentException(
                    "Arrival outside the 3-tick window"
            );
        }

        pending[(int) (arrivalTick % RING_SIZE)].add(
                source, target,
                energy, mx, my, mz, arrival
        );
    }

    public long getCurrentTick() {
        return currentTick;
    }

    WaveBucket currentBucket() {
        return pending[(int) (currentTick % RING_SIZE)];
    }

    void finishTick() {

        currentBucket().clear();

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

}
