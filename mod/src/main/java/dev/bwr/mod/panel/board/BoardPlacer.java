package dev.bwr.mod.panel.board;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelConfig;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.board.BoardTemplate.Comp;
import dev.bwr.mod.panel.board.BoardTemplate.Surface;
import dev.bwr.mod.panel.component.ComponentData;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ConfigSchema;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.mimic.MimicGeometry;
import dev.bwr.mod.panel.mimic.MimicOps;
import dev.bwr.mod.panel.mimic.MimicStyle;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.text.BwrText;
import dev.bwr.mod.world.AssemblyAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts a premade board in the world: the checks, the blocks, the components with their settings, the mimic paths, and
 * in survival the materials. The one place the blueprint item, the tests and the client check all go through.
 *
 * <p>Everything is checked before anything is changed: a board that does not fit, a player who may not build there or
 * one who lacks the materials changes nothing, and says why. The board is placed <b>unwired</b>: no component has a
 * source or a sink, nothing acts on the plant. In creative mode (and for a null player, the tests and tools) it is
 * free; in survival it costs the panel blocks, one item for each component and the mimic tape its paths need, taken
 * from the player's inventory when the board is placed.
 */
public final class BoardPlacer {
    private BoardPlacer() {
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Blocked positions listed in a refusal. */
    private static final int LIST_LIMIT = 16;

    public enum Status {
        OK, NOT_LOADED, OUT_OF_WORLD, NOT_PERMITTED, BLOCKED, LIMITS, MISSING, FAILED
    }

    /**
     * What a placement did or why it did not.
     *
     * @param blocked  the positions in the way (first {@value #LIST_LIMIT})
     * @param missing  per item, how many are lacking from the inventory
     * @param detail   the reason of a LIMITS or FAILED
     * @param blocks   panel blocks placed (or that would be)
     * @param components components placed
     * @param paths    mimic paths placed
     */
    public record Outcome(Status status, List<BlockPos> blocked, Map<Item, Integer> missing, String detail, int blocks,
                          int components, int paths) {
        public boolean ok() {
            return status == Status.OK;
        }

        /** The player-facing line for this outcome. */
        public Component message(String title) {
            return switch (status) {
                case OK -> BwrText.tr("message.bwr.board.placed", "%s placed: %s panel blocks, %s components, %s mimic paths",
                        title, blocks, components, paths);
                case NOT_LOADED -> BwrText.tr("message.bwr.board.not_loaded", "%s: part of the board is in a chunk that is not loaded", title);
                case OUT_OF_WORLD -> BwrText.tr("message.bwr.board.out_of_world", "%s does not fit between the bottom and the top of the world", title);
                case NOT_PERMITTED -> BwrText.tr("message.bwr.board.not_permitted", "You cannot build here (%s)", title);
                case BLOCKED -> BwrText.tr("message.bwr.board.blocked", "No room for %s: %s block(s) in the way, the first at %s",
                        title, blocked.size() >= LIST_LIMIT ? LIST_LIMIT + "+" : Integer.toString(blocked.size()),
                        blocked.isEmpty() ? "?" : blocked.get(0).toShortString());
                case LIMITS -> BwrText.tr("message.bwr.board.limits", "%s cannot be placed here: %s", title, detail);
                case MISSING -> BwrText.tr("message.bwr.board.missing", "Missing for %s: %s", title, describe(missing));
                case FAILED -> BwrText.tr("message.bwr.board.failed", "%s was not placed: %s", title, detail);
            };
        }
    }

    private static Outcome refuse(Status status, List<BlockPos> blocked, Map<Item, Integer> missing, String detail) {
        return new Outcome(status, List.copyOf(blocked), Map.copyOf(missing), detail, 0, 0, 0);
    }

    /** "4 x Dial Gauge, 2 x Nameplate, and 3 more kinds": the missing items, the biggest shortfall first. */
    public static String describe(Map<Item, Integer> missing) {
        List<Map.Entry<Item, Integer>> entries = new ArrayList<>(missing.entrySet());
        entries.sort(Comparator.<Map.Entry<Item, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(entry -> entry.getKey().getDescriptionId()));
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (Map.Entry<Item, Integer> entry : entries) {
            if (shown == 6) {
                out.append(", and ").append(entries.size() - shown).append(" more");
                break;
            }
            out.append(shown++ == 0 ? "" : ", ").append(entry.getValue()).append(" x ")
                    .append(entry.getKey().getDescription().getString());
        }
        return out.toString();
    }

    private static volatile java.util.function.BiPredicate<Player, BlockPos> permissionOverride;

    /** Test hook: replaces the permission check ({@link AssemblyAccess#permitted}); null restores the real one. */
    public static void overridePermission(java.util.function.BiPredicate<Player, BlockPos> permitted) {
        permissionOverride = permitted;
    }

    private static boolean permitted(ServerLevel level, Player player, BlockPos pos) {
        java.util.function.BiPredicate<Player, BlockPos> override = permissionOverride;
        return override != null ? override.test(player, pos) : AssemblyAccess.permitted(level, player, pos);
    }

    /** Whether the board is free for this player: creative, or no player at all (tests and tools). */
    public static boolean isFree(Player player) {
        return player == null || player.getAbilities().instabuild;
    }

    // ---- the checks

    /** Everything {@link #place} checks, changing nothing. */
    public static Outcome check(ServerLevel level, Player player, BoardTemplate template, BlockPos origin, Direction facing) {
        return check(level, player, new BoardPlan(template, origin, facing));
    }

    public static Outcome check(ServerLevel level, Player player, BoardPlan plan) {
        List<BlockPos> blocked = new ArrayList<>();
        boolean outside = false;
        boolean unloaded = false;
        boolean forbidden = false;
        CollisionContext context = player == null ? CollisionContext.empty() : CollisionContext.of(player);
        for (BoardPlan.Block block : plan.blocks()) {
            BlockPos pos = block.pos();
            if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) {
                outside = true;
            } else if (!level.isLoaded(pos)) {
                unloaded = true;
            } else if (player != null && !permitted(level, player, pos)) {
                forbidden = true;
            } else if (blocked.size() < LIST_LIMIT) {
                BlockState there = level.getBlockState(pos);
                if (!there.canBeReplaced() || !level.isUnobstructed(block.state(), pos, context)) {
                    blocked.add(pos.immutable());
                }
            }
        }
        if (outside) {
            return refuse(Status.OUT_OF_WORLD, List.of(), Map.of(), "");
        }
        if (unloaded) {
            return refuse(Status.NOT_LOADED, List.of(), Map.of(), "");
        }
        if (forbidden) {
            return refuse(Status.NOT_PERMITTED, List.of(), Map.of(), "");
        }
        if (!blocked.isEmpty()) {
            return refuse(Status.BLOCKED, blocked, Map.of(), "");
        }
        String limit = limits(plan.template());
        if (limit != null) {
            return refuse(Status.LIMITS, List.of(), Map.of(), limit);
        }
        if (!isFree(player)) {
            Map<Item, Integer> missing = missing(player, materials(plan));
            if (!missing.isEmpty()) {
                return refuse(Status.MISSING, List.of(), missing, "");
            }
        }
        return new Outcome(Status.OK, List.of(), Map.of(), "", plan.blocks().size(), plan.template().components().size(),
                plan.template().mimic().size());
    }

