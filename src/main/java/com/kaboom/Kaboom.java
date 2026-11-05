package com.kaboom;

import com.kaboom.commands.boomstickcommads;
import com.kaboom.server.ChunkLoadingManager;
import com.kaboom.server.ExplosionHandler;
import net.minecraft.init.Blocks;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.*;
import org.apache.logging.log4j.Logger;

@Mod(modid = Kaboom.MODID, name = Kaboom.NAME, version = Kaboom.VERSION)
public class Kaboom
{
    public static final String MODID = "lts_kaboom";
    public static final String NAME = "LTS_KABOOM";
    public static final String VERSION = "0.0";
    private static Logger logger;

    private static ExplosionHandler explosionHandler;
    public static ExplosionHandler getExplosionHandler() {
        return explosionHandler;
    }

    private static ChunkLoadingManager chunkLoadingManager;
    public static ChunkLoadingManager getChunkLoadingManager() {
        return chunkLoadingManager;
    }

    @EventHandler
    public void preInit(FMLPreInitializationEvent event)
    {
        ForgeChunkManager.setForcedChunkLoadingCallback(
                this,
                (restoredTickets, world) -> {

                    for (ForgeChunkManager.Ticket ticket : restoredTickets) {
                        ForgeChunkManager.releaseTicket(ticket);
                    }
                }
        );
        logger = event.getModLog();
    }

    @EventHandler
    public void init(FMLInitializationEvent event)
    {
        // some example code
        logger.info("DIRT BLOCK >> {}", Blocks.DIRT.getRegistryName());
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new boomstickcommads());

    }

    @Mod.EventHandler
    public void onServerStarted(FMLServerStartedEvent event){
        explosionHandler = new ExplosionHandler();
        explosionHandler.start();
        chunkLoadingManager = new ChunkLoadingManager();
        MinecraftForge.EVENT_BUS.register(chunkLoadingManager);
    }
    @Mod.EventHandler
    public void onServerStopping(FMLServerStoppingEvent event) {
        if (explosionHandler != null) {
            explosionHandler.stop();
            explosionHandler = null;
        }
        if (chunkLoadingManager != null) {
            MinecraftForge.EVENT_BUS.unregister(chunkLoadingManager);

            chunkLoadingManager.stop();
            chunkLoadingManager = null;
        }
    }

    @Mod.Instance(Kaboom.MODID)
    public static Kaboom INSTANCE;
}
