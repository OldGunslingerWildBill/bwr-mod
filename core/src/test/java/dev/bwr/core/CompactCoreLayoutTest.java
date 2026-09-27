package dev.bwr.core;

import dev.bwr.core.fuel.*;
import java.util.Arrays;
import java.util.HashSet;

public final class CompactCoreLayoutTest {
    public static void testInternalPumpRingHasOneContinuousCore() {
        for(int w=5;w<=21;w++)for(int d=5;d<=21;d++) {
            var design=InternalPumpLayout.design(w,d);var sites=design.mounts();
            var columns=InternalPumpLayout.occupiedColumns(sites);
            Check.exactly(sites.size()*4,columns.size(),"RIP mounts must not overlap");
            var base=new CompactCoreLayout(w,d);var reduced=new CompactCoreLayout(w,d,true);
            Check.isTrue(reduced.assemblyCount()<base.assemblyCount()&&!reduced.drives().isEmpty(),"annulus must leave a smaller usable core");
            for(var drive:reduced.drives())Check.isTrue(!columns.contains(drive),"drive intersects pump ring");
            for(var m:sites) {
                double vx=.448*(w+2),vz=.448*(d+2),sx=.385*(w+2)*design.coreScale(),sz=.385*(d+2)*design.coreScale();
                double x=m.x()+1-w/2.0,z=m.z()+1-d/2.0;
                Check.isTrue(Math.hypot(x/vx,z/vz)+.60/Math.min(vx,vz)<=1+1e-8,"pump clips vessel wall");
                Check.isTrue(Math.hypot(x/sx,z/sz)-.55/Math.min(sx,sz)>1,"pump clips reserved construction envelope");
            }
            var fuel=new HashSet<Integer>();for(int slot:reduced.fuelPositions())fuel.add(slot);
            // Every occupied row and column must be one solid interval: individual
            // pump bays or concealed interior holes cannot pass this regression.
            for(int axis=0;axis<2;axis++)for(int row=0;row<42;row++) {
                int first=42,last=-1;
                for(int c=0;c<42;c++)if(fuel.contains(axis==0?row*42+c:c*42+row)){first=Math.min(first,c);last=c;}
                for(int c=first;c<=last;c++)Check.isTrue(fuel.contains(axis==0?row*42+c:c*42+row),"hole in continuous RIP core");
            }
            for(int slot:fuel)Check.isTrue(fuel.contains(slot/42*42+41-slot%42)&&fuel.contains((41-slot/42)*42+slot%42),"asymmetric reduced core");
            Check.isTrue(design.areaFraction()>0&&design.areaFraction()<1,"invalid circular area fraction");
            Check.exactly(base.assemblyCount(),new CompactCoreLayout(w,d).assemblyCount(),"ordinary cores changed");
        }
        Check.exactly(4,InternalPumpLayout.mounts(5,5).size(),"minimum RIP sites");
        Check.exactly(12,InternalPumpLayout.mounts(15,15).size(),"reference RIP sites");
        Check.exactly(16,InternalPumpLayout.mounts(21,21).size(),"maximum RIP sites");
        Check.exactly(40,new CompactCoreLayout(5,5,true).assemblyCount(),"minimum continuous core");
        Check.exactly(476,new CompactCoreLayout(15,15,true).assemblyCount(),"reference continuous core");
        Check.exactly(1084,new CompactCoreLayout(21,21,true).assemblyCount(),"maximum continuous core");
        Check.exactly(109,new CompactCoreLayout(15,15,true).drives().size(),"expanded reference drives");
        Check.exactly(253,new CompactCoreLayout(21,21,true).drives().size(),"expanded maximum drives");
        // The eight cardinal-side sites remain compatible; only four diagonal
        // mounts need relocation to make room for the expanded central drive ring.
        for(var site:new InternalPumpLayout.Mount[]{new InternalPumpLayout.Mount(0,5),new InternalPumpLayout.Mount(0,8),
                new InternalPumpLayout.Mount(5,0),new InternalPumpLayout.Mount(8,0),
                new InternalPumpLayout.Mount(5,13),new InternalPumpLayout.Mount(8,13),
                new InternalPumpLayout.Mount(13,5),new InternalPumpLayout.Mount(13,8)})
            Check.isTrue(InternalPumpLayout.mounts(15,15).contains(site),"unnecessary reference pump relocation");
    }
    public static void testRipPackingFillsClearSpaceAndRetainsAlpha19Fuel() {
        for(int w=5;w<=21;w++)for(int d=5;d<=21;d++) {
            var core=new CompactCoreLayout(w,d,true);var base=new CompactCoreLayout(w,d);
            var p=RipCorePacking.layout(w,d);var allowed=new HashSet<Integer>(p.fuel());
            var ordinary=new HashSet<Integer>();for(int slot:base.fuelPositions())ordinary.add(slot);
            var blocked=InternalPumpLayout.occupiedColumns(InternalPumpLayout.mounts(w,d));
            Check.isTrue(ordinary.containsAll(allowed),"packed fuel cannot be returned to ordinary layout");
            for(int slot:ordinary) {
                double radius=RipCorePacking.corner(slot,p.pitchX(),p.pitchZ(),w,d);
                if(allowed.contains(slot))Check.isTrue(radius<=AbwrShroudGeometry.FUEL_RADIUS+1e-10,"fuel support corner clips shroud");
                else if(radius<=AbwrShroudGeometry.FUEL_RADIUS) {
                    var extended=new HashSet<>(allowed);extended.addAll(RipCorePacking.orbit(slot));
                    Check.isTrue(!RipCorePacking.validOutline(extended,blocked,w,d),"unfilled usable perimeter slot");
                }
            }
            for(int rod=0;rod<core.drives().size();rod++) {
                var drive=core.drives().get(rod);var owned=core.rodMap().positionsOfRod(rod);
                Check.isTrue(!blocked.contains(drive),"new drive intersects RIP construction cells");
                Check.isTrue(RipCorePacking.bladeFits(drive.x(),drive.z(),p.pitchX(),p.pitchZ(),w,d),"peripheral blade clips shroud");
                Check.inRange(1,4,owned.length,"peripheral control cell size");
                for(int slot:owned) {
                    Check.isTrue(allowed.contains(slot),"blade assigned nonexistent fuel");
                    Check.exactly(drive.x(),(slot%42-(21-w))/2,"packed fuel/drive X alignment");
                    Check.exactly(drive.z(),(slot/42-(21-d))/2,"packed fuel/drive Z alignment");
                }
            }
            double q=InternalPumpLayout.design(w,d).coreScale();
            // Reconstruct the alpha.18/.19 mask to prove no loaded slot/drive is lost.
            for(int x=0;x<w;x++)for(int z=0;z<d;z++) {
                int[] group=new int[4];int count=0;
                for(int a=0;a<2;a++)for(int b=0;b<2;b++)group[count++]=(21-d+2*z+b)*42+21-w+2*x+a;
                boolean full=Arrays.stream(group).allMatch(allowed::contains);
                if(full) {
                    Check.isTrue(!blocked.contains(new CompactCoreLayout.Drive(x,z)),"full fuel cell lacks room for a drive");
                    for(int slot:group)Check.isTrue(core.rodMap().rodAtPosition(slot)>=0,"complete cell has no control blade");
                }
                if(oldInside(2*x+1-w,2*z+1-d,w,d,236,q)) {
                    Check.isTrue(core.drives().contains(new CompactCoreLayout.Drive(x,z)),"upgrade dropped existing drive");
                    for(int slot:group)Check.isTrue(allowed.contains(slot),"upgrade dropped controlled fuel slot");
                }
            }
            for(int x=0;x<2*w;x++)for(int z=0;z<2*d;z++)
                if(oldInside(2*x+1-2*w,2*z+1-2*d,w,d,970,q))
                    Check.isTrue(allowed.contains((21-d+z)*42+21-w+x),"upgrade dropped peripheral fuel slot");
        }
    }
    private static boolean oldInside(int x,int z,int w,int d,int limit,double q) {
        return 225L*((long)x*x*d*d+(long)z*z*w*w)<=(long)limit*w*w*d*d*q*q;
    }
    public static void testReferenceAndLimits() {
        int[][] sizes = {{5,88,21},{15,764,185},{21,1476,357}};
        for (var size : sizes) {
            var layout = new CompactCoreLayout(size[0],size[0]);
            Check.exactly(size[1],layout.assemblyCount(),"assembly capacity");
            Check.exactly(size[2],layout.drives().size(),"independent drives");
            Check.note("Outside %dx%d: %d assemblies / %d blades",size[0]+2,size[0]+2,size[1],size[2]);
        }
        var legacy = ReactorCore.defaultCoreLoading(new CoreConfig(),FuelType.LEU);
        Check.exactly(31,legacy.latticeWidth(),"standalone legacy lattice");
        Check.exactly(748,legacy.loadedAssemblyCount(),"standalone reference unchanged");
    }

