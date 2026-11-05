package com.kaboom.api;

import com.kaboom.Kaboom;
import com.kaboom.server.ExplosionHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

public class KaboomAPI {
    public static void createKABOOM(
            WorldServer world,
            BlockPos pos,
            int power
    ) {
        if (world == null || pos == null) {
            throw new IllegalArgumentException("World and position cannot be null");
        }

        if (power <= 0 || power > 1000000) {
            throw new IllegalArgumentException("Invalid explosion power");
        }

        if (!world.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("Explosion must be called on Server Thread");
        }

        ExplosionHandler handler = Kaboom.getExplosionHandler();

        if (handler == null) {
            throw new IllegalStateException("ExplosionHandler is not initialized");
        }

        handler.explode(world, pos, power);
    }
}
