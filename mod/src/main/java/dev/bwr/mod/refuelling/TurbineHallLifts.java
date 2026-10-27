package dev.bwr.mod.refuelling;

import dev.bwr.core.refuelling.BridgeMotion;
import dev.bwr.core.refuelling.BridgeMotion.Point;
import dev.bwr.core.refuelling.CraneMotion;
import dev.bwr.core.refuelling.PlugLift;
import dev.bwr.core.turbine.TurbineBlades;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.power.PowerModuleBlockEntity;
import dev.bwr.mod.power.PowerTrain;
import dev.bwr.mod.power.TurbineLoadItem;
import dev.bwr.mod.power.TurbineStandBlock;
import dev.bwr.mod.power.TurbineStandBlockEntity;
import dev.bwr.mod.refuelling.BuildingCraneBlockEntity.Kind;
import dev.bwr.mod.refuelling.BuildingCraneBlockEntity.Loc;
import dev.bwr.mod.refuelling.BuildingCraneBlockEntity.Move;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The building crane's turbine hall lifts (TURBINE-MAINTENANCE.md): COVER OFF / COVER ON (an LP turbine's hood, its upper outer and inner
 * casing halves, between the turbine and a {@link TurbineStandBlock.Kind#CASING Casing Laydown Stand}) and BLADES OUT / BLADES IN (one
 * flow's bladed rotor section between the open casing and an {@link TurbineStandBlock.Kind#BLADES LP Blade Storage Stand}). The same crane,
 * the same rails and console, the same phases, ABORT and RESUME as its reactor lifts; it needs no reactor between its rails for these.
 *
 * <h2>Refused, with the reason</h2>
 * The shaft must be stopped (at most {@link TurbineBlades#OPEN_MAX_RPM} rpm) with the turning gear off; the casing isolated (no steam in
 * the section, none flowing) and cool (its rotor metal and exhaust hood at most {@link TurbineBlades#OPEN_MAX_C} C) for the cover; the
 * cover off for a blade section; the section and the stand under the crane, in loaded chunks; the load within the crane's
 * {@value PlugLift#RATED_CAPACITY_T} t rating; the rails high enough to carry the load over the top of the turbine train. A refusal moves
 * nothing.
 *
 * <h2>One place at a time</h2>
 * The load is a {@link TurbineLoadItem}: the hood leaves the LP (it is then open) when the hook latches, hangs on the crane as that item,
 * and is put on the stand (or the LP) when the hook releases; a blade section the same. Nothing is created or lost: the crane's save holds
 * the item while it hangs, and breaking the console puts the load back where it came from or drops it as the item.
 */
final class TurbineHallLifts {
    private TurbineHallLifts() {
    }

    /** The hook over the bottom of a hood: its 2 m height plus the lifting rig (four slings to its lugs). */
    static final double HOOD_HANG = 2.0 + BuildingCraneBlockEntity.RIG_M;
    /** The hood's bottom (the horizontal joint) over the LP's floor, and over a casing stand's block. */
    static final double HOOD_JOINT = 3.0, CRADLE_TOP = 0.5;
    /** A blade section: its blades' tip radius; the hook over its axis (slings round its two journals); its axis over a blade stand's block. */
    static final double SECTION_RADIUS = 2.3, SECTION_HANG = 3.6, SADDLE_AXIS = 2.7;
    /** The shaft axis over the LP's floor, and the middle of a flow's section from the LP's middle along the shaft. */
    static final double AXIS = 2.5, FLOW_OFFSET = 1.5;
    /** The top of a turbine train over its floor (every section is 5 blocks tall). */
    static final double TRAIN_TOP = 5.0;

    static boolean isTurbineLoc(Loc l) {
        return l.kind() == Kind.TURBINE_COVER || l.kind() == Kind.CASING_STAND || l.kind() == Kind.TURBINE_BLADES || l.kind() == Kind.BLADE_STAND;
    }

    static boolean isTurbineMove(BuildingCraneBlockEntity c, Move m) {
        return isTurbineLoc(m.from()) || isTurbineLoc(m.to()) || (m.from().kind() == Kind.HELD && !c.carriedTurbine().isEmpty());
    }

    // ------------------------------------------------------------------------------------------------ the world

    @Nullable
    static PowerModuleBlockEntity lp(Level level, BlockPos at) {
        return level != null && level.isLoaded(at) && level.getBlockEntity(at) instanceof PowerModuleBlockEntity m && m.lp() && !m.isRemoved()
                && m.block().complete(level, at, m.getBlockState()) ? m : null;
    }

    @Nullable
    static TurbineStandBlockEntity stand(Level level, BlockPos at, TurbineStandBlock.Kind kind) {
        return level != null && level.isLoaded(at) && level.getBlockEntity(at) instanceof TurbineStandBlockEntity s && s.kind() == kind && !s.isRemoved() ? s : null;
    }

    private static Direction facing(PowerModuleBlockEntity lp) {
        return lp.getBlockState().getValue(PumpAssemblyBlock.FACING);
    }

    /** The column a place is lifted at (y unused). */
    @Nullable
    static Point column(BuildingCraneBlockEntity c, Loc loc) {
        Level level = c.getLevel();
        BlockPos at = loc.at();
        switch (loc.kind()) {
            case TURBINE_COVER, CASING_STAND -> {
                return new Point(at.getX() + 0.5, 0, at.getZ() + 0.5);
            }
            case TURBINE_BLADES -> {
                PowerModuleBlockEntity lp = lp(level, at);
                Direction f = lp == null ? Direction.NORTH : facing(lp);
                double s = loc.which() == 0 ? FLOW_OFFSET : -FLOW_OFFSET;
                return new Point(at.getX() + 0.5 + f.getStepX() * s, 0, at.getZ() + 0.5 + f.getStepZ() * s);
            }
            case BLADE_STAND -> {
                TurbineStandBlockEntity s = stand(level, at, TurbineStandBlock.Kind.BLADES);
                if (s == null) {
                    return new Point(at.getX() + 0.5, 0, at.getZ() + 0.5);
                }
                double[] xz = s.slotXZ(loc.which());
                return new Point(xz[0], 0, xz[1]);
            }
            default -> {
                return null;
            }
        }
    }

    /** Where the hook is when the load sits on this place. */
    static BridgeMotion.Stop stop(BuildingCraneBlockEntity c, Loc loc) {
        Point col = column(c, loc);
        BlockPos at = loc.at();
        double y = switch (loc.kind()) {
            case TURBINE_COVER -> at.getY() + HOOD_JOINT + HOOD_HANG;
            case CASING_STAND -> at.getY() + CRADLE_TOP + HOOD_HANG;
            case TURBINE_BLADES -> at.getY() + AXIS + SECTION_HANG;
            default -> at.getY() + SADDLE_AXIS + SECTION_HANG;
        };
        return new BridgeMotion.Stop(col.x(), col.z(), y, Double.NaN);
    }

    /** How far under the hook the load's bottom hangs, m. */
    static double hanging(boolean hood) {
        return hood ? HOOD_HANG : SECTION_HANG + SECTION_RADIUS;
    }

    // ------------------------------------------------------------------------------------------------ refusals

    private static Component tr(String key, String english, Object... args) {
        return BwrText.tr("status.bwr.turbine_crane." + key, english, args);
    }

    private static String t(double tonnes) {
        return String.format(Locale.ROOT, "%.0f", tonnes);
    }

    /** Why the shaft of this LP's train is not still for the casing to be opened or a section lifted, or null. */
    @Nullable
    static Component shaftProblem(PowerModuleBlockEntity lp) {
        if (lp.rpm > TurbineBlades.OPEN_MAX_RPM) {
            return tr("shaft_turning", "The shaft is turning (%s rpm): it must be stopped, under %s rpm, with the turning gear off.",
                    String.format(Locale.ROOT, "%.1f", lp.rpm), String.format(Locale.ROOT, "%.0f", TurbineBlades.OPEN_MAX_RPM));
        }
        for (var m : PowerTrain.members(lp)) {
            if (m.block().generator() && m.turningGear()) {
                return tr("turning_gear", "The turning gear is engaged: switch it off on the generator's screen before the casing is opened.");
            }
        }
        return null;
    }

    /** Why this LP's casing may not be opened now, or null: steam in it or flowing, or the casing too hot. */
    @Nullable
    static Component casingProblem(PowerModuleBlockEntity lp) {
        if (lp.flowKgPerS > 0 || lp.inletMass() > 1.0) {
            return tr("steam", "Steam is in the casing (%s kg, %s kg/s flowing): close the stop and control valves and let it clear before the cover comes off.",
                    String.format(Locale.ROOT, "%.0f", lp.inletMass()), String.format(Locale.ROOT, "%.1f", lp.flowKgPerS));
        }
        double hot = Math.max(lp.rotorC, lp.hoodC);
        if (hot > TurbineBlades.OPEN_MAX_C) {
            return tr("hot", "The casing is too hot to open: %s C, it must be at %s C or less.", String.format(Locale.ROOT, "%.0f", hot),
                    String.format(Locale.ROOT, "%.0f", TurbineBlades.OPEN_MAX_C));
        }
        return null;
    }

    @Nullable
    private static Component overweight(double massT, boolean hood) {
        if (!PlugLift.overCapacity(massT)) {
            return null;
        }
        return hood ? tr("hood_over", "The LP turbine's hood weighs %s t, over the crane's rated %s t.", t(massT), t(PlugLift.RATED_CAPACITY_T))
                : tr("section_over", "An LP blade section weighs %s t, over the crane's rated %s t.", t(massT), t(PlugLift.RATED_CAPACITY_T));
    }

    @Nullable
    private static Component reach(BuildingCraneBlockEntity c, Point col, double halfWidth) {
        BridgeSite site = c.site();
        for (int i = 0; i < 4; i++) {
            double x = col.x() + ((i & 1) == 0 ? -halfWidth : halfWidth) * 0.98;
            double z = col.z() + ((i & 2) == 0 ? -halfWidth : halfWidth) * 0.98;
            if (!site.reaches(x, z)) {
                return tr("out_of_reach", "That is not under the crane: the load (%s blocks across) would hang over the edge of its reach at %s, %s.",
                        String.format(Locale.ROOT, "%.0f", 2 * halfWidth), (int) Math.floor(col.x()), (int) Math.floor(col.z()));
            }
        }
        return null;
    }

    /** Why the rails are too low to carry this load over the top of a turbine train standing on {@code floorY}, or null. */
    @Nullable
    private static Component railsProblem(BuildingCraneBlockEntity c, boolean hood, int floorY) {
        double clear = floorY + TRAIN_TOP + 0.25;
        double hang = hanging(hood);
        if (c.site().homeY() - hang < clear) {
            return tr("rails_too_low", "The runway is too low to carry the %s over the turbines: it hangs %s blocks under the hook. Raise the rails to y %s or higher.",
                    hood ? "hood" : "blade section", String.format(Locale.ROOT, "%.1f", hang), (int) Math.ceil(clear + hang - CraneMotion.HOOK_HOME_ABOVE_RAIL_M));
        }
        return null;
    }

    /** Why the move cannot be done now, or null. The crane's own survey has been checked already. */
    @Nullable
    static Component refuse(BuildingCraneBlockEntity c, Move m) {
        Level level = c.getLevel();
        Loc from = m.from(), to = m.to();
        boolean held = from.kind() == Kind.HELD;
        ItemStack carried = c.carriedTurbine();
        if (held && carried.isEmpty()) {
            return BwrText.tr("status.bwr.building_crane.nothing_held", "The hook is not holding anything.");
        }
        boolean hood = held ? TurbineLoadItem.isHood(carried) : from.kind() == Kind.TURBINE_COVER || from.kind() == Kind.CASING_STAND;
        boolean ok = hood ? (to.kind() == Kind.TURBINE_COVER || to.kind() == Kind.CASING_STAND) && (held || from.kind() != to.kind())
                : (to.kind() == Kind.TURBINE_BLADES || to.kind() == Kind.BLADE_STAND) && (held || from.kind() != to.kind());
        if (!ok) {
            return tr("bad_move", "The hood goes between an LP turbine and a Casing Laydown Stand; a blade section between an LP turbine's open casing and an LP Blade Storage Stand.");
        }
        double mass = held ? TurbineLoadItem.massT(carried) : hood ? dev.bwr.mod.config.GameplayConfig.turbineHoodMassT() : dev.bwr.mod.config.GameplayConfig.lpBladeSectionMassT();
        Component why = overweight(mass, hood);
        if (why != null) {
            return why;
        }
        PowerModuleBlockEntity turbine = null;
        for (Loc l : new Loc[]{from, to}) {
            switch (l.kind()) {
                case TURBINE_COVER, TURBINE_BLADES -> {
                    turbine = lp(level, l.at());
                    if (turbine == null) {
                        return tr("no_lp", "There is no complete LP turbine section at %s (or its chunk is not loaded).", l.at().toShortString());
                    }
                    why = shaftProblem(turbine);
                    if (why != null) {
                        return why;
                    }
                }
                case CASING_STAND -> {
                    if (stand(level, l.at(), TurbineStandBlock.Kind.CASING) == null) {
                        return tr("no_casing_stand", "There is no Casing Laydown Stand at %s.", l.at().toShortString());
                    }
                }
                case BLADE_STAND -> {
                    if (stand(level, l.at(), TurbineStandBlock.Kind.BLADES) == null) {
                        return tr("no_blade_stand", "There is no LP Blade Storage Stand at %s.", l.at().toShortString());
                    }
                }
                default -> {
                }
            }
        }
        // the source still holds the load (a move from the hook is past this)
        switch (from.kind()) {
            case TURBINE_COVER -> {
                if (turbine.coverOff()) {
                    return tr("cover_already_off", "The LP turbine at %s has its cover off already.", from.at().toShortString());
                }
                why = casingProblem(turbine);
                if (why != null) {
                    return why;
                }
            }
            case CASING_STAND -> {
                if (!TurbineLoadItem.isHood(stand(level, from.at(), TurbineStandBlock.Kind.CASING).items().getStackInSlot(0))) {
                    return tr("no_hood", "The Casing Laydown Stand at %s holds no hood.", from.at().toShortString());
                }
            }
            case TURBINE_BLADES -> {
                PowerModuleBlockEntity src = lp(level, from.at());
                if (!src.coverOff()) {
                    return tr("cover_on", "The LP turbine at %s has its cover on: COVER OFF first.", from.at().toShortString());
                }
                if (!src.bladeIn(from.which())) {
                    return tr("section_gone", "Blade section %s of the LP turbine at %s is already out.", flow(from.which()), from.at().toShortString());
                }
            }
            case BLADE_STAND -> {
                ItemStack s = stand(level, from.at(), TurbineStandBlock.Kind.BLADES).items().getStackInSlot(from.which());
                if (!TurbineLoadItem.isGoodSection(s)) {
                    return s.isEmpty() ? tr("saddle_empty", "That saddle of the LP Blade Storage Stand at %s is empty.", from.at().toShortString())
                            : tr("damaged_section", "That blade section is damaged: it can be scrapped, not installed.");
                }
            }
            default -> {
            }
        }
        if (held && !hood && to.kind() == Kind.TURBINE_BLADES && !TurbineLoadItem.isGoodSection(carried)) {
            return tr("damaged_section", "That blade section is damaged: it can be scrapped, not installed.");
        }
        switch (to.kind()) {
            case TURBINE_COVER -> {
                PowerModuleBlockEntity dst = lp(level, to.at());
                if (!dst.coverOff()) {
                    return tr("cover_already_on", "The LP turbine at %s has its cover on.", to.at().toShortString());
                }
                if (!dst.allBladesIn()) {
                    return tr("blades_out", "A blade section of the LP turbine at %s is out: BLADES IN before the cover goes back.", to.at().toShortString());
                }
            }
            case CASING_STAND -> {
                TurbineStandBlockEntity s = stand(level, to.at(), TurbineStandBlock.Kind.CASING);
                if (!s.items().getStackInSlot(0).isEmpty()) {
                    return tr("stand_taken", "The Casing Laydown Stand at %s holds a hood already.", to.at().toShortString());
                }
            }
            case TURBINE_BLADES -> {
                PowerModuleBlockEntity dst = lp(level, to.at());
                if (!dst.coverOff()) {
                    return tr("cover_on", "The LP turbine at %s has its cover on: COVER OFF first.", to.at().toShortString());
                }
                if (dst.bladeIn(to.which())) {
                    return tr("section_in", "Blade section %s of the LP turbine at %s is in place: BLADES OUT first.", flow(to.which()), to.at().toShortString());
                }
            }
            case BLADE_STAND -> {
                if (!stand(level, to.at(), TurbineStandBlock.Kind.BLADES).items().getStackInSlot(to.which()).isEmpty()) {
                    return tr("saddle_taken", "That saddle of the LP Blade Storage Stand at %s holds a section already.", to.at().toShortString());
                }
            }
            default -> {
            }
        }
        for (Loc l : new Loc[]{held ? null : from, to}) {
            if (l == null) {
                continue;
            }
            Point col = column(c, l);
            why = reach(c, col, hood ? 3.5 : SECTION_RADIUS);
            if (why != null) {
                return why;
            }
        }
        if (to.kind() == Kind.CASING_STAND && from.kind() == Kind.TURBINE_COVER) {
            BlockPos s = to.at(), r = from.at();
            if (Math.abs(s.getX() - r.getX()) < 7 && Math.abs(s.getZ() - r.getZ()) < 7) {
                return tr("stand_on_turbine", "The Casing Laydown Stand at %s is too close to the turbine: the hood is 7 blocks across and would lie on it.", s.toShortString());
            }
        }
        int floor = turbine != null ? turbine.getBlockPos().getY() : to.at().getY();
        return railsProblem(c, hood, floor);
    }

    static String flow(int which) {
        return which == 0 ? "A" : "B";
    }

    // ------------------------------------------------------------------------------------------------ hook on, set down

    /** The hook closes on the load: it leaves its place and becomes the item on the hook. Returns null, or why not. */
    @Nullable
    static Component take(BuildingCraneBlockEntity c, Loc from) {
        Level level = c.getLevel();
        ItemStack load = ItemStack.EMPTY;
        switch (from.kind()) {
            case TURBINE_COVER -> {
                PowerModuleBlockEntity lp = lp(level, from.at());
                if (lp != null && !lp.coverOff() && shaftProblem(lp) == null && casingProblem(lp) == null) {
                    lp.setCoverOff(true);
                    load = TurbineLoadItem.hood(lp.getBlockPos(), facing(lp));
                }
            }
            case CASING_STAND -> {
                TurbineStandBlockEntity s = stand(level, from.at(), TurbineStandBlock.Kind.CASING);
                if (s != null && TurbineLoadItem.isHood(s.items().getStackInSlot(0))) {
                    load = s.items().extractItem(0, 1, false);
                }
            }
            case TURBINE_BLADES -> {
                PowerModuleBlockEntity lp = lp(level, from.at());
                if (lp != null && lp.coverOff() && lp.bladeIn(from.which()) && shaftProblem(lp) == null) {
                    double condition = lp.takeBladeSection(from.which());
                    load = TurbineLoadItem.section(condition);
                }
            }
            case BLADE_STAND -> {
                TurbineStandBlockEntity s = stand(level, from.at(), TurbineStandBlock.Kind.BLADES);
                if (s != null && TurbineLoadItem.isGoodSection(s.items().getStackInSlot(from.which()))) {
                    load = s.items().extractItem(from.which(), 1, false);
                }
            }
            default -> {
            }
        }
        if (load.isEmpty()) {
            return BwrText.tr("status.bwr.building_crane.nothing_to_latch", "Stopped: the hook closed on nothing; the load is no longer there.");
        }
        c.setCarriedTurbine(load);
        return null;
    }

    /** The hook sets the load down: it goes into its new place and the hook is empty. Returns null, or why not (it stays on the hook). */
    @Nullable
    static Component put(BuildingCraneBlockEntity c, Loc to) {
        Level level = c.getLevel();
        ItemStack load = c.carriedTurbine();
        boolean placed = false;
        switch (to.kind()) {
            case TURBINE_COVER -> {
                PowerModuleBlockEntity lp = lp(level, to.at());
                if (lp != null && TurbineLoadItem.isHood(load) && lp.coverOff() && lp.allBladesIn()) {
                    lp.setCoverOff(false);
                    placed = true;
                }
            }
            case CASING_STAND -> {
                TurbineStandBlockEntity s = stand(level, to.at(), TurbineStandBlock.Kind.CASING);
                placed = s != null && s.items().getStackInSlot(0).isEmpty() && s.items().insertItem(0, load.copy(), false).isEmpty();
            }
            case TURBINE_BLADES -> {
                PowerModuleBlockEntity lp = lp(level, to.at());
                placed = lp != null && TurbineLoadItem.isGoodSection(load) && lp.coverOff()
                        && lp.installBladeSection(to.which(), TurbineLoadItem.condition(load));
            }
            case BLADE_STAND -> {
                TurbineStandBlockEntity s = stand(level, to.at(), TurbineStandBlock.Kind.BLADES);
                placed = s != null && s.items().getStackInSlot(to.which()).isEmpty() && s.items().insertItem(to.which(), load.copy(), false).isEmpty();
            }
            default -> {
            }
        }
        if (!placed) {
            return BwrText.tr("status.bwr.building_crane.cannot_release",
                    "Stopped: the load cannot be set down at %s (occupied, or the reactor is no longer ready). It stays on the hook.", to.describe());
        }
        c.setCarriedTurbine(ItemStack.EMPTY);
        return null;
    }

    /** The console is broken under a hanging load: back where it came from if that place can take it, otherwise dropped as its item under the hook. */
    static void release(BuildingCraneBlockEntity c, ServerLevel server, @Nullable Move move) {
        ItemStack load = c.carriedTurbine();
        if (load.isEmpty()) {
            return;
        }
        if (move != null && move.from().kind() == Kind.TURBINE_BLADES) {
            // a section, worn or not, goes back into the flow it came out of
            PowerModuleBlockEntity lp = lp(c.getLevel(), move.from().at());
            if (lp != null && TurbineLoadItem.isBladeSection(load) && lp.installBladeSection(move.from().which(), TurbineLoadItem.condition(load))) {
                c.setCarriedTurbine(ItemStack.EMPTY);
                return;
            }
        } else if (move != null && move.from().kind() != Kind.HELD && put(c, move.from()) == null) {
            return;
        }
        Point tip = c.tip();
        double x = tip == null ? c.getBlockPos().getX() + 0.5 : tip.x(), y = tip == null ? c.getBlockPos().getY() : tip.y() - 2, z = tip == null ? c.getBlockPos().getZ() + 0.5 : tip.z();
        Containers.dropItemStack(server, x, y, z, load.copy());
        c.setCarriedTurbine(ItemStack.EMPTY);
    }

    /** True when the places of a turbine lift and the next step of the hook are in loaded chunks (the crane waits otherwise). */
    static boolean ready(BuildingCraneBlockEntity c, ServerLevel server, @Nullable Move move) {
        if (move != null) {
            for (Loc l : new Loc[]{move.from(), move.to()}) {
                if (isTurbineLoc(l) && !server.isLoaded(l.at())) {
                    return false;
                }
            }
        }
        Point t = c.tip();
        return t == null || server.isLoaded(BlockPos.containing(t.x(), t.y() - 3, t.z()));
    }

    // ------------------------------------------------------------------------------------------------ queueing

    private static double dist(Point a, BlockPos b) {
        return a == null ? 0 : Math.hypot(a.x() - (b.getX() + 0.5), a.z() - (b.getZ() + 0.5));
    }

    /** LP sections whose middle is under the crane, nearest the hook first. */
    static List<PowerModuleBlockEntity> lps(BuildingCraneBlockEntity c) {
        List<PowerModuleBlockEntity> out = new ArrayList<>();
        Level level = c.getLevel();
        for (BlockPos p : PowerModuleBlockEntity.lpSections(level)) {
            PowerModuleBlockEntity m = lp(level, p);
            if (m != null && c.site().reaches(p.getX() + 0.5, p.getZ() + 0.5)) {
                out.add(m);
            }
        }
        Point hook = c.tip();
        out.sort((a, b) -> Double.compare(dist(hook, a.getBlockPos()), dist(hook, b.getBlockPos())));
        return out;
    }

    private static List<TurbineStandBlockEntity> stands(BuildingCraneBlockEntity c, TurbineStandBlock.Kind kind, BlockPos near) {
        List<TurbineStandBlockEntity> out = new ArrayList<>();
        for (BlockPos p : TurbineStandBlockEntity.stands(c.getLevel(), kind)) {
            TurbineStandBlockEntity s = stand(c.getLevel(), p, kind);
            if (s != null && c.site().reaches(p.getX() + 0.5, p.getZ() + 0.5)) {
                out.add(s);
            }
        }
        Point n = new Point(near.getX() + 0.5, 0, near.getZ() + 0.5);
        out.sort((a, b) -> Double.compare(dist(n, a.getBlockPos()), dist(n, b.getBlockPos())));
        return out;
    }

    @Nullable
    private static Component siteProblem(BuildingCraneBlockEntity c) {
        c.refreshSite();
        BridgeSite site = c.site();
        if (site == null || !site.ok()) {
            return site == null ? BwrText.tr("status.bwr.building_crane.not_surveyed", "The crane has not surveyed its rails yet.") : site.problem;
        }
        return null;
    }

    @Nullable
    private static PowerModuleBlockEntity pick(BuildingCraneBlockEntity c, @Nullable BlockPos given, java.util.function.Predicate<PowerModuleBlockEntity> want) {
        if (given != null) {
            BlockPos root = given;
            if (c.getLevel().getBlockState(given).getBlock() instanceof dev.bwr.mod.power.PowerModuleBlock b) {
                root = b.origin(given, c.getLevel().getBlockState(given));
            }
            PowerModuleBlockEntity m = lp(c.getLevel(), root);
            return m != null && want.test(m) ? m : null;
        }
        for (PowerModuleBlockEntity m : lps(c)) {
            if (want.test(m)) {
                return m;
            }
        }
        return null;
    }

    /** COVER OFF ({@code off}) or COVER ON for the given LP (any of its blocks) or the nearest one that wants it. */
    @Nullable
    static Component queueCover(BuildingCraneBlockEntity c, boolean off, @Nullable BlockPos lpAt) {
        Component why = siteProblem(c);
        if (why != null) {
            return why;
        }
        ItemStack carried = c.carriedTurbine();
        if (!carried.isEmpty() && c.currentMove() == null) {
            if (!TurbineLoadItem.isHood(carried)) {
                return BwrText.tr("status.bwr.building_crane.holding", "The hook still holds a load: set it down first.");
            }
            if (off) {
                TurbineStandBlockEntity s = firstFreeCasingStand(c, c.getBlockPos());
                return s == null ? noCasingStand() : c.queue(new Move(Loc.HELD, Loc.casing(s.getBlockPos())));
            }
            BlockPos origin = TurbineLoadItem.hoodOrigin(carried);
            PowerModuleBlockEntity lp = pick(c, lpAt != null ? lpAt : origin, m -> m.coverOff() && m.allBladesIn());
            if (lp == null && lpAt == null) {
                lp = pick(c, null, m -> m.coverOff() && m.allBladesIn());
            }
            return lp == null ? noOpenLp() : c.queue(new Move(Loc.HELD, Loc.cover(lp.getBlockPos())));
        }
        if (off) {
            PowerModuleBlockEntity lp = pick(c, lpAt, m -> !m.coverOff());
            if (lp == null) {
                return tr("no_closed_lp", "No LP turbine section with its cover on lies under the crane.");
            }
            TurbineStandBlockEntity s = firstFreeCasingStand(c, lp.getBlockPos());
            return s == null ? noCasingStand() : c.queue(new Move(Loc.cover(lp.getBlockPos()), Loc.casing(s.getBlockPos())));
        }
        PowerModuleBlockEntity lp = pick(c, lpAt, PowerModuleBlockEntity::coverOff);
        if (lp == null) {
            return noOpenLp();
        }
        TurbineStandBlockEntity from = null;
        for (TurbineStandBlockEntity s : stands(c, TurbineStandBlock.Kind.CASING, lp.getBlockPos())) {
            ItemStack h = s.items().getStackInSlot(0);
            if (TurbineLoadItem.isHood(h) && (from == null || lp.getBlockPos().equals(TurbineLoadItem.hoodOrigin(h)))) {
                from = s;
                if (lp.getBlockPos().equals(TurbineLoadItem.hoodOrigin(h))) {
                    break;
                }
            }
        }
        if (from == null) {
            return tr("no_hood_in_reach", "No LP turbine hood lies on a Casing Laydown Stand under the crane.");
        }
        return c.queue(new Move(Loc.casing(from.getBlockPos()), Loc.cover(lp.getBlockPos())));
    }

    @Nullable
    private static TurbineStandBlockEntity firstFreeCasingStand(BuildingCraneBlockEntity c, BlockPos near) {
        for (TurbineStandBlockEntity s : stands(c, TurbineStandBlock.Kind.CASING, near)) {
            if (s.items().getStackInSlot(0).isEmpty()) {
                return s;
            }
        }
        return null;
    }

    private static Component noCasingStand() {
        return tr("no_casing_stand_free", "No free Casing Laydown Stand under the crane: place one on clear floor beside the turbines, with 7 x 7 of room round it.");
    }

    private static Component noOpenLp() {
        return tr("no_open_lp", "No LP turbine section under the crane has its cover off (with all its blade sections in, for COVER ON).");
    }

    /** BLADES OUT ({@code out}) or BLADES IN for the given LP (any of its blocks) or the nearest open one that wants it. */
    @Nullable
    static Component queueBlades(BuildingCraneBlockEntity c, boolean out, @Nullable BlockPos lpAt) {
        Component why = siteProblem(c);
        if (why != null) {
            return why;
        }
        ItemStack carried = c.carriedTurbine();
        if (!carried.isEmpty() && c.currentMove() == null) {
            if (!TurbineLoadItem.isBladeSection(carried)) {
                return BwrText.tr("status.bwr.building_crane.holding", "The hook still holds a load: set it down first.");
            }
            if (out || !TurbineLoadItem.isGoodSection(carried)) {
                Loc to = freeSaddle(c, c.getBlockPos());
                return to == null ? noSaddle() : c.queue(new Move(Loc.HELD, to));
            }
            PowerModuleBlockEntity lp = pick(c, lpAt, m -> m.coverOff() && !m.allBladesIn());
            return lp == null ? noEmptyFlow() : c.queue(new Move(Loc.HELD, Loc.blades(lp.getBlockPos(), firstOut(lp))));
        }
        if (out) {
            PowerModuleBlockEntity lp = pick(c, lpAt, m -> m.coverOff() && m.worstFlow() >= 0);
            if (lp == null) {
                return tr("no_section_to_lift", "No LP turbine under the crane has its cover off and a blade section in it: COVER OFF first.");
            }
            Loc to = freeSaddle(c, lp.getBlockPos());
            return to == null ? noSaddle() : c.queue(new Move(Loc.blades(lp.getBlockPos(), lp.worstFlow()), to));
        }
        PowerModuleBlockEntity lp = pick(c, lpAt, m -> m.coverOff() && !m.allBladesIn());
        if (lp == null) {
            return noEmptyFlow();
        }
        for (TurbineStandBlockEntity s : stands(c, TurbineStandBlock.Kind.BLADES, lp.getBlockPos())) {
            for (int i = 0; i < s.items().getSlots(); i++) {
                if (TurbineLoadItem.isGoodSection(s.items().getStackInSlot(i))) {
                    return c.queue(new Move(Loc.bladeStand(s.getBlockPos(), i), Loc.blades(lp.getBlockPos(), firstOut(lp))));
                }
            }
        }
        return tr("no_spare", "No spare LP blade section lies on an LP Blade Storage Stand under the crane.");
    }

    private static int firstOut(PowerModuleBlockEntity lp) {
        for (int f = 0; f < TurbineBlades.FLOWS; f++) {
            if (!lp.bladeIn(f)) {
                return f;
            }
        }
        return 0;
    }

    @Nullable
    private static Loc freeSaddle(BuildingCraneBlockEntity c, BlockPos near) {
        for (TurbineStandBlockEntity s : stands(c, TurbineStandBlock.Kind.BLADES, near)) {
            for (int i = 0; i < s.items().getSlots(); i++) {
                if (s.items().getStackInSlot(i).isEmpty()) {
                    return Loc.bladeStand(s.getBlockPos(), i);
                }
            }
        }
        return null;
    }

    private static Component noSaddle() {
        return tr("no_saddle", "No free saddle on an LP Blade Storage Stand under the crane.");
    }

    private static Component noEmptyFlow() {
        return tr("no_empty_flow", "No LP turbine under the crane has its cover off and a blade section out.");
    }

    // ------------------------------------------------------------------------------------------------ readouts

    /** The LP sections under the crane, nearest the hook first, for the pendant and the computer. */
    static List<Map<String, Object>> survey(BuildingCraneBlockEntity c) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (c.site() == null || !c.site().ok()) {
            return list;
        }
        for (PowerModuleBlockEntity m : lps(c)) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("x", m.getBlockPos().getX());
            e.put("y", m.getBlockPos().getY());
            e.put("z", m.getBlockPos().getZ());
            e.put("coverOff", m.coverOff());
            e.put("rpm", m.rpm);
            e.put("bladeA", m.bladeIn(0) ? m.blade(0) : -1.0);
            e.put("bladeB", m.bladeIn(1) ? m.blade(1) : -1.0);
            e.put("sectionAIn", m.bladeIn(0));
            e.put("sectionBIn", m.bladeIn(1));
            Component why = shaftProblem(m);
            if (why == null && !m.coverOff()) {
                why = casingProblem(m);
            }
            e.put("ready", why == null);
            e.put("reason", why == null ? "" : BwrText.english(why));
            list.add(e);
        }
        return list;
    }

    /** One line for the pendant: the nearest LP under the crane, its cover and its sections. */
    static String summary(BuildingCraneBlockEntity c) {
        var all = c.site() == null || !c.site().ok() ? List.<PowerModuleBlockEntity>of() : lps(c);
        if (all.isEmpty()) {
            return "";
        }
        PowerModuleBlockEntity m = all.get(0);
        return String.format(Locale.ROOT, "LP %s: cover %s, A %s, B %s", m.getBlockPos().toShortString(), m.coverOff() ? "off" : "on",
                m.bladeIn(0) ? String.format(Locale.ROOT, "%.0f%%", 100 * m.blade(0)) : "out", m.bladeIn(1) ? String.format(Locale.ROOT, "%.0f%%", 100 * m.blade(1)) : "out");
    }
}
