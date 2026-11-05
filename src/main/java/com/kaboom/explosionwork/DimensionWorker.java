package com.kaboom.explosionwork;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.util.math.BlockPos;

public class DimensionWorker {
    private final int dimensionId;

    public DimensionWorker(int dimensionId) {
        this.dimensionId = dimensionId;
    }
    private static final Logger LOGGER =
            LogManager.getLogger("LTS_KABOOM");

    public void submitExplosion(BlockPos pos, int power){
        LOGGER.info(
                "Explosion accepted: dimension={}, pos={}, power={}",
                dimensionId, pos, power
        );
    }
}
