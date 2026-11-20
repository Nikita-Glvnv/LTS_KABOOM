package com.kaboom.sound;


import com.kaboom.Kaboom;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = Kaboom.MODID)
public class KaboomSounds {
    public static final SoundEvent EXPLOSION =
            new SoundEvent(
                    new ResourceLocation(Kaboom.MODID, "explosion")
            ).setRegistryName(
                    new ResourceLocation(Kaboom.MODID, "explosion")
            );

    @SubscribeEvent
    public static void registerSounds(
            RegistryEvent.Register<SoundEvent> event
    ) {
        event.getRegistry().register(EXPLOSION);
    }


}