    /** The live config limits the template must fit, or null. (The parser checked the shipped defaults.) */
    public static String limits(BoardTemplate template) {
        for (BoardTemplate.Frame frame : template.frames()) {
            if (frame.columns() > PanelConfig.maxColumns() || frame.rows() > PanelConfig.maxRows()) {
                return "the frame '" + frame.id() + "' is " + frame.columns() + " x " + frame.rows()
                        + " blocks; a panel group may be " + PanelConfig.maxColumns() + " x " + PanelConfig.maxRows()
                        + " (bwr-panels.toml)";
            }
        }
        Map<String, Integer> perBlock = new HashMap<>();
        for (Comp comp : template.components()) {
            String block = comp.surface() + "/" + comp.x() / PanelGeometry.GRID + "," + comp.y() / PanelGeometry.GRID;
            if (perBlock.merge(block, 1, Integer::sum) > PanelConfig.maxComponentsPerBlock()) {
                return "more than " + PanelConfig.maxComponentsPerBlock() + " components start in one panel block ("
                        + "maxComponentsPerBlock in bwr-panels.toml)";
            }
            if (comp.label().length() > PanelConfig.maxLabelLength()) {
                return "the label '" + comp.label() + "' is longer than maxLabelLength (" + PanelConfig.maxLabelLength() + ")";
            }
        }
        return null;
    }

