package dev.bwr.mod.advancement;

import dev.bwr.core.ReactorCore;
import dev.bwr.core.accident.ContainmentAtmosphere;
import dev.bwr.core.boundary.BoundaryComponent;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.power.PowerModuleBlockEntity;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.VesselState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Server-side achievement observations. Never commands or changes plant physics. */
@EventBusSubscriber(modid = BwrMod.MOD_ID)
public final class PlantMilestones {
    public static final String DATA_KEY = "BwrMilestones";
    public static final String OWNER_KEY = "milestoneOwner";
    public static final String CRITERION = "achieved";
    /** Gameplay achievement definitions, not operating limits or protection setpoints. */
    public static final double SCRAM_START_POWER = 0.01;
    public static final double SCRAM_END_POWER = 0.001;
    public static final double SCRAM_HOLD_SECONDS = 10.0;
    public static final int CONTROL_ROOM_PANELS = 4;
    public static final int CONTROL_ROOM_COMPONENTS = 4;

    /** Ordinals are saved bit positions; append new entries, never reorder these. */
    public enum Milestone {
        FIRST_CRITICALITY("first_criticality"), POWER_TO_GRID("power_to_grid"),
        SURVIVE_SCRAM("survive_scram"), REFUEL("refuel"),
        SEAL_CONTAINMENT("seal_containment"), BUILD_CONTROL_ROOM("build_control_room"),
        LOWER_HEAD_FAILURE("lower_head_failure"), MELT_THROUGH("melt_through");
        public final String path;
        Milestone(String path) { this.path = path; }
        public int mask() { return 1 << ordinal(); }
    }

    // Only entities already encountered through their normal lifecycle; no world scan or chunk ticket.
    private static final Set<BlockEntity> LOADED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<PanelBlockEntity> RESOLVING_PANEL_GROUP = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<PanelGroup, ServerLevel> PANEL_EDITS = new IdentityHashMap<>();
    private static int panelEditDepth;
    /** Actual component traversals, for the structural-only evaluation regression. Server thread only. */
    static long panelGroupEvaluations;
    private PlantMilestones() {}

    private static boolean supported(BlockEntity be) {
        return be instanceof ReactorControllerBlockEntity || be instanceof PowerModuleBlockEntity
                || be instanceof ContainmentControllerBlockEntity || be instanceof PanelBlockEntity;
    }

    private static CompoundTag data(BlockEntity be) {
        CompoundTag root = be.getPersistentData();
        if (!root.contains(DATA_KEY, Tag.TAG_COMPOUND)) root.put(DATA_KEY, new CompoundTag());
        return root.getCompound(DATA_KEY);
    }

    public static UUID milestoneOwner(BlockEntity be) {
        CompoundTag tag = data(be);
        return tag.hasUUID(OWNER_KEY) ? tag.getUUID(OWNER_KEY) : null;
    }

    /** Cosmetic attribution only; never consulted by permission checks or plant control. */
    public static boolean claim(BlockEntity be, ServerPlayer player) {
        if (be == null || player.isFakePlayer() || !supported(be) || !(be.getLevel() instanceof ServerLevel level)
                || level != player.level() || !level.isLoaded(be.getBlockPos())
                || !level.mayInteract(player, be.getBlockPos())) return false;
        CompoundTag tag = data(be);
        if (!tag.hasUUID(OWNER_KEY)) {
            tag.putUUID(OWNER_KEY, player.getUUID());
            be.setChanged();
        }
        loaded(be);
        boolean ownsMilestones = player.getUUID().equals(milestoneOwner(be));
        if (ownsMilestones && be instanceof PanelBlockEntity panel) panelChanged(panel);
        return ownsMilestones;
    }

    public static void loaded(BlockEntity be) {
        if (!supported(be) || !(be.getLevel() instanceof ServerLevel)) return;
        LOADED.add(be);
        flush(be);
    }

    public static void unloaded(BlockEntity be) { LOADED.remove(be); }

    private static void earn(BlockEntity be, Milestone milestone) {
        if (!(be.getLevel() instanceof ServerLevel) || milestoneOwner(be) == null) return;
        CompoundTag tag = data(be);
        if ((tag.getInt("Earned") & milestone.mask()) != 0) return;
        tag.putInt("Earned", tag.getInt("Earned") | milestone.mask());
        tag.putInt("Pending", tag.getInt("Pending") | milestone.mask());
        be.setChanged();
        loaded(be);
    }

