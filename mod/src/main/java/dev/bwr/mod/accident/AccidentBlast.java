package dev.bwr.mod.accident;

import dev.bwr.core.accident.BlastPhysics;
import dev.bwr.mod.containment.ContainmentControllerBlock;
import dev.bwr.mod.containment.ContainmentStrength;
import dev.bwr.mod.reactor.FormedReactorRegistry;
import dev.bwr.mod.reactor.ReactorControllerBlock;
import dev.bwr.mod.registry.BwrBlocks;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Accident blasts that physically damage the world. Always on: there is no configuration switch and no
 * gamerule gate (the owner's decision), but every block still goes through the standard protection path.
 *
 * <h2>How a blast works</h2>
 * A blast is a {@link Job}: a source, a TNT-equivalent mass from {@link BlastPhysics}, a shape that says where the
 * pressure acts, and a sweep box. A block breaks when the pressure at its centre exceeds its rating
 * ({@link ContainmentStrength#contribution}: the same {@code K * sqrt(blast resistance)} psi a containment wall
 * is rated with, so data pack overrides apply). Nothing is simulated per tick: a job examines each position at most
 * once, in a fixed order, and is finished.
 *
 * <h2>Protection</h2>
 * Per slice of work:
 * <ol>
 *   <li>a real {@link Explosion} object is made for the broken blocks and {@code ExplosionEvent.Start} is posted;
 *       a cancelled Start discards the slice, so a claim mod can stop a blast;</li>
 *   <li>for every block a {@link BlockEvent.BreakEvent} is posted with NeoForge's standard fake player; a
 *       cancelled event spares that block;</li>
 *   <li>{@code ExplosionEvent.Detonate} is posted with the surviving list, which protection mods may prune;</li>
 *   <li>{@link Explosion#finalizeExplosion} removes the blocks with vanilla explosion drop rules (decay by
 *       {@code 1 / radius} when the block-explosion drop-decay rule is on, as for TNT).</li>
 * </ol>
 * Never broken whatever the pressure: air and pure fluid, blocks with {@code destroySpeed < 0} (bedrock, end portal
 * frames, barriers, command blocks), reactor and containment controllers, and the formed reactors' structure (see
 * {@link Job#protect}). Pipe whip also spares the rest of the broken line's pipe blocks.
 *
 * <h2>Chunks</h2>
 * Nothing is ever loaded. A position in an unloaded chunk is <b>deferred</b>: the job keeps the chunk, saves it, and
 * handles it when the chunk loads, at the block states then. A job whose chunks never load stays saved and
 * silent. Work per tick is bounded by {@link #EXAMINE_PER_TICK} positions and {@link #BREAK_PER_TICK} breaks, and
 * the queue costs nothing while it is empty.
 *
 * <h2>Persistence</h2>
 * A job is saved with the reactor while it has work left and not before; a finished job leaves no trace. The
 * trigger (a core event) is delivered once and never saved, so a reload resumes a half-done blast but never starts
 * a second one.
 */
public final class AccidentBlast {
    private AccidentBlast() {
    }

    /** Positions examined per tick across all jobs of one reactor. */
    public static final int EXAMINE_PER_TICK = 16_384;

    /** Blocks removed per tick across all jobs of one reactor. */
    public static final int BREAK_PER_TICK = 1_024;

    /** Jobs one reactor will hold; the oldest stays, newer ones past this are dropped. */
    public static final int MAX_JOBS = 16;

    /** Chunk columns one job will sweep. */
    public static final int MAX_CHUNKS = 144;

    /** Half angle of the head failure cone, degrees. */
    public static final double CONE_HALF_ANGLE_DEGREES = 35.0;

    /** Pedestal blast depth limit, blocks. */
    public static final int MAX_PEDESTAL_DEPTH = 8;

    public enum Kind {
        /** Pressure inside a room, falling with distance from the source and clipped to a cap. */
        INTERIOR,
        /** An explicit list of wall blocks chosen by the containment survey. */
        BREACH,
        /** A steam burst and missile up through an open vessel head. */
        CONE,
        /** Pipe whip and jet impingement around a break. */
        WHIP,
        /** Molten debris dropping into the cavity under a failed lower head. */
        PEDESTAL
    }

    private static final int NO_CHUNK = Integer.MIN_VALUE;

    /** One blast. Plain data so it saves and resumes. */
    public static final class Job {
        public final Kind kind;
        public final double cx, cy, cz;
        public final double tntKg;
        /** Pressure cap (INTERIOR), or the pipe's pressure in psig (WHIP). */
        public final double pressurePsi;
        /** Explosion radius for entity effects and drop decay. */
        public final float radius;
        final int[] box = new int[6];
        /** The source reactor's protected volume (minX, minY, minZ, maxX, maxY, maxZ); min above max means none. */
        final int[] protect = {1, 1, 1, 0, 0, 0};
        /** Kind-specific numbers. CONE: axisX, axisZ, topY, innerRadius, tanTheta. WHIP: radius. PEDESTAL: axisX, axisZ, innerRadius, topY, maxDepth. */
        final double[] shape;
        /** BREACH only: the positions still to do. */
        LongArrayList explicit = new LongArrayList();
        /** Chunk columns still to do, nearest the source first. */
        LongArrayList chunks = new LongArrayList();
        long cursorChunk = Long.MIN_VALUE;
        int cursor;
        /** WHIP: the pipe block that was the break, which is not spared. */
        long centreBlock = Long.MIN_VALUE;

        Job(Kind kind, double cx, double cy, double cz, double tntKg, double pressurePsi, float radius, double[] shape) {
            this.kind = kind;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.tntKg = tntKg;
            this.pressurePsi = pressurePsi;
            this.radius = radius;
            this.shape = shape;
        }

        /** Positions (or chunk columns) not yet done. A finished job reports 0. */
        public int remaining() {
            return kind == Kind.BREACH ? explicit.size() : chunks.size();
        }

        public BlockPos centreBlock() {
            return centreBlock == Long.MIN_VALUE ? null : BlockPos.of(centreBlock);
        }

        /** Chunk columns the job is still waiting to do (region jobs). */
        public List<ChunkPos> pendingChunks() {
            List<ChunkPos> result = new ArrayList<>();
            for (long key : chunks) result.add(new ChunkPos(key));
            return result;
        }

        /** Protect a reactor's formed structure from this blast. */
        public Job protect(BlockPos interiorMin, BlockPos interiorMax) {
            protect[0] = interiorMin.getX() - 1;
            protect[1] = interiorMin.getY() - 2;
            protect[2] = interiorMin.getZ() - 1;
            protect[3] = interiorMax.getX() + 1;
            protect[4] = interiorMax.getY() + 1;
            protect[5] = interiorMax.getZ() + 1;
            return this;
        }

        /** Pressure the blast puts on the centre of block (x, y, z), psi; 0 outside the blast's shape. */
        public double pressureAt(int x, int y, int z) {
            double bx = x + 0.5, by = y + 0.5, bz = z + 0.5;
            double dx = bx - cx, dy = by - cy, dz = bz - cz;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            switch (kind) {
                case INTERIOR:
                    return Math.min(pressurePsi, BlastPhysics.overpressurePsi(tntKg, Math.max(d, 0.5)));
                case CONE: {
                    double h = by - shape[2];
                    if (h < 0.0 || d > BlastPhysics.MAX_REACH_BLOCKS) return 0.0;
                    double rho = Math.hypot(bx - shape[0], bz - shape[1]);
                    if (rho > shape[3] + h * shape[4]) return 0.0;
                    return BlastPhysics.overpressurePsi(tntKg, Math.max(d, 0.5));
                }
                case WHIP:
                    return d <= shape[0] + 1.0e-9 ? BlastPhysics.jetPsi(pressurePsi, d) : 0.0;
                case PEDESTAL: {
                    if (y > shape[3] || y <= shape[3] - shape[4]) return 0.0;
                    double rho = Math.hypot(bx - shape[0], bz - shape[1]);
                    if (rho > shape[2]) return 0.0;
                    return BlastPhysics.overpressurePsi(tntKg, Math.max(d, 0.5));
                }
                default:
                    return 0.0;
            }
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("Kind", kind.name());
            t.putDouble("X", cx);
            t.putDouble("Y", cy);
            t.putDouble("Z", cz);
            t.putDouble("Tnt", tntKg);
            t.putDouble("Psi", pressurePsi);
            t.putFloat("Radius", radius);
            t.putIntArray("Box", box);
            t.putIntArray("Protect", protect);
            t.put("Shape", doubles(shape));
            if (!explicit.isEmpty()) t.putLongArray("Explicit", explicit.toLongArray());
            if (!chunks.isEmpty()) t.putLongArray("Chunks", chunks.toLongArray());
            if (cursor != 0) {
                t.putLong("CursorChunk", cursorChunk);
                t.putInt("Cursor", cursor);
            }
            if (centreBlock != Long.MIN_VALUE) t.putLong("Centre", centreBlock);
            return t;
        }

        static Job load(CompoundTag t) {
            Kind kind;
            try {
                kind = Kind.valueOf(t.getString("Kind"));
            } catch (IllegalArgumentException e) {
                return null;
            }
            double[] shape = readDoubles(t.getList("Shape", Tag.TAG_DOUBLE));
            if (shape.length < 5) shape = java.util.Arrays.copyOf(shape, 5);
            double tnt = t.getDouble("Tnt"), psi = t.getDouble("Psi");
            if (!Double.isFinite(tnt) || !Double.isFinite(psi) || !Double.isFinite(t.getDouble("X"))
                    || !Double.isFinite(t.getDouble("Y")) || !Double.isFinite(t.getDouble("Z"))) return null;
            Job job = new Job(kind, t.getDouble("X"), t.getDouble("Y"), t.getDouble("Z"), Math.max(0.0, tnt),
                    Math.max(0.0, psi), Math.clamp(t.getFloat("Radius"), 1.0f, 32.0f), shape);
            int[] box = t.getIntArray("Box");
            int[] protect = t.getIntArray("Protect");
            if (box.length != 6) return null;
            System.arraycopy(box, 0, job.box, 0, 6);
            if (protect.length == 6) System.arraycopy(protect, 0, job.protect, 0, 6);
            long[] explicit = t.getLongArray("Explicit");
            for (int i = 0; i < Math.min(explicit.length, MAX_EXPLICIT); i++) job.explicit.add(explicit[i]);
            long[] chunks = t.getLongArray("Chunks");
            for (int i = 0; i < Math.min(chunks.length, MAX_CHUNKS); i++) job.chunks.add(chunks[i]);
            if (t.contains("CursorChunk", Tag.TAG_LONG)) {
                job.cursorChunk = t.getLong("CursorChunk");
                job.cursor = Math.max(0, t.getInt("Cursor"));
            }
            if (t.contains("Centre", Tag.TAG_LONG)) job.centreBlock = t.getLong("Centre");
            return job;
        }
    }

    static final int MAX_EXPLICIT = 8192;

    private static ListTag doubles(double[] values) {
        ListTag list = new ListTag();
        for (double v : values) list.add(net.minecraft.nbt.DoubleTag.valueOf(v));
        return list;
    }

    private static double[] readDoubles(ListTag list) {
        double[] out = new double[Math.min(16, list.size())];
        for (int i = 0; i < out.length; i++) out[i] = list.getDouble(i);
        return out;
    }

    // ------------------------------------------------------------------
    // Job builders
    // ------------------------------------------------------------------

    private static Job region(Kind kind, double cx, double cy, double cz, double tnt, double psi, float radius,
                              double[] shape, int[] box) {
        Job job = new Job(kind, cx, cy, cz, tnt, psi, radius, shape);
        System.arraycopy(box, 0, job.box, 0, 6);
        job.chunks = chunkColumns(box, cx, cz);
        return job;
    }

    /** Chunk columns of a box, nearest the source first, at most {@link #MAX_CHUNKS}. */
    private static LongArrayList chunkColumns(int[] box, double cx, double cz) {
        List<Long> keys = new ArrayList<>();
        for (int x = box[0] >> 4; x <= box[3] >> 4; x++) {
            for (int z = box[2] >> 4; z <= box[5] >> 4; z++) keys.add(ChunkPos.asLong(x, z));
        }
        int sx = Math.floorDiv((int) Math.floor(cx), 16), sz = Math.floorDiv((int) Math.floor(cz), 16);
        keys.sort(Comparator.<Long>comparingLong(k -> {
            long dx = ChunkPos.getX(k) - sx, dz = ChunkPos.getZ(k) - sz;
            return dx * dx + dz * dz;
        }).thenComparingLong(k -> k));
        LongArrayList out = new LongArrayList();
        for (int i = 0; i < Math.min(keys.size(), MAX_CHUNKS); i++) out.add(keys.get(i).longValue());
        return out;
    }

    private static int[] cube(double cx, double cy, double cz, double reach, ServerLevelBounds bounds) {
        int r = (int) Math.ceil(reach) + 1;
        int[] box = {(int) Math.floor(cx) - r, (int) Math.floor(cy) - r, (int) Math.floor(cz) - r,
                (int) Math.floor(cx) + r, (int) Math.floor(cy) + r, (int) Math.floor(cz) + r};
        box[1] = Math.max(box[1], bounds.minY);
        box[4] = Math.min(box[4], bounds.maxY);
        return box;
    }

    private record ServerLevelBounds(int minY, int maxY) {
        static ServerLevelBounds of(ServerLevel level) {
            return new ServerLevelBounds(level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        }
    }

    /**
     * Pressure inside a room: a hydrogen burn or a bursting containment.
     *
     * @param room   the room's interior box to confine the sweep to (min then max), or null for open space
     * @param capPsi the most the room can reach: the AICC peak, the detonation load or the breach excess
     */
    public static Job interior(ServerLevel level, double x, double y, double z, double tntKg, double capPsi,
                               float radius, BlockPos[] room) {
        double reach = BlastPhysics.reachBlocks(tntKg);
        int[] box = cube(x, y, z, reach, ServerLevelBounds.of(level));
        if (room != null) {
            box[0] = Math.max(box[0], room[0].getX());
            box[1] = Math.max(box[1], room[0].getY());
            box[2] = Math.max(box[2], room[0].getZ());
            box[3] = Math.min(box[3], room[1].getX());
            box[4] = Math.min(box[4], room[1].getY());
            box[5] = Math.min(box[5], room[1].getZ());
        }
        Job job = region(Kind.INTERIOR, x, y, z, tntKg, capPsi, radius, new double[5], box);
        if (box[0] > box[3] || box[1] > box[4] || box[2] > box[5]) job.chunks.clear();
        return job;
    }

    /** An explicit breach: the wall blocks the containment survey chose. */
    public static Job breach(double x, double y, double z, float radius, List<BlockPos> blocks) {
        Job job = new Job(Kind.BREACH, x, y, z, 0.0, 0.0, radius, new double[5]);
        List<BlockPos> sorted = new ArrayList<>(blocks);
        sorted.sort(Comparator.comparingLong(BlockPos::asLong));
        for (int i = 0; i < Math.min(sorted.size(), MAX_EXPLICIT); i++) job.explicit.add(sorted.get(i).asLong());
        return job;
    }

    /**
     * A vessel head failure: a steam burst and the missile (head, studs, shield plug) up through the opened
     * head. The cone starts at the first row above the shell and widens at {@link #CONE_HALF_ANGLE_DEGREES}.
     *
     * @param shellMin the vessel shell's minimum corner
     * @param shellMax the vessel shell's maximum corner
     */
    public static Job headCone(ServerLevel level, BlockPos shellMin, BlockPos shellMax, double tntKg, float radius) {
        double axisX = (shellMin.getX() + shellMax.getX() + 1) / 2.0;
        double axisZ = (shellMin.getZ() + shellMax.getZ() + 1) / 2.0;
        int topY = shellMax.getY() + 1;
        double inner = Math.max(1.0, Math.min(shellMax.getX() - shellMin.getX(), shellMax.getZ() - shellMin.getZ()) / 2.0 - 0.5);
        double tan = Math.tan(Math.toRadians(CONE_HALF_ANGLE_DEGREES));
        double reach = BlastPhysics.reachBlocks(tntKg);
        double rho = inner + reach * tan;
        ServerLevelBounds b = ServerLevelBounds.of(level);
        int[] box = {(int) Math.floor(axisX - rho), topY, (int) Math.floor(axisZ - rho),
                (int) Math.ceil(axisX + rho), Math.min(b.maxY, topY + (int) Math.ceil(reach)), (int) Math.ceil(axisZ + rho)};
        Job job = region(Kind.CONE, axisX, topY - 0.5, axisZ, tntKg, 0.0, radius,
                new double[] {axisX, axisZ, topY, inner, tan}, box);
        job.protect(shellMin.offset(1, 1, 1), shellMax.offset(-1, -1, -1));
        if (reach <= 0.0) job.chunks.clear();
        return job;
    }

    /** Pipe whip and jet impingement around a break at {@code breakPos}, on a line at {@code pressurePsig}. */
    public static Job pipeWhip(ServerLevel level, BlockPos breakPos, double pressurePsig, float radius) {
        double r = BlastPhysics.pipeWhipRadiusBlocks(pressurePsig);
        double cx = breakPos.getX() + 0.5, cy = breakPos.getY() + 0.5, cz = breakPos.getZ() + 0.5;
        Job job = region(Kind.WHIP, cx, cy, cz, 0.0, Math.max(0.0, pressurePsig), radius,
                new double[] {r, 0, 0, 0, 0}, cube(cx, cy, cz, r, ServerLevelBounds.of(level)));
        job.centreBlock = breakPos.asLong();
        return job;
    }

    /**
     * The cavity under a failed lower head: the footprint of the vessel, from below the drive row down as far as
     * the debris blast reaches.
     */
    public static Job pedestal(ServerLevel level, BlockPos interiorMin, BlockPos interiorMax, double tntKg, float radius) {
        double axisX = (interiorMin.getX() + interiorMax.getX() + 1) / 2.0;
        double axisZ = (interiorMin.getZ() + interiorMax.getZ() + 1) / 2.0;
        double inner = Math.max(0.75, (Math.min(interiorMax.getX() - interiorMin.getX(), interiorMax.getZ() - interiorMin.getZ()) + 1) / 2.0);
        int topY = interiorMin.getY() - 3;
        int depth = (int) Math.clamp(Math.floor(BlastPhysics.rangeForPsi(tntKg, BlastPhysics.MIN_DAMAGE_PSI)),
                0, MAX_PEDESTAL_DEPTH);
        ServerLevelBounds b = ServerLevelBounds.of(level);
        int[] box = {(int) Math.floor(axisX - inner), Math.max(b.minY, topY - depth + 1), (int) Math.floor(axisZ - inner),
                (int) Math.ceil(axisX + inner), topY, (int) Math.ceil(axisZ + inner)};
        Job job = region(Kind.PEDESTAL, axisX, interiorMin.getY() - 0.5, axisZ, tntKg, 0.0, radius,
                new double[] {axisX, axisZ, inner, topY, Math.max(1, depth)}, box);
        job.protect(interiorMin, interiorMax);
        if (depth <= 0 || box[1] > box[4]) job.chunks.clear();
        return job;
    }

    // ------------------------------------------------------------------
    // The queue
    // ------------------------------------------------------------------

    /** One reactor's pending blasts. */
    public static final class Queue {
        private final List<Job> jobs = new ArrayList<>();

        public boolean isEmpty() {
            return jobs.isEmpty();
        }

        public List<Job> jobs() {
            return List.copyOf(jobs);
        }

        public boolean has(Kind kind) {
            for (Job job : jobs) if (job.kind == kind) return true;
            return false;
        }

        /** Queue a blast. A job with nothing to sweep is not kept. */
        public void add(Job job) {
            if (job == null || job.remaining() == 0 || jobs.size() >= MAX_JOBS) return;
            jobs.add(job);
        }

        /**
         * One tick of work. Returns true while any job has work left. Examines at most
         * {@link #EXAMINE_PER_TICK} positions and removes at most {@link #BREAK_PER_TICK} blocks.
         */
        public boolean run(ServerLevel level) {
            if (jobs.isEmpty()) return false;
            Context context = new Context(level);
            Iterator<Job> iterator = jobs.iterator();
            while (iterator.hasNext() && context.examine > 0 && context.breaks > 0) {
                Job job = iterator.next();
                boolean done = job.kind == Kind.BREACH ? context.stepExplicit(job) : context.stepRegion(job);
                if (done) iterator.remove();
            }
            return !jobs.isEmpty();
        }

        /**
         * Run until nothing is left or nothing can move because every remaining chunk is unloaded. For tests and
         * tools; the game calls {@link #run} once a tick. Returns the ticks used.
         */
        public int runToCompletion(ServerLevel level, int maxTicks) {
            int ticks = 0;
            while (!jobs.isEmpty() && ticks < maxTicks) {
                int before = progress();
                run(level);
                ticks++;
                if (progress() == before && stuck(level)) break;
            }
            return ticks;
        }

        private boolean stuck(ServerLevel level) {
            for (Job job : jobs) {
                if (job.kind == Kind.BREACH) {
                    for (long p : job.explicit) {
                        if (level.hasChunk(BlockPos.getX(p) >> 4, BlockPos.getZ(p) >> 4)) return false;
                    }
                } else {
                    for (long key : job.chunks) {
                        if (level.hasChunk(ChunkPos.getX(key), ChunkPos.getZ(key))) return false;
                    }
                }
            }
            return true;
        }

        private int progress() {
            int n = 0;
            for (Job job : jobs) n += job.remaining() * 1_000_003 + job.cursor;
            return n;
        }

        /** Saved only when there is something left to do. */
        public void write(CompoundTag tag) {
            if (jobs.isEmpty()) return;
            ListTag list = new ListTag();
            for (Job job : jobs) list.add(job.save());
            tag.put(KEY, list);
        }

        public void read(CompoundTag tag) {
            jobs.clear();
            if (!tag.contains(KEY, Tag.TAG_LIST)) return;
            ListTag list = tag.getList(KEY, Tag.TAG_COMPOUND);
            for (int i = 0; i < Math.min(MAX_JOBS, list.size()); i++) {
                Job job = Job.load(list.getCompound(i));
                if (job != null && job.remaining() > 0) jobs.add(job);
            }
        }
    }

    /** Key of the pending blasts in the reactor's tag. Absent when none are pending. */
    public static final String KEY = "AccidentBlasts";

    // ------------------------------------------------------------------
    // One tick's worth of sweeping and breaking
    // ------------------------------------------------------------------

    private static final class Context {
        final ServerLevel level;
        int examine = EXAMINE_PER_TICK;
        int breaks = BREAK_PER_TICK;
        final List<int[]> formed = new ArrayList<>();
        FakePlayer fake;
        final Explosion.BlockInteraction interaction;

        Context(ServerLevel level) {
            this.level = level;
            this.interaction = level.getGameRules().getBoolean(GameRules.RULE_BLOCK_EXPLOSION_DROP_DECAY)
                    ? Explosion.BlockInteraction.DESTROY_WITH_DECAY : Explosion.BlockInteraction.DESTROY;
            for (var reactor : FormedReactorRegistry.controllers(level)) {
                var structure = reactor.structure();
                if (structure == null) continue;
                formed.add(new int[] {structure.interiorMin().getX() - 1, structure.interiorMin().getY() - 2,
                        structure.interiorMin().getZ() - 1, structure.interiorMax().getX() + 1,
                        structure.interiorMax().getY() + 1, structure.interiorMax().getZ() + 1});
            }
        }

        boolean covered(Job job, int x, int y, int z) {
            if (inside(job.protect, x, y, z)) return true;
            for (int[] box : formed) if (inside(box, x, y, z)) return true;
            return false;
        }

        static boolean inside(int[] b, int x, int y, int z) {
            return x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5];
        }

        /** True when the block may be broken by this job, whatever the pressure. */
        boolean breakable(Job job, BlockPos pos, BlockState state) {
            if (state.isAir() || state.getBlock() instanceof LiquidBlock) return false;
            if (state.getDestroySpeed(level, pos) < 0.0f) return false;
            if (state.getBlock() instanceof ReactorControllerBlock
                    || state.getBlock() instanceof ContainmentControllerBlock) return false;
            if (covered(job, pos.getX(), pos.getY(), pos.getZ())) return false;
            if (job.kind == Kind.WHIP && isPipe(state) && pos.asLong() != job.centreBlock) return false;
            return true;
        }

        boolean stepRegion(Job job) {
            List<BlockPos> hits = new ArrayList<>();
            int k = 0;
            while (k < job.chunks.size() && examine > 0 && hits.size() < breaks) {
                long key = job.chunks.getLong(k);
                int chunkX = ChunkPos.getX(key), chunkZ = ChunkPos.getZ(key);
                if (!level.hasChunk(chunkX, chunkZ)) {
                    k++; // Deferred: not loaded, so not touched, and kept for when it is.
                    continue;
                }
                int x0 = Math.max(job.box[0], chunkX << 4), x1 = Math.min(job.box[3], (chunkX << 4) + 15);
                int z0 = Math.max(job.box[2], chunkZ << 4), z1 = Math.min(job.box[5], (chunkZ << 4) + 15);
                int y0 = job.box[1], y1 = job.box[4];
                long nx = x1 - x0 + 1, nz = z1 - z0 + 1, ny = y1 - y0 + 1;
                long total = Math.max(0, nx) * Math.max(0, nz) * Math.max(0, ny);
                long index = key == job.cursorChunk ? job.cursor : 0;
                BlockPos.MutableBlockPos cursorPos = new BlockPos.MutableBlockPos();
                for (; index < total && examine > 0 && hits.size() < breaks; index++) {
                    examine--;
                    int x = (int) (x0 + index % nx);
                    int z = (int) (z0 + (index / nx) % nz);
                    int y = (int) (y0 + index / (nx * nz));
                    double pressure = job.pressureAt(x, y, z);
                    if (pressure <= 0.0) continue;
                    cursorPos.set(x, y, z);
                    BlockState state = level.getBlockState(cursorPos);
                    if (!breakable(job, cursorPos, state)) continue;
                    double rating = BlastPhysics.effectiveRating(ContainmentStrength.contribution(level, cursorPos, state));
                    if (rating < pressure) hits.add(cursorPos.immutable());
                }
                if (index >= total) {
                    job.chunks.removeLong(k);
                    job.cursorChunk = Long.MIN_VALUE;
                    job.cursor = 0;
                } else {
                    job.cursorChunk = key;
                    job.cursor = (int) index;
                    break;
                }
            }
            breakBlocks(job, hits);
            return job.chunks.isEmpty();
        }

        boolean stepExplicit(Job job) {
            List<BlockPos> hits = new ArrayList<>();
            LongArrayList keep = new LongArrayList();
            for (long packed : job.explicit) {
                if (examine <= 0 || hits.size() >= breaks) {
                    keep.add(packed);
                    continue;
                }
                BlockPos pos = BlockPos.of(packed);
                if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                    keep.add(packed); // Deferred until its chunk loads.
                    continue;
                }
                examine--;
                BlockState state = level.getBlockState(pos);
                if (breakable(job, pos, state)) hits.add(pos);
            }
            job.explicit = keep;
            breakBlocks(job, hits);
            return keep.isEmpty();
        }

        /** The protected path: Start, a break event per block, Detonate, then the vanilla finalisation and drops. */
        void breakBlocks(Job job, List<BlockPos> hits) {
            breaks -= hits.size();
            if (hits.isEmpty()) return;
            hits.sort(Comparator.comparingLong(BlockPos::asLong));
            Explosion explosion = new Explosion(level, null, job.cx, job.cy, job.cz, job.radius, false, interaction);
            if (EventHooks.onExplosionStart(level, explosion)) {
                return; // Cancelled by a protection mod: this slice is discarded, not retried.
            }
            if (fake == null) fake = FakePlayerFactory.getMinecraft(level);
            for (BlockPos pos : hits) {
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) continue;
                if (NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, state, fake)).isCanceled()) continue;
                explosion.getToBlow().add(pos);
            }
            EventHooks.onExplosionDetonate(level, explosion, new ArrayList<>(), job.radius * 2.0);
            if (explosion.getToBlow().isEmpty()) return;
            int sample = Math.max(1, explosion.getToBlow().size() / 12);
            for (int i = 0; i < explosion.getToBlow().size(); i += sample) {
                BlockPos at = explosion.getToBlow().get(i);
                level.sendParticles(ParticleTypes.EXPLOSION, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5,
                        1, 0.3, 0.3, 0.3, 0.0);
            }
            explosion.finalizeExplosion(false);
        }
    }

    /** The two BWR pipe blocks a whip never takes beyond the break itself. */
    private static boolean isPipe(BlockState state) {
        return state.is(BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get()) || state.is(BwrBlocks.PRESSURISED_TUBE.get());
    }
}
