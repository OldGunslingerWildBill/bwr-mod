package dev.bwr.mod.reactor;

import dev.bwr.core.thermal.PressureVessel;
import dev.bwr.mod.refuelling.CanalGateBlock;
import dev.bwr.mod.refuelling.TransferCanalBlock;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/**
 * The cavity seal of a formed reactor and the well above it. Surveyed once when the reactor forms and again whenever the
 * structure is revalidated (a block next to the controller changes, or the five second sweep every formed reactor already does);
 * never per tick, never per block, no chunk is loaded to do it, nothing is saved.
 *
 * <h2>Real practice</h2>
 * The gap around the vessel body is the drywell and stays dry. Above the vessel is the reactor well, a concrete shaft up to the
 * refuel floor. A cavity seal (a bellows and a seal plate) bridges the vessel to the well wall; with the head off, water raised
 * out of the vessel over the flange fills the well and the seal holds it above the drywell.
 *
 * <h2>The gap rule (owner's)</h2>
 * Looking outward from the vessel's block footprint at one elevation (one row of blocks), all the way round the ring:
 * <ul>
 *   <li>a full, solid block touching the vessel (gap 0) or one block away (gap 1) closes the ring there;</li>
 *   <li>the ring closes at that row only if every column round the footprint, corners included, finds such a block;</li>
 *   <li>the seal forms at the highest such row, scanning down from the flange (the top of the vessel's body once the head is off,
 *   so the dome rows above it, which go with the head, are not candidates) to the deepest allowed row, {@code top - round(H / 3)}:
 *   the seal sits in the upper third of the vessel, H being the formed vessel's total height in blocks and top its top shell row;</li>
 *   <li>no row closes between those: no seal. A wall farther than one block anywhere round the ring, at every allowed row, means none.</li>
 * </ul>
 * The seal plate itself is part of the vessel's drawing (it bridges the round flange to the square footprint and the walls, the
 * corner gaps included); it is not a block a player places. Canal and gate blocks of the cattle chute count as wall.
 *
 * <h2>The well</h2>
 * From the seal row upward, layer by layer, the cells that water could stand in: the vessel's own footprint (above its body
 * the shell is gone with the head, and the cells over the top are the well's), and every open cell joined to it within
 * {@value #WELL_EXTENT} blocks. A layer that leaks (open to beyond that, or a ceiling over the footprint) ends the well; the well's top is
 * the top of the last closed layer, where the refuel floor is. If the well does not reach the flange it cannot hold water.
 * Water above the flange fills the annulus (between the vessel body and the wall, down to the plate) first, with the level in the
 * vessel standing at the lip, then rises through the well.
 */
public final class CavitySeal {
    /** The most a wall may stand off the vessel footprint and still close the ring: one block of air. */
    public static final int MAX_GAP = 1;
    /** How far from the footprint the well's cells may spread before the layer counts as open. */
    public static final int WELL_EXTENT = 3;
    public static final int MAX_WELL_LAYERS = 48;
    /** Outer radius of the barrel, fraction of the footprint (the Blender barrel's), for the annulus area. */
    static final double BARREL_RADIUS_FRACTION = 0.46;
    /** The plate rests this far above the foot of its row when it is not at the flange, and is this thick. */
    static final double PLATE_TOP_IN_ROW = 0.30;
    static final double PLATE_THICKNESS = 0.15;
    /** Sides of the footprint, in the order of the gap mask bits. */
    public static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    /** Why the ring did not close at the best row. */
    public enum Failure { NONE, WALL_TOO_FAR, VESSEL_TOO_SHORT }

    /**
     * What the clients draw: the seal plate and the shape of the well. Compares by value so an unchanged survey does not
     * rebuild a mesh or dirty a chunk section.
     */
    public record Visual(int sealRow, int gapMask, double plateTop, boolean plateAtFlange, @Nullable WellShape well) {
        public void write(CompoundTag tag) {
            tag.putIntArray("VisualSeal", new int[] {sealRow, gapMask, plateAtFlange ? 1 : 0});
            tag.putDouble("VisualSealTop", plateTop);
            if (well != null) {
                well.write(tag);
            }
        }

