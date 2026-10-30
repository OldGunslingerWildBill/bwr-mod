package dev.bwr.mod.devtest;

import dev.bwr.mod.signal.SignalProviders;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class StartupDetectorLocationTests {
    @GameTest(template="empty",batch="fcd",timeoutTicks=FcdTestSupport.WAIT_TICKS+150)
    public static void coreDisplayAndSignalsShareStartupTubeLocations(GameTestHelper h) {
        FcdTestSupport.Run.start(h,175_520,0,15,15,run->{
            var locations=run.be.core().getStartupDetectorLocations();var display=run.be.coreMap().startupDetectors();
            h.assertTrue(locations.size()==12&&display.size()==12,"missing SRM/IRM locations");
            var signals=SignalProviders.of(run.be);
            for(int i=0;i<12;i++) {
                var d=locations.get(i);var label=display.get(i);
                h.assertTrue(d.x()==label.x()&&d.y()==label.y()&&d.stringIndex()==label.stringIndex()
                        &&d.label().equals(label.label()),"FCD labels disagree with physical tube");
                String prefix=d.range().toLowerCase(java.util.Locale.ROOT)+"_"+(d.channel()+1)+"_";
                h.assertTrue(signals.read(prefix+"lattice_x").orElse(-1)==d.x()
                        &&signals.read(prefix+"lprm_string").orElse(-1)==d.stringIndex()+1,"location signal disagrees");
            }
            if(net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
                StartupDetectorLocationCcChecks.typicalPlacementMetadata(h,run.be);
            }
        });
    }
}