    private static void flush(BlockEntity be) {
        if (be.isRemoved() || !(be.getLevel() instanceof ServerLevel level)
                || !level.isLoaded(be.getBlockPos())) return;
        CompoundTag tag = data(be);
        int pending = tag.getInt("Pending");
        UUID owner = milestoneOwner(be);
        if (pending == 0 || owner == null) return;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
        if (player == null) return;
        var root = level.getServer().getAdvancements().get(BwrMod.id("milestones/root"));
        if (root != null) player.getAdvancements().award(root, CRITERION);
        int remaining = pending;
        for (Milestone milestone : Milestone.values()) {
            if ((pending & milestone.mask()) == 0) continue;
            var advancement = level.getServer().getAdvancements().get(BwrMod.id("milestones/" + milestone.path));
            if (advancement == null || !advancement.value().criteria().containsKey(CRITERION)) continue;
            player.getAdvancements().award(advancement, CRITERION);
            if (player.getAdvancements().getOrStartProgress(advancement).isDone()) remaining &= ~milestone.mask();
        }
        if (remaining != pending) {
            tag.putInt("Pending", remaining);
            be.setChanged();
        }
    }

    /** A login flush visits only the small set of already loaded participating entities. */
    public static void flush(ServerPlayer player) {
        for (BlockEntity be : new ArrayList<>(LOADED)) {
            if (be.getLevel() instanceof ServerLevel level && level.getServer() == player.getServer()
                    && player.getUUID().equals(milestoneOwner(be))) flush(be);
        }
    }

    private static boolean headFailed(ReactorCore core) { return core.getSevereAccident().isLowerHeadFailed(); }
    private static boolean meltedThrough(ReactorCore core) {
        return core.getSevereAccident().isBasematFailed();
    }

    /** Establish an old save's accident baseline before its first resumed physics step. */
    public static void beforeReactorStep(ReactorControllerBlockEntity be) {
        if (!(be.getLevel() instanceof ServerLevel) || be.core() == null) return;
        loaded(be);
        CompoundTag tag = data(be);
        if (be.vesselState() != VesselState.REFUELING && tag.contains("RefuelSlots")) {
            tag.remove("RefuelSlots");
            be.setChanged();
        }
        if (!tag.getBoolean("Observed")) {
            tag.putBoolean("Observed", true);
            remember(tag, be.core());
            clearScram(tag);
            be.setChanged();
        }
    }

    private static void remember(CompoundTag tag, ReactorCore core) {
        tag.putBoolean("LastScram", core.isScramActive());
        tag.putDouble("LastNeutron", core.getNeutronPowerFraction());
        tag.putBoolean("LastHeadFailed", headFailed(core));
        tag.putBoolean("LastMeltThrough", meltedThrough(core));
    }

    public static void reactorUnavailable(ReactorControllerBlockEntity be) {
        if (!(be.getLevel() instanceof ServerLevel)) return;
        CompoundTag tag = data(be);
        if (tag.getBoolean("Observed") || tag.getBoolean("ScramPending")
                || tag.getDouble("ScramHold") != 0.0 || tag.contains("RefuelSlots")) {
            clearScram(tag);
            tag.putBoolean("Observed", false);
            tag.remove("RefuelSlots");
            be.setChanged();
        }
    }

    private static void clearScram(CompoundTag tag) {
        tag.putBoolean("ScramPending", false);
        tag.putDouble("ScramHold", 0.0);
    }

    private static boolean intactCovered(ReactorCore core) {
        if (core.getUncoveredFuelFraction() > 0.0 || core.getSevereAccident().getFailedCladFraction() > 0.0
                || core.getSevereAccident().getMeltedFraction() > 0.0 || headFailed(core)
                || core.getContainment().getFailureMode() != ContainmentAtmosphere.FailureMode.NONE) return false;
        for (BoundaryComponent component : BoundaryComponent.values())
            if (core.getBoundaryStress().isBroken(component)) return false;
        return true;
    }

