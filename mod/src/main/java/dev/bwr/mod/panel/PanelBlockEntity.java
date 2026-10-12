package dev.bwr.mod.panel;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.fuel.BwrDataComponents;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.InputSource;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The state of one panel block: the components whose anchor cell is in this
 * block ("owned"), plus {@link Claim}s for cells of this block that a
 * straddling component owned by another block covers. No ticker: everything
 * happens in response to a player, a load, or a pushed signal value.
 *
 * <h3>Saves</h3>
 * One versioned compound under {@code bwr_panel} ({@link #SAVE_VERSION}):
 * {@code NextId}, {@code Components} (see {@link PanelComponent#save}) and
 * {@code Claims} (absolute owner position, component id, covered rectangle).
 * Claims on the owner's own block are derived from the components and not saved.
 * Live values are not saved; the network form ({@link #savePanel(boolean)} with
 * {@code true}) adds them.
 *
 * <h3>Sync</h3>
 * Server to client: a full-state payload on chunk watch and after any
 * structural or config change ({@link #syncFull()}), and batched value deltas
 * ({@link PanelSync}). The vanilla block-entity update tag is deliberately
 * empty.
 */
public final class PanelBlockEntity extends BlockEntity {
    public static final int SAVE_VERSION = 1;
    private static final String KEY = "bwr_panel";
    private static final Logger LOGGER = LogUtils.getLogger();

    /** A reservation of cells {@code [x0,x1) x [y0,y1)} of one block for a component owned at {@code owner}. */
    public record Claim(BlockPos owner, int id, int x0, int y0, int x1, int y1) {
    }

    /** One changed live value, queued for the next batched packet. */
    public record ValueDelta(int id, boolean output, int index, double value) {
    }

    private final List<PanelComponent> owned = new ArrayList<>();
    private final List<PanelComponent> ownedView = Collections.unmodifiableList(owned);
    private final List<Claim> foreign = new ArrayList<>();
    private final List<ValueDelta> pending = new ArrayList<>();
    private Claim[] grid;
    private int nextId = 1;
    private PanelGroup group;
    private AABB renderBounds;

    public PanelBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.PANEL.get(), pos, state);
    }

    public PanelType panelType() {
        return ((PanelBlock) getBlockState().getBlock()).panelType();
    }

    public Direction facing() {
        return getBlockState().getValue(PanelBlock.FACING);
    }

    // ---- group

    /** The shared group this block belongs to, recomputed if anything changed. Null before the block is in a level. */
    public PanelGroup group() {
        if (level == null) {
            return null;
        }
        if (group == null || !group.isValid()) {
            group = PanelGroup.compute(level, worldPosition, panelType(), facing(), null);
            for (BlockPos pos : group.members()) {
                if (!pos.equals(worldPosition) && level.isLoaded(pos) && level.getBlockEntity(pos) instanceof PanelBlockEntity other) {
                    other.group = group;
                }
            }
        }
        return group;
    }

    /** Drops this block's group and those of its neighbours so they recompute. */
    void invalidateGroups() {
        dropGroup();
        if (level == null) {
            return;
        }
        BlockState state = getBlockState();
        if (state.getBlock() instanceof PanelBlock) {
            invalidateAround(level, worldPosition, state);
        }
    }

    /**
     * Drops the cached group of every loaded panel block that could be a group neighbour of {@code pos}. Never
     * touches {@code pos} itself: asking a level for a block entity that is still being attached would create a
     * second one.
     */
    static void invalidateAround(Level level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof PanelBlock block)) {
            return;
        }
        Direction facing = state.getValue(PanelBlock.FACING);
        PanelType type = block.panelType();
        BlockPos[] around = {PanelType.nextColumn(pos, facing), PanelType.previousColumn(pos, facing),
                type.nextRow(pos, facing), type.previousRow(pos, facing)};
        for (BlockPos neighbour : around) {
            if (neighbour != null && level.isLoaded(neighbour)
                    && level.getBlockEntity(neighbour) instanceof PanelBlockEntity other) {
                other.dropGroup();
            }
        }
    }

    private void dropGroup() {
        if (group != null) {
            group.invalidate();
            group = null;
        }
    }

    // ---- components and claims

    public List<PanelComponent> owned() {
        return ownedView;
    }

    public int ownedCount() {
        return owned.size();
    }

    public PanelComponent component(int id) {
        for (PanelComponent component : owned) {
            if (component.id() == id) {
                return component;
            }
        }
        return null;
    }

    public void collectLabels(Set<String> labels) {
        for (PanelComponent component : owned) {
            labels.add(component.label());
        }
    }

    private Claim[] grid() {
        if (grid == null) {
            grid = new Claim[PanelGeometry.GRID * PanelGeometry.GRID];
            for (PanelComponent component : owned) {
                fill(grid, new Claim(worldPosition, component.id(), component.x(), component.y(),
                        Math.min(PanelGeometry.GRID, component.x() + component.w()),
                        Math.min(PanelGeometry.GRID, component.y() + component.h())));
            }
            for (Claim claim : foreign) {
                fill(grid, claim);
            }
        }
        return grid;
    }

    private static void fill(Claim[] cells, Claim claim) {
        for (int y = claim.y0(); y < claim.y1(); y++) {
            for (int x = claim.x0(); x < claim.x1(); x++) {
                cells[y * PanelGeometry.GRID + x] = claim;
            }
        }
    }

    /** Who reserves cell (cx, cy) of this block, or null. */
    public Claim claimAt(int cx, int cy) {
        if (cx < 0 || cy < 0 || cx >= PanelGeometry.GRID || cy >= PanelGeometry.GRID) {
            return null;
        }
        return grid()[cy * PanelGeometry.GRID + cx];
    }

    public boolean isFree(int x0, int y0, int x1, int y1) {
        Claim[] cells = grid();
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (cells[y * PanelGeometry.GRID + x] != null) {
                    return false;
                }
            }
        }
        return true;
    }

    /** All claims by other blocks on this one. */
    public List<Claim> foreignClaims() {
        return Collections.unmodifiableList(foreign);
    }

    private void structureChanged() {
        grid = null;
        renderBounds = null;
        setChanged();
    }

    /**
     * Adds a component and reserves its cells. The caller has already verified
     * the placement ({@link PanelGroup#checkPlacement}); {@code rects} are the
     * per-block rectangles it returned.
     */
    PanelComponent addComponent(ComponentType type, int x, int y, Footprint footprint, String label,
                                List<PanelGroup.Rect> rects) {
        PanelComponent component = new PanelComponent(nextId++, type, x, y, footprint, label);
        owned.add(component);
        structureChanged();
        for (PanelGroup.Rect rect : rects) {
            if (rect.pos().equals(worldPosition)) {
                continue;
            }
            if (level.getBlockEntity(rect.pos()) instanceof PanelBlockEntity other) {
                other.foreign.add(new Claim(worldPosition, component.id(), rect.x0(), rect.y0(), rect.x1(), rect.y1()));
                other.structureChanged();
            }
        }
        resample(component, false);
        return component;
    }

    /** Removes a component and releases every cell it reserved, here and in neighbouring blocks. */
    PanelComponent removeComponent(int id) {
        PanelComponent component = component(id);
        if (component == null) {
            return null;
        }
        owned.remove(component);
        structureChanged();
        for (BlockPos pos : coverage(component)) {
            if (!pos.equals(worldPosition) && level.isLoaded(pos) && level.getBlockEntity(pos) instanceof PanelBlockEntity other) {
                other.releaseClaims(worldPosition, id);
            }
        }
        return component;
    }

    /**
     * Every block position a component owned here covers, anchor first. Derived
     * from the footprint and the panel style alone (not from the cached group),
     * so it is still right while a neighbouring block is being removed.
     */
    public List<BlockPos> coverage(PanelComponent component) {
        int spanX = (component.x() + component.w() + PanelGeometry.GRID - 1) / PanelGeometry.GRID;
        int spanY = (component.y() + component.h() + PanelGeometry.GRID - 1) / PanelGeometry.GRID;
        PanelType type = panelType();
        Direction facing = facing();
        List<BlockPos> covered = new ArrayList<>(spanX * spanY);
        BlockPos rowStart = worldPosition;
        for (int j = 0; j < spanY && rowStart != null; j++) {
            BlockPos pos = rowStart;
            for (int i = 0; i < spanX; i++) {
                covered.add(pos);
                pos = PanelType.nextColumn(pos, facing);
            }
            rowStart = type.nextRow(rowStart, facing);
        }
        return covered;
    }

    /** Drops claims held by {@code (owner, id)} on this block. */
    void releaseClaims(BlockPos owner, int id) {
        if (foreign.removeIf(claim -> claim.owner().equals(owner) && claim.id() == id)) {
            structureChanged();
        }
    }

    // ---- server-side item drops

    /** The item that places {@code component} again, set to the same footprint; empty if the type is unknown. */
    public static ItemStack stackFor(PanelComponent component) {
        ComponentType type = component.type();
        if (type == null || type.item() == net.minecraft.world.item.Items.AIR) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(type.item());
        int variant = type.footprints().indexOf(new Footprint(component.w(), component.h()));
        if (variant > 0) {
            stack.set(BwrDataComponents.COMPONENT_VARIANT.get(), variant);
        }
        return stack;
    }

    /** Spawns the component's item just in front of its face. */
    void dropItem(PanelComponent component) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        ItemStack stack = stackFor(component);
        if (stack.isEmpty()) {
            LOGGER.warn("Panel component {} at {} has an unknown type; nothing to drop", component.id(), worldPosition);
            return;
        }
        PanelGeometry.Frame frame = PanelGeometry.frame(panelType(), facing());
        double cu = component.x() + component.w() / 2.0;
        double cv = component.y() + component.h() / 2.0;
        double x = worldPosition.getX() + frame.x(cu, cv, 4.0);
        double y = worldPosition.getY() + frame.y(cu, cv, 4.0);
        double z = worldPosition.getZ() + frame.z(cu, cv, 4.0);
        ItemEntity entity = new ItemEntity(server, x, y, z, stack);
        entity.setDefaultPickUpDelay();
        server.addFreshEntity(entity);
    }

    /**
     * Removes this block's components and every claim on it, dropping the items
     * of everything lost. Called while the block is being removed, before its
     * group is torn down.
     */
    void releaseEverything() {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        for (PanelComponent component : new ArrayList<>(owned)) {
            PanelComponent removed = removeComponent(component.id());
            if (removed != null) {
                dropItem(removed);
                syncCoverage(removed, worldPosition);
            }
        }
        for (Claim claim : new ArrayList<>(foreign)) {
            if (server.isLoaded(claim.owner()) && server.getBlockEntity(claim.owner()) instanceof PanelBlockEntity owner) {
                PanelComponent removed = owner.removeComponent(claim.id());
                if (removed != null) {
                    owner.dropItem(removed);
                    owner.syncCoverage(removed, worldPosition);
                }
            }
        }
        foreign.clear();
        structureChanged();
    }

    // ---- live values

    /**
     * Pushes a value into an input (server). The only supported way for the
     * wiring layer to set inputs: stores it, queues a delta for watching
     * clients and lets the type react.
     */
    public boolean setInput(PanelComponent component, int index, double value) {
        if (!component.storeInput(index, value)) {
            return false;
        }
        if (component.type() != null) {
            component.type().valuesChanged(component);
            if (component.data().consumeLatchedChange()) {
                setChanged(); // a type latched something because of this input (an annunciator going into alarm)
            }
        }
        queue(new ValueDelta(component.id(), false, index, value));
        return true;
    }

    /** Sets an output of an input component (server) and tells {@link PanelBus} listeners. */
    public boolean setOutput(PanelComponent component, int index, double value) {
        if (!component.storeOutput(index, value)) {
            return false;
        }
        if (component.type() != null) {
            component.type().valuesChanged(component);
        }
        queue(new ValueDelta(component.id(), true, index, value));
        if (level instanceof ServerLevel server) {
            PanelBus.fireOutput(server, this, component, index, value);
        }
        return true;
    }

    private void queue(ValueDelta delta) {
        if (level == null || level.isClientSide()) {
            return;
        }
        pending.add(delta);
        PanelSync.markDirty(this);
    }

    List<ValueDelta> drainDeltas() {
        List<ValueDelta> drained = new ArrayList<>(pending);
        pending.clear();
        return drained;
    }

    /** Client: applies a received delta. */
    public void applyDelta(ValueDelta delta) {
        PanelComponent component = component(delta.id());
        if (component == null) {
            return;
        }
        boolean changed = delta.output() ? component.storeOutput(delta.index(), delta.value())
                : component.storeInput(delta.index(), delta.value());
        if (changed && component.type() != null) {
            component.type().valuesChanged(component);
        }
    }

    /**
     * Re-reads every source of {@code component} and stores the results. With
     * {@code notify} the changes are queued as deltas (a config edit); without,
     * not (initial load, where clients get the values in the full state).
     */
    public void resample(PanelComponent component, boolean notify) {
        if (level == null || level.isClientSide()) {
            return;
        }
        for (int i = 0; i < component.inputCount(); i++) {
            InputSource source = component.source(i);
            double value = source.sample(new InputSource.SampleContext(level, this, component, i));
            if (notify) {
                setInput(component, i, value);
            } else {
                component.storeInput(i, value);
            }
        }
    }

    /** Re-sends the state of every block a removed component covered, except {@code skip} (a block going away). */
    void syncCoverage(PanelComponent component, BlockPos skip) {
        for (BlockPos pos : coverage(component)) {
            if (!pos.equals(skip) && level.isLoaded(pos) && level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                be.syncFull();
            }
        }
    }

    /** Re-sends this block's whole state to everyone watching it. */
    public void syncFull() {
        setChanged();
        renderBounds = null;
        if (level instanceof ServerLevel server) {
            PanelSync.sendFull(server, this);
        }
    }

    // ---- persistence

    /** The versioned save form; {@code network} adds live values for the client. */
    public CompoundTag savePanel(boolean network) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", SAVE_VERSION);
        tag.putInt("NextId", nextId);
        ListTag components = new ListTag();
        for (PanelComponent component : owned) {
            components.add(network ? component.saveWithValues() : component.save());
        }
        tag.put("Components", components);
        ListTag claims = new ListTag();
        for (Claim claim : foreign) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("Ox", claim.owner().getX());
            entry.putInt("Oy", claim.owner().getY());
            entry.putInt("Oz", claim.owner().getZ());
            entry.putInt("Id", claim.id());
            entry.putInt("X0", claim.x0());
            entry.putInt("Y0", claim.y0());
            entry.putInt("X1", claim.x1());
            entry.putInt("Y1", claim.y1());
            claims.add(entry);
        }
        tag.put("Claims", claims);
        return tag;
    }

    /** Replaces this block's panel state with {@code tag}. Never throws on bad data. */
    public void loadPanel(CompoundTag tag) {
        owned.clear();
        foreign.clear();
        int version = tag.getInt("Version");
        if (version > SAVE_VERSION) {
            LOGGER.warn("Panel at {} was saved by a newer version ({} > {}); loading what is understood",
                    worldPosition, version, SAVE_VERSION);
        }
        int highest = 0;
        ListTag components = tag.getList("Components", Tag.TAG_COMPOUND);
        for (int i = 0; i < components.size(); i++) {
            PanelComponent component = PanelComponent.load(components.getCompound(i));
            owned.add(component);
            highest = Math.max(highest, component.id());
        }
        ListTag claims = tag.getList("Claims", Tag.TAG_COMPOUND);
        for (int i = 0; i < claims.size(); i++) {
            CompoundTag entry = claims.getCompound(i);
            int x0 = Math.max(0, Math.min(PanelGeometry.GRID, entry.getInt("X0")));
            int y0 = Math.max(0, Math.min(PanelGeometry.GRID, entry.getInt("Y0")));
            int x1 = Math.max(x0, Math.min(PanelGeometry.GRID, entry.getInt("X1")));
            int y1 = Math.max(y0, Math.min(PanelGeometry.GRID, entry.getInt("Y1")));
            foreign.add(new Claim(new BlockPos(entry.getInt("Ox"), entry.getInt("Oy"), entry.getInt("Oz")),
                    entry.getInt("Id"), x0, y0, x1, y1));
        }
        nextId = Math.max(tag.getInt("NextId"), highest + 1);
        grid = null;
        renderBounds = null;
        for (PanelComponent component : owned) {
            if (component.type() != null) {
                component.type().onLoaded(component);
            }
        }
    }

    /** Client: a full-state packet arrived. */
    public void applyNetworkState(CompoundTag tag) {
        loadPanel(tag);
        for (PanelComponent component : owned) {
            component.touch();
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!owned.isEmpty() || !foreign.isEmpty()) {
            tag.put(KEY, savePanel(false));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(KEY, Tag.TAG_COMPOUND)) {
            loadPanel(tag.getCompound(KEY));
        } else {
            loadPanel(new CompoundTag());
        }
    }

    /**
     * Called when the block entity is attached to a level, on both sides, for
     * placement as well as chunk load (onLoad is not reliable for placement), so
     * this is where neighbouring groups learn about a new member.
     */
    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        invalidateGroups();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        invalidateGroups();
        if (level != null && !level.isClientSide()) {
            for (PanelComponent component : owned) {
                resample(component, false);
                if (component.type() != null) {
                    component.type().valuesChanged(component); // derived state (annunciators) sees the sampled inputs
                    if (component.data().consumeLatchedChange()) {
                        setChanged();
                    }
                }
            }
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        invalidateGroups();
    }

    // ---- rendering support (common code, no client classes)

    /** World-space box containing the block and every component it owns, including parts straddling into neighbours. */
    public AABB renderBounds() {
        if (renderBounds == null) {
            AABB bounds = new AABB(worldPosition);
            PanelGeometry.Frame frame = PanelGeometry.frame(panelType(), facing());
            for (PanelComponent component : owned) {
                for (int corner = 0; corner < 8; corner++) {
                    double cu = component.x() + ((corner & 1) == 0 ? 0 : component.w());
                    double cv = component.y() + ((corner & 2) == 0 ? 0 : component.h());
                    double cz = (corner & 4) == 0 ? 0 : 16;
                    bounds = bounds.minmax(new AABB(worldPosition.getX() + frame.x(cu, cv, cz),
                            worldPosition.getY() + frame.y(cu, cv, cz),
                            worldPosition.getZ() + frame.z(cu, cv, cz),
                            worldPosition.getX() + frame.x(cu, cv, cz),
                            worldPosition.getY() + frame.y(cu, cv, cz),
                            worldPosition.getZ() + frame.z(cu, cv, cz)));
                }
            }
            renderBounds = bounds.inflate(0.05);
        }
        return renderBounds;
    }

    /** Debug aid for tests: a copy of the owned component ids. */
    int[] ownedIds() {
        return owned.stream().mapToInt(PanelComponent::id).toArray();
    }

    @Override
    public String toString() {
        return "PanelBlockEntity" + worldPosition + Arrays.toString(ownedIds());
    }

    /** Level accessor kept for package neighbours that cannot see the protected field. */
    Level world() {
        return level;
    }
}
