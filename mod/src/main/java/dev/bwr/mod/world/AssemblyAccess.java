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
    /**
     * A multi-cell placement was refused after its root block was set (a cell is taken, not loaded, not permitted, or would not
     * set): the root goes and the placer gets the item back. Vanilla and the building mods take the item once {@code setPlacedBy}
     * returns, so it is given back here, not through {@code popResource}, which the doTileDrops rule silences: into a player's
     * inventory (a building mod reaches far), as a dropped item with no placer, and not at all for a creative player, who was
     * not charged. Clients only remove the root.
     */
    public static void refusePlacement(Level level, BlockPos root, LivingEntity placer, net.minecraft.world.item.ItemStack stack) {
        level.removeBlock(root,false);
        if(level.isClientSide()) return;
        if(placer instanceof Player p) p.displayClientMessage(dev.bwr.mod.text.BwrText.tr("status.bwr.pump_assembly.no_room_for_the_full_assembly","Not enough room for the full assembly here"),true);
        if(stack.isEmpty() || placer instanceof Player p && p.getAbilities().instabuild) return;
        net.minecraft.world.item.ItemStack back=stack.copyWithCount(1);
        if(placer instanceof Player p) p.getInventory().placeItemBackInInventory(back);
        else level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level,root.getX()+.5,root.getY()+.5,root.getZ()+.5,back));
    }
    /**
     * Operating a control (press, turn, hold, recorder text): everyone but a spectator, so adventure maps work. The
     * custom panel packets bypass the vanilla game-mode gates, so they ask here.
     */
    public static boolean mayOperate(Player player) {
        return !player.isSpectator();
    }
    /**
     * Placing, removing, configuring, wiring and renaming: a player who may build and is not a spectator, the same test
     * {@code CondensateTankAssembly.resize} applies. Adventure mode is refused.
     */
    public static boolean mayEdit(Player player) {
        return !player.isSpectator() && player.mayBuild();
    }
    public static List<BlockPos> cells(Level level,BlockPos pos,BlockState state) {
        List<BlockPos> cells=new ArrayList<>();
        if(state.getBlock() instanceof PumpAssemblyBlock block) {
            BlockPos root=block.origin(pos,state);
            for(int i=0;i<block.cellCount(state);i++)cells.add(root.offset(TurbineAssemblyBlock.turn(block.cellOffset(state,i),state.getValue(PumpAssemblyBlock.FACING))));
        } else if(state.getBlock() instanceof TurbineAssemblyBlock block) {
            BlockPos root=block.origin(pos,state);
            for(int i=0;i<block.cellCount(state);i++)cells.add(root.offset(TurbineAssemblyBlock.turn(block.cellOffset(state,i),state.getValue(TurbineAssemblyBlock.FACING))));
        } else if(state.getBlock() instanceof dev.bwr.mod.suppression.RhrHeatExchangerBlock
                && dev.bwr.mod.suppression.RhrHeatExchangerBlock.isAssembled(state)) {
            BlockPos root=dev.bwr.mod.suppression.RhrHeatExchangerBlock.rootOf(pos,state);
            for(int i=0;i<dev.bwr.mod.suppression.RhrHeatExchangerBlock.LAYOUT.count();i++)
                cells.add(dev.bwr.mod.suppression.RhrHeatExchangerBlock.cellPos(root,state,i));
        } else if(state.getBlock() instanceof dev.bwr.mod.panel.DuplexPanelBlock) {
            BlockPos root=dev.bwr.mod.panel.DuplexPanelBlock.origin(pos,state);
            for(var cell:dev.bwr.mod.panel.DuplexCell.values())cells.add(cell.at(root,state.getValue(dev.bwr.mod.panel.PanelBlock.FACING)));
        } else if(state.getBlock() instanceof dev.bwr.mod.panel.DeskPanelBlock) {
            BlockPos root=dev.bwr.mod.panel.DeskPanelBlock.origin(pos,state);
            for(var half:dev.bwr.mod.panel.DeskHalf.values())cells.add(half.at(root,state.getValue(dev.bwr.mod.panel.PanelBlock.FACING)));
        } else if(level.isLoaded(pos)) {
            var be=level.getBlockEntity(pos);
            if(be instanceof CondenserBlockEntity part) {
                for(var cell:part.layout().cells)cells.add(part.layout().world(part.root(),state.getValue(CondenserBlock.FACING),cell));
            } else if(be instanceof CoolingBlockEntity part && state.getBlock() instanceof CoolingBlock block) {
                for(var cell:part.layout().cells)cells.add(part.layout().world(part.root(),state.getValue(CoolingBlock.FACING),cell));
            } else if(be instanceof CondensateStorageTankBlockEntity part && state.getValue(CondensateStorageTankBlock.ASSEMBLED)
                    && CondensateTankShape.valid(part.diameter,part.height)) {
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