    public static void afterReactorStep(ReactorControllerBlockEntity be) {
        if (!(be.getLevel() instanceof ServerLevel) || be.core() == null || !be.isFormed()) return;
        ReactorCore core = be.core();
        CompoundTag tag = data(be);
        if (!tag.getBoolean("Observed")) { beforeReactorStep(be); return; }
        double neutron = core.getNeutronPowerFraction();
        double reactivity = core.getReactivityDkOverK();
        if (core.getCoreLoading().loadedAssemblyCount() > 0 && Double.isFinite(reactivity)
                && reactivity >= 0.0 && Double.isFinite(neutron) && neutron > 0.0)
            earn(be, Milestone.FIRST_CRITICALITY);
        if (!tag.getBoolean("LastHeadFailed") && headFailed(core)) earn(be, Milestone.LOWER_HEAD_FAILURE);
        if (!tag.getBoolean("LastMeltThrough") && meltedThrough(core)) earn(be, Milestone.MELT_THROUGH);

        boolean healthy = intactCovered(core);
        double previousPower = tag.getDouble("LastNeutron");
        if (!tag.getBoolean("LastScram") && core.isScramActive()
                && Double.isFinite(previousPower) && previousPower >= SCRAM_START_POWER
                && healthy && milestoneOwner(be) != null) {
            tag.putBoolean("ScramPending", true);
            tag.putDouble("ScramHold", 0.0);
        }
        if (!core.isScramActive() || !healthy) clearScram(tag);
        if (tag.getBoolean("ScramPending")) {
            boolean inserted = core.getControlRodCount() > 0;
            for (int rod = 0; rod < core.getControlRodCount(); rod++) {
                double notch = core.getRodPositionNotches(rod);
                inserted &= Double.isFinite(notch) && notch <= 1.0e-6;
            }
            double dt = core.getConfig().tickSeconds;
            double held = tag.getDouble("ScramHold");
            if (!Double.isFinite(held) || held < 0.0) held = 0.0;
            if (inserted && Double.isFinite(neutron) && neutron <= SCRAM_END_POWER
                    && Double.isFinite(dt) && dt > 0.0) held += Math.min(dt, 1.0);
            else held = 0.0;
            tag.putDouble("ScramHold", held);
            if (held + 1.0e-9 >= SCRAM_HOLD_SECONDS) {
                earn(be, Milestone.SURVIVE_SCRAM);
                clearScram(tag);
            }
        }
        remember(tag, core);
        be.setChanged();
    }

    /** Called only after successful extraction, including pushed and externally pulled FE. */
    public static void energyDelivered(PowerModuleBlockEntity be, int actualFe) {
        if (actualFe > 0 && be.block().generator()) earn(be, Milestone.POWER_TO_GRID);
    }

    /** Called after a real fuel bundle was removed, never for a specialty rod or an empty slot. */
    public static void fuelUnloaded(ReactorControllerBlockEntity be, int slot) {
        if (!(be.getLevel() instanceof ServerLevel) || be.vesselState() != VesselState.REFUELING
                || milestoneOwner(be) == null || be.core() == null || slot < 0
                || slot >= be.core().getCoreLoading().positionCount()) return;
        CompoundTag tag = data(be);
        int[] old = tag.getIntArray("RefuelSlots");
        if (Arrays.stream(old).anyMatch(value -> value == slot)) return;
        int[] next = Arrays.copyOf(old, old.length + 1);
        next[old.length] = slot;
        tag.putIntArray("RefuelSlots", next);
        be.setChanged();
    }

    /** Called only after successful real fuel insertion into a previously emptied slot. */
    public static void fuelLoaded(ReactorControllerBlockEntity be, int slot) {
        if (!(be.getLevel() instanceof ServerLevel) || be.vesselState() != VesselState.REFUELING) return;
        CompoundTag tag = data(be);
        int[] old = tag.getIntArray("RefuelSlots");
        if (Arrays.stream(old).noneMatch(value -> value == slot)) return;
        tag.putIntArray("RefuelSlots", Arrays.stream(old).filter(value -> value != slot).toArray());
        be.setChanged();
        earn(be, Milestone.REFUEL);
    }

