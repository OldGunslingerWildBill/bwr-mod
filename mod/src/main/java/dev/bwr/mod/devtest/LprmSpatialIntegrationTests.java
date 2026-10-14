package dev.bwr.mod.devtest;

import dev.bwr.mod.reactor.*;
import dev.bwr.mod.signal.SignalProviders;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;
import java.util.Arrays;

@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class LprmSpatialIntegrationTests {
    @GameTest(template="empty",batch="fcd",timeoutTicks=150)
    public static void all39StringsHaveUniqueHardwareAndSave(GameTestHelper h) {
        FcdTestSupport.Run.start(h,175_360,0,15,15,run->{
            var core=run.be.core();var layout=core.getLprmLayout();var map=run.be.coreMap();
            h.assertTrue(layout.count()==39,"formed core has wrong physical string count");
            for(int s=0;s<39;s++) h.assertTrue(layout.strings().get(s).x()==map.lprmStrings().get(s).x()
                    &&layout.strings().get(s).y()==map.lprmStrings().get(s).y(),"display/core string order disagrees");
            var last=core.getAveragePowerRangeMonitor(layout.channel(38,3));last.setLprmFailed(layout.input(38,3),true);
            var signals=SignalProviders.of(run.be);
            h.assertTrue(signals.read("lprm_39_d_failed").orElse(0)==1,"last physical chamber not exposed");
            h.assertTrue(signals.read("lprm_3_d_failed").orElse(1)==0,"last chamber aliases first twelve");
            var state=core.toState();var saved=ReactorStateNbt.write(state);var restored=ReactorStateNbt.read(saved);
            h.assertTrue(Arrays.equals(state.lprmState(),restored.lprmState()),"NBT lost rack hardware");
            saved.remove("lprm");h.assertTrue(ReactorStateNbt.read(saved).lprmState().length==0,"legacy load not supported");
        });
    }
    @GameTest(template="empty",batch="fcd",timeoutTicks=150)
    public static void reloadReconcilesOldDetectorGeometryWithCurrentVessel(GameTestHelper h) {
        FcdTestSupport.Run.start(h,175_360,1,15,15,run->{
            var core=run.be.core();var physical=core.getLprmLayout();
            var oldStrings=new java.util.ArrayList<>(physical.strings());oldStrings.remove(oldStrings.size()-1);
            core.installLprmLayout(new dev.bwr.core.instrument.LprmLayout(oldStrings));
            var old=core.getLprmLayout();core.getAveragePowerRangeMonitor(old.channel(0,0)).setLprmFailed(old.input(0,0),true);
            var restored=FcdRodTests.reload(run,run.be.saveWithFullMetadata(run.level.registryAccess())).core();
            var layout=restored.getLprmLayout();
            h.assertTrue(layout.strings().equals(physical.strings()),"saved detector layout overrode the rebuilt vessel");
            h.assertTrue(restored.getAveragePowerRangeMonitor(layout.channel(0,0)).isLprmFailed(layout.input(0,0)),"surviving detector lost its fault");
        });
    }

}
