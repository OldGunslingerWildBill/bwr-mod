package dev.bwr.mod.world;

import dev.bwr.mod.eccs.*;
import dev.bwr.mod.condenser.*;
import dev.bwr.mod.cooling.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import java.util.*;
import java.util.function.Predicate;

/** The complete footprint participates in player permissions and unload recovery. */
@EventBusSubscriber(modid="bwr")
public final class AssemblyAccess {
    private AssemblyAccess() {}
    public static boolean permitted(Level level, LivingEntity actor, BlockPos pos) {
        return !(actor instanceof Player player) || level.mayInteract(player,pos);
    }
    public static List<BlockPos> cells(Level level,BlockPos pos,BlockState state) {
        List<BlockPos> cells=new ArrayList<>();
        if(state.getBlock() instanceof PumpAssemblyBlock block) {
            BlockPos root=block.origin(pos,state);
            for(int i=0;i<block.cellCount(state);i++)cells.add(root.offset(TurbineAssemblyBlock.turn(block.cellOffset(state,i),state.getValue(PumpAssemblyBlock.FACING))));
        } else if(state.getBlock() instanceof TurbineAssemblyBlock block) {
            BlockPos root=block.origin(pos,state);
            for(int i=0;i<block.cellCount();i++)cells.add(root.offset(TurbineAssemblyBlock.turn(block.cellOffset(i),state.getValue(TurbineAssemblyBlock.FACING))));
        } else if(level.isLoaded(pos)) {
            var be=level.getBlockEntity(pos);
            if(be instanceof CondenserBlockEntity part) {
                for(var cell:part.layout().cells)cells.add(part.layout().world(part.root(),state.getValue(CondenserBlock.FACING),cell));
            } else if(be instanceof CoolingBlockEntity part && state.getBlock() instanceof CoolingBlock block) {
                for(var cell:part.layout().cells)cells.add(part.layout().world(part.root(),state.getValue(CoolingBlock.FACING),cell));
            } else if(be instanceof CondensateStorageTankBlockEntity part && state.getValue(CondensateStorageTankBlock.ASSEMBLED)) {
                for(var offset:CondensateTankShape.get(part.diameter,part.height).cells.keySet())cells.add(part.root().offset(offset));
            }
        }
        if(cells.isEmpty())cells.add(pos.immutable());
        return cells;
    }
    public static boolean allAllowed(Iterable<BlockPos> cells,Predicate<BlockPos> allowed) {
        for(BlockPos cell:cells)if(!allowed.test(cell))return false;
        return true;
    }
    public static boolean allLoaded(Level level,BlockPos pos,BlockState state) {
        return allAllowed(cells(level,pos,state),level::isLoaded);
    }
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if(event.getLevel() instanceof Level level &&
                !allAllowed(cells(level,event.getPos(),event.getState()),p->permitted(level,event.getPlayer(),p)))
            event.setCanceled(true);
    }
}
