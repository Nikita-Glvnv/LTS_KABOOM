package com.kaboom.registry;

import net.minecraft.item.Item;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import com.kaboom.item.Boomstick;

@Mod.EventBusSubscriber(modid = "lts_kaboom")

public class ModItems {
    public static final Item BOOMSTICK = new Boomstick();
    @SubscribeEvent
    public static void registerItems(
            RegistryEvent.Register<Item> event
    ) {
        event.getRegistry().register(BOOMSTICK);
    }


}
