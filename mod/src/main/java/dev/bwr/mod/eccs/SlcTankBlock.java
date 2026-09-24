package dev.bwr.mod.eccs;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** A placed tank owns one finite, mixed solution inventory. */
public final class SlcTankBlock extends PumpAssemblyBlock {
    public SlcTankBlock(Properties p){super(p,Kind.SLC_TANK);}
    @Override protected ItemInteractionResult useItemOn(ItemStack stack,BlockState state,Level level,BlockPos pos,Player player,InteractionHand hand,BlockHitResult hit){
        if(!stack.is(dev.bwr.mod.registry.BwrItems.BORATE_CHARGE.get()))return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if(!level.isClientSide() && controller(level,pos,state) instanceof SlcTankBlockEntity tank){
            if(tank.addCharge()){if(!player.isCreative())stack.shrink(1);player.displayClientMessage(net.minecraft.network.chat.Component.literal("Borate charge dissolved."),true);}
            else player.displayClientMessage(net.minecraft.network.chat.Component.literal("Add water first; leave 25 kg free. Maximum borate concentration: 13%."),true);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }
}
