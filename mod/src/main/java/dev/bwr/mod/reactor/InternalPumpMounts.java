package dev.bwr.mod.reactor;

import dev.bwr.mod.text.BwrText;

import dev.bwr.core.fuel.*;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.world.LoadedWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import java.util.*;

/** Validates complete bottom-head penetrations once during structure validation. */
public final class InternalPumpMounts {
    public record Installed(List<BlockPos> roots) {}
    public static Installed inspect(Level level,BlockPos min,BlockPos max,int version,ValidationResult result) {
        int width=max.getX()-min.getX()+1,depth=max.getZ()-min.getZ()+1;
        var allowed=InternalPumpLayout.mounts(width,depth);var roots=new LinkedHashSet<BlockPos>();
        for(var p:BlockPos.betweenClosed(min.offset(-1,-1,-1),new BlockPos(max.getX()+1,min.getY(),max.getZ()+1))) {
            var s=LoadedWorld.block(level,p);if(!s.is(BwrBlocks.RIP_PUMP.get()))continue;
            var b=(PumpAssemblyBlock)s.getBlock();var root=b.origin(p,s);
            if(!roots.add(root))continue;
            if(!b.isFull(s)||root.getY()!=min.getY()-1) {result.fail(p, BwrText.tr(
                    "status.bwr.internal_pump_mounts.rip_mounting_flange_must_cross_the_vessel_floor",
                    "RIP mounting flange must cross the vessel floor"));continue;}
            int x0=Integer.MAX_VALUE,z0=Integer.MAX_VALUE,x1=Integer.MIN_VALUE,z1=Integer.MIN_VALUE;
            for(int i=0;i<b.cellCount(s);i++) {
                var cell=root.offset(TurbineAssemblyBlock.turn(b.cellOffset(s,i),s.getValue(PumpAssemblyBlock.FACING)));
                LoadedWorld.block(level,cell); // Missing chunks defer validation, never erase saved topology.
                x0=Math.min(x0,cell.getX());z0=Math.min(z0,cell.getZ());x1=Math.max(x1,cell.getX());z1=Math.max(z1,cell.getZ());
            }
            if(!b.complete(level,root,s)){result.fail(root, BwrText.tr("status.bwr.internal_pump_mounts.incomplete_reactor_internal_pump", "Incomplete reactor internal pump"));continue;}
            if(version==1) { // Existing legacy rim mounts retain their original construction contract.
                if(x0<min.getX()-1||z0<min.getZ()-1||x1>max.getX()+1||z1>max.getZ()+1)
                    result.fail(root, BwrText.tr("status.bwr.internal_pump_mounts.rip_lies_outside_the_vessel_head", "RIP lies outside the vessel head"));
                continue;
            }
            var mount=new InternalPumpLayout.Mount(x0-min.getX(),z0-min.getZ());
            if(x1-x0!=1||z1-z0!=1||!allowed.contains(mount)) {
                result.fail(root, BwrText.tr(
                        "status.bwr.internal_pump_mounts.invalid_rip_position_use_a_highlighted_2x2_bottom",
                        "Invalid RIP position; use a highlighted 2x2 bottom-head mount"));continue;
            }
        }
        return new Installed(List.copyOf(roots));
    }
    private InternalPumpMounts() {}
}
