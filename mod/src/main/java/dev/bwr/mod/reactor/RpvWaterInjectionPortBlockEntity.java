package dev.bwr.mod.reactor;

import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Assigned only by the controller whose validated shell contains this port. No proximity binding. */
public class RpvWaterInjectionPortBlockEntity extends BlockEntity {
    private BlockPos controllerPos;
    public RpvWaterInjectionPortBlockEntity(BlockPos pos, BlockState state) { super(BwrBlockEntities.RPV_WATER_INJECTION_PORT.get(), pos, state); }
    public void noteController(BlockPos pos) { controllerPos = pos.immutable(); }
    public ReactorControllerBlockEntity controller() {
        if (level == null || isRemoved() || controllerPos == null || !level.isLoaded(controllerPos)
                || !(level.getBlockEntity(controllerPos) instanceof ReactorControllerBlockEntity c) || !c.isFormed()
                || !c.structure().waterInjectionPositions().contains(getBlockPos())) return null;
        if (getBlockState().getBlock() instanceof RecirculationPortBlock port) {
            var direction=getBlockState().getValue(RpvWaterInjectionPortBlock.FACING);
            int low=c.structure().interiorMin().getY(),high=c.structure().interiorMax().getY();
            if (!direction.getAxis().isHorizontal()) return null;
            if (port.isOutlet() ? getBlockPos().getY() < (low+high+1)/2 : getBlockPos().getY() > low+1) return null;
        }
        BlockPos inside = getBlockPos().relative(getBlockState().getValue(RpvWaterInjectionPortBlock.FACING).getOpposite());
        BlockPos min = c.structure().interiorMin(), max = c.structure().interiorMax();
        return inside.getX() >= min.getX() && inside.getX() <= max.getX()
                && inside.getY() >= min.getY() && inside.getY() <= max.getY()
                && inside.getZ() >= min.getZ() && inside.getZ() <= max.getZ() ? c : null;
    }
}
