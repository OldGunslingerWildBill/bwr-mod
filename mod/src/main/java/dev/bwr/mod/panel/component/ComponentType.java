package dev.bwr.mod.panel.component;

import dev.bwr.mod.panel.PanelBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * A kind of panel component: the single object that defines everything common
 * (non-client) about, say, a lamp. There is exactly one instance per kind,
 * registered in {@link ComponentTypes}; instances of the kind on a panel are
 * {@link PanelComponent}s carrying a {@link ComponentData} made by
 * {@link #createData()}.
 *
 * <h2>How to add a component type</h2>
 * <ol>
 *   <li>Write a {@link ComponentData} subclass: config fields + latched state,
 *       {@code save}/{@code load} (see {@code LampData}).</li>
 *   <li>Subclass this: id, {@link #footprints()}, {@link #createData()},
 *       {@link #configSchema()}, port lists, and the interaction hooks you
 *       need. Input components override {@link #operate} and/or {@link #use}
 *       and call {@code ctx.setOutput(i, v)}; output components read
 *       {@code component.input(i)} when drawing.</li>
 *   <li>Register it in {@link ComponentTypes} (one line).</li>
 *   <li>Register its item in {@code BwrItems} (one line, a
 *       {@link ComponentItem} with the same id), then add the item model, lang
 *       keys {@code item.bwr.<id>} / {@code component.bwr.<id>}, and a recipe.
 *       {@code tools-audit-assets.py} and the {@code ComponentRegistryTests}
 *       GameTest fail if any of these is missing.</li>
 *   <li>Client: write a {@code ComponentModel} and register it in
 *       {@code ComponentModels} under the same id. Until then the type draws a
 *       generic grey slab. A baked Blender model replaces the procedural body
 *       by registering a different {@code ComponentModel} for the id; nothing
 *       here changes.</li>
 *   <li>Add the type to the save/reload GameTest.</li>
 * </ol>
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>Types are stateless singletons. All per-instance state is in
 *       {@link ComponentData} (saved) or {@link PanelComponent} live values.</li>
 *   <li>Never act on the plant automatically. A type may only change outputs in
 *       response to a player ({@link #operate}, {@link #use}); sinks are wired
 *       later by the binding layer.</li>
 *   <li>Everything here runs on the server unless marked; the client only
 *       draws. Do not store client-only objects in {@link ComponentData}.</li>
 * </ul>
 */
public abstract class ComponentType {
    private final ResourceLocation id;

    protected ComponentType(ResourceLocation id) {
        this.id = id;
    }

    public final ResourceLocation id() {
        return id;
    }

    /** {@code component.bwr.<path>}: display name of the type. */
    public String translationKey() {
        return "component." + id.getNamespace() + "." + id.getPath();
    }

    /**
     * Sizes the item can be placed at, in cells. Index 0 is the fallback default;
     * a new stack starts at the real size if the type has one ({@link #realSizes}), else at its baked variant's
     * preferred index. The
     * item remembers an index; right-clicking air with it cycles. A footprint
     * may never exceed {@link Footprint#MAX_CELLS} either way.
     *
     * <p>Sizes are in cells (1/16 block), chosen so a component reads from a few
     * blocks away: about 6 x 6 for a pushbutton, 12 x 12 for a dial. A list may
     * change between versions: a saved component keeps the width and height it
     * was placed with even when that footprint is no longer listed, and its item
     * then drops at the default size.
     */
    public abstract List<Footprint> footprints();

    /**
     * How many of the LAST footprints in {@link #footprints()} are real sizes (what the part measures on a real
     * board, rounded to whole cells; see CONTROL-PANELS.md). The first of them is where a new, unconfigured item
     * starts; the rest are the other real sizes. 0 for a type that has no real size (recorders, displays, mimic
     * pieces), which then starts at index 0 or its model's preferred size. They are appended so that the indices
     * stored in item stacks never move.
     */
    public int realSizes() {
        return 0;
    }

    /** The index a new item of this type starts at when it has real sizes, else -1. */
    public final int realDefaultIndex() {
        int real = realSizes();
        return real <= 0 ? -1 : footprints().size() - real;
    }

    /**
     * The footprint indices from the smallest size to the biggest (by area, then width, then list position): the
     * order the item cycles through and the size picker lists. The list itself keeps its order, because saved item
     * stacks hold indices into it.
     */
    public final int[] sizeOrder() {
        List<Footprint> list = footprints();
        return java.util.stream.IntStream.range(0, list.size()).boxed()
                .sorted(java.util.Comparator.<Integer>comparingInt(i -> list.get(i).w() * list.get(i).h())
                        .thenComparingInt(i -> list.get(i).w()).thenComparingInt(i -> i))
                .mapToInt(Integer::intValue).toArray();
    }

    /**
     * How many blocks this type may span either way. Most types stay within 4 (64 cells); the core displays need up
     * to 8 for a full size core. The footprints it lists may not exceed it.
     */
    public int maxSpanBlocks() {
        return dev.bwr.mod.panel.PanelGeometry.MAX_SPAN_BLOCKS;
    }

    /**
     * A plant binding of input {@code inputIndex} to {@code device} is about to be made (server, after the usual
     * checks): a reason to refuse it, shown to the player, or null to allow it.
     */
    public Component refuseBinding(ServerLevel level, PanelComponent component, int inputIndex, BlockPos device) {
        return null;
    }

    /** A config edit would produce {@code proposed} (the edit merged into the current data): a reason to refuse it, or null. */
    public Component refuseConfig(ServerLevel level, PanelComponent component, CompoundTag proposed) {
        return null;
    }

    /** A fresh {@link ComponentData} holding the defaults. */
    public abstract ComponentData createData();

    /** A fresh {@link ComponentData} for a component about to be placed with {@code footprint}: defaults that depend on its size. */
    public ComponentData createData(Footprint footprint) {
        return createData();
    }

    /**
     * A config edit is about to be checked and applied: {@code proposed} is the edit merged into the current data and
     * may be adjusted in place (a setting that follows another one the player changed, such as a default that depends
     * on the look). Called before {@link #refuseConfig}.
     */
    public void adjustConfig(PanelComponent component, CompoundTag proposed) {
    }

    /** Editable settings; the wrench screen is generated from this and the server validates against it. */
    public List<ConfigField> configSchema() {
        return List.of();
    }

    /**
     * Inputs (values this component displays), by index. May depend on the
     * config; the framework calls {@link PanelComponent#syncPorts()} after every
     * config change.
     */
    public List<PortSpec> inputPorts(ComponentData data) {
        return List.of();
    }

    /** Outputs (values this component produces), by index. */
    public List<PortSpec> outputPorts(ComponentData data) {
        return List.of();
    }

    /** Prefix of auto-assigned labels, producing PB-1, PB-2 ... unique within the group. */
    public String labelPrefix() {
        return id.getPath().toUpperCase(java.util.Locale.ROOT).substring(0, Math.min(3, id.getPath().length()));
    }

    /**
     * A player is operating the component with a press / release / turn / set.
     * Server only, already validated (distance, permission, finite value); clamp
     * {@code value} to your own range. Return whether anything was handled.
     */
    public boolean operate(OperateContext context, OperateAction action, double value) {
        return false;
    }

    /**
     * Whether the panel sampler must keep calling {@link ComponentData#sampleContent} for this component even though
     * none of its inputs is a live source (a recorder whose channels are wired to constants or to CC still has a
     * clock to keep). Defaults to no.
     */
    public boolean keepsSampling(PanelComponent component) {
        return false;
    }

    /** Whether a bare-handed click does anything (makes the client swing the arm). Override with {@link #use}. */
    public boolean isInteractive() {
        return false;
    }

    // ---- operating hints for input components (read by the client input hook; defaults suit indicators)

    /**
     * Client: the value sent with a PRESS when the player clicks at {@code (cellX, cellY)}, measured in cells from
     * the component's top-left corner. Types use it to tell which half or which digit was clicked.
     */
    public double clickValue(PanelComponent component, double cellX, double cellY) {
        return 0.0;
    }

    /** Whether scrolling over the component sends TURN (the hotbar does not scroll while pointing at one). */
    public boolean scrollable() {
        return false;
    }

    /** Client: the value sent with a TURN for {@code notches} of scroll (positive is up) at the given cell position. */
    public double scrollValue(PanelComponent component, double cellX, double cellY, int notches) {
        return notches;
    }

    /**
     * Whether holding the use key keeps the input operated: the client repeats PRESS while the key is down and sends
     * RELEASE when it is let go (a momentary pushbutton, a spring-return switch).
     */
    public boolean holdable() {
        return false;
    }

    /** A bare-handed right-click landed on the component. Server only. */
    public InteractionResult use(UseContext context) {
        return InteractionResult.PASS;
    }

    /**
     * An input value or output value changed (both sides). Recompute derived
     * data here, never schedule ticks. Keep it cheap: it runs for every delta.
     */
    public void valuesChanged(PanelComponent component) {
    }

    /**
     * The component was just loaded from NBT or received over the network
     * (both sides). Restore {@code storeOutput} values from latched data here.
     */
    public void onLoaded(PanelComponent component) {
    }

    /** The registered item that places this type. */
    public final Item item() {
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == null ? Items.AIR : item;
    }

    /**
     * The item a removed {@code component} drops: the type's own item, unless a type has more than one (the paperless
     * recorder drops the DX1000 or the DX2000 item, by its make).
     */
    public Item itemFor(PanelComponent component) {
        return item();
    }

    /** Server-side handle for {@link #operate}. */
    public record OperateContext(ServerLevel level, ServerPlayer player, PanelBlockEntity owner,
                                 PanelComponent component) {
        /** Sets an output, syncs it and notifies {@link dev.bwr.mod.panel.PanelBus} listeners. */
        public void setOutput(int index, double value) {
            owner.setOutput(component, index, value);
        }

        /** Re-sends the whole owner state to watchers; use after changing latched {@link ComponentData}. */
        public void syncFull() {
            owner.syncFull();
        }
    }

    /** Server-side handle for {@link #use}: where on the component the click landed. */
    public record UseContext(ServerLevel level, ServerPlayer player, PanelBlockEntity owner,
                             PanelComponent component, double cellX, double cellY) {
        public void setOutput(int index, double value) {
            owner.setOutput(component, index, value);
        }

        public void syncFull() {
            owner.syncFull();
        }
    }
}
