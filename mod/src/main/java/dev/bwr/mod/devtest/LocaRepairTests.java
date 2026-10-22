package dev.bwr.mod.devtest;

import dev.bwr.core.boundary.*;
import dev.bwr.mod.containment.ContainmentPenetrationBlock;
import dev.bwr.mod.containment.ContainmentPenetrationBlockEntity;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.*;

/**
 * Pressure-boundary breaks that the player can seal by putting the pipe back, and breaks further along a
 * recirculation, feedwater or steam line than the nozzle (PORT-LOCA-UPDATE.md, "Sealing a break" and "Line breaks").
 * Every test drives the reactor's own tick synchronously, so no other GameTest interleaves with its readings.
 */
@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class LocaRepairTests {
    private LocaRepairTests() {}

    private static void tick(ReactorControllerBlockEntity be) {
        ReactorControllerBlockEntity.serverTick(be.getLevel(),be.getBlockPos(),be.getBlockState(),be);
    }
    private static void ticks(ReactorControllerBlockEntity be, int n) { for(int i=0;i<n;i++) tick(be); }
    private static void pipe(ServerLevel l, BlockPos p) {
        l.setBlock(p,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(),3);
    }
    private static void water(ServerLevel l, BlockPos a, BlockPos b) {
        for(var p:BlockPos.betweenClosed(a,b)) pipe(l,p.immutable());
    }
    private static double mass(ReactorControllerBlockEntity be) {
        var v=be.core().getPressureVessel(); return v.getLiquidMassKg()+v.getSteamMassKg();
    }
    private static BoundaryStress boundary(ReactorControllerBlockEntity be) { return be.core().getBoundaryStress(); }
    private static ReactorControllerBlockEntity reload(GameTestHelper h, ReactorControllerBlockEntity be, java.util.function.UnaryOperator<CompoundTag> edit) throws Exception {
        var l=h.getLevel(); var root=be.getBlockPos();
        var saved=edit.apply(be.saveWithFullMetadata(l.registryAccess()));
        l.removeBlockEntity(root);
        var next=(ReactorControllerBlockEntity)BlockEntity.loadStatic(root,l.getBlockState(root),saved,l.registryAccess());
        l.setBlockEntity(next); SpargerRegressionTests.validate(next); return next;
    }
    private static BlockPos port(ServerLevel l, BlockPos at, net.minecraft.world.level.block.Block block, Direction facing) {
        l.setBlock(at,block.defaultBlockState().setValue(RpvWaterInjectionPortBlock.FACING,facing),3); return at;
    }
    private static boolean sealedLabel(ReactorControllerBlockEntity be, String label) {
        return be.boundaryEffects().recentlySealed(be.getLevel().getGameTime()).stream()
                .anyMatch(s->dev.bwr.mod.text.BwrText.english(s.label()).equals(label));
    }

    /** A nozzle break sealed by putting its pipe back: the opening closes, stress is gone, inventory stops falling. */
    @GameTest(template="empty",timeoutTicks=200)
    public static void reattachedNozzlePipeSealsTheBreakAcrossReload(GameTestHelper h) throws Exception {
        var l=h.getLevel(); var min=new BlockPos(70_100,190,70_100);
        var be=CompactCoreRegressionTests.build(l,min,9,9,12,false);
        var inlet=port(l,min.offset(6,1,-1),BwrBlocks.RECIRCULATION_INLET.get(),Direction.NORTH);
        pipe(l,inlet.north());
        SpargerRegressionTests.validate(be); tick(be);
        boundary(be).step(1300,60);
        double worn=boundary(be).getStress(BoundaryComponent.RECIRCULATION_LINE);
        h.assertTrue(worn>0 && !boundary(be).hasFailed(),"fixture did not wear the recirculation line without breaking it");
        l.removeBlock(inlet.north(),false); tick(be);
        h.assertTrue(boundary(be).isBroken(BoundaryComponent.RECIRCULATION_LINE),"removed nozzle pipe did not break the line");
        double before=mass(be); ticks(be,10);
        h.assertTrue(mass(be)<before-1,"an open nozzle break did not drain the vessel: "+(before-mass(be))+" kg");
        be=reload(h,be,t->t); tick(be);
        h.assertTrue(boundary(be).isBroken(BoundaryComponent.RECIRCULATION_LINE),"reload healed an open nozzle break");
        h.assertTrue(boundary(be).activeOpenings().size()==1,"reload lost the nozzle opening");
        pipe(l,inlet.north()); tick(be);
        h.assertTrue(!boundary(be).isBroken(BoundaryComponent.RECIRCULATION_LINE),"reattached pipe did not seal the break");
        h.assertTrue(boundary(be).activeOpenings().isEmpty(),"sealed break still has an opening");
        h.assertTrue(boundary(be).getStress(BoundaryComponent.RECIRCULATION_LINE)==0,"new pipe kept the old line's stress");
        h.assertTrue(boundary(be).getStress(BoundaryComponent.FEEDWATER_LINE)>0,"sealing one line reset another line's stress");
        h.assertTrue(sealedLabel(be,"Recirculation loop A discharge"),"the seal was not reported: "
                +dev.bwr.mod.text.BwrText.english(dev.bwr.mod.damage.BoundaryDamageReadout.breakComponents(be.boundaryEffects(),l.getGameTime())));
        before=mass(be); ticks(be,10);
        h.assertTrue(Math.abs(mass(be)-before)<.01,"vessel inventory kept falling after the seal: "+(before-mass(be))+" kg");
        be=reload(h,be,t->t); ticks(be,2);
        h.assertTrue(!boundary(be).isBroken(BoundaryComponent.RECIRCULATION_LINE) && boundary(be).activeOpenings().isEmpty(),
                "reload reopened a sealed break");
        h.succeed();
    }

    /** The pipe an overpressure failure destroyed, put back: the break closes. */
    @GameTest(template="empty",timeoutTicks=200)
    public static void reattachingAnOverpressureDestroyedPipeSealsTheBreak(GameTestHelper h) throws Exception {
        var l=h.getLevel(); var min=new BlockPos(70_400,190,70_400);
        var be=CompactCoreRegressionTests.build(l,min,9,9,12,false);
        var feed=port(l,min.offset(4,3,-1),BwrBlocks.RPV_WATER_INJECTION_PORT.get(),Direction.NORTH);
        pipe(l,feed.north()); pipe(l,feed.north(2));
        SpargerRegressionTests.validate(be); tick(be);
        // The same broken-without-world-effect state an overpressure roll leaves: the reactor tick then breaks the pipe.
        boundary(be).forceFailure(BoundaryComponent.FEEDWATER_LINE,1400); tick(be);
        h.assertTrue(l.getBlockState(feed.north()).isAir(),"overpressure failure did not destroy the nozzle pipe");
        h.assertTrue(boundary(be).isBroken(BoundaryComponent.FEEDWATER_LINE),"overpressure failure not recorded");
        ticks(be,3);
        h.assertTrue(boundary(be).isBroken(BoundaryComponent.FEEDWATER_LINE),"overpressure break closed by itself");
        pipe(l,feed.north()); tick(be);
        h.assertTrue(!boundary(be).isBroken(BoundaryComponent.FEEDWATER_LINE),"reattached pipe did not seal an overpressure break");
        h.assertTrue(boundary(be).activeOpenings().isEmpty(),"sealed overpressure break still drains");
        h.assertTrue(l.getBlockState(feed.north()).is(BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get()),"seal destroyed the new pipe");
        h.succeed();
    }

    /** Two broken ports of one component: the component is repaired only when both have their pipe back. */
    @GameTest(template="empty",timeoutTicks=200)
    public static void twoBrokenPortsSealOnlyWhenBothAreReattached(GameTestHelper h) throws Exception {
        var l=h.getLevel(); var min=new BlockPos(70_700,190,70_700);
        var be=CompactCoreRegressionTests.build(l,min,9,9,12,false);
        var upper=port(l,min.offset(3,7,-1),BwrBlocks.RECIRCULATION_OUTLET.get(),Direction.NORTH);
        var lower=port(l,min.offset(6,1,-1),BwrBlocks.RECIRCULATION_INLET.get(),Direction.NORTH);
        pipe(l,upper.north()); pipe(l,lower.north());
        SpargerRegressionTests.validate(be); tick(be);
        l.removeBlock(upper.north(),false); l.removeBlock(lower.north(),false); tick(be);
        h.assertTrue(boundary(be).activeOpenings().size()==2,"both ports did not break");
        pipe(l,upper.north()); tick(be);
        h.assertTrue(boundary(be).isBroken(BoundaryComponent.RECIRCULATION_LINE),"one reattached port repaired the whole component");
        var left=boundary(be).activeOpenings();
        h.assertTrue(left.size()==1 && left.getFirst().ratedLiquidKgPerS()>.1*BoundaryStress.RATED_CORE_FLOW_KG_PER_S,
                "the wrong opening closed: "+left);
        pipe(l,lower.north()); tick(be);
        h.assertTrue(!boundary(be).isBroken(BoundaryComponent.RECIRCULATION_LINE) && boundary(be).activeOpenings().isEmpty(),
                "both ports reattached but the component stayed broken");
        h.succeed();
    }

    /**
     * A 9 x 9 x 8 vessel with an external DVSS loop on its east wall: top outlet, suction header, pump, discharge header,
     * bottom inlet. Returns {outlet, inlet, pump root}.
     */
    static BlockPos[] recirculationLoop(ServerLevel l, BlockPos min) {
        var outlet=port(l,min.offset(9,6,2),BwrBlocks.RECIRCULATION_OUTLET.get(),Direction.EAST);
        var inlet=port(l,min.offset(9,0,2),BwrBlocks.RECIRCULATION_INLET.get(),Direction.EAST);
        var block=BwrBlocks.RECIRCULATION_PUMP.get(); var root=min.offset(15,0,5); var s=block.placementState();
        l.removeBlock(root,false); l.setBlock(root,s,3); block.setPlacedBy(l,root,s,null,new ItemStack(block));
        int[][] paths={{15,0,8,15,6,8},{15,6,8,11,6,8},{11,6,8,11,6,2},{11,6,2,10,6,2},{15,2,2,15,0,2},{15,0,2,10,0,2}};
        for(var p:paths) water(l,min.offset(p[0],p[1],p[2]),min.offset(p[3],p[4],p[5]));
        return new BlockPos[]{outlet,inlet,root};
    }

    /**
     * A recirculation discharge header broken three blocks from the vessel drains the vessel through the bottom inlet,
     * with the inlet's opening, at the rate that opening gives; putting the pipe back stops it. The idle plant does no
     * line work: a steady tick neither rebuilds a pipe graph nor re-evaluates a line.
     */
    @GameTest(template="empty",timeoutTicks=300)
    public static void recirculationLineBreakAwayFromTheVesselDrainsAndSeals(GameTestHelper h) throws Exception {
        var l=h.getLevel(); var min=new BlockPos(71_000,190,71_000);
        for(int x=(min.getX()-8)>>4;x<=(min.getX()+40)>>4;x++)for(int z=(min.getZ()-8)>>4;z<=(min.getZ()+40)>>4;z++)l.getChunk(x,z);
        var be=CompactCoreRegressionTests.build(l,min,9,9,false);
        var loop=recirculationLoop(l,min); var inlet=loop[1];
        SpargerRegressionTests.validate(be);
        h.assertTrue(dev.bwr.mod.flow.RecirculationCircuit.controller(l,loop[2])==be,"recirculation fixture not connected");
        ticks(be,3);
        h.assertTrue(be.boundaryEffects().lineCellCount(inlet)==8,"discharge header not established: "+be.boundaryEffects().lineCellCount(inlet));
        h.assertTrue(be.boundaryEffects().lineCellCount(loop[0])>=15,"suction header not established: "+be.boundaryEffects().lineCellCount(loop[0]));
        // Idle: synchronous on the server thread, so nothing else can add to either counter in between.
        long graphs=dev.bwr.mod.piping.PipeTopology.buildCount(),lines=ReactorBoundaryEffects.lineEvaluations();
        ticks(be,100);
        h.assertTrue(dev.bwr.mod.piping.PipeTopology.buildCount()==graphs,"an intact loop rebuilt a pipe graph on a steady tick");
        h.assertTrue(ReactorBoundaryEffects.lineEvaluations()==lines,"an intact loop re-evaluated its lines on a steady tick: "
                +(ReactorBoundaryEffects.lineEvaluations()-lines)+" in 100 ticks");
        h.assertTrue(!boundary(be).hasFailed(),"an intact loop broke");
        var gap=min.offset(13,0,2);
        l.removeBlock(gap,false); tick(be);
        h.assertTrue(boundary(be).isBroken(BoundaryComponent.RECIRCULATION_LINE),"a discharge header break was not a LOCA");
        var openings=boundary(be).activeOpenings();
        var expected=ReactorBoundaryEffects.opening(be.vesselEnvelope(),inlet,BoundaryComponent.RECIRCULATION_LINE,true);
        h.assertTrue(openings.equals(java.util.List.of(expected)),"line break did not drain like a break at its inlet nozzle: "+openings+" vs "+expected);
        var breaks=be.boundaryEffects().breaks();
        h.assertTrue(breaks.size()==1 && breaks.getFirst().at().equals(gap) && breaks.getFirst().open() && !breaks.getFirst().nozzle(),
                "the break is not listed at the gap: "+breaks);
        h.assertTrue(be.getUpdateTag(l.registryAccess()).getLongArray("VisualLineBreaks").length==1,"the plume was not placed at the gap");
        double before=mass(be),flowed=0;
        for(int i=0;i<20;i++){tick(be);flowed+=(be.core().getBreakLiquidFlowKgPerS()+be.core().getBreakSteamFlowKgPerS())*.05;}
        double lost=before-mass(be);
        h.assertTrue(flowed>10 && Math.abs(lost-flowed)<.05*flowed,"vessel lost "+lost+" kg but the break passed "+flowed+" kg");
        // The same opening at the nozzle passes the same flow: the sqrt(gauge + hydrostatic) law at the inlet's elevation.
        var v=be.core().getPressureVessel();
        double law=expected.liquidFlow(v.getPressurePsig(),v.getCollapsedLevelIn(),
                dev.bwr.core.thermal.Saturation.liquidDensityKgPerM3(v.getPressurePsia()));
        h.assertTrue(law>0 && Math.abs(be.core().getBreakLiquidFlowKgPerS()-law)<.05*law,
                "break flow "+be.core().getBreakLiquidFlowKgPerS()+" kg/s is not the inlet nozzle's "+law+" kg/s");
        pipe(l,gap); tick(be);
        h.assertTrue(!boundary(be).isBroken(BoundaryComponent.RECIRCULATION_LINE) && boundary(be).activeOpenings().isEmpty(),
                "the reattached header pipe did not seal the line break");
        h.assertTrue(sealedLabel(be,"Recirculation loop A discharge"),"the line seal was not reported");
        before=mass(be); ticks(be,10);
        h.assertTrue(Math.abs(mass(be)-before)<.01,"vessel kept draining after the line break was sealed");
        h.succeed();
    }

    /**
     * Feedwater line: port, two pipes, a containment water penetration (its valve closed: no power), three pipes, a tank.
     * A break beyond the closed valve is no LOCA; opening the valve starts it, closing it stops it again, and the pipe
     * put back seals it.
     */
    @GameTest(template="empty",timeoutTicks=200)
    public static void feedwaterBreakBehindAClosedPenetrationLeaksOnlyOnceItOpens(GameTestHelper h) throws Exception {
        var l=h.getLevel(); var min=new BlockPos(71_400,190,71_400);
        var be=CompactCoreRegressionTests.build(l,min,9,9,false);
        var feed=port(l,min.offset(4,5,-1),BwrBlocks.RPV_WATER_INJECTION_PORT.get(),Direction.NORTH);
        pipe(l,feed.north()); pipe(l,feed.north(2));
        var sleeve=feed.north(3);
        // A sleeve left by an earlier run keeps its block entity (and its valve position) through a same-state setBlock.
        l.removeBlock(sleeve,false);
        l.setBlock(sleeve,BwrBlocks.CONTAINMENT_WATER_PENETRATION.get().defaultBlockState()
                .setValue(ContainmentPenetrationBlock.FACING,Direction.NORTH),3);
        water(l,feed.north(4),feed.north(6));
        l.setBlock(feed.north(7),BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState(),3);
        SpargerRegressionTests.validate(be); ticks(be,2);
        var valve=(ContainmentPenetrationBlockEntity)l.getBlockEntity(sleeve);
        h.assertTrue(valve.isIsolated(),"fixture penetration valve is not closed");
        h.assertTrue(be.boundaryEffects().lineCellCount(feed)==6,"feed line not established: "+be.boundaryEffects().lineCellCount(feed));
        var gap=feed.north(5);
        l.removeBlock(gap,false); tick(be);
        h.assertTrue(be.boundaryEffects().breaks().size()==1 && !be.boundaryEffects().breaks().getFirst().open(),
                "a break behind a closed valve was not recorded as isolated: "+be.boundaryEffects().breaks());
        h.assertTrue(!boundary(be).isBroken(BoundaryComponent.FEEDWATER_LINE) && boundary(be).activeOpenings().isEmpty(),
                "a break behind a closed isolation valve drained the vessel");
        double before=mass(be); ticks(be,5);
        h.assertTrue(Math.abs(mass(be)-before)<.01,"vessel inventory fell through a closed isolation valve");
        var position=ContainmentPenetrationBlockEntity.class.getDeclaredField("position"); position.setAccessible(true);
        position.setDouble(valve,1.0);
        tick(be);
        h.assertTrue(boundary(be).isBroken(BoundaryComponent.FEEDWATER_LINE),"opening the valve did not start the LOCA");
        var expected=ReactorBoundaryEffects.opening(be.vesselEnvelope(),feed,BoundaryComponent.FEEDWATER_LINE,false);
        h.assertTrue(boundary(be).activeOpenings().equals(java.util.List.of(expected)),"feed line break not sized like its nozzle: "+boundary(be).activeOpenings());
        double stress=boundary(be).getStress(BoundaryComponent.FEEDWATER_LINE);
        position.setDouble(valve,0.0); tick(be);
        h.assertTrue(!boundary(be).isBroken(BoundaryComponent.FEEDWATER_LINE) && boundary(be).activeOpenings().isEmpty(),
                "closing the valve again did not isolate the break");
        h.assertTrue(boundary(be).getStress(BoundaryComponent.FEEDWATER_LINE)==stress,"isolating a break changed the line's stress");
        be=reload(h,be,t->t); tick(be);
        h.assertTrue(be.boundaryEffects().breaks().size()==1 && !boundary(be).isBroken(BoundaryComponent.FEEDWATER_LINE),
                "reload lost the isolated break or opened it");
        position.setDouble(valve,1.0); tick(be);
        h.assertTrue(boundary(be).isBroken(BoundaryComponent.FEEDWATER_LINE),"the reloaded isolated break did not open with its valve");
        pipe(l,gap); tick(be);
        h.assertTrue(!boundary(be).isBroken(BoundaryComponent.FEEDWATER_LINE) && be.boundaryEffects().breaks().isEmpty(),
                "the reattached pipe did not seal the feed line break");
        h.assertTrue(sealedLabel(be,"Feedwater line A"),"the feed line seal was not reported");
        h.succeed();
    }

    /** Pipe that never reached a pump or machine is construction: taking any of it away is not a break. */
    @GameTest(template="empty",timeoutTicks=200)
    public static void neverConnectedLinesAreNotBreaks(GameTestHelper h) throws Exception {
        var l=h.getLevel(); var min=new BlockPos(71_800,190,71_800);
        var be=CompactCoreRegressionTests.build(l,min,9,9,false);
        var stub=port(l,min.offset(2,5,-1),BwrBlocks.RPV_WATER_INJECTION_PORT.get(),Direction.NORTH);
        water(l,stub.north(),stub.north(5));
        // A complete line, but taken apart before the reactor ever saw it whole.
        var unseen=port(l,min.offset(6,5,-1),BwrBlocks.RPV_WATER_INJECTION_PORT.get(),Direction.NORTH);
        water(l,unseen.north(),unseen.north(3));
        l.setBlock(unseen.north(4),BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState(),3);
        l.removeBlock(unseen.north(2),false);
        SpargerRegressionTests.validate(be); ticks(be,2);
        h.assertTrue(be.boundaryEffects().lineCellCount(stub)==0,"a dead-end stub was taken for pressure boundary");
        l.removeBlock(stub.north(3),false); l.removeBlock(stub.north(5),false); ticks(be,2);
        h.assertTrue(!boundary(be).hasFailed() && be.boundaryEffects().breaks().isEmpty(),
                "taking pipe off a never-connected line made a break: "+be.boundaryEffects().breaks());
        l.removeBlock(unseen.north(3),false); ticks(be,2);
        h.assertTrue(!boundary(be).hasFailed(),"a line that was never whole broke");
        h.succeed();
    }

    /** A head failure is not a pipe: reattaching the nozzle pipe seals the feed break and the head stays failed. */
    @GameTest(template="empty",timeoutTicks=200)
    public static void headFailureStaysWhenThePipesAreSealed(GameTestHelper h) throws Exception {
        var l=h.getLevel(); var min=new BlockPos(72_200,190,72_200);
        var be=CompactCoreRegressionTests.build(l,min,9,9,12,false);
        var feed=port(l,min.offset(4,3,-1),BwrBlocks.RPV_WATER_INJECTION_PORT.get(),Direction.NORTH);
        pipe(l,feed.north());
        SpargerRegressionTests.validate(be); tick(be);
        boundary(be).forceFailure(BoundaryComponent.REACTOR_VESSEL_HEAD,1700);
        l.removeBlock(feed.north(),false); ticks(be,2);
        h.assertTrue(boundary(be).isBroken(BoundaryComponent.FEEDWATER_LINE) && boundary(be).isBroken(BoundaryComponent.REACTOR_VESSEL_HEAD),
                "fixture did not break the feed nozzle and the head");
        pipe(l,feed.north()); ticks(be,2);
        h.assertTrue(!boundary(be).isBroken(BoundaryComponent.FEEDWATER_LINE),"feed nozzle pipe did not seal");
        h.assertTrue(boundary(be).isBroken(BoundaryComponent.REACTOR_VESSEL_HEAD),"sealing a pipe repaired the vessel head");
        h.assertTrue(boundary(be).activeOpenings().size()==1
                && boundary(be).activeOpenings().getFirst().component()==BoundaryComponent.REACTOR_VESSEL_HEAD,"head opening lost");
        be=reload(h,be,t->t); ticks(be,2);
        h.assertTrue(boundary(be).isBroken(BoundaryComponent.REACTOR_VESSEL_HEAD),"reload healed the vessel head");
        h.succeed();
    }

    /**
     * Saves from before this change: an opening with no pipe loads and leaks until the pipe goes back; an opening whose
     * pipe the player had already put back under the old rule keeps leaking until that pipe is taken off and put back.
     */
    @GameTest(template="empty",timeoutTicks=200)
    public static void oldSaveOpeningsLeakUntilRepaired(GameTestHelper h) throws Exception {
        var l=h.getLevel(); var min=new BlockPos(72_600,190,72_600);
        var be=CompactCoreRegressionTests.build(l,min,9,9,12,false);
        var upper=port(l,min.offset(3,7,-1),BwrBlocks.RECIRCULATION_OUTLET.get(),Direction.NORTH);
        var feed=port(l,min.offset(6,3,-1),BwrBlocks.RPV_WATER_INJECTION_PORT.get(),Direction.NORTH);
        pipe(l,upper.north()); pipe(l,feed.north());
        SpargerRegressionTests.validate(be); tick(be);
        l.removeBlock(upper.north(),false); l.removeBlock(feed.north(),false); tick(be);
        h.assertTrue(boundary(be).activeOpenings().size()==2,"fixture did not open both ports");
        pipe(l,feed.north());
        // The old format: the same keys this version writes for an intact plant, none of the new ones.
        be=reload(h,be,t->{
            for(var key:new String[]{"BoundaryOpeningsSealable","BoundaryHeldOpenings","BoundaryLineBreaks","BoundaryLines"}) t.remove(key);
            return t;
        });
        ticks(be,3);
        h.assertTrue(boundary(be).activeOpenings().size()==2,"an old save's openings did not load and leak: "+boundary(be).activeOpenings());
        pipe(l,upper.north()); tick(be);
        h.assertTrue(boundary(be).activeOpenings().size()==1,"the old save's open port did not seal when its pipe went back");
        h.assertTrue(boundary(be).isBroken(BoundaryComponent.FEEDWATER_LINE),"a pipe put back under the old rule sealed on load");
        l.removeBlock(feed.north(),false); tick(be); pipe(l,feed.north()); tick(be);
        h.assertTrue(!boundary(be).hasFailed() && boundary(be).activeOpenings().isEmpty(),"taking the old pipe off and back on did not seal it");
        h.succeed();
    }
}
