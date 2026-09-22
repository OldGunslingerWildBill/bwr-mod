package dev.bwr.core;

import dev.bwr.core.fuel.*;
import java.util.Arrays;
import java.util.HashSet;

public final class CompactCoreLayoutTest {
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
        for(int size:new int[]{15,21}) {
            var layout=new CompactCoreLayout(size,size);
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