        @Nullable
        public static Visual read(CompoundTag tag, BlockPos min, BlockPos max) {
            int[] seal = tag.getIntArray("VisualSeal");
            if (seal.length != 3 || seal[0] <= min.getY() || seal[0] > max.getY() || seal[1] < 0 || seal[1] > 15) {
                return null;
            }
            double top = tag.getDouble("VisualSealTop");
            if (!Double.isFinite(top)) {
                return null;
            }
            return new Visual(seal[0], seal[1], top, seal[2] == 1, WellShape.read(tag, min, max));
        }
    }

    /**
     * Which cells of each layer of the well hold water. Layers repeat (a shaft is the same for many rows), so each distinct shape
     * is stored once and every layer names its shape. Cells are a bit grid over the footprint plus {@value #WELL_EXTENT} on every side.
     */
    public static final class WellShape {
        public final int firstRow;
        public final int minX;
        public final int minZ;
        public final int sizeX;
        public final int sizeZ;
        private final List<BitSet> shapes;
        private final int[] layerShape;

        WellShape(int firstRow, int minX, int minZ, int sizeX, int sizeZ, List<BitSet> shapes, int[] layerShape) {
            this.firstRow = firstRow;
            this.minX = minX;
            this.minZ = minZ;
            this.sizeX = sizeX;
            this.sizeZ = sizeZ;
            this.shapes = List.copyOf(shapes);
            this.layerShape = layerShape.clone();
        }

        public int layers() {
            return layerShape.length;
        }

        public int topRow() {
            return firstRow + layerShape.length - 1;
        }

        /** Whether the cell at world {@code x,z} holds water in the layer at world row {@code row}. */
        public boolean cell(int row, int x, int z) {
            int layer = row - firstRow;
            int gx = x - minX;
            int gz = z - minZ;
            if (layer < 0 || layer >= layerShape.length || gx < 0 || gz < 0 || gx >= sizeX || gz >= sizeZ) {
                return false;
            }
            return shapes.get(layerShape[layer]).get(gz * sizeX + gx);
        }

        void write(CompoundTag tag) {
            tag.putIntArray("VisualWell", new int[] {firstRow, minX, minZ, sizeX, sizeZ});
            tag.putIntArray("VisualWellLayers", layerShape);
            ListTag list = new ListTag();
            for (BitSet shape : shapes) {
                list.add(new LongArrayTag(shape.toLongArray()));
            }
            tag.put("VisualWellShapes", list);
        }

        @Nullable
        static WellShape read(CompoundTag tag, BlockPos min, BlockPos max) {
            int[] head = tag.getIntArray("VisualWell");
            int[] layers = tag.getIntArray("VisualWellLayers");
            ListTag list = tag.getList("VisualWellShapes", Tag.TAG_LONG_ARRAY);
            if (head.length != 5 || layers.length == 0 || layers.length > MAX_WELL_LAYERS || list.isEmpty() || list.size() > MAX_WELL_LAYERS
                    || head[3] <= 0 || head[4] <= 0 || head[3] > 64 || head[4] > 64 || head[0] <= min.getY()) {
                return null;
            }
            List<BitSet> shapes = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                shapes.add(BitSet.valueOf(((LongArrayTag) list.get(i)).getAsLongArray()));
            }
            for (int layer : layers) {
                if (layer < 0 || layer >= shapes.size()) {
                    return null;
                }
            }
            return new WellShape(head[0], head[1], head[2], head[3], head[4], shapes, layers);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof WellShape w && w.firstRow == firstRow && w.minX == minX && w.minZ == minZ && w.sizeX == sizeX
                    && w.sizeZ == sizeZ && w.shapes.equals(shapes) && java.util.Arrays.equals(w.layerShape, layerShape);
        }

