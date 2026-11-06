package com.kaboom.server;

import com.kaboom.explosionwork.DimensionWorker;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import com.kaboom.server.chunksnapshot;

import java.util.HashMap;
import java.util.Map;

public class ExplosionHandler {

    private final Map<Integer, DimensionWorker> dimensionWorkers =
            new HashMap<>();

    private void registerDimension(int dimensionId) {
        dimensionWorkers.computeIfAbsent(
                dimensionId,
                DimensionWorker::new
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

        dimensionWorkers.remove(dimensionId);
    }

    public void stop() {
        MinecraftForge.EVENT_BUS.unregister(this);
        dimensionWorkers.clear();
    }

    public void explode(WorldServer world, BlockPos pos, int power) {
        int dimensionId = world.provider.getDimension();

        DimensionWorker worker = dimensionWorkers.get(dimensionId);

        if (worker != null) {
            worker.submitExplosion(pos, power);
        } else {
            throw new IllegalStateException(
                    "No DimensionWorker for dimension " + dimensionId
            );
        }
    }

    public boolean offerSectionSnapshot(
            chunksnapshot.SectionSnapshot snapshot
    ) {
        DimensionWorker worker =
                dimensionWorkers.get(snapshot.dimensionId);

        return worker != null
                && worker.offerSectionSnapshot(snapshot);
    }
}