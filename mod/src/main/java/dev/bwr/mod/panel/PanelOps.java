package dev.bwr.mod.panel;

import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ConfigSchema;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.InputSource;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.world.AssemblyAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The authoritative, validated server-side operations on panels. The C2S
 * packets, the vanilla right-click fallbacks and the GameTests all go through
 * here, so there is exactly one set of rules:
 * <ul>
 *   <li>the player must be in reach of the block (distance), and the block's
 *       claim must be permitted for that player ({@link AssemblyAccess#permitted})
 *       for every block the component covers;</li>
 *   <li>the item or tool must be in the player's hands;</li>
 *   <li>every number from the client is range-checked here or by the type.</li>
 * </ul>
 */
public final class PanelOps {
    private PanelOps() {
    }

    /** Extra reach beyond the vanilla interaction range, for latency. */
    private static final double REACH_SLACK = 1.0;

    public static boolean inReach(ServerPlayer player, BlockPos pos) {
        return player.canInteractWithBlock(pos, REACH_SLACK);
    }

    /** The wrench in either hand, or empty. */
    public static ItemStack heldWrench(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof PanelWrenchItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Which cell of the block at {@code pos} a view ray lands on.
     *
     * @return false when the ray misses the mounting plane of that block
     */
    public static boolean cellAt(PanelBlockEntity be, Vec3 eye, Vec3 look, int[] out) {
        double[] cell = new double[2];
        BlockPos pos = be.getBlockPos();
        PanelGeometry.Frame frame = PanelGeometry.frame(be.panelType(), be.facing());
        if (!PanelGeometry.rayToCell(frame, pos.getX(), pos.getY(), pos.getZ(), eye.x, eye.y, eye.z,
                look.x, look.y, look.z, cell)) {
            return false;
        }
        if (cell[0] < -0.5 || cell[1] < -0.5 || cell[0] > PanelGeometry.GRID + 0.5 || cell[1] > PanelGeometry.GRID + 0.5) {
            return false;
        }
        out[0] = Math.max(0, Math.min(PanelGeometry.GRID - 1, (int) Math.floor(cell[0])));
        out[1] = Math.max(0, Math.min(PanelGeometry.GRID - 1, (int) Math.floor(cell[1])));
        return true;
    }

    // ---- place

    /**
     * Places the component item held in {@code hand} with its footprint's
     * top-left at cell {@code (cx, cy)} of the block at {@code anchor}.
     */
    public static PanelGroup.Result place(ServerPlayer player, InteractionHand hand, BlockPos anchor, int cx, int cy) {
        ServerLevel level = player.serverLevel();
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof ComponentItem item) || item.type() == null) {
            return PanelGroup.Result.BAD_ARGUMENTS;
        }
        if (!inReach(player, anchor) || !AssemblyAccess.permitted(level, player, anchor)) {
            return PanelGroup.Result.BAD_ARGUMENTS;
        }
        ComponentType type = item.type();
        Footprint footprint = ComponentItem.footprint(stack);
        PanelGroup.Placement placement = PanelGroup.checkPlacement(level, anchor, cx, cy, footprint, type.maxSpanBlocks());
        if (!placement.ok()) {
            return placement.result();
        }
        for (PanelGroup.Rect rect : placement.rects()) {
            if (!AssemblyAccess.permitted(level, player, rect.pos())) {
                return PanelGroup.Result.BAD_ARGUMENTS;
            }
        }
        commit(level, anchor, cx, cy, type, footprint, placement);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return PanelGroup.Result.OK;
    }

    /**
     * Places a component without a player: no reach, permission or item. For tests and tooling that build
     * panels directly; the fit is still checked.
     *
     * @return the component, or null with the reason lost (call {@link PanelGroup#checkPlacement} for it)
     */
    public static PanelComponent placeDirect(ServerLevel level, BlockPos anchor, int cx, int cy, ComponentType type,
                                             Footprint footprint) {
        PanelGroup.Placement placement = PanelGroup.checkPlacement(level, anchor, cx, cy, footprint, type.maxSpanBlocks());
        return placement.ok() ? commit(level, anchor, cx, cy, type, footprint, placement) : null;
    }

    private static PanelComponent commit(ServerLevel level, BlockPos anchor, int cx, int cy, ComponentType type,
                                         Footprint footprint, PanelGroup.Placement placement) {
        PanelBlockEntity be = (PanelBlockEntity) level.getBlockEntity(anchor);
        String label = be.group().nextLabel(level, type.labelPrefix());
        PanelComponent component = be.addComponent(type, cx, cy, footprint, label, placement.rects());
        for (PanelGroup.Rect rect : placement.rects()) {
            if (level.getBlockEntity(rect.pos()) instanceof PanelBlockEntity touched) {
                touched.syncFull();
            }
        }
        return component;
    }

    // ---- remove

    /** Removes a component and drops its item. The player must hold the wrench. */
    public static boolean remove(ServerPlayer player, BlockPos ownerPos, int id) {
        ServerLevel level = player.serverLevel();
        if (heldWrench(player).isEmpty() || !inReach(player, ownerPos) || !level.isLoaded(ownerPos)
                || !(level.getBlockEntity(ownerPos) instanceof PanelBlockEntity owner)) {
            return false;
        }
        PanelComponent component = owner.component(id);
        if (component == null) {
            return false;
        }
        List<BlockPos> touched = coveredBlocks(level, owner, id);
        for (BlockPos pos : touched) {
            if (!AssemblyAccess.permitted(level, player, pos)) {
                return false;
            }
        }
        PanelComponent removed = owner.removeComponent(id);
        owner.dropItem(removed);
        for (BlockPos pos : touched) {
            if (level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                be.syncFull();
            }
        }
        return true;
    }

    /** The owner block plus every block holding a claim for component {@code id}. */
    private static List<BlockPos> coveredBlocks(ServerLevel level, PanelBlockEntity owner, int id) {
        PanelComponent component = owner.component(id);
        List<BlockPos> blocks = new ArrayList<>();
        blocks.add(owner.getBlockPos());
        if (component != null) {
            for (BlockPos pos : owner.coverage(component)) {
                if (!pos.equals(owner.getBlockPos()) && level.isLoaded(pos)
                        && level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                    for (PanelBlockEntity.Claim claim : be.foreignClaims()) {
                        if (claim.id() == id && claim.owner().equals(owner.getBlockPos())) {
                            blocks.add(pos);
                            break;
                        }
                    }
                }
            }
        }
        return blocks;
    }

    // ---- configure

    /** Source kinds a client may set directly through the configure packet. */
    private static final java.util.Set<String> CLIENT_SETTABLE_KINDS = new java.util.HashSet<>(
            List.of(InputSources.KIND_NONE, InputSources.KIND_CONSTANT));

    /** The wiring layer calls this for kinds its own packets set, to keep them out of the generic path. */
    public static boolean isClientSettable(String kind) {
        return CLIENT_SETTABLE_KINDS.contains(kind);
    }

    /**
     * Applies a config edit. {@code edit} may contain {@code Label} (string),
     * {@code Config} (compound of the type's fields) and {@code Inputs} (list of
     * source compounds, positional). Anything invalid is ignored field by field.
     * The player must hold the wrench.
     */
    public static boolean configure(ServerPlayer player, BlockPos ownerPos, int id, CompoundTag edit) {
        ServerLevel level = player.serverLevel();
        if (heldWrench(player).isEmpty() || !inReach(player, ownerPos) || !AssemblyAccess.permitted(level, player, ownerPos)
                || !level.isLoaded(ownerPos) || !(level.getBlockEntity(ownerPos) instanceof PanelBlockEntity owner)) {
            return false;
        }
        PanelComponent component = owner.component(id);
        if (component == null || component.type() == null) {
            return false;
        }
        ComponentType type = component.type();
        if (edit.contains("Label", Tag.TAG_STRING)) {
            String label = cleanLabel(edit.getString("Label"));
            if (!label.isEmpty() && !label.equals(component.label())) {
                PanelGroup group = owner.group();
                if (group != null && !group.labelsInUse(level).contains(label)) {
                    component.setLabel(label);
                }
            }
        }
        if (edit.contains("Config", Tag.TAG_COMPOUND)) {
            CompoundTag merged = ConfigSchema.merge(type.configSchema(), component.data().toTag(), edit.getCompound("Config"));
            type.adjustConfig(component, merged);
            net.minecraft.network.chat.Component refusal = type.refuseConfig(level, component, merged);
            if (refusal != null) {
                // A setting whose drawing would not fit the component is refused as a whole, and says why.
                player.displayClientMessage(refusal, true);
            } else {
                component.data().load(merged);
                component.syncPorts();
                type.onLoaded(component); // latched outputs and derived state follow the new config
                component.touch();
            }
        }
        if (edit.contains("Inputs", Tag.TAG_LIST)) {
            ListTag list = edit.getList("Inputs", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size() && i < component.inputCount(); i++) {
                CompoundTag entry = list.getCompound(i);
                String kind = entry.getString("Kind");
                boolean finite = !InputSources.KIND_CONSTANT.equals(kind) || Double.isFinite(entry.getDouble("Value"));
                if (finite && isClientSettable(kind) && InputSources.isKnown(kind)) {
                    InputSource source = InputSources.load(entry);
                    component.setSource(i, source);
                }
            }
        }
        owner.resample(component, true);
        component.touch();
        owner.syncFull();
        return true;
    }

    /** Labels are short, printable and free of leading or trailing blanks. */
    public static String cleanLabel(String raw) {
        return ConfigSchema.cleanText(raw, PanelConfig.maxLabelLength()).strip();
    }

    // ---- operate and use

    /** A press / release / turn / set on an input component. */
    public static boolean operate(ServerPlayer player, BlockPos ownerPos, int id, OperateAction action, double value) {
        ServerLevel level = player.serverLevel();
        if (action == null || !Double.isFinite(value) || Math.abs(value) > 1.0e6 || !inReach(player, ownerPos)
                || !AssemblyAccess.permitted(level, player, ownerPos) || !level.isLoaded(ownerPos)
                || !(level.getBlockEntity(ownerPos) instanceof PanelBlockEntity owner)) {
            return false;
        }
        PanelComponent component = owner.component(id);
        if (component == null || component.type() == null) {
            return false;
        }
        return component.type().operate(new ComponentType.OperateContext(level, player, owner, component), action, value);
    }

    /**
     * A bare-handed right-click on cell {@code (cx, cy)} of {@code clicked}: forwards
     * to the owner of whatever component covers the cell, possibly in another block.
     */
    public static InteractionResult forwardUse(ServerPlayer player, PanelBlockEntity clicked, int cx, int cy,
                                               double fx, double fy) {
        ServerLevel level = player.serverLevel();
        PanelBlockEntity.Claim claim = clicked.claimAt(cx, cy);
        if (claim == null || !level.isLoaded(claim.owner())
                || !(level.getBlockEntity(claim.owner()) instanceof PanelBlockEntity owner)) {
            return InteractionResult.PASS;
        }
        PanelComponent component = owner.component(claim.id());
        if (component == null || component.type() == null) {
            return InteractionResult.PASS;
        }
        // Position of the click relative to the component's top-left, in cells, across block boundaries.
        PanelGroup group = owner.group();
        double ox = 0;
        double oy = 0;
        if (group != null && group.contains(clicked.getBlockPos())) {
            ox = (group.columnOf(clicked.getBlockPos()) - group.columnOf(owner.getBlockPos())) * PanelGeometry.GRID;
            oy = (group.rowOf(clicked.getBlockPos()) - group.rowOf(owner.getBlockPos())) * PanelGeometry.GRID;
        }
        return component.type().use(new ComponentType.UseContext(level, player, owner, component,
                ox + fx - component.x(), oy + fy - component.y()));
    }
}