    /** A completed geometric survey is the achievement; it does not certify an accident pressure rating. */
    public static void containmentSurveyCompleted(ContainmentControllerBlockEntity be) {
        if (be.isSealed() && !be.isStale() && !be.isScanning() && be.freeVolume() > 0)
            earn(be, Milestone.SEAL_CONTAINMENT);
    }

    /** Begin a nested structural edit, so a reader fan-out evaluates each affected group once. */
    public static void beginPanelEdit() { panelEditDepth++; }

    /** Called in finally by the matching edit. There is no recurring tick or pending world search. */
    public static void endPanelEdit() {
        if (--panelEditDepth != 0) return;
        var pending = new IdentityHashMap<>(PANEL_EDITS);
        PANEL_EDITS.clear();
        for (var entry : pending.entrySet()) evaluatePanelGroup(entry.getValue(), entry.getKey());
    }

    /** A structural/configuration event; never called by periodic live-value sampling. */
    public static void panelChanged(PanelBlockEntity be) {
        if (!(be.getLevel() instanceof ServerLevel level) || be.isRemoved()) return;
        // group() publishes its new shared group before its formation callback. Suppress only that
        // callback here: this request evaluates the resolved group below, without a second pass.
        if (!RESOLVING_PANEL_GROUP.add(be)) return;
        PanelGroup group;
        try { group = be.group(); }
        finally { RESOLVING_PANEL_GROUP.remove(be); }
        requestPanelGroup(level, group);
    }

    /** Called only after a newly computed group has been shared with every loaded member. */
    public static void panelGroupFormed(PanelBlockEntity be, PanelGroup group) {
        if (be.getLevel() instanceof ServerLevel level && !RESOLVING_PANEL_GROUP.contains(be))
            requestPanelGroup(level, group);
    }

    private static void requestPanelGroup(ServerLevel level, PanelGroup group) {
        if (group == null || !group.isValid()) return;
        if (panelEditDepth > 0) PANEL_EDITS.put(group, level);
        else evaluatePanelGroup(level, group);
    }

    private static void evaluatePanelGroup(ServerLevel level, PanelGroup group) {
        if (!group.isValid() || group.oversize() || group.members().size() < CONTROL_ROOM_PANELS) return;
        var members = new ArrayList<PanelBlockEntity>(group.members().size());
        boolean uncreditedOwner = false;
        for (BlockPos pos : group.members()) {
            if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof PanelBlockEntity member)
                    || member.isRemoved()) return;
            members.add(member);
            uncreditedOwner |= milestoneOwner(member) != null
                    && (data(member).getInt("Earned") & Milestone.BUILD_CONTROL_ROOM.mask()) == 0;
        }
        if (!uncreditedOwner) return;
        panelGroupEvaluations++;
        int components = 0;
        boolean input = false, output = false, live = false;
        for (PanelBlockEntity member : members) {
            for (var component : member.owned()) {
                if (component.type() == null) continue;
                components++;
                input |= component.inputCount() > 0;
                output |= component.outputCount() > 0;
                for (int i = 0; i < component.inputCount(); i++)
                    live |= component.source(i).isLive() && Double.isFinite(component.input(i));
            }
        }
        if (components >= CONTROL_ROOM_COMPONENTS && input && output && live) {
            for (PanelBlockEntity member : members) earn(member, Milestone.BUILD_CONTROL_ROOM);
        }
    }

    private static BlockEntity target(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) return null;
        var state = level.getBlockState(pos);
        if (state.getBlock() instanceof PumpAssemblyBlock block) pos = block.origin(pos, state);
        return level.isLoaded(pos) ? level.getBlockEntity(pos) : null;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void placed(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            BlockEntity be = target(level, event.getPos());
            boolean claimed = event.getEntity() instanceof ServerPlayer player && claim(be, player);
            // Automated placement may join groups, but does not acquire cosmetic player ownership.
            if (!claimed && be instanceof PanelBlockEntity panel) panelChanged(panel);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void interacted(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getLevel() instanceof ServerLevel level
                && level.mayInteract(player, event.getPos())) {
            claim(target(level, event.getPos()), player);
        }
    }

    @SubscribeEvent public static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) flush(player);
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        LOADED.clear();
        RESOLVING_PANEL_GROUP.clear();
        PANEL_EDITS.clear();
        panelEditDepth = 0;
        panelGroupEvaluations = 0;
    }
}
