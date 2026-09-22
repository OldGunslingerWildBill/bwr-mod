package dev.bwr.mod.eccs;

import dev.bwr.mod.feedwater.FeedwaterPumpBlockEntity;
import dev.bwr.mod.flow.RecirculationPumpBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.world.LiveCapabilities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.*;

/** All part cells address their one controller; fluid is exposed only at suction. */
public final class PumpAssemblyCapabilities {
    private PumpAssemblyCapabilities() {}
    public static Block[] blocks() { return new Block[]{BwrBlocks.LPCS_PUMP.get(),BwrBlocks.HPCS_PUMP.get(),BwrBlocks.RHR_PUMP.get(),
            BwrBlocks.MOTOR_FEED_PUMP.get(),BwrBlocks.TURBINE_FEED_PUMP.get(),BwrBlocks.RIP_PUMP.get(),BwrBlocks.RECIRCULATION_PUMP.get(),BwrBlocks.RCIC_TWL.get(),BwrBlocks.HPCI_TURBINE.get()}; }
    public static BlockEntity controller(Level level,BlockPos pos,BlockState state) {
        if (!(state.getBlock() instanceof ProcessAssembly b)) return null;
        BlockPos root=b.origin(pos,state);
        BlockEntity owner=b.complete(level,root,state) ? level.getBlockEntity(root) : null;
        return owner!=null&&!owner.isRemoved()?owner:null;
    }
    public static boolean waterFace(BlockState state,Direction side) {
        if (state.getBlock() instanceof PumpAssemblyBlock b && !b.isFull(state)) return true;
        return !(state.getBlock() instanceof ProcessAssembly b)
                || side != null && b.portAt(state,side) == AssemblyPort.WATER_SUCTION;
    }
    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlock(Capabilities.EnergyStorage.BLOCK,(level,pos,initial,unused,side) -> LiveCapabilities.energy(level,pos,initial.getBlock(),state -> {
            BlockEntity be=controller(level,pos,state);
            if(be instanceof EccsPumpBlockEntity p) return p.energy();
            if(be instanceof FeedwaterPumpBlockEntity p) return p.energy();
            if(be instanceof RecirculationPumpBlockEntity p) return p.energy();
            return null;
        }),blocks());
        event.registerBlock(Capabilities.FluidHandler.BLOCK,(level,pos,state,unused,side) -> {
            if (!waterFace(state,side)) return null;
            return LiveCapabilities.fluid(level,pos,state.getBlock(),current -> {
            if (!waterFace(current,side)) return null;
            BlockEntity be = controller(level,pos,current);
            if (be instanceof FeedwaterPumpBlockEntity p) return p.suction();
            if (be instanceof EccsPumpBlockEntity p) return p.waterInlet();
            return null;
            });
        },blocks());
    }
}