    public static void testEveryFootprintIsSymmetricAndEveryDriveOwnsFourLocalBundles() {
        for (int width=5;width<=21;width++) for (int depth=5;depth<=21;depth++) {
            var layout=new CompactCoreLayout(width,depth);
            int[] slots=layout.fuelPositions();
            var fuel=new HashSet<Integer>();
            for(int slot:slots) Check.isTrue(fuel.add(slot),"duplicate fuel position");
            for(int slot:slots) {
                int x=slot%42,z=slot/42;
                Check.isTrue(fuel.contains(z*42+41-x)&&fuel.contains((41-z)*42+x),"asymmetric fuel mask");
            }
            var drives=layout.drives();
            var cells=new HashSet<>(drives);
            for(int r=0;r<drives.size();r++) {
                var drive=drives.get(r);
                Check.isTrue(cells.contains(new CompactCoreLayout.Drive(width-1-drive.x(),drive.z()))
                        && cells.contains(new CompactCoreLayout.Drive(drive.x(),depth-1-drive.z())),"asymmetric drive mask");
                int[] owned=layout.rodMap().positionsOfRod(r);
                Check.exactly(4,owned.length,"four assemblies per blade");
                for(int slot:owned) {
                    Check.isTrue(fuel.contains(slot),"blade shadows a non-fuel position");
                    Check.exactly(r,layout.rodMap().rodAtPosition(slot),"stable blade ID");
                    Check.exactly(drive.x(),(slot%42-(21-width))/2,"local bundle x");
                    Check.exactly(drive.z(),(slot/42-(21-depth))/2,"local bundle z");
                }
            }
            var transposed=new CompactCoreLayout(depth,width);
            Check.exactly(layout.assemblyCount(),transposed.assemblyCount(),"rectangular transpose");
            Check.exactly(drives.size(),transposed.drives().size(),"transposed blade count");
            Check.isTrue(Arrays.equals(slots,new CompactCoreLayout(width,depth).fuelPositions()),"unstable ordering");
            slots[0]=-99;
            Check.isTrue(layout.fuelPositions()[0]>=0,"mutable layout leaked");
        }
    }

