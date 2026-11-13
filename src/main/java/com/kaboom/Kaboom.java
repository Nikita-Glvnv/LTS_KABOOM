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
import static com.kaboom.ModLogger.LOGGER;
import com.kaboom.server.chunksnapshot;

@Mod(modid = Kaboom.MODID, name = Kaboom.NAME, version = Kaboom.VERSION)
public class Kaboom
{
    public static final String MODID = "lts_kaboom";
    public static final String NAME = "LTS_KABOOM";
    public static final String VERSION = "0.0";

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
        ModLogger.initialize(
                event.getModConfigurationDirectory().getParentFile()
        );
    }

    @EventHandler
    public void init(FMLInitializationEvent event)
    {
        // some example code
        LOGGER.info("DIRT BLOCK >> {}", Blocks.DIRT.getRegistryName());
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
        chunkSnapshot = new chunksnapshot(
                snapshot -> explosionHandler != null
                        && explosionHandler.offerSnapshotUpdate(snapshot)
        );
        MinecraftForge.EVENT_BUS.register(chunkSnapshot);
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

        if (chunkSnapshot != null) {
            MinecraftForge.EVENT_BUS.unregister(chunkSnapshot);
            chunkSnapshot.stop();
            chunkSnapshot = null;
        }
    }

    private static chunksnapshot chunkSnapshot;
    public static chunksnapshot getChunkSnapshot() {
        return chunkSnapshot;
    }

    @Mod.Instance(Kaboom.MODID)
    public static Kaboom INSTANCE;
}