        @Override
        public int hashCode() {
            return Objects.hash(firstRow, minX, minZ, sizeX, sizeZ, shapes, java.util.Arrays.hashCode(layerShape));
        }
    }

    /**
     * The profile of the space above the flange, in the vessel's own units (metres above the bottom of the vessel).
     * {@code annulusFrom/annulusRise/annulusArea} are the layers outside the vessel body from the plate up to the lip, and
     * {@code wellRise/wellArea} the layers above the lip.
     */
    public record Profile(double plateTop, double lip, double[] annulusRise, double[] annulusArea, double[] wellRise,
                          double[] wellArea) {
        public double annulusM3() {
            double v = 0;
            for (int i = 0; i < annulusRise.length; i++) {
                v += annulusRise[i] * annulusArea[i];
            }
            return v;
        }

        public double wellTop() {
            double h = lip;
            for (double r : wellRise) {
                h += r;
            }
            return h;
        }

        public PressureVessel.Well toWell() {
            return new PressureVessel.Well(annulusM3(), wellRise, wellArea);
        }

        /**
         * Height of the water surface above the bottom of the vessel, metres, for {@code waterM3} of liquid above the
         * flange volume (the annulus first). The lip while there is none.
         */
        public double surface(double waterM3, double riseAboveLipM) {
            if (!(waterM3 > 0)) {
                return lip;
            }
            double annulus = annulusM3();
            if (waterM3 <= annulus) {
                double left = waterM3;
                double h = plateTop;
                for (int i = 0; i < annulusRise.length; i++) {
                    double layer = annulusRise[i] * annulusArea[i];
                    if (left <= layer || !(annulusArea[i] > 0)) {
                        return h + (annulusArea[i] > 0 ? left / annulusArea[i] : annulusRise[i]);
                    }
                    left -= layer;
                    h += annulusRise[i];
                }
                return lip;
            }
            return lip + Math.max(0.0, riseAboveLipM);
        }
    }

    /** The outcome of one survey. */
    public record Result(boolean present, int height, int bottomY, int lowestRow, int flangeRow, int sealRow, int gapMask,
                         Failure failure, int bestRow, @Nullable Direction badSide, @Nullable BlockPos badPos, int badGap, int badColumns,
                         boolean wellUsable, int wellTopRow, @Nullable Profile profile, @Nullable Visual visual) {

        /** What the screen and the computers say: present, or absent and where the wall is too far. */
        public Component describe() {
            if (present) {
                int gaps = Integer.bitCount(gapMask);
                Component base = BwrText.tr("status.bwr.cavity.present",
                        "Present at y %s (%s blocks below the top of the vessel; at most %s allowed, a third of H = %s), %s", sealRow, topY() - sealRow,
                        maxDepth(height), height,
                        gaps == 0 ? BwrText.tr("status.bwr.cavity.touching", "walls touching all round")
                                : BwrText.tr("status.bwr.cavity.gaps", "one block of gap on %s side(s)", gaps));
                if (!wellUsable) {
                    return Component.empty().append(base).append(" ").append(BwrText.tr("status.bwr.cavity.well_open",
                            "The wall opens below the flange, so the well cannot hold water."));
                }
                return base;
            }
            if (failure == Failure.VESSEL_TOO_SHORT) {
                return BwrText.tr("status.bwr.cavity.too_short", "Absent: no row between y %s and y %s to seal at (vessel height %s).",
                        lowestRow, flangeRow, height);
            }
            String far = badGap > 8 ? "more than 8" : String.valueOf(badGap);
            return BwrText.tr("status.bwr.cavity.absent",
                    "Absent: no row from y %s down to y %s (deepest allowed: %s blocks below the top, a third of H = %s) has a wall within one block all round. Closest row y %s: the %s side is %s blocks from the vessel at x %s, z %s (%s columns open).",
                    flangeRow, lowestRow, topY() - lowestRow, height, bestRow, badSide == null ? "?" : badSide.getName(), far,
                    badPos == null ? 0 : badPos.getX(), badPos == null ? 0 : badPos.getZ(), badColumns);
        }

        public String english() {
            return BwrText.english(describe());
        }

        /** World y of the top shell row of the vessel the survey was made for. */
        public int topY() {
            return bottomY + height - 1;
        }
    }

    private CavitySeal() {
    }

    /** How far below the top of the vessel a seal may sit: a third of its height, rounded to the nearest block. */
    public static int maxDepth(int height) {
        return (int) Math.round(height / 3.0);
    }

    /** The deepest row a seal may form at: the top shell row less {@link #maxDepth}. */
    public static int lowestRow(int topY, int height) {
        return topY - maxDepth(height);
    }

    /** The row of blocks that holds the water lip of the open vessel (its flange). */
    public static int flangeRow(VesselAppearance.Envelope e) {
        return e.min().getY() + (int) Math.floor(VesselWaterGeometry.rim(e));
    }

    private record Row(int open, int gapMask, @Nullable Direction badSide, @Nullable BlockPos bad, int badGap) {
    }

    /** Block reads one {@link Survey#step} may begin (a little over a millisecond of server time at most; one row or one cell may run past it). */
    public static final int READS_PER_TICK = 4000;

    /**
     * Surveys the seal and, if there is one, the well, all at once. Null when a block that matters is in a chunk that is not loaded.
     * The reactor does not use this: it steps a {@link Survey} a few thousand block reads a tick so no tick pays for the whole of it.
     */
    @Nullable
    public static Result survey(Level level, VesselAppearance.Envelope e) {
        Survey survey = new Survey(level, e);
        survey.step(Integer.MAX_VALUE);
        return survey.result();
    }

    /**
     * One survey, resumable: the ring scan row by row, then the well layer by layer and cell by cell. {@link #step} does up to a
     * budget of block reads and says whether it has finished, so a large vessel's survey (up to about 190 000 reads) is spread over
     * the ticks after the structure revalidates, like the containment survey. The answer stays null (nothing to apply) if a block it
     * needed is in a chunk that is not loaded; no chunk is ever loaded for it.
     */
    public static final class Survey {
        private enum Phase { RING, WELL, DONE }

        private final Level level;
        private final VesselAppearance.Envelope e;
        private final int bottom;
        private final int height;
        private final int lowest;
        private final int flange;
        private final int x0;
        private final int x1;
        private final int z0;
        private final int z1;
        private int reads;
        private int stepStart;
        private int maxStepReads;
        private boolean unloaded;
        private Phase phase = Phase.RING;
        private int ringRow;
        private int sealRow = Integer.MIN_VALUE;
        private int sealMask;
        private int bestRow;
        private Row best;
        private Well well;
        private int[] queue;
        private BitSet cells;
        private int layerY;
        private int seed;
        private int head;
        private int tail;
        private boolean seeded;
        private boolean escaped;
        private Result result;

        public Survey(Level level, VesselAppearance.Envelope e) {
            this.level = level;
            this.e = e;
            this.bottom = e.min().getY();
            this.height = e.height();
            this.lowest = lowestRow(e.max().getY(), height);
            this.flange = flangeRow(e);
            this.x0 = e.min().getX();
            this.x1 = e.max().getX();
            this.z0 = e.min().getZ();
            this.z1 = e.max().getZ();
            this.ringRow = flange;
            this.bestRow = flange;
            if (lowest > flange) {
                result = new Result(false, height, bottom, lowest, flange, 0, 0, Failure.VESSEL_TOO_SHORT, 0, null, null, 0, 0, false, 0, null, null);
                phase = Phase.DONE;
            }
        }

        public boolean done() {
            return phase == Phase.DONE;
        }

        /** The answer once {@link #done}; null for none (a block was in an unloaded chunk). */
        @Nullable
        public Result result() {
            return result;
        }

        public int totalReads() {
            return reads;
        }

        /** The most block reads any one step made so far: its budget, and at most one row of the ring or one cell of the well more. */
        public int maxStepReads() {
            return maxStepReads;
        }

        /** Does up to {@code budget} block reads (more by at most one row or cell); true when the survey is finished. */
        public boolean step(int budget) {
            long limit = (long) reads + budget;
            stepStart = reads;
            while (phase != Phase.DONE && reads < limit) {
                if (phase == Phase.RING) {
                    ringStep();
                } else {
                    wellStep(limit);
                }
                if (unloaded) {
                    result = null;
                    phase = Phase.DONE;
                }
            }
            maxStepReads = Math.max(maxStepReads, reads - stepStart);
            return phase == Phase.DONE;
        }

        /** True when a block stops water and bounds the ring: a full block, or a part of the cattle chute. */
        private boolean wall(BlockPos p) {
            reads++;
            if (!level.isLoaded(p)) {
                unloaded = true;
                return false;
            }
            var s = level.getBlockState(p);
            return s.isCollisionShapeFullBlock(level, p) || s.getBlock() instanceof TransferCanalBlock || s.getBlock() instanceof CanalGateBlock;
        }

        private void ringStep() {
            Row r = ring(ringRow);
            if (unloaded) {
                return;
            }
            if (r.open() == 0) {
                sealRow = ringRow;
                sealMask = r.gapMask();
                startWell();
                return;
            }
            if (best == null || r.open() < best.open()) {
                best = r;
                bestRow = ringRow;
            }
            if (--ringRow < lowest) {
                result = new Result(false, height, bottom, lowest, flange, 0, 0, Failure.WALL_TOO_FAR, bestRow, best.badSide(), best.bad(),
                        best.badGap(), best.open(), false, 0, null, null);
                phase = Phase.DONE;
            }
        }

        /** One ring at world row {@code y}: how many columns find no wall within one block, and which sides carry a gap. */
        private Row ring(int y) {
            int open = 0;
            int mask = 0;
            Direction badSide = null;
            BlockPos bad = null;
            int badGap = 0;
            for (int side = 0; side < 4; side++) {
                Direction d = SIDES[side];
                boolean alongX = d.getAxis() == Direction.Axis.Z;
                int lo = (alongX ? x0 : z0) - 1;
                int hi = (alongX ? x1 : z1) + 1;
                for (int c = lo; c <= hi; c++) {
                    BlockPos edge = alongX ? new BlockPos(c, y, d == Direction.NORTH ? z0 : z1) : new BlockPos(d == Direction.WEST ? x0 : x1, y, c);
                    if (wall(edge.relative(d, 1))) {
                        continue;
                    }
                    if (wall(edge.relative(d, 2))) {
                        mask |= 1 << side;
                        continue;
                    }
                    open++;
                    if (badSide == null) {
                        badSide = d;
                        bad = edge.relative(d, 1).immutable();
                        int away = 2;
                        while (away < 12 && !wall(edge.relative(d, 1 + away))) {
                            away++;
                        }
                        badGap = away < 12 ? away : 99;
                    }
                }
            }
            return new Row(open, mask, badSide, bad, badGap);
        }

        private void startWell() {
            well = new Well();
            well.gridMinX = x0 - WELL_EXTENT;
            well.gridMinZ = z0 - WELL_EXTENT;
            well.sizeX = x1 - x0 + 1 + 2 * WELL_EXTENT;
            well.sizeZ = z1 - z0 + 1 + 2 * WELL_EXTENT;
            well.lastRow = sealRow - 1;
            queue = new int[well.sizeX * well.sizeZ];
            layerY = sealRow;
            newLayer();
            phase = Phase.WELL;
        }

        private void newLayer() {
            cells = new BitSet(well.sizeX * well.sizeZ);
            seed = 0;
            head = 0;
            tail = 0;
            seeded = false;
            escaped = false;
        }

        private void wellStep(long limit) {
            if (!seeded) {
                int depth = z1 - z0 + 1;
                int total = (x1 - x0 + 1) * depth;
                while (seed < total && reads < limit && !unloaded) {
                    int x = x0 + seed / depth;
                    int z = z0 + seed % depth;
                    seed++;
                    // Inside the vessel's own height the shell is the vessel; above it a block over the footprint is a ceiling.
                    boolean open = layerY <= e.max().getY() || !wall(new BlockPos(x, layerY, z));
                    if (open) {
                        int cell = (z - well.gridMinZ) * well.sizeX + (x - well.gridMinX);
                        cells.set(cell);
                        queue[tail++] = cell;
                    }
                }
                if (seed < total) {
                    return;
                }
                seeded = true;
                if (tail == 0) {
                    finishWell();
                    return;
                }
            }
            while (head < tail && !escaped && reads < limit && !unloaded) {
                int cell = queue[head++];
                int gx = cell % well.sizeX;
                int gz = cell / well.sizeX;
                for (int k = 0; k < 4 && !escaped; k++) {
                    int nx = gx + (k == 0 ? 1 : k == 1 ? -1 : 0);
                    int nz = gz + (k == 2 ? 1 : k == 3 ? -1 : 0);
                    if (wall(new BlockPos(well.gridMinX + nx, layerY, well.gridMinZ + nz))) {
                        continue;
                    }
                    if (nx < 0 || nz < 0 || nx >= well.sizeX || nz >= well.sizeZ) {
                        escaped = true;
                        continue;
                    }
                    int next = nz * well.sizeX + nx;
                    if (!cells.get(next)) {
                        cells.set(next);
                        queue[tail++] = next;
                    }
                }
            }
            if (unloaded || (head < tail && !escaped)) {
                return;
            }
            if (escaped) {
                finishWell();
                return;
            }
            well.layers.add(cells);
            well.counts.add(cells.cardinality());
            well.lastRow = layerY;
            if (++layerY >= sealRow + MAX_WELL_LAYERS) {
                finishWell();
                return;
            }
            newLayer();
        }

        private void finishWell() {
            Well w = well.layers.isEmpty() ? null : well;
            boolean usable = w != null && w.lastRow >= flange;
            Profile profile = usable ? profile(e, sealRow, flange, w) : null;
            double plateTop = profile != null ? profile.plateTop() : plateTop(e, sealRow, flange);
            Visual visual = new Visual(sealRow, sealMask, plateTop, sealRow == flange, usable ? w.shape(e, sealRow) : null);
            result = new Result(true, height, bottom, lowest, flange, sealRow, sealMask, Failure.NONE, sealRow, null, null, 0, 0, usable,
                    usable ? w.lastRow : (w == null ? sealRow : w.lastRow), profile, visual);
            phase = Phase.DONE;
        }
    }

    private static double plateTop(VesselAppearance.Envelope e, int sealRow, int flange) {
        return sealRow == flange ? VesselWaterGeometry.rim(e) - 0.12 : (sealRow - e.min().getY()) + PLATE_TOP_IN_ROW;
    }

    /** The closed layers of the well, from the seal row up. */
    private static final class Well {
        final List<BitSet> layers = new ArrayList<>();
        final List<Integer> counts = new ArrayList<>();
        int lastRow;
        int gridMinX;
        int gridMinZ;
        int sizeX;
        int sizeZ;

        WellShape shape(VesselAppearance.Envelope e, int sealRow) {
            List<BitSet> distinct = new ArrayList<>();
            int[] index = new int[layers.size()];
            for (int i = 0; i < layers.size(); i++) {
                int at = distinct.indexOf(layers.get(i));
                if (at < 0) {
                    distinct.add(layers.get(i));
                    at = distinct.size() - 1;
                }
                index[i] = at;
            }
            return new WellShape(sealRow, gridMinX, gridMinZ, sizeX, sizeZ, distinct, index);
        }
    }

    private static Profile profile(VesselAppearance.Envelope e, int sealRow, int flange, Well well) {
        int bottom = e.min().getY();
        double lip = VesselWaterGeometry.rim(e);
        double plateTop = plateTop(e, sealRow, flange);
        double barrel = Math.PI * BARREL_RADIUS_FRACTION * e.width() * BARREL_RADIUS_FRACTION * e.depth();
        List<double[]> annulus = new ArrayList<>();
        for (int row = sealRow; row <= flange; row++) {
            double lo = Math.max(plateTop, row - bottom);
            double hi = row == flange ? lip : row + 1 - bottom;
            double rise = hi - lo;
            if (rise > 1.0e-6) {
                annulus.add(new double[] {rise, Math.max(0.0, well.counts.get(row - sealRow) - barrel)});
            }
        }
        List<double[]> rises = new ArrayList<>();
        for (int row = flange; row <= well.lastRow; row++) {
            double lo = row == flange ? lip : row - bottom;
            double rise = row + 1 - bottom - lo;
            if (rise > 1.0e-6) {
                rises.add(new double[] {rise, well.counts.get(row - sealRow)});
            }
        }
        return new Profile(plateTop, lip, column(annulus, 0), column(annulus, 1), column(rises, 0), column(rises, 1));
    }

    private static double[] column(List<double[]> rows, int i) {
        double[] out = new double[rows.size()];
        for (int k = 0; k < out.length; k++) {
            out[k] = rows.get(k)[i];
        }
        return out;
    }
}
