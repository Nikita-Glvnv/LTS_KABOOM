package com.kaboom.client;

import com.kaboom.registry.ModItems;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(
        modid = "lts_kaboom",
        value = Side.CLIENT
)

public class ItemModelHandler {
    @SubscribeEvent
    public static void registerModels(ModelRegistryEvent event){
        ModelLoader.setCustomModelResourceLocation(
                ModItems.BOOMSTICK, 0, new ModelResourceLocation(
                        ModItems.BOOMSTICK.getRegistryName(),
                        "inventory"
                )
        );
    }
}
