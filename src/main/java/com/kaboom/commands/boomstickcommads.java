package com.kaboom.commands;

import com.kaboom.item.Boomstick;
import com.kaboom.registry.ModItems;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;

public class boomstickcommads extends CommandBase {
    @Override
    public String getName() {
        return "boomstick";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/boomstick <power>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(
            MinecraftServer server,
            ICommandSender sender,
            String [] args
    ) throws CommandException {
        if (args.length !=1){
            throw new WrongUsageException(getUsage(sender));
        }

        int power = parseInt(args[0], 1, 1000000);

        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        ItemStack stack = new ItemStack(ModItems.BOOMSTICK);

        Boomstick.setExplosionPower(stack, power);

        if (!player.inventory.addItemStackToInventory(stack)) {
            player.dropItem(stack, false);
        } else {
            player.inventoryContainer.detectAndSendChanges();
        }
    }
}
