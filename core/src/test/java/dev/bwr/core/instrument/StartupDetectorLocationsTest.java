package dev.bwr.core.instrument;

import dev.bwr.core.harness.TransientHarness;
import java.util.HashSet;
public final class StartupDetectorLocationsTest {
    public static void testFourSourceAndEightIntermediateTubesHavePhysicalPositions() {
        var core=TransientHarness.ratedCore();var locations=core.getStartupDetectorLocations();var seen=new HashSet<Integer>();
        if(locations.size()!=12)throw new AssertionError("expected 4 SRM and 8 IRM tubes");
        for(int i=0;i<locations.size();i++) {
            var d=locations.get(i);var string=core.getLprmLayout().strings().get(d.stringIndex());
            if(!d.range().equals(i<4?"SRM":"IRM")||d.channel()!=(i<4?i:i-4)||!seen.add(d.stringIndex())
                    ||d.x()!=string.x()||d.y()!=string.y()||d.latticePosition()!=string.latticePosition())
                throw new AssertionError("startup detector has inconsistent lattice location: "+d);
        }
        System.out.println("TYPICAL DETECTORS "+locations);
    }
}
