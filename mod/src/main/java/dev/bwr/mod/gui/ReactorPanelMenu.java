package dev.bwr.mod.gui;

import dev.bwr.core.ReactorCore;
import dev.bwr.core.kinetics.RodWorth;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.ReactorStructure;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrMenus;
import dev.bwr.mod.rods.ControlRodDriveNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * The control room panel — the operating half of {@code SPEC.md} section 10.
 *
 * <p>Carries the core map (flux by default, rod positions on the other
 * overlay), the plant readouts an operator works from, and a small set of
 * actuators: scram, rod motion, and the vessel head.
 *
 * <h2>No protection system</h2>
 * Every command here is unconditional. {@link #CMD_SCRAM} scrams — it does not
 * look at power, pressure, period or level first, and there is deliberately no
 * command that would. Colouring a pressure readout red is a display decision
 * made on the client and nothing in the plant reads it. If a player wants the
 * reactor to trip on 1250 psig, they write that in Lua against the peripheral,
 * which is the same actuator this button uses.
 *
 * <h2>Instruments, not ground truth</h2>
 * Everything in this snapshot that an operator would read off a meter comes
 * from the instrument that measures it, never from the model behind it. In
 * particular the period readouts are {@code PeriodMeter} indications derived
 * from a detector's own signal — {@code ReactorCore.getTruePeriodSeconds()} is
 * the analytic n/(dn/dt) of the point kinetics and its javadoc, and
 * {@code PointKinetics.getPeriodSeconds()}'s, and {@code PeriodMeter}'s all say
 * in as many words that it must never be wired to a panel. A saturated source
 * range channel reads a plausible count rate on the way back down and its
 * period meter faithfully reports a <i>negative</i> period on a core that is
 * climbing; that is the central instrument hazard of a BWR startup, and a panel
 * fed from the model would hide it. Both channels are sent so the player can do
 * the cross-check themselves — picking one for them would be a judgement.
 */
public class ReactorPanelMenu extends BwrMenu {

    /** Scram. Unconditional, immediate, no interlock, no conditions examined. */
    public static final int CMD_SCRAM = 0;
    /** Reset the scram signal so the drives can be driven out again. */
    public static final int CMD_RESET_SCRAM = 1;
    /** a = {@link VesselState} ordinal. Refused, with a reason, if physically impossible. */
    public static final int CMD_SET_VESSEL_STATE = 2;
    /** Swap this screen for the refuelling screen, if the head is off. */
    public static final int CMD_OPEN_REFUELLING = 3;
    /** a = rod index or -1 for every rod, b = notch label 00..48. */
    public static final int CMD_ROD_NOTCH = 4;
    /** a = rod index or -1 for every rod, b = signed notch count to move. */
    public static final int CMD_ROD_STEP = 5;

    /** The map is re-solved at 1 Hz, so there is nothing to gain from sending it faster. */
    private static final int MAP_EVERY_N_SYNCS = 4;

    private int syncCount;

    // --- client-visible snapshot -------------------------------------

    public boolean formed;
    public VesselState vesselState = VesselState.SHUTDOWN;
    public List<String> statusLines = new ArrayList<>();

    public double powerFractionOfRated;
    public double thermalMW;
    public double decayHeatFraction;
    public double pressurePsig;
    public double pressureRatePsiPerS;
    public double indicatedLevelIn;
    public double coreFlowFraction;
    public double coreFlowKgPerS;
    public double steamKgPerS;
    public double feedwaterKgPerS;
    public double reactivityDollars;
    public double reactivityDkOverK;
    /** Indicated period from source range channel 0, seconds. Meter, not model. */
    public double srmPeriodSeconds;
    /** Indicated period from intermediate range channel 0, seconds. Meter, not model. */
    public double irmPeriodSeconds;
    public double betaEffective;
    public double fuelTemperatureC;
    public double cladTemperatureC;
    public double peakCladTemperatureC;
    public double coolantTemperatureC;
    public double voidFraction;
    public double exitVoidFraction;
    public double xenonFraction;
    public double averageBurnup;
    public double aggregateKInf;
    public double oxidationFraction;
    public double hydrogenKg;
    public double uncoveredFuelFraction;
    public double sprayRingCompleteness;
    public double aprmPercent;
    public double srmCountsPerSecond;

    public int chargedAccumulators;
    public int rodCount;
    public int scrammableRods;
    public int operableDrives;
    public int poweredDrives;
    public int waterSuppliedDrives;
    public int loadedAssemblies;
    public int assemblyCount;
    public int pumpCount;
    public boolean scramActive;

    /** Full core display labels, 00..48, one per rod. */
    public int[] rodNotchLabels = new int[0];
    /** What each drive has been told to go to, same units. */
    public int[] rodDemandLabels = new int[0];

    /**
     * Shape of the control rod lattice, columns (x) by rows (z).
     *
     * <p>Sent rather than inferred. {@code ReactorStructure} puts one drive
     * under every 2x2 assembly group and walks x outside z, so rod index
     * {@code r = xIndex * rodsPerZ + zIndex} — which only looks square when the
     * vessel interior is. Interiors are legal from 5x5 to 21x21 in either
     * direction, so a rectangular core is ordinary, and a client guessing
     * {@code ceil(sqrt(count))} draws every rod but the first in the wrong
     * place. Zero when the shape is unknown.
     */
    public int rodsPerX;
    public int rodsPerZ;

    /** Null until the first map arrives. */
    public CoreMapSnapshot map;

    // -----------------------------------------------------------------

    /** Client constructor. */
    public ReactorPanelMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(containerId, inventory, extra.readBlockPos());
    }

    public ReactorPanelMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(BwrMenus.REACTOR_PANEL.get(), containerId, inventory, pos,
                BwrBlocks.REACTOR_CONTROLLER.get());
    }

    public static void open(ServerPlayer player, ReactorControllerBlockEntity be) {
        player.openMenu(new SimpleMenuProvider(
                        (id, inventory, who) -> new ReactorPanelMenu(id, inventory, be.getBlockPos()),
                        Component.translatable("menu.bwr.reactor_panel")),
                be.getBlockPos());
    }

    private ReactorControllerBlockEntity controller() {
        return blockEntity(ReactorControllerBlockEntity.class);
    }

    // -----------------------------------------------------------------
    // Snapshot
    // -----------------------------------------------------------------

    @Override
    protected void writeSnapshot(FriendlyByteBuf buf) {
        ReactorControllerBlockEntity be = controller();
        boolean isFormed = be != null && be.isFormed();
        boolean withMap = isFormed && (syncCount++ % MAP_EVERY_N_SYNCS == 0);

        buf.writeBoolean(isFormed);
        buf.writeByte(be == null ? VesselState.SHUTDOWN.ordinal() : be.vesselState().ordinal());

        if (!isFormed) {
            List<String> lines = be == null ? List.of("No reactor controller here.") : be.statusLines();
            buf.writeVarInt(Math.min(lines.size(), 12));
            for (int i = 0; i < Math.min(lines.size(), 12); i++) {
                buf.writeUtf(lines.get(i), 200);
            }
            return;
        }

        ReactorCore core = be.core();
        ControlRodDriveNetwork rods = be.rods();

        buf.writeDouble(core.getTotalPowerFractionOfRated());
        buf.writeDouble(core.getThermalPowerMW());
        buf.writeDouble(core.getDecayHeatFraction());
        buf.writeDouble(core.getPressurePsig());
        buf.writeDouble(core.getPressureRateOfChangePsiPerSecond());
        buf.writeDouble(core.getIndicatedLevelIn());
        buf.writeDouble(core.getCoreFlowFraction());
        buf.writeDouble(core.getCoreFlowKgPerS());
        buf.writeDouble(core.getSteamGenerationKgPerS());
        buf.writeDouble(core.getFeedwaterFlowKgPerS());
        buf.writeDouble(core.getReactivityDollars());
        buf.writeDouble(core.getReactivityDkOverK());
        // Indicated period from the instruments, one channel of each range.
        // Never core.getTruePeriodSeconds(): see the class comment. Two
        // channels rather than one because the whole point is that they can
        // disagree, and choosing between them is the player's call.
        buf.writeDouble(core.getSourceRangePeriodSeconds(0));
        buf.writeDouble(core.getIntermediateRangePeriodSeconds(0));
        buf.writeDouble(core.getBetaEffective());
        buf.writeDouble(core.getFuelTemperatureC());
        buf.writeDouble(core.getCladTemperatureC());
        buf.writeDouble(core.getPeakCladTemperatureC());
        buf.writeDouble(core.getCoolantTemperatureC());
        buf.writeDouble(core.getVoidFraction());
        buf.writeDouble(core.getExitVoidFraction());
        buf.writeDouble(core.getXenonFractionOfRatedEquilibrium());
        buf.writeDouble(core.getAverageBurnupMwdPerTonne());
        buf.writeDouble(core.getAggregateKInfinity());
        buf.writeDouble(core.getOxidationFraction());
        buf.writeDouble(core.getHydrogenGeneratedKg());
        buf.writeDouble(core.getUncoveredFuelFraction());
        buf.writeDouble(be.sprayRingCompleteness());
        buf.writeDouble(core.getAveragePowerRangePercent(0));
        buf.writeDouble(core.getSourceRangeCountsPerSecond(0));

        buf.writeVarInt(core.getChargedAccumulatorCount());
        buf.writeVarInt(core.getControlRodCount());
        buf.writeVarInt(rods == null ? 0 : rods.getScrammableRodCount());
        buf.writeVarInt(rods == null ? 0 : rods.getOperableDriveCount());
        buf.writeVarInt(rods == null ? 0 : rods.getPoweredDriveCount());
        buf.writeVarInt(rods == null ? 0 : rods.getWaterSuppliedDriveCount());
        buf.writeVarInt(core.getCoreLoading().loadedAssemblyCount());
        buf.writeVarInt(be.assemblyCount());
        buf.writeVarInt(be.pumpCount());
        buf.writeBoolean(core.isScramActive());

        int n = core.getControlRodCount();
        buf.writeVarInt(n);
        // Lattice shape, so the rod overlay can place the drives where they
        // physically are instead of guessing a square. One drive per 2x2
        // assembly group means floor(interior/2) of them along each axis.
        ReactorStructure structure = be.structure();
        buf.writeVarInt(structure == null ? 0
                : (structure.interiorMax().getX() - structure.interiorMin().getX() + 1) / 2);
        buf.writeVarInt(structure == null ? 0
                : (structure.interiorMax().getZ() - structure.interiorMin().getZ() + 1) / 2);
        for (int r = 0; r < n; r++) {
            buf.writeByte(core.getRodNotchLabel(r));
            buf.writeByte(rods == null ? core.getRodNotchLabel(r) : rods.getCommandedNotchLabel(r));
        }

        buf.writeBoolean(withMap);
        if (withMap) {
            CoreMapSnapshot.write(buf, core, be.assemblyCount());
        }
    }

    @Override
    protected void readSnapshot(FriendlyByteBuf buf) {
        formed = buf.readBoolean();
        vesselState = VesselState.values()[buf.readByte() % VesselState.values().length];

        if (!formed) {
            int lines = buf.readVarInt();
            List<String> out = new ArrayList<>(lines);
            for (int i = 0; i < lines; i++) {
                out.add(buf.readUtf(200));
            }
            statusLines = out;
            return;
        }
        statusLines = List.of();

        powerFractionOfRated = buf.readDouble();
        thermalMW = buf.readDouble();
        decayHeatFraction = buf.readDouble();
        pressurePsig = buf.readDouble();
        pressureRatePsiPerS = buf.readDouble();
        indicatedLevelIn = buf.readDouble();
        coreFlowFraction = buf.readDouble();
        coreFlowKgPerS = buf.readDouble();
        steamKgPerS = buf.readDouble();
        feedwaterKgPerS = buf.readDouble();
        reactivityDollars = buf.readDouble();
        reactivityDkOverK = buf.readDouble();
        srmPeriodSeconds = buf.readDouble();
        irmPeriodSeconds = buf.readDouble();
        betaEffective = buf.readDouble();
        fuelTemperatureC = buf.readDouble();
        cladTemperatureC = buf.readDouble();
        peakCladTemperatureC = buf.readDouble();
        coolantTemperatureC = buf.readDouble();
        voidFraction = buf.readDouble();
        exitVoidFraction = buf.readDouble();
        xenonFraction = buf.readDouble();
        averageBurnup = buf.readDouble();
        aggregateKInf = buf.readDouble();
        oxidationFraction = buf.readDouble();
        hydrogenKg = buf.readDouble();
        uncoveredFuelFraction = buf.readDouble();
        sprayRingCompleteness = buf.readDouble();
        aprmPercent = buf.readDouble();
        srmCountsPerSecond = buf.readDouble();

        chargedAccumulators = buf.readVarInt();
        rodCount = buf.readVarInt();
        scrammableRods = buf.readVarInt();
        operableDrives = buf.readVarInt();
        poweredDrives = buf.readVarInt();
        waterSuppliedDrives = buf.readVarInt();
        loadedAssemblies = buf.readVarInt();
        assemblyCount = buf.readVarInt();
        pumpCount = buf.readVarInt();
        scramActive = buf.readBoolean();

        int n = buf.readVarInt();
        rodsPerX = buf.readVarInt();
        rodsPerZ = buf.readVarInt();
        rodNotchLabels = new int[n];
        rodDemandLabels = new int[n];
        for (int r = 0; r < n; r++) {
            rodNotchLabels[r] = buf.readByte();
            rodDemandLabels[r] = buf.readByte();
        }

        if (buf.readBoolean()) {
            map = CoreMapSnapshot.read(buf);
        }
    }

    // -----------------------------------------------------------------
    // Commands
    // -----------------------------------------------------------------

    @Override
    public void handleCommand(ServerPlayer sender, int command, int a, int b) {
        ReactorControllerBlockEntity be = controller();
        if (be == null) {
            return;
        }

        switch (command) {
            case CMD_SCRAM -> {
                // Unconditional. Nothing is checked; that is the whole point.
                if (be.rods() != null) {
                    be.rods().scram();
                } else if (be.core() != null) {
                    be.core().scram();
                }
            }
            case CMD_RESET_SCRAM -> {
                if (be.rods() != null) {
                    be.rods().resetScram();
                } else if (be.core() != null) {
                    be.core().resetScram();
                }
            }
            case CMD_SET_VESSEL_STATE -> {
                VesselState[] states = VesselState.values();
                if (a >= 0 && a < states.length) {
                    String refusal = be.setVesselState(states[a]);
                    if (refusal != null) {
                        refuse(sender, refusal);
                    }
                }
            }
            case CMD_OPEN_REFUELLING -> {
                String blocked = be.refuellingBlockedReason();
                if (blocked != null) {
                    refuse(sender, "Refuelling is not possible: " + blocked + ".");
                    return;
                }
                RefuellingMenu.open(sender, be);
                return;
            }
            case CMD_ROD_NOTCH -> commandRods(be, a, b, false);
            case CMD_ROD_STEP -> commandRods(be, a, b, true);
            default -> {
                return;
            }
        }
        markSnapshotDirty();
    }

    /**
     * Move rods. A rod index of -1 means every rod, which is a convenience for
     * the player's fingers and not a bank: the drives remain individual and each
     * one still has to find its own way there on its own accumulator.
     *
     * <p><b>Both arguments arrive off the wire and neither is trusted.</b> The
     * index is range-checked against the rod count; the step count is clamped to
     * plus or minus a full stroke before it goes anywhere near the drive
     * network. That clamp is not cosmetic: {@code withdraw} adds before it
     * clamps, so a client sending {@code Integer.MAX_VALUE} used to overflow the
     * sum to a large negative notch index, which {@code Math.min(target, 24)}
     * then happily kept and stored. The next tick fed it to
     * {@code ReactorCore.setRodNotchDemand} inside the block entity ticker,
     * which throws for a negative index, and a throw there is a
     * ReportedException — the server goes down. Clamping first is one line and
     * makes the overflow unreachable; the drive network clamps defensively too,
     * so neither layer is the only thing standing between a hostile packet and
     * a crash.
     */
    private void commandRods(ReactorControllerBlockEntity be, int rodIndex, int value,
                             boolean relative) {
        ControlRodDriveNetwork rods = be.rods();
        if (rods == null) {
            return;
        }
        int count = rods.getControlRodCount();
        int from = rodIndex < 0 ? 0 : rodIndex;
        int to = rodIndex < 0 ? count - 1 : rodIndex;
        if (from < 0 || to >= count) {
            return;
        }
        // A full stroke is 24 notches, so anything beyond that is already
        // saturated. Clamping to the symmetric range also keeps the negation
        // below safe: -Integer.MIN_VALUE is Integer.MIN_VALUE, and negating a
        // clamped value cannot overflow.
        int step = Math.max(-RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN,
                Math.min(RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN, value));
        for (int r = from; r <= to; r++) {
            if (relative) {
                if (step >= 0) {
                    rods.withdraw(r, step);
                } else {
                    rods.insert(r, -step);
                }
            } else {
                int label = Math.max(0, Math.min(48, value));
                rods.setNotchLabelDemand(r, label - (label % 2));
            }
        }
    }
}
