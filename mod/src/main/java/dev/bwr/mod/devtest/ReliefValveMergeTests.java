package dev.bwr.mod.devtest;

import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.steam.AdsReliefValveBlock;
import dev.bwr.mod.steam.MainSteamIsolationValveBlock;
import dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity;
import dev.bwr.mod.steam.SafetyReliefValveBlock;
import dev.bwr.mod.steam.SafetyReliefValveBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE;

/**
 * The relief valve merge (owner, 2026-10-25) and the main steam valve remodel: {@code bwr:ads_relief_valve} is now the one
 * Safety Relief Valve; the old plain cube {@code bwr:safety_relief_valve} is no longer crafted but still loads and works;
 * the remodelled MSIV shows its stroke in all three cells.
 */
@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class ReliefValveMergeTests {
    private static final int SHIFT = Math.floorMod(java.util.UUID.randomUUID().hashCode(), 100_000) * 64;

    private static void load(ServerLevel l, BlockPos p, int radius) {
        for (int x = (p.getX() - radius) >> 4; x <= (p.getX() + radius) >> 4; x++)
            for (int z = (p.getZ() - radius) >> 4; z <= (p.getZ() + radius) >> 4; z++) l.getChunk(x, z);
    }

    private static boolean crafted(GameTestHelper h, String id) {
        var key = ResourceLocation.fromNamespaceAndPath("bwr", id);
        for (var holder : h.getLevel().getServer().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING))
            if (BuiltInRegistries.ITEM.getKey(holder.value().getResultItem(h.getLevel().registryAccess()).getItem()).equals(key)) return true;
        return false;
    }

    /** A placed old cube from an earlier world keeps its saved state, its six steam faces, redstone control and discharge. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void oldCubeReliefValveStillLoadsAndWorks(GameTestHelper h) {
        var l = h.getLevel();
        var p = new BlockPos(26100 + SHIFT, 200, 26300);
        load(l, p, 8);
        var block = BwrBlocks.SAFETY_RELIEF_VALVE.get();
        h.assertTrue(BuiltInRegistries.ITEM.containsKey(ResourceLocation.fromNamespaceAndPath("bwr", "safety_relief_valve"))
                && new ItemStack(block).getItem() != net.minecraft.world.item.Items.AIR, "the old cube's item must stay registered for inventories");
        h.assertTrue(!crafted(h, "safety_relief_valve"), "the old cube must no longer be crafted");
        h.assertTrue(crafted(h, "ads_relief_valve") && crafted(h, "ads_controller"), "the Safety Relief Valve and the Division cabinet must stay craftable");
        l.setBlock(p, block.defaultBlockState(), 3);
        var be = (SafetyReliefValveBlockEntity) l.getBlockEntity(p);
        // What an alpha-era save holds for a cube that was left open by hand.
        var saved = new CompoundTag();
        saved.putBoolean("Open", true);
        saved.putBoolean("ComputerControlled", false);
        be.loadWithComponents(saved, l.registryAccess());
        h.assertTrue(be.isOpen() && be.getDivision() == 0, "old cube lost its saved state: open=" + be.isOpen() + " division=" + be.getDivision());
        for (var face : Direction.values())
            h.assertTrue(block.acceptsSteamLineOn(l.getBlockState(p), face), "old cube stopped taking a steam pipe on " + face);
        be.setOpen(false);
        l.setBlock(p, l.getBlockState(p).setValue(SafetyReliefValveBlock.OPEN, false), 3);
        l.setBlock(p.east(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        h.assertTrue(be.isOpen() && l.getBlockState(p).getValue(SafetyReliefValveBlock.OPEN), "redstone no longer opens the old cube");
        l.removeBlock(p.east(), false);
        h.assertTrue(!be.isOpen() && !l.getBlockState(p).getValue(SafetyReliefValveBlock.OPEN), "removing redstone no longer closes the old cube");
        l.setBlock(p.below(2), Blocks.WATER.defaultBlockState(), 3);
        be.revalidateDischarge(l);
        h.assertTrue(be.isDischargeSubmerged(), "old cube no longer finds the water below it: " + be.dischargePath());
        l.removeBlock(p, false);
        l.removeBlock(p.below(2), false);
        h.succeed();
    }

    /** After the merge the piped valve still takes reactor steam through its side inlet and relieves it to the pool. */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void safetyReliefValveStillRelievesToThePool(GameTestHelper h) throws Exception {
        var l = h.getLevel();
        var o = new BlockPos(26100 + SHIFT, 190, 26100);
        var pool = SuppressionTankRegressionTests.build(l, o);
        pool.water(false).fill(new FluidStack(Fluids.WATER, 50_000), EXECUTE);
        var vo = o.offset(15, 0, -5);
        for (int x = (vo.getX() - 2) >> 4; x <= (vo.getX() + 28) >> 4; x++) for (int z = (vo.getZ() - 5) >> 4; z <= (vo.getZ() + 18) >> 4; z++) l.getChunk(x, z);
        var build = PhaseOneRegressionTests.class.getDeclaredMethod("buildVessel", ServerLevel.class, BlockPos.class, int.class);
        build.setAccessible(true);
        build.invoke(null, l, vo, 11);
        var cp = vo.offset(-1, 3, 2);
        l.setBlock(cp, BwrBlocks.REACTOR_CONTROLLER.get().defaultBlockState(), 2);
        var np = vo.offset(11, 6, 5);
        l.setBlock(np, BwrBlocks.RPV_STEAM_OUTLET.get().defaultBlockState(), 2);
        var r = (dev.bwr.mod.reactor.ReactorControllerBlockEntity) l.getBlockEntity(cp);
        var validate = r.getClass().getDeclaredMethod("revalidate", Level.class);
        validate.setAccessible(true);
        validate.invoke(r, l);
        h.assertTrue(r.isFormed(), "steam source fixture incomplete");
        var n = (dev.bwr.mod.reactor.RpvSteamOutletBlockEntity) l.getBlockEntity(np);
        n.noteController(cp);
        n.setPosition(1);
        var f = r.core().getClass().getDeclaredField("vessel");
        f.setAccessible(true);
        ((dev.bwr.core.thermal.PressureVessel) f.get(r.core())).restorePressurePsig(1000);
        // Nozzle -> riser -> the valve's side inlet; its downward outlet -> the tank's steam inlet.
        pipe(l, np.east(), np.east(2));
        pipe(l, np.east(2), new BlockPos(np.getX() + 2, np.getY(), o.getZ() - 5));
        pipe(l, new BlockPos(np.getX() + 2, np.getY(), o.getZ() - 5), o.offset(2, 6, -5));
        pipe(l, o.offset(2, 6, -5), o.offset(2, 3, -5));
        pipe(l, o.offset(2, 3, -4), o.offset(2, 3, -4));
        var vp = o.offset(2, 3, -3);
        l.setBlock(vp, BwrBlocks.ADS_RELIEF_VALVE.get().defaultBlockState().setValue(AdsReliefValveBlock.FACING, Direction.EAST), 3);
        pipe(l, o.offset(2, 2, -3), o.offset(2, 2, -1));
        var relief = (SafetyReliefValveBlockEntity) l.getBlockEntity(vp);
        n.setComputerControlled(true);
        n.setPosition(1);
        n.refreshAttachment(l);
        SuppressionBasinRegressionTests.validate(pool);
        relief.revalidateDischarge(l);
        h.assertTrue(relief.isDischargeSubmerged(), "relief valve outlet does not reach the tank: " + relief.dischargePath());
        long now = l.getGameTime();
        var data = l.getServer().getWorldData().overworldData();
        try {
            data.setGameTime(now + 1);
            n.flowKgPerS(1000);
            double shut = pool.pool().getMassKg();
            tick(l, pool);
            h.assertTrue(Math.abs(pool.pool().getMassKg() - shut) < 1e-6, "a shut relief valve passed steam to the pool");
            relief.setComputerControlled(true);
            relief.setOpen(true);
            h.assertTrue(l.getBlockState(vp).getValue(SafetyReliefValveBlock.OPEN), "the open valve must show open (its model)");
            data.setGameTime(now + 2);
            n.flowKgPerS(1000);
            double before = pool.pool().getMassKg();
            tick(l, pool);
            double gained = pool.pool().getMassKg() - before;
            h.assertTrue(relief.getLastFlowKgPerS() > 0 && gained > 0, "open relief valve did not relieve to the pool: flow=" + relief.getLastFlowKgPerS() + " gained=" + gained);
            h.assertTrue(Math.abs(gained - relief.getLastFlowKgPerS() / 20) < 1e-6 * Math.max(1, gained), "pool gained " + gained + " kg but the valve passed " + relief.getLastFlowKgPerS() / 20);
        } finally {
            data.setGameTime(now);
        }
        h.succeed();
    }

    /** The MSIV's stem, springs and pointer are drawn in the upper cells, so all three cells carry the open flag. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void msivUpperCellsShowTheStroke(GameTestHelper h) {
        var l = h.getLevel();
        var p = new BlockPos(26300 + SHIFT, 200, 26300);
        load(l, p, 8);
        var block = BwrBlocks.MSIV.get();
        var state = block.placementState().setValue(MainSteamIsolationValveBlock.FACING, Direction.EAST);
        l.setBlock(p, state, 3);
        block.setPlacedBy(l, p, state, null, new ItemStack(block));
        var be = (MainSteamIsolationValveBlockEntity) l.getBlockEntity(p);
        be.energy().receiveEnergy(MainSteamIsolationValveBlockEntity.ENERGY_CAPACITY_FE, false);
        be.setDemandOpen(true);
        MainSteamIsolationValveBlockEntity.serverTick(l, p, l.getBlockState(p), be);
        h.assertTrue(be.getPosition() > 0 && be.getPosition() < 1, "MSIV did not start to stroke: " + be.getPosition());
        for (int part = 0; part < 3; part++)
            h.assertTrue(l.getBlockState(p.above(part)).getValue(MainSteamIsolationValveBlock.OPEN), "a stroking MSIV must show open in cell " + part);
        for (int i = 0; i < 90; i++) {
            be.energy().receiveEnergy(MainSteamIsolationValveBlockEntity.ENERGY_CAPACITY_FE, false);
            MainSteamIsolationValveBlockEntity.serverTick(l, p, l.getBlockState(p), be);
        }
        be.setDemandOpen(false);
        for (int i = 0; i < 90; i++) MainSteamIsolationValveBlockEntity.serverTick(l, p, l.getBlockState(p), be);
        h.assertTrue(be.getPosition() == 0, "MSIV did not close: " + be.getPosition());
        for (int part = 0; part < 3; part++) {
            var s = l.getBlockState(p.above(part));
            h.assertTrue(!s.getValue(MainSteamIsolationValveBlock.OPEN) && s.getValue(MainSteamIsolationValveBlock.PART) == part
                    && s.getValue(MainSteamIsolationValveBlock.FACING) == Direction.EAST, "a shut MSIV must show shut in cell " + part + ": " + s);
        }
        for (int part = 2; part >= 0; part--) l.removeBlock(p.above(part), false);
        h.succeed();
    }

    private static void tick(ServerLevel l, dev.bwr.mod.suppression.SuppressionPoolBlockEntity pool) {
        dev.bwr.mod.suppression.SuppressionPoolBlockEntity.serverTick(l, pool.getBlockPos(), pool.getBlockState(), pool);
    }

    private static void pipe(ServerLevel l, BlockPos a, BlockPos b) {
        for (var p : BlockPos.betweenClosed(a, b)) l.setBlock(p, BwrBlocks.PRESSURISED_TUBE.get().defaultBlockState(), 3);
    }
}
