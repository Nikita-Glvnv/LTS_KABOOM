package com.kaboom.item;

import com.kaboom.api.KaboomAPI;
import static com.kaboom.ModLogger.LOGGER;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.Constants;

public class Boomstick extends Item{
    public Boomstick() {
        setRegistryName("lts_kaboom", "boomstick");
        setUnlocalizedName("lts_kaboom.boomstick");
        setMaxStackSize(1);
    }

    private static final String POWER_TAG = "ExplosionPower";
    private static final int DEFAULT_POWER = 1;

    public static int getExplosionPower(ItemStack stack){
        NBTTagCompound tag = stack.getTagCompound();

        if(tag != null && tag.hasKey(POWER_TAG, Constants.NBT.TAG_INT)) {
            return tag.getInteger(POWER_TAG);
        }

        return DEFAULT_POWER;
    }

    public static void setExplosionPower(ItemStack stack, int power){
        NBTTagCompound tag = stack.getTagCompound();

        if (tag == null){
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }

        tag.setInteger(POWER_TAG, Math.max(1,power));
    }

    @Override
    public String getItemStackDisplayName(ItemStack stack) {
        return "Boomstick " + getExplosionPower(stack);
    }

    @Override
    public EnumActionResult onItemUse (
            EntityPlayer player,
            World world,
            BlockPos pos,
            EnumHand hand,
            EnumFacing facing,
            float hitX,
            float hitY,
            float hitZ
    ) {
        if (!world.isRemote && world instanceof WorldServer) {

            int power = getExplosionPower(player.getHeldItem(hand));
            LOGGER.info("Boomstick used: player={}, dimension={}, pos={}, power={}, hand={}, facing={}",
                    player.getName(), world.provider.getDimension(), pos, power, hand, facing);

            KaboomAPI.createKABOOM(
                    (WorldServer) world,
                    pos,
                    power
            );
        }
        return EnumActionResult.SUCCESS;
    }
}