    // ---- materials

    /** What the board costs in survival: the panel blocks, one item per component, the tape its paths need. */
    public static Map<Item, Integer> materials(BoardPlan plan) {
        Map<Item, Integer> bill = new LinkedHashMap<>();
        for (BoardPlan.Block block : plan.blocks()) {
            if (block.item() != null) {
                bill.merge(block.item(), 1, Integer::sum);
            }
        }
        for (Comp comp : plan.template().components()) {
            PanelComponent probe = probe(comp);
            Item item = comp.type().itemFor(probe);
            bill.merge(item, 1, Integer::sum);
        }
        for (BoardTemplate.Mimic path : plan.template().mimic()) {
            Surface surface = plan.template().surface(path.surface());
            double aspect = PanelGeometry.frame(surface.panelType(), plan.facing()).vLength;
            bill.merge(BwrItems.MIMIC_TAPE.get(), MimicGeometry.cost(MimicGeometry.length(path.points(), aspect)), Integer::sum);
        }
        return bill;
    }

    /** A component that is not on any panel, carrying the template's settings (for the item it drops as). */
    private static PanelComponent probe(Comp comp) {
        PanelComponent probe = new PanelComponent(0, comp.type(), 0, 0, Footprint.of(comp.w(), comp.h()), "");
        if (!comp.config().isEmpty()) {
            probe.data().load(ConfigSchema.merge(comp.type().configSchema(), probe.data().toTag(), comp.config()));
        }
        return probe;
    }