    public static void testLargeFuelInventoryAndRodStateSurviveRestore() {
        for(int size:new int[]{15,21})for(boolean rips:new boolean[]{false,true}) {
            var layout=new CompactCoreLayout(size,size,rips);
            var config=new CoreConfig();config.assemblyCount=layout.assemblyCount();config.controlRodCount=layout.drives().size();
            var loading=new CoreLoading(42);
            for(int slot:layout.fuelPositions()) loading.load(slot,new FuelAssembly(FuelType.LEU));
            var core=new ReactorCore(config,loading);
            core.getNodalFluxSolver().setRodLatticeMap(layout.rodMap());
            core.initialiseCold();
            core.setRodNotchLabelDemand(0,28);
            core.step();
            var state=core.toState();
            var restored=new ReactorCore(config,loading);
            restored.getNodalFluxSolver().setRodLatticeMap(layout.rodMap());
            restored.fromState(state);
            Check.exactly(layout.assemblyCount(),restored.getCoreLoading().loadedAssemblyCount(),"uncapped inventory");
            Check.exactly(core.getRodNotchLabel(0),restored.getRodNotchLabel(0),"restored moving blade");
            Check.exactly(core.getReactivityDkOverK(),restored.getReactivityDkOverK(),"restored reactivity");
            Check.isTrue(Double.isFinite(restored.getThermalPowerMW()),"non-finite large-core power");
        }
    }
}
