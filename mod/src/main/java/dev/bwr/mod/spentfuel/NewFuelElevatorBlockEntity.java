package dev.bwr.mod.spentfuel;

import dev.bwr.core.refuelling.BridgeMotion;
import dev.bwr.core.refuelling.FuelElevatorMotion;
import dev.bwr.core.refuelling.FuelElevatorMotion.Kind;
import dev.bwr.mod.fuel.SpentFuelHeat;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The new fuel elevator: a hoist on the rim of a formed spent fuel pool that lowers a fuel assembly down a guide frame under the
 * water and sets it into the first free rack cell, and can raise one back out on command. Real plants receive new fuel on the refuel
 * floor and lower it into the pool with a "new fuel elevator" (NRC, US EPR FSAR Chapter 9, ML14209A587; KNS APR1400 fuel handling paper; see
 * {@code SPENT-FUEL-POOL.md}).
 *
 * <h2>Where it goes</h2>
 * It stands <b>on top of the rim</b> of a pool wall: the block under it is a pool shell block on the top row of a straight run of wall
 * (not a corner), of a pool that is formed. The pool is found when it is asked (loading, a command, a screen, the second after it is
 * placed or loaded), never by a scan: the pools of the level are the registry's, and the one whose rim this block is on is the one.
 * The guide frame hangs in the interior column against the inside face of that wall, from the rim to the pool floor.
 *
 * <h2>How it works</h2>
 * Up to {@value #HATCH_SLOTS} assemblies wait in the hatch (an item handler: a hopper or a pipe fills it, so does a right-click); they go one
 * at a time. A trip is {@link FuelElevatorMotion}: the carriage rides down the guide, the assembly runs over the rack tops to the cell and is
 * set down. It is refused, with the reason, when the elevator is not on a formed pool, every cell is taken (counting those the waiting
 * assemblies will use), or the water is too shallow for an assembly at the transfer height (the bridge's 1.5 m hoist limit). Raising is
 * a command ({@link #raise}): the carriage goes down empty, the assembly that has cooled longest is taken out of its cell when it arrives and rides
 * up to the hatch, where it waits for a hopper, a pipe or a hand. <b>Nothing is hurried or started on its own</b> beyond what was put in the hatch.
 *
 * <h2>No ticker</h2>
 * The block has no ticker. A trip schedules ordinary block ticks for the two moments that matter (the assembly changes hands with the rack;
 * the carriage is home), nothing in between. The client draws the carriage from the trip's start tick and its legs; the dose of an assembly
 * on the way is computed from the same pose when an instrument asks ({@link #doseRate}), so an idle or a moving elevator costs the server
 * the same. A blocked elevator (no cell, no water) schedules nothing and waits for {@link #retry()} or for another assembly.
 *
 * <h2>Fuel is never lost</h2>
 * An assembly is in exactly one place: the hatch queue, the trip, a rack cell or the landing slot. If a cell was taken meanwhile it goes to
 * the next free one; if there is none (or the pool is gone) it comes back to the landing slot, or drops on the ground if that is taken.
 */
public class NewFuelElevatorBlockEntity extends BlockEntity {

    public static final int HATCH_SLOTS = 4;
    /** Slot numbers of the item handler: 0 to 3 the hatch queue (insert only), 4 the landing (take only). */
    public static final int SLOT_LANDING = HATCH_SLOTS;
    public static final ResourceLocation RADIATION_ID = ResourceLocation.fromNamespaceAndPath("bwr", "new_fuel_elevators");

    private static final Map<Level, Set<BlockPos>> ELEVATORS = new WeakHashMap<>();

    /** Why the elevator refused something. */
    public enum Why {
        NOT_ON_POOL, POOL_UNFORMED, POOL_FULL, WATER_TOO_LOW, NOT_AN_ASSEMBLY, HATCH_FULL, BUSY, NOTHING_TO_RAISE, LANDING_OCCUPIED
    }

    /** A refusal: a stable key for programs and tests, and the sentence a player is shown. */
    public record Refusal(Why why, Component text) {
        public String english() {
            return BwrText.english(text);
        }
    }

    /** What the elevator is on: the pool, which way is into the pool, the guide column and the heights. */
    public record Link(SpentFuelPoolBlockEntity pool, Direction inward, double guideX, double guideZ, double hatchY, double floorY) {
        public double seatY() {
            return floorY + SpentFuelPoolLayout.RACK_HEIGHT;
        }
    }

    /** What a screen and a program read. */
    public record Status(boolean linked, String problem, String state, String leg, double progress, double secondsLeft, int queued,
                         boolean landing, double landingDecayKw, double inFlightDecayKw, int freeCells, int cells,
                         double waterOverTransferM, double minCoverM, String lastRefusal, double coolestKw, double levelM) {
    }

    // ---- contents
    private final List<ItemStack> inbound = new ArrayList<>();
    private ItemStack landing = ItemStack.EMPTY;

    // ---- the trip
    @Nullable
    private Kind tripKind;
    private ItemStack flying = ItemStack.EMPTY;
    private boolean handedOff;
    private long tripStart;
    private double tGuideX, tGuideZ, tHatchY, tSeatY, tCellX, tCellZ;
    private BlockPos tRack = BlockPos.ZERO;
    private int tCell;
    private int[] tLegs = new int[0];
    @Nullable
    private FuelElevatorMotion.Trip trip;

    // ---- what the clients need to draw the frame (saved when linked, synced)
    private int inwardIndex = -1;
    private double floorY;

    // ---- not saved
    @Nullable
    private Refusal lastRefusal;
    private boolean registered;

    public NewFuelElevatorBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.NEW_FUEL_ELEVATOR.get(), pos, state);
    }

    // ---------------------------------------------------------------
    // Registry of the loaded elevators (for the dose provider only)
    // ---------------------------------------------------------------

    public static void registerRadiation() {
        RadiationSources.register(RADIATION_ID, NewFuelElevatorBlockEntity::doseRateAt);
    }

    private static synchronized Set<BlockPos> elevators(Level level) {
        Set<BlockPos> set = ELEVATORS.get(level);
        return set == null ? Set.of() : Set.copyOf(set);
    }

    private void remember() {
        if (level != null && !level.isClientSide() && !isRemoved()) {
            synchronized (NewFuelElevatorBlockEntity.class) {
                ELEVATORS.computeIfAbsent(level, k -> new HashSet<>()).add(worldPosition.immutable());
            }
        }
    }

    private void forget() {
        if (level != null && !level.isClientSide()) {
            synchronized (NewFuelElevatorBlockEntity.class) {
                Set<BlockPos> set = ELEVATORS.get(level);
                if (set != null) {
                    set.remove(worldPosition);
                }
            }
        }
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        remember();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        remember();
        if (level instanceof ServerLevel server) {
            // one look at the pool shortly after load (and a trip that was under way picks up where the clock says it is)
            schedule(server, tripKind != null ? Math.max(1, nextEventDelay(server.getGameTime())) : 20);
        }
    }

    @Override
    public void setRemoved() {
        forget();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        forget();
        super.onChunkUnloaded();
    }

    // ---------------------------------------------------------------
    // Finding the pool
    // ---------------------------------------------------------------

    @Nullable
    private Refusal linkProblem;

    /**
     * Finds the formed pool whose rim this block stands on, updating what the clients draw. Null (and {@link #linkProblem}) when there is
     * none. Server side; cheap: a loop over the loaded pool controllers of the level.
     */
    @Nullable
    public Link link() {
        if (!(level instanceof ServerLevel server)) {
            return null;
        }
        BlockPos p = worldPosition;
        boolean nearUnformed = false;
        String unformedWhy = "";
        for (SpentFuelPoolBlockEntity pool : SpentFuelPoolRegistry.controllers(server)) {
            if (Math.abs(pool.getBlockPos().getX() - p.getX()) > 26 || Math.abs(pool.getBlockPos().getY() - p.getY()) > 26
                    || Math.abs(pool.getBlockPos().getZ() - p.getZ()) > 26) {
                continue;
            }
            pool.refreshForReport();
            var layout = pool.layout();
            if (!pool.isFormed() || layout == null) {
                nearUnformed = true;
                unformedWhy = pool.problem() == null ? "" : BwrText.english(pool.problem());
                continue;
            }
            BlockPos lo = layout.min();
            BlockPos hi = layout.max();
            if (p.getY() != hi.getY() + 1 || p.getX() < lo.getX() || p.getX() > hi.getX() || p.getZ() < lo.getZ() || p.getZ() > hi.getZ()) {
                continue;
            }
            boolean onX = p.getX() == lo.getX() || p.getX() == hi.getX();
            boolean onZ = p.getZ() == lo.getZ() || p.getZ() == hi.getZ();
            if (onX == onZ || !SpentFuelPoolLayout.shell(server.getBlockState(p.below()))) {
                continue;
            }
            Direction inward = onX ? (p.getX() == lo.getX() ? Direction.EAST : Direction.WEST)
                    : (p.getZ() == lo.getZ() ? Direction.SOUTH : Direction.NORTH);
            double gx = p.getX() + 0.5 + inward.getStepX() * 0.95;
            double gz = p.getZ() + 0.5 + inward.getStepZ() * 0.95;
            Link link = new Link(pool, inward, gx, gz, p.getY() + FuelElevatorMotion.HATCH_ABOVE_BLOCK_M, lo.getY() + 1);
            linkProblem = null;
            setFrame(inward.get2DDataValue(), link.floorY());
            return link;
        }
        linkProblem = nearUnformed
                ? refusal(Why.POOL_UNFORMED, unformedWhy)
                : refusal(Why.NOT_ON_POOL, "");
        setFrame(-1, 0.0);
        return null;
    }

    private void setFrame(int inward, double floor) {
        if (inward != inwardIndex || floor != floorY) {
            inwardIndex = inward;
            floorY = floor;
            setChanged();
            sync();
        }
    }

    /** Which way is into the pool as last found (horizontal index), or -1 when the elevator is not on a pool. */
    public int inwardIndex() {
        return inwardIndex;
    }

    public double floorY() {
        return floorY;
    }

    // ---------------------------------------------------------------
    // Refusals
    // ---------------------------------------------------------------

    private static Refusal refusal(Why why, String detail) {
        return new Refusal(why, switch (why) {
            case NOT_ON_POOL -> BwrText.tr("status.bwr.new_fuel_elevator.not_on_pool",
                    "Not on a pool: stand the elevator on top of the rim of a formed spent fuel pool wall, on a straight run, not a corner.");
            case POOL_UNFORMED -> BwrText.tr("status.bwr.new_fuel_elevator.pool_unformed",
                    "The pool is not formed. %s", detail);
            case POOL_FULL -> BwrText.tr("status.bwr.new_fuel_elevator.pool_full", "Every rack cell is taken (or promised to an assembly already waiting).");
            case WATER_TOO_LOW -> BwrText.tr("status.bwr.new_fuel_elevator.water_too_low",
                    "The water is too low: %s", detail);
            case NOT_AN_ASSEMBLY -> BwrText.tr("status.bwr.new_fuel_elevator.not_an_assembly", "Only fuel assemblies go in the hatch.");
            case HATCH_FULL -> BwrText.tr("status.bwr.new_fuel_elevator.hatch_full", "The hatch is full: %s assemblies are waiting.", HATCH_SLOTS);
            case BUSY -> BwrText.tr("status.bwr.new_fuel_elevator.busy", "The carriage is busy: wait for it to finish.");
            case NOTHING_TO_RAISE -> BwrText.tr("status.bwr.new_fuel_elevator.nothing_to_raise", "There is no assembly in the pool to raise.");
            case LANDING_OCCUPIED -> BwrText.tr("status.bwr.new_fuel_elevator.landing_occupied", "An assembly is waiting at the hatch: take it out first.");
        });
    }

    private static boolean isAssembly(ItemStack stack) {
        return !stack.isEmpty() && stack.is(BwrItems.FUEL_ASSEMBLY.get());
    }

    /** Metres of water over the handle of an assembly at the transfer height of the racks: what the water-level rule checks. */
    private static double waterOverTransfer(Link link) {
        return link.pool().surfaceY() - (link.seatY() + FuelElevatorMotion.CLEARANCE_M);
    }

    @Nullable
    private Refusal checkLower(@Nullable Link link, int pendingBefore) {
        if (link == null) {
            return linkProblem != null ? linkProblem : refusal(Why.NOT_ON_POOL, "");
        }
        if (link.pool().freeCells() - pendingBefore <= 0) {
            return refusal(Why.POOL_FULL, "");
        }
        if (!FuelElevatorMotion.coveredEnough(link.pool().surfaceY(), link.seatY())) {
            return refusal(Why.WATER_TOO_LOW, String.format(Locale.ROOT,
                    "%.1f m of water over an assembly at the transfer height, %.1f m needed (the pool level is %.2f m, the racks are %d m tall).",
                    Math.max(0.0, waterOverTransfer(link)), FuelElevatorMotion.MIN_COVER_M, link.pool().pool().levelM(), SpentFuelPoolLayout.RACK_HEIGHT));
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------

    /**
     * Puts one assembly in the hatch if the elevator can take it. Returns null when it was (or, with {@code simulate}, would be) taken, else
     * why not. A taken assembly goes down as soon as the carriage is free.
     */
    @Nullable
    public Refusal offer(ItemStack one, boolean simulate) {
        if (!isAssembly(one)) {
            return note(refusal(Why.NOT_AN_ASSEMBLY, ""), simulate);
        }
        if (inbound.size() >= HATCH_SLOTS) {
            return note(refusal(Why.HATCH_FULL, ""), simulate);
        }
        Link link = link();
        Refusal why = checkLower(link, inbound.size() + (tripKind == Kind.LOWER && !handedOff ? 1 : 0));
        if (why != null) {
            return note(why, simulate);
        }
        if (!simulate) {
            inbound.add(one.copyWithCount(1));
            lastRefusal = null;
            setChanged();
            if (level instanceof ServerLevel server) {
                startNext(server);
            }
            sync();
        }
        return null;
    }

    @Nullable
    private Refusal note(Refusal why, boolean simulate) {
        if (!simulate) {
            lastRefusal = why;
        }
        return why;
    }

    /** Tries again to start the next waiting assembly (a blocked elevator waits for this, or for another assembly). Returns why not, or null. */
    @Nullable
    public Refusal retry() {
        if (!(level instanceof ServerLevel server)) {
            return null;
        }
        if (tripKind != null) {
            return refusal(Why.BUSY, "");
        }
        startNext(server);
        return inbound.isEmpty() ? null : lastRefusal;
    }

    private void startNext(ServerLevel server) {
        if (tripKind != null || inbound.isEmpty()) {
            return;
        }
        if (!landing.isEmpty()) {
            lastRefusal = refusal(Why.LANDING_OCCUPIED, "");
            return;
        }
        Link link = link();
        Refusal why = checkLower(link, 0);
        if (why != null) {
            lastRefusal = why;
            return;
        }
        var cell = link.pool().firstFreeCell();
        double[] column = columnOf(cell.rack(), cell.cell());
        begin(server, link, Kind.LOWER, cell.rack(), cell.cell(), column, inbound.remove(0));
    }

    /** Raises the assembly in the given cell, or the one that has cooled longest. Returns why not, or null when the carriage is on its way. */
    @Nullable
    public Refusal raise(@Nullable SpentFuelPoolBlockEntity.CellRef cell) {
        if (!(level instanceof ServerLevel server)) {
            return null;
        }
        Link link = link();
        if (link == null) {
            return linkProblem != null ? linkProblem : refusal(Why.NOT_ON_POOL, "");
        }
        if (tripKind != null || !inbound.isEmpty()) {
            return note(refusal(Why.BUSY, ""), false);
        }
        if (!landing.isEmpty()) {
            return note(refusal(Why.LANDING_OCCUPIED, ""), false);
        }
        var target = cell != null ? cell : link.pool().coolestCell();
        if (target == null || link.pool().stackAt(target.rack(), target.cell()).isEmpty()) {
            return note(refusal(Why.NOTHING_TO_RAISE, ""), false);
        }
        lastRefusal = null;
        begin(server, link, Kind.RAISE, target.rack(), target.cell(), columnOf(target.rack(), target.cell()), ItemStack.EMPTY);
        return null;
    }

    private static double[] columnOf(BlockPos rack, int cell) {
        return new double[] {rack.getX() + 0.25 + 0.5 * (cell & 1), rack.getZ() + 0.25 + 0.5 * (cell >> 1)};
    }

    private void begin(ServerLevel server, Link link, Kind kind, BlockPos rack, int cell, double[] column, ItemStack stack) {
        tripKind = kind;
        flying = stack;
        handedOff = false;
        tripStart = server.getGameTime();
        tGuideX = link.guideX();
        tGuideZ = link.guideZ();
        tHatchY = link.hatchY();
        tSeatY = link.seatY();
        tCellX = column[0];
        tCellZ = column[1];
        tRack = rack.immutable();
        tCell = cell;
        trip = FuelElevatorMotion.trip(kind, tGuideX, tGuideZ, tHatchY, tSeatY, tCellX, tCellZ);
        tLegs = trip.legTicks().clone();
        schedule(server, trip.handoffTicks());
        setChanged();
        sync();
    }

    // ---------------------------------------------------------------
    // Time: two moments per trip
    // ---------------------------------------------------------------

    private void schedule(ServerLevel server, int delayTicks) {
        var block = getBlockState().getBlock();
        if (!server.getBlockTicks().hasScheduledTick(worldPosition, block)) {
            server.scheduleTick(worldPosition, block, Math.max(1, delayTicks));
        }
    }

    private int nextEventDelay(long now) {
        if (trip == null) {
            return 1;
        }
        long target = tripStart + (handedOff ? trip.totalTicks() : trip.handoffTicks());
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, target - now));
    }

    /** The block's scheduled tick: handle every event that is due, then schedule the next one (or nothing). */
    public void scheduledTick(ServerLevel server) {
        if (tripKind == null || trip == null) {
            link();
            startNext(server);
            return;
        }
        long elapsed = server.getGameTime() - tripStart;
        if (!handedOff && elapsed >= trip.handoffTicks()) {
            handoff(server);
            if (tripKind == null) {
                startNext(server);
                return;
            }
        }
        if (elapsed >= trip.totalTicks()) {
            finish(server);
            startNext(server);
            return;
        }
        schedule(server, nextEventDelay(server.getGameTime()));
    }

    private void handoff(ServerLevel server) {
        handedOff = true;
        Link link = link();
        SpentFuelPoolBlockEntity pool = link == null ? null : link.pool();
        if (tripKind == Kind.LOWER) {
            boolean in = pool != null && pool.insertAt(tRack, tCell, flying);
            if (!in && pool != null) {
                in = pool.insertAnywhere(flying);
            }
            if (!in) {
                giveBack(server, flying);
            }
            flying = ItemStack.EMPTY;
        } else {
            ItemStack out = pool == null ? ItemStack.EMPTY : pool.extractFrom(tRack, tCell);
            if (out.isEmpty() && pool != null) {
                var other = pool.coolestCell();
                out = other == null ? ItemStack.EMPTY : pool.extractFrom(other.rack(), other.cell());
            }
            if (out.isEmpty()) {
                // nothing left to raise: the carriage stops where it is and goes home
                tripKind = null;
                trip = null;
                setChanged();
                sync();
                return;
            }
            flying = out;
        }
        setChanged();
        sync();
    }

    private void finish(ServerLevel server) {
        if (tripKind == Kind.RAISE && !flying.isEmpty()) {
            if (landing.isEmpty()) {
                landing = flying;
            } else {
                giveBack(server, flying);
            }
            flying = ItemStack.EMPTY;
        }
        tripKind = null;
        trip = null;
        handedOff = false;
        setChanged();
        sync();
    }

    /** An assembly that could not go into the pool comes back to the landing slot, or lands on the ground at the elevator. */
    private void giveBack(ServerLevel server, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        if (landing.isEmpty()) {
            landing = stack;
        } else {
            Containers.dropItemStack(server, worldPosition.getX() + 0.5, worldPosition.getY() + 1.0, worldPosition.getZ() + 0.5, stack);
        }
    }

    // ---------------------------------------------------------------
    // Item handler
    // ---------------------------------------------------------------

    private final IItemHandler items = new IItemHandler() {
        @Override
        public int getSlots() {
            return HATCH_SLOTS + 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot == SLOT_LANDING) {
                return landing;
            }
            return slot >= 0 && slot < inbound.size() ? inbound.get(slot) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot < 0 || slot >= HATCH_SLOTS || stack.isEmpty() || (slot < inbound.size() && !inbound.get(slot).isEmpty())) {
                return stack;
            }
            return offer(stack.copyWithCount(1), simulate) == null ? stack.copyWithCount(stack.getCount() - 1) : stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot != SLOT_LANDING || landing.isEmpty() || amount < 1) {
                return ItemStack.EMPTY;
            }
            if (simulate) {
                return landing.copy();
            }
            ItemStack out = landing;
            landing = ItemStack.EMPTY;
            setChanged();
            sync();
            if (level instanceof ServerLevel server) {
                startNext(server);
            }
            return out;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot < HATCH_SLOTS && isAssembly(stack);
        }
    };

    public IItemHandler itemHandler() {
        return items;
    }

    /** Takes the assembly waiting at the hatch (a raised one, or one that could not go in), or empty. */
    public ItemStack takeLanding() {
        return items.extractItem(SLOT_LANDING, 1, false);
    }

    /** Everything the elevator holds, emptied out: the hatch queue, the landing slot and the assembly on its way. */
    public List<ItemStack> removalDrops() {
        List<ItemStack> drops = new ArrayList<>(inbound);
        inbound.clear();
        if (!landing.isEmpty()) {
            drops.add(landing);
            landing = ItemStack.EMPTY;
        }
        if (!flying.isEmpty()) {
            drops.add(flying);
            flying = ItemStack.EMPTY;
        }
        tripKind = null;
        trip = null;
        return drops;
    }

    // ---------------------------------------------------------------
    // Readouts
    // ---------------------------------------------------------------

    public int queued() {
        return inbound.size() + (tripKind == Kind.LOWER ? 1 : 0);
    }

    public boolean busy() {
        return tripKind != null;
    }

    @Nullable
    public Kind tripKind() {
        return tripKind;
    }

    public ItemStack landing() {
        return landing;
    }

    @Nullable
    public Refusal lastRefusal() {
        return lastRefusal;
    }

    /** The cell the carriage is going to or coming from, while it is on a trip. */
    @Nullable
    public SpentFuelPoolBlockEntity.CellRef tripCell() {
        return tripKind == null ? null : new SpentFuelPoolBlockEntity.CellRef(tRack, tCell);
    }

    public Status status() {
        Link link = link();
        long now = level == null ? 0L : level.getGameTime();
        String leg = "";
        double progress = 0.0;
        double secondsLeft = 0.0;
        String state;
        if (tripKind != null && trip != null) {
            long elapsed = Math.max(0L, now - tripStart);
            var pose = FuelElevatorMotion.poseAt(trip, elapsed);
            leg = pose.leg().name().toLowerCase(Locale.ROOT);
            progress = Math.min(1.0, elapsed / (double) trip.totalTicks());
            secondsLeft = Math.max(0.0, (trip.totalTicks() - elapsed) / 20.0);
            state = tripKind == Kind.LOWER ? "lowering" : "raising";
        } else if (link == null) {
            state = "unlinked";
        } else if (!inbound.isEmpty()) {
            state = "waiting";
        } else {
            state = "idle";
        }
        String problem = link != null ? "" : (linkProblem == null ? "" : linkProblem.english());
        double landingKw = landing.isEmpty() ? 0.0 : SpentFuelHeat.decayWatts(landing, now) / 1000.0;
        double flyingKw = flying.isEmpty() ? 0.0 : SpentFuelHeat.decayWatts(flying, now) / 1000.0;
        return new Status(link != null, problem, state, leg, progress, secondsLeft, queued(), !landing.isEmpty(), landingKw, flyingKw,
                link == null ? 0 : link.pool().freeCells(), link == null ? 0 : link.pool().cellCount(),
                link == null ? 0.0 : waterOverTransfer(link), FuelElevatorMotion.MIN_COVER_M,
                lastRefusal == null ? "" : lastRefusal.english(), link == null ? -1.0 : coolestKw(link.pool(), now),
                link == null ? 0.0 : link.pool().pool().levelM());
    }

    private static double coolestKw(SpentFuelPoolBlockEntity pool, long now) {
        var cell = pool.coolestCell();
        return cell == null ? -1.0 : SpentFuelHeat.decayWatts(pool.stackAt(cell.rack(), cell.cell()), now) / 1000.0;
    }

    public List<Component> statusComponents() {
        Status s = status();
        List<Component> lines = new ArrayList<>();
        if (!s.linked()) {
            lines.add(linkProblem != null ? linkProblem.text() : refusal(Why.NOT_ON_POOL, "").text());
            return lines;
        }
        lines.add(BwrText.tr("status.bwr.new_fuel_elevator.state",
                "New fuel elevator: %s; %s waiting; %s of %s rack cells free; %s m of water over an assembly at the transfer height (%s m needed).",
                s.state(), s.queued(), s.freeCells(), s.cells(), String.format(Locale.ROOT, "%.1f", Math.max(0.0, s.waterOverTransferM())),
                String.format(Locale.ROOT, "%.1f", s.minCoverM())));
        if (!s.leg().isEmpty()) {
            lines.add(BwrText.tr("status.bwr.new_fuel_elevator.trip", "On its way: %s, %s s to go.", s.leg(),
                    String.format(Locale.ROOT, "%.0f", s.secondsLeft())));
        }
        if (s.landing()) {
            lines.add(BwrText.tr("status.bwr.new_fuel_elevator.landing", "An assembly waits at the hatch (%s kW decay heat). Take it out.",
                    String.format(Locale.ROOT, "%.1f", s.landingDecayKw())));
        }
        if (!s.lastRefusal().isEmpty()) {
            lines.add(Component.literal(s.lastRefusal()));
        }
        return lines;
    }

    // ---------------------------------------------------------------
    // The pose, for the client and the dose
    // ---------------------------------------------------------------

    @Nullable
    public FuelElevatorMotion.Trip currentTrip() {
        return trip;
    }

    public long tripStart() {
        return tripStart;
    }

    /** Whether an assembly waits at the hatch (queued, or landed) and no trip is running. */
    public boolean assemblyAtHatch() {
        return tripKind == null && (!inbound.isEmpty() || !landing.isEmpty());
    }

    // ---------------------------------------------------------------
    // Radiation: a hot assembly out of the water is a source (the same law as the bridge's grapple)
    // ---------------------------------------------------------------

    static double doseRateAt(Level level, Vec3 at) {
        if (level.isClientSide()) {
            return 0.0;
        }
        double total = 0.0;
        for (BlockPos p : elevators(level)) {
            if (!level.isLoaded(p) || !(level.getBlockEntity(p) instanceof NewFuelElevatorBlockEntity be) || be.isRemoved()) {
                continue;
            }
            total += be.doseRateFromHere(level, at);
        }
        return total;
    }

    private double doseRateFromHere(Level level, Vec3 at) {
        long now = level.getGameTime();
        double hatchX = worldPosition.getX() + 0.5 + (inwardIndex >= 0 ? Direction.from2DDataValue(inwardIndex).getStepX() * 0.95 : 0.0);
        double hatchZ = worldPosition.getZ() + 0.5 + (inwardIndex >= 0 ? Direction.from2DDataValue(inwardIndex).getStepZ() * 0.95 : 0.0);
        double hatchY = worldPosition.getY() + FuelElevatorMotion.HATCH_ABOVE_BLOCK_M;
        double surface = surfaceForDose();
        double total = 0.0;
        // assemblies waiting in the hatch or at the landing: handle at the hatch
        for (ItemStack stack : inbound) {
            total += oneSource(stack, now, hatchX, hatchY, hatchZ, surface, at);
        }
        total += oneSource(landing, now, hatchX, hatchY, hatchZ, surface, at);
        // the assembly on its way
        if (tripKind != null && trip != null && !flying.isEmpty()) {
            var pose = FuelElevatorMotion.poseAt(trip, Math.max(0L, now - tripStart));
            if (pose.hasAssembly()) {
                total += oneSource(flying, now, pose.ax(), pose.ay(), pose.az(), surface, at);
            }
        }
        return total;
    }

    private double surfaceForDose() {
        if (level == null || inwardIndex < 0) {
            return Double.NaN;
        }
        for (SpentFuelPoolBlockEntity pool : SpentFuelPoolRegistry.controllers(level)) {
            if (pool.isFormed() && pool.layout() != null && pool.layout().max().getY() + 1 == worldPosition.getY()
                    && worldPosition.getX() >= pool.layout().min().getX() && worldPosition.getX() <= pool.layout().max().getX()
                    && worldPosition.getZ() >= pool.layout().min().getZ() && worldPosition.getZ() <= pool.layout().max().getZ()) {
                return pool.surfaceY();
            }
        }
        return Double.NaN;
    }

    private static double oneSource(ItemStack stack, long now, double x, double y, double z, double surface, Vec3 at) {
        if (stack.isEmpty()) {
            return 0.0;
        }
        double gamma = BridgeMotion.gammaWatts(SpentFuelHeat.decayWatts(stack, now));
        if (!(gamma > 0.0)) {
            return 0.0;
        }
        return FuelElevatorMotion.doseRateMsvPerH(gamma, at.distanceTo(new Vec3(x, y, z)), surface, y);
    }

    // ---------------------------------------------------------------
    // Saves and sync
    // ---------------------------------------------------------------

    private void sync() {
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        writeContents(tag, registries);
        if (inwardIndex >= 0) {
            tag.putInt("Inward", inwardIndex);
            tag.putDouble("FloorY", floorY);
        }
    }

    private void writeContents(CompoundTag tag, HolderLookup.Provider registries) {
        if (!inbound.isEmpty()) {
            ListTag list = new ListTag();
            for (ItemStack stack : inbound) {
                list.add(stack.save(registries));
            }
            tag.put("Hatch", list);
        }
        if (!landing.isEmpty()) {
            tag.put("Landing", landing.save(registries));
        }
        if (tripKind != null && trip != null) {
            CompoundTag t = new CompoundTag();
            t.putString("Kind", tripKind.name());
            t.putLong("Start", tripStart);
            if (handedOff) {
                t.putBoolean("HandedOff", true);
            }
            t.put("Geometry", doubles(new double[] {tGuideX, tGuideZ, tHatchY, tSeatY, tCellX, tCellZ}));
            t.putIntArray("Legs", tLegs);
            t.putLong("Rack", tRack.asLong());
            t.putInt("Cell", tCell);
            if (!flying.isEmpty()) {
                t.put("Flying", flying.save(registries));
            }
            tag.put("Trip", t);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        inbound.clear();
        for (Tag raw : tag.getList("Hatch", Tag.TAG_COMPOUND)) {
            ItemStack.parse(registries, raw).ifPresent(s -> {
                if (!s.isEmpty() && inbound.size() < HATCH_SLOTS) {
                    inbound.add(s);
                }
            });
        }
        landing = tag.contains("Landing") ? ItemStack.parse(registries, tag.getCompound("Landing")).orElse(ItemStack.EMPTY) : ItemStack.EMPTY;
        tripKind = null;
        trip = null;
        flying = ItemStack.EMPTY;
        handedOff = false;
        if (tag.contains("Trip")) {
            CompoundTag t = tag.getCompound("Trip");
            double[] g = doubles(t.getList("Geometry", Tag.TAG_DOUBLE));
            int[] legs = t.getIntArray("Legs");
            if (g.length == 6 && legs.length == 4) {
                tripKind = Kind.byName(t.getString("Kind"));
                tripStart = t.getLong("Start");
                handedOff = t.getBoolean("HandedOff");
                tGuideX = g[0];
                tGuideZ = g[1];
                tHatchY = g[2];
                tSeatY = g[3];
                tCellX = g[4];
                tCellZ = g[5];
                tLegs = legs;
                tRack = BlockPos.of(t.getLong("Rack"));
                tCell = t.getInt("Cell");
                trip = new FuelElevatorMotion.Trip(tripKind, tGuideX, tGuideZ, tHatchY, tSeatY + FuelElevatorMotion.CLEARANCE_M, tSeatY,
                        tCellX, tCellZ, tLegs);
                if (t.contains("Flying")) {
                    flying = ItemStack.parse(registries, t.getCompound("Flying")).orElse(ItemStack.EMPTY);
                }
            }
        }
        inwardIndex = tag.contains("Inward") ? tag.getInt("Inward") : -1;
        floorY = tag.contains("FloorY") ? tag.getDouble("FloorY") : 0.0;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        if (inwardIndex >= 0) {
            tag.putInt("Inward", inwardIndex);
            tag.putDouble("FloorY", floorY);
        }
        if (tripKind != null && trip != null) {
            // the picture needs the trip, not the stacks
            CompoundTag t = new CompoundTag();
            t.putString("Kind", tripKind.name());
            t.putLong("Start", tripStart);
            t.put("Geometry", doubles(new double[] {tGuideX, tGuideZ, tHatchY, tSeatY, tCellX, tCellZ}));
            t.putIntArray("Legs", tLegs);
            tag.put("Trip", t);
        }
        if (assemblyAtHatch()) {
            tag.putBoolean("AtHatch", true);
        }
        return tag;
    }

    // client-side picture
    private boolean visAtHatch;

    public boolean visAtHatch() {
        return visAtHatch;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        tripKind = null;
        trip = null;
        inwardIndex = tag.contains("Inward") ? tag.getInt("Inward") : -1;
        floorY = tag.contains("FloorY") ? tag.getDouble("FloorY") : 0.0;
        visAtHatch = tag.getBoolean("AtHatch");
        if (tag.contains("Trip")) {
            CompoundTag t = tag.getCompound("Trip");
            double[] g = doubles(t.getList("Geometry", Tag.TAG_DOUBLE));
            int[] legs = t.getIntArray("Legs");
            if (g.length == 6 && legs.length == 4) {
                tripKind = Kind.byName(t.getString("Kind"));
                tripStart = t.getLong("Start");
                trip = new FuelElevatorMotion.Trip(tripKind, g[0], g[1], g[2], g[3] + FuelElevatorMotion.CLEARANCE_M, g[3], g[4], g[5], legs);
            }
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** The block is at {@code pos} in {@code level} (convenience for tests and tools). */
    public static NewFuelElevatorBlockEntity at(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof NewFuelElevatorBlockEntity be ? be : null;
    }

    private static ListTag doubles(double[] values) {
        ListTag list = new ListTag();
        for (double v : values) {
            list.add(net.minecraft.nbt.DoubleTag.valueOf(v));
        }
        return list;
    }

    private static double[] doubles(ListTag list) {
        double[] values = new double[list.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = list.getDouble(i);
        }
        return values;
    }

    /** Whether a horizontal index names a direction the renderer can use. */
    public static boolean validInward(int index) {
        return index >= 0 && index < 4;
    }

    /** Tests and tools only: moves the start of the trip back by {@code ticks} (as if that long had passed) and handles what is then due. */
    public void advanceForTest(ServerLevel server, long ticks) {
        tripStart -= ticks;
        setChanged();
        scheduledTick(server);
        sync();
    }
}
