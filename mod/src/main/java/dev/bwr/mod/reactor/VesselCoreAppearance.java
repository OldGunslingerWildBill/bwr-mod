package dev.bwr.mod.reactor;

import java.util.*;
import net.minecraft.nbt.CompoundTag;

/** Immutable visual snapshot of the authoritative loading, separate from the shell
 * so refuelling cannot dirty/rebuild the vessel's chunks or shell mesh. */
public record VesselCoreAppearance(int layoutVersion,int latticeWidth,List<Cell> cells,List<Drive> drives) {
    public record Cell(int slot,int content) {} // 0 empty, 1 fuel channel, 2 specialty insert
    public record Drive(int x,int z) {} // physical drive offset in the interior footprint
    public VesselCoreAppearance {cells=List.copyOf(cells);drives=List.copyOf(drives);}
    public static VesselCoreAppearance capture(ReactorControllerBlockEntity be) {
        if(!be.isFormed())return null;
        var s=be.structure();var loading=be.core().getCoreLoading();var cells=new ArrayList<Cell>();
        for(int slot:s.fuelPositions())cells.add(new Cell(slot,loading.assemblyAt(slot)!=null?1:loading.isOccupied(slot)?2:0));
        var drives=s.crdPositions().stream().map(p->new Drive(p.getX()-s.interiorMin().getX(),p.getZ()-s.interiorMin().getZ())).toList();
        return new VesselCoreAppearance(be.coreLayoutVersion(),s.latticeWidth(),cells,drives);
    }
    public void write(CompoundTag tag) {
        tag.putInt("VisualCoreVersion",layoutVersion);tag.putInt("VisualCoreLattice",latticeWidth);
        tag.putIntArray("VisualCoreCells",cells.stream().mapToInt(c->c.slot()*3+c.content()).toArray());
        tag.putIntArray("VisualCoreDrives",drives.stream().mapToInt(d->d.x()*21+d.z()).toArray());
    }
    public static VesselCoreAppearance read(CompoundTag tag,VesselAppearance.Envelope e) {
        if(e==null||!tag.contains("VisualCoreCells"))return null; // Old visual packets contain no invented fuel.
        int version=tag.getInt("VisualCoreVersion"),width=tag.getInt("VisualCoreLattice");
        if(!((version==1&&width==31)||(version==2&&width==42)))return null;
        int[] packed=tag.getIntArray("VisualCoreCells"),rawDrives=tag.getIntArray("VisualCoreDrives");
        if(packed.length>1764||rawDrives.length>441)return null;
        var cells=new ArrayList<Cell>();var seen=new HashSet<Integer>();
        for(int n:packed) {int slot=n/3;if(n<0||slot>=width*width||!seen.add(slot))return null;cells.add(new Cell(slot,n%3));}
        var drives=new ArrayList<Drive>();seen.clear();
        for(int n:rawDrives) {
            if(n<0||n/21>=e.width()-2||n%21>=e.depth()-2||!seen.add(n))return null;
            drives.add(new Drive(n/21,n%21));
        }
        return new VesselCoreAppearance(version,width,cells,drives);
    }
}
