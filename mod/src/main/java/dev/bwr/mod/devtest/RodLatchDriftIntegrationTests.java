package dev.bwr.mod.devtest;

import dev.bwr.mod.reactor.*;
import dev.bwr.mod.signal.SignalProviders;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;
import java.util.Arrays;

@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class RodLatchDriftIntegrationTests {
    @GameTest(template="empty",batch="fcd",timeoutTicks=150)
    public static void passiveDriftSurvivesNetworkRefreshAndReload(GameTestHelper h) {
        FcdTestSupport.Run.start(h,175_680,0,9,9,run->{
            var core=run.be.core();var rods=run.be.rods();
            h.assertTrue(core.getConfig().rodColletFailureHazardPerSecond==0,"new-world hazard must default off");
            core.setRodColletFailed(0,true);
            for(int i=0;i<220;i++){rods.preStep(.05);core.step();rods.postStep();}
            double position=core.getRodPositionNotches(0);
            h.assertTrue(position>1.09&&dev.bwr.mod.rods.RodIndications.passiveDrift(core,0),"regular drive refresh cancelled passive drift");
            h.assertTrue(SignalProviders.of(run.be).read("rod_1_drift").orElse(0)==1,"drift signal missing");
            var state=core.toState();var saved=ReactorStateNbt.write(state);
            h.assertTrue(Arrays.equals(state.rodLatchState(),ReactorStateNbt.read(saved).rodLatchState()),"NBT lost collet/command/RNG state");
            saved.remove("rodLatch");h.assertTrue(ReactorStateNbt.read(saved).rodLatchState().length==0,"legacy rod state did not load");
            int count=core.getControlRodCount();int[] before=new int[count],after=new int[count];
            for(int i=0;i<count;i++){before[i]=i;after[i]=count-1-i;}
            var remap=CoreDriveRemap.state(state,before,after);
            h.assertTrue((remap.rodLatchState()[3+count-1]&1)==1&&(remap.rodLatchState()[3]&1)==0,"fault did not follow its physical drive on remap");
            var restored=FcdRodTests.reload(run,run.be.saveWithFullMetadata(run.level.registryAccess()));
            h.assertTrue(restored.core().isRodColletFailed(0)&&dev.bwr.mod.rods.RodIndications.passiveDrift(restored.core(),0),"controller reload lost fault/alarm");
            FcdTestSupport.supplyDrives(run.level,restored);
            restored.rods().preStep(.05);restored.core().step();restored.rods().postStep();
            h.assertTrue(restored.core().getRodPositionNotches(0)>position,"reload rearmed an old completed command");
            restored.rods().setNotchDemand(0,1);restored.rods().preStep(.05);restored.core().step();
            h.assertTrue(!dev.bwr.mod.rods.RodIndications.passiveDrift(restored.core(),0),"new explicit command still reported as drift");
            restored.rods().scram();restored.rods().preStep(.05);restored.core().step();
            h.assertTrue(!dev.bwr.mod.rods.RodIndications.passiveDrift(restored.core(),0),"scram incorrectly reported as drift");
        });
    }
}