    /** Per item, how many the player lacks (empty when the inventory holds the lot). */
    public static Map<Item, Integer> missing(Player player, Map<Item, Integer> bill) {
        Map<Item, Integer> lacking = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : bill.entrySet()) {
            int have = count(player, entry.getKey());
            if (have < entry.getValue()) {
                lacking.put(entry.getKey(), entry.getValue() - have);
            }
        }
        return lacking;
    }

    /** How many of an item the player carries (any stack of it, whatever data it has: a component of any size). */
    public static int count(Player player, Item item) {
        int total = 0;
        Inventory inventory = player.getInventory();
        for (ItemStack stack : inventory.items) {
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        for (ItemStack stack : inventory.offhand) {
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void consume(Player player, Map<Item, Integer> bill) {
        Inventory inventory = player.getInventory();
        for (Map.Entry<Item, Integer> entry : bill.entrySet()) {
            int left = entry.getValue();
            List<ItemStack> stacks = new ArrayList<>(inventory.items);
            stacks.addAll(inventory.offhand);
            for (ItemStack stack : stacks) {
                if (left > 0 && stack.is(entry.getKey())) {
                    int take = Math.min(left, stack.getCount());
                    stack.shrink(take);
                    left -= take;
                }
            }
        }
        inventory.setChanged();
    }

    // ---- placing

    /**
     * Places the board, or refuses and changes nothing (see {@link #check}).
     *
     * @param player the player placing it (null for a free, unchecked placement)
     * @param origin the block at the front left of the board, as the operator sees it
     * @param facing the way the panels face, toward the operator
     */
    public static Outcome place(ServerLevel level, Player player, BoardTemplate template, BlockPos origin, Direction facing) {
        BoardPlan plan = new BoardPlan(template, origin, facing);
        Outcome checked = check(level, player, plan);
        if (!checked.ok()) {
            return checked;
        }
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        for (BoardPlan.Block block : plan.blocks()) {
            before.put(block.pos(), level.getBlockState(block.pos()));
        }
        dev.bwr.mod.advancement.PlantMilestones.beginPanelEdit();
        try {
            for (BoardPlan.Block block : plan.blocks()) {
                level.setBlock(block.pos(), block.state(), 3);
            }
            List<PanelBlockEntity> panels = new ArrayList<>();
            for (BlockPos pos : plan.panelPositions()) {
                if (!(level.getBlockEntity(pos) instanceof PanelBlockEntity be)) {
                    return rollback(level, plan, before, "a panel block at " + pos.toShortString() + " has no panel entity");
                }
                if (be.group() == null || be.group().oversize()) {
                    return rollback(level, plan, before, "the board would make a panel group too big next to the panels already there");
                }
                panels.add(be);
            }
            int placed = 0;
            for (Comp comp : template.components()) {
                String failure = component(level, plan, comp);
                if (failure != null) {
                    return rollback(level, plan, before, failure);
                }
                placed++;
            }
            int paths = 0;
            for (BoardTemplate.Mimic path : template.mimic()) {
                Surface surface = template.surface(path.surface());
                MimicStyle style = new MimicStyle();
                if (!path.style().isEmpty()) {
                    CompoundTag merged = ConfigSchema.merge(MimicStyle.SCHEMA, style.toTag(), path.style());
                    style.load(merged);
                }
                MimicOps.Placed result = MimicOps.place(level, null, plan.corner(surface), path.points().clone(), style);
                if (result.result() != MimicOps.Result.OK) {
                    return rollback(level, plan, before, "a mimic path on " + path.surface() + " was refused: " + result.result());
                }
                paths++;
            }
            for (PanelBlockEntity be : panels) {
                be.syncFull();
            }
            if (!isFree(player)) {
                consume(player, materials(plan));
            }
            return new Outcome(Status.OK, List.of(), Map.of(), "", plan.blocks().size(), placed, paths);
        } finally {
            dev.bwr.mod.advancement.PlantMilestones.endPanelEdit();
        }
    }

    /** Places one component with its label, caption and settings; null on success, else why not. */
    private static String component(ServerLevel level, BoardPlan plan, Comp comp) {
        Surface surface = plan.template().surface(comp.surface());
        BoardPlan.Anchor anchor = plan.anchor(surface, comp.x(), comp.y());
        Footprint size = Footprint.of(comp.w(), comp.h());
        PanelComponent component = PanelOps.placeDirectQuiet(level, anchor.pos(), anchor.cx(), anchor.cy(), comp.type(), size,
                comp.label());
        if (component == null) {
            return comp.type().id() + " " + size + " at " + comp.x() + "," + comp.y() + " on " + comp.surface() + ": "
                    + PanelOps.checkDirect(level, anchor.pos(), anchor.cx(), anchor.cy(), comp.type(), size);
        }
        if (!comp.caption().isEmpty()) {
            component.setCaption(comp.caption());
        }
        if (!comp.config().isEmpty()) {
            ComponentType type = comp.type();
            ComponentData data = component.data();
            CompoundTag merged = ConfigSchema.merge(type.configSchema(), data.toTag(), comp.config());
            type.adjustConfig(component, merged);
            Component refusal = type.refuseConfig(level, component, merged);
            if (refusal != null) {
                return type.id() + " at " + comp.x() + "," + comp.y() + " on " + comp.surface() + ": " + refusal.getString();
            }
            data.load(merged);
            component.syncPorts();
            type.onLoaded(component);
            component.touch();
            if (level.getBlockEntity(anchor.pos()) instanceof PanelBlockEntity owner) {
                owner.resample(component, true);
            }
        }
        return null;
    }

    /** Puts the blocks back as they were after something unexpected; the items the components drop are discarded. */
    private static Outcome rollback(ServerLevel level, BoardPlan plan, Map<BlockPos, BlockState> before, String why) {
        LOGGER.error("Board {} could not be placed at {}: {}", plan.template().id(), plan.origin(), why);
        for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
            level.setBlock(entry.getKey(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2 | 16 | 32);
        }
        for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
            if (!entry.getValue().isAir()) {
                level.setBlock(entry.getKey(), entry.getValue(), 3);
            }
        }
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, plan.bounds().inflate(1.0))) {
            if (drop.getItem().getItem() instanceof dev.bwr.mod.panel.component.ComponentItem && drop.tickCount < 2) {
                drop.discard();
            }
        }
        return new Outcome(Status.FAILED, List.of(), Map.of(), why, 0, 0, 0);
    }
}
