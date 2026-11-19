package com.kaboom.server;

import com.kaboom.explosionwork.DimensionWorker;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import com.kaboom.Kaboom;
import com.kaboom.server.ChunkLoadingManager;
import com.kaboom.server.DestroyApplyManager;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import static com.kaboom.ModLogger.LOGGER;

import java.util.HashMap;
import java.util.Map;

public class ExplosionHandler {

    private final Map<Integer, DimensionWorker> dimensionWorkers =
            new HashMap<>();

    private void registerDimension(int dimensionId) {
        dimensionWorkers.computeIfAbsent(
                dimensionId,
                id -> {
                    DimensionWorker worker = new DimensionWorker(id);
                    worker.start();
                    return worker;
                }
        );
    }

    public void start() {
        MinecraftForge.EVENT_BUS.register(this);

        for (WorldServer world : DimensionManager.getWorlds()) {
            registerDimension(world.provider.getDimension());
        }
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (!(event.getWorld() instanceof WorldServer)) {
            return;
        }

        int dimensionId = event.getWorld().provider.getDimension();

        registerDimension(dimensionId);
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (!(event.getWorld() instanceof WorldServer)) {
            return;
        }

        int dimensionId = event.getWorld().provider.getDimension();

        DimensionWorker worker = dimensionWorkers.remove(dimensionId);

        if (worker != null) {
            worker.stop();
        }

        DestroyApplyManager manager =
                Kaboom.getDestroyApplyManager();

        if (manager != null) {
            manager.resetDimension(dimensionId);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {

        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        for (Map.Entry<Integer, DimensionWorker> entry
                : dimensionWorkers.entrySet()) {

            int dimensionId = entry.getKey();
            DimensionWorker worker = entry.getValue();

            if (!worker.hasFailed()) {
                continue;
            }

            // Не перезапускаем worker выгруженного мира.
            if (DimensionManager.getWorld(
                    dimensionId, false
            ) == null) {
                continue;
            }

            try {
                DimensionWorker replacement =
                        restartFailedWorker(dimensionId, worker);

                entry.setValue(replacement);

            } catch (RuntimeException e) {

                LOGGER.error(
                        "DimensionWorker recovery failed: dimension={}",
                        dimensionId,
                        e
                );
            }
        }
    }

    public void stop() {
        MinecraftForge.EVENT_BUS.unregister(this);
        for (DimensionWorker worker : dimensionWorkers.values()) {
            worker.stop();
        }
        dimensionWorkers.clear();
    }

    public void explode(WorldServer world, BlockPos pos, int power) {
        int dimensionId = world.provider.getDimension();

        DimensionWorker worker = dimensionWorkers.get(dimensionId);
        if (worker != null && worker.hasFailed()) {

            worker = restartFailedWorker(
                    dimensionId,
                    worker
            );

            dimensionWorkers.put(dimensionId, worker);
        }

        if (worker != null) {
            worker.submitExplosion(pos, power);
        } else {
            throw new IllegalStateException(
                    "No DimensionWorker for dimension " + dimensionId
            );
        }
    }

    public boolean offerSnapshotUpdate(
            chunksnapshot.SnapshotUpdate update
    ) {
        if (update == null) {
            return false;
        }

        DimensionWorker worker =
                dimensionWorkers.get(update.getDimensionId());

        return worker != null
                && !worker.hasFailed()
                && worker.getSnapshotManager()
                .offerSnapshotUpdate(update);
    }

    private DimensionWorker restartFailedWorker(
            int dimensionId,
            DimensionWorker oldWorker
    ) {
        LOGGER.error(
                "DimensionWorker recovery started: dimension={}",
                dimensionId
        );

        // Дожидаемся полного завершения старого потока.
        oldWorker.stop();

        // Удаляем серверные снимки и запросы отслеживания.
        chunksnapshot snapshots = Kaboom.getChunkSnapshot();

        if (snapshots != null) {
            snapshots.resetDimension(dimensionId);
        }

        // Освобождаем чанки и Forge Tickets.
        ChunkLoadingManager loading =
                Kaboom.getChunkLoadingManager();

        if (loading != null) {
            loading.resetDimension(dimensionId);
        }

        // Удаляем ещё не применённые разрушения.
        DestroyApplyManager destroys =
                Kaboom.getDestroyApplyManager();

        if (destroys != null) {
            destroys.resetDimension(dimensionId);
        }

        // Абсолютно новый экземпляр:
        // новая очередь взрывов, SnapshotManager и explosionstep.
        DimensionWorker replacement =
                new DimensionWorker(dimensionId);

        replacement.start();

        LOGGER.warn(
                "DimensionWorker recovery completed: dimension={}",
                dimensionId
        );

        return replacement;
    }
}